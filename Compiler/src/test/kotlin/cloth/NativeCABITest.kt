package cloth

import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.ValueType
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Test
import kotlin.test.*

class NativeCABITest {
	@Test
	fun `supported target triples select explicit platform ABI profiles`() {
		val targets = listOf(
			"x86_64-pc-windows-msvc" to NativeCABI.Profile.WINDOWS_LLP64,
			"aarch64-pc-windows-msvc" to NativeCABI.Profile.WINDOWS_LLP64,
			"x86_64-unknown-linux-gnu" to NativeCABI.Profile.LINUX_LP64,
			"aarch64-unknown-linux-gnu" to NativeCABI.Profile.LINUX_LP64,
			"x86_64-unknown-linux-musl" to NativeCABI.Profile.LINUX_LP64,
			"aarch64-unknown-linux-musl" to NativeCABI.Profile.LINUX_LP64,
			"x86_64-apple-darwin" to NativeCABI.Profile.DARWIN_LP64,
			"arm64-apple-darwin" to NativeCABI.Profile.DARWIN_LP64
		)
		for ((triple, profile) in targets) {
			val abi = NativeCABI.forTarget(triple)
			assertEquals(profile, abi.profile, triple)
			assertEquals(32, abi.intType.bits, triple)
			assertEquals(if (profile == NativeCABI.Profile.WINDOWS_LLP64) 32 else 64, abi.longType.bits, triple)
			assertEquals(64, abi.longLongType.bits, triple)
			assertEquals(64, abi.pointerBits, triple)
			assertEquals(abi.pointerBits, abi.sizeType.bits, triple)
			assertTrue(abi.sizeType.unsigned, triple)
			assertEquals(ValueType.DOUBLE, abi.doubleType, triple)
			assertEquals(when (profile) {
				NativeCABI.Profile.WINDOWS_LLP64 -> ExecutableFormat.PE
				NativeCABI.Profile.LINUX_LP64 -> ExecutableFormat.ELF
				NativeCABI.Profile.DARWIN_LP64 -> ExecutableFormat.MACH_O
			}, abi.executableFormat, triple)
		}
	}

	@Test
	fun `LLVM normalization and supported OS and environment versions preserve profile selection`() {
		for (triple in listOf("x86_64-linux-gnu", "aarch64-linux-musl")) {
			val abi = NativeCABI.forTarget(triple)
			assertEquals(NativeCABI.Profile.LINUX_LP64, abi.profile, triple)
			assertContains(abi.targetTriple, "-unknown-linux-", message = triple)
			assertEquals(abi.targetTriple, NativeCABI.forTarget(abi.targetTriple).targetTriple)
		}
		for (triple in listOf("x86_64-apple-darwin23.6.0", "arm64-apple-macosx14.0.0", "aarch64-apple-darwin24.0.0")) {
			assertEquals(NativeCABI.Profile.DARWIN_LP64, NativeCABI.forTarget(triple).profile, triple)
		}
		assertEquals(NativeCABI.Profile.WINDOWS_LLP64, NativeCABI.forTarget("x86_64-pc-windows-msvc19.40.0").profile)
		// LLVM itself supplies the default MSVC environment for this abbreviated triple.
		val windows = NativeCABI.forTarget("x86_64-pc-windows")
		assertEquals(NativeCABI.Profile.WINDOWS_LLP64, windows.profile)
		assertContains(windows.targetTriple, "-windows-msvc")
	}

	@Test
	fun `unknown architectures operating systems and ABI variants never fall back to a profile`() {
		val unsupported = listOf(
			"", "unknown-unknown-unknown", "not-a-target", "x86_64", "aarch64-none-elf",
			"i686-pc-windows-msvc", "armv7-unknown-linux-gnueabihf", "riscv64-unknown-linux-gnu",
			"x86_64-unknown-freebsd", "x86_64-pc-windows-gnu", "aarch64-pc-windows-gnu",
			"x86_64-pc-windows-msvc19.bad", "x86_64-unknown-linux",
			"x86_64-unknown-linux-gnux32", "aarch64-unknown-linux-gnu_ilp32",
			"aarch64-unknown-linux-android", "x86_64-unknown-linux-gnufuture",
			"x86_64-apple-ios-simulator", "arm64-apple-ios", "arm64ec-pc-windows-msvc",
			"x86_64-apple-darwin24.invalid", "x86_64-apple-macosx14.0.0-simulator"
		)
		for (triple in unsupported) {
			val failure = assertFailsWith<UnsupportedOperationException>(triple) { NativeCABI.forTarget(triple) }
			assertContains(failure.message.orEmpty(), "Unsupported native C ABI target '$triple'")
			assertContains(failure.message.orEmpty(), "Supported targets:")
		}
	}

	@Test
	fun `native emitted IR records the selected LLVM target and ABI declarations`() {
		val abi = NativeCABI.native()
		val ast = Parser(Lexer("def fraction = 3.75\nfraction -> int").scanTokens()).parse()
		val ir = LLVMEmitter().emit(ast, forcePrint = true)
		assertContains(ir, "target triple = \"${abi.targetTriple}\"")
		assertContains(ir, "define i${abi.intType.bits} @main()")
		assertContains(ir, "declare i${abi.intType.bits} @printf(")
		assertContains(ir, "declare i${abi.intType.bits} @puts(")
		assertContains(ir, "declare void @exit(i${abi.intType.bits})")
		for (bits in ValueType.INTEGER_WIDTHS) {
			val signed = ValueType.Integer(bits)
			val unsigned = ValueType.Integer(bits, true)
			assertEquals(if (bits <= abi.intType.bits) "d" else "lld", abi.printSpecifier(signed))
			assertEquals(if (bits <= abi.intType.bits) "u" else "llu", abi.printSpecifier(unsigned))
		}
	}
}
