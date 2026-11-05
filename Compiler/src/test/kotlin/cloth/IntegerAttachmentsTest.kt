package cloth

import cloth.backend.IntegerKernelProgram
import cloth.builtin.BuiltinParameter
import cloth.builtin.attachments.IntegerMethod
import cloth.builtin.attachments.PrimitiveAttachments
import cloth.data.CISize
import cloth.data.CInt
import cloth.data.CUSize
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.types.primitives.EmitIntegerMember
import cloth.emitter.types.EmitStackScope
import cloth.emitter.types.primitives.EmitString
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticAnalyzer
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.*

class IntegerAttachmentsTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("integer-members.co", source.trimIndent())).scanTokens()).parse()
	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(unit(source)) }
		assertEquals(code, error.diagnosticCode, source)
		assertLocated(error)
	}

	@Test
	fun `family lookup specializes exact identities without enumerating all widths`() {
		for (abi in IntegerKernelProgram.targets()) {
			val target = abi.layout
			for (type in (1..64).flatMap { listOf(CInt(it), CInt(it, true)) } + listOf(CInt(65), CInt(129, true), CInt(65536), CInt(65536, true), CISize(target.sizeBits), CUSize(target.sizeBits))) {
				val abs = assertNotNull(PrimitiveAttachments[type, "abs", target])
				assertSame(abs, PrimitiveAttachments[type, "abs", target])
				assertEquals(type, abs.receiver)
				assertEquals(type, abs.overload.returns)
				assertEquals(List(2) { BuiltinParameter.Exact(type) }, PrimitiveAttachments[type, "clamp", target]?.parameters)
				assertEquals(ValueType.INT, PrimitiveAttachments[type, "signum", target]?.overload?.returns)
				assertEquals(CUSize(target.sizeBits), PrimitiveAttachments[type, "leadingZeros", target]?.overload?.returns)
				assertEquals(CUSize(target.sizeBits), PrimitiveAttachments[type, "trailingZeros", target]?.overload?.returns)
				assertEquals(listOf(BuiltinParameter.Exact(ValueType.INT)), PrimitiveAttachments[type, "toStringRadix", target]?.parameters)
				assertNull(PrimitiveAttachments[type, "Abs", target])
			}
		}
		assertNull(PrimitiveAttachments[ValueType.BOOL, "abs"])
		assertEquals(ValueType.FLOAT, PrimitiveAttachments[ValueType.FLOAT, "abs"]?.overload?.returns)
		assertNull(PrimitiveAttachments[ValueType.CHAR, "abs"])
	}

	@Test
	fun `every scalar width and wide extrema agree with independent full value oracles`() = IntegerKernelProgram.scratch { directory ->
		val widths = (1..64).toList() + listOf(65, 127, 128, 129, 257, 1024, 4096, 65535, 65536)
		for (abi in IntegerKernelProgram.targets()) IntegerKernelProgram.run(abi, directory, "integer-members-${abi.architecture}") {
			val random = Random(43071)
			for (bits in widths) for (unsigned in listOf(false, true)) case("bits${bits}_$unsigned") {
				val type = CInt(bits, unsigned)
				val modulus = BigInteger.ONE.shiftLeft(bits)
				val patterns = listOf(BigInteger.ZERO, BigInteger.ONE, modulus - BigInteger.ONE, BigInteger.ONE.shiftLeft(bits - 1), BigInteger(1, random.nextBytes((bits + 7) / 8)).mod(modulus)).distinct()
				val member = EmitIntegerMember(context, builder, abi, "integer-members.co:3:7")
				val strings = EmitString(context, builder, abi)
				fun value(n: BigInteger) = if (bits <= 64) LLVMConstIntOfString(type.llvmValue(context, abi.layout), n.toString(), 10.toByte()) else integers.constant(n, bits)
				for (pattern in patterns) {
					val n = if (!unsigned && pattern.testBit(bits - 1)) pattern - modulus else pattern
					val input = value(n)
					fun emit(method: IntegerMethod, vararg extra: org.bytedeco.llvm.LLVM.LLVMValueRef) = member.emit(method, type, listOf(input) + extra)
					fun equalsNumber(actual: org.bytedeco.llvm.LLVM.LLVMValueRef, expected: BigInteger, label: String) {
						if (type.indirect) integer(actual, expected, bits, label)
						else check(LLVMBuildICmp(builder, LLVMIntEQ, actual, value(expected), "matches_member"), label)
					}
					if (unsigned || n != type.minimum) equalsNumber(emit(IntegerMethod.ABS), n.abs(), "abs")
					status(emit(IntegerMethod.SIGNUM), n.signum(), "signum")
					val lower = type.minimum / BigInteger.TWO
					val upper = type.maximum / BigInteger.TWO
					equalsNumber(emit(IntegerMethod.CLAMP, value(lower), value(upper)), n.max(lower).min(upper), "clamp")
					equalsNumber(emit(IntegerMethod.CLAMP, value(type.maximum), value(type.maximum)), type.maximum, "equal clamp")
					for ((method, expected) in listOf(IntegerMethod.LEADING_ZEROS to bits - pattern.bitLength(), IntegerMethod.TRAILING_ZEROS to if (pattern == BigInteger.ZERO) bits else pattern.lowestSetBit)) {
						val actual = emit(method)
						check(LLVMBuildICmp(builder, LLVMIntEQ, actual, LLVMConstInt(LLVMTypeOf(actual), expected.toLong(), 0), "matches_zero_count"), method.name)
					}
					val radices = if (bits in listOf(1, 8, 65, 129)) (2..36).toList() else listOf(2, 8, 10, 16, 36)
					for (radix in radices) {
						val stack = EmitStackScope(context, builder)
						val checkpoint = stack.save()
						check(strings.equal(emit(IntegerMethod.TO_STRING_RADIX, int(radix)), strings.literal(n.toString(radix))), "radix $radix")
						stack.restore(checkpoint)
					}
					if (type.indirect) integer(input, pattern, bits, "receiver preserved")
				}
			}
		}
	}

	@Test
	fun `constant invalid calls retain intrinsic diagnostics including in skipped code and overload probes`() {
		failure("fn main() { int<8>::min.abs() }", DiagnosticCode.INTEGER_ABS_OVERFLOW)
		failure("fn main() { (-128 -> int<8>).abs() }", DiagnosticCode.INTEGER_ABS_OVERFLOW)
		failure("fn main() { (int::min + 0).abs() }", DiagnosticCode.INTEGER_ABS_OVERFLOW)
		failure("fn main() { 1.clamp(5, 4) }", DiagnosticCode.INVALID_CLAMP_BOUNDS)
		failure("fn main() { 1.toStringRadix(1 + 36) }", DiagnosticCode.INVALID_INTEGER_RADIX)
		failure("fn main() { false && int::min.abs() == 0 }", DiagnosticCode.INTEGER_ABS_OVERFLOW)
		failure("fn choose(string x) {}\nfn choose(int x) {}\nfn main() { choose(1.toStringRadix(0)) }", DiagnosticCode.INVALID_INTEGER_RADIX)
	}

	@Test
	fun `arguments receivers and integer identities remain strict`() {
		failure("fn main() { def<int<8>> n = 1; def<int> lower = 0; n.clamp(lower, 2) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn main() { def<usize> n = 1; def<unsigned int<64>> lower = 0; n.clamp(lower, 2) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn main() { def<usize> base = 16; 1.toStringRadix(base) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn main() { 1.abs(0) }", DiagnosticCode.CALL_ARITY)
		failure("fn main() { 1.clamp(min = 0, max = 2) }", DiagnosticCode.INVALID_ARGUMENT_BINDING)
		failure("fn test(int? n) { n.abs() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn main() { true.signum() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn main() { int.abs() }", DiagnosticCode.TYPE_RECEIVER_VALUE)
	}

	@Test
	fun `source calls preserve evaluation snapshots nullable proofs ordered returns and composition`(@TempDir directory: Path) {
		val source = """
			fn<int<129>> receiver() { print("R"); return -255 }
			fn<int<129>> bound(int n) { print(n); return n -> int<129> }
			fn<string> text(int<129> n, int radix) = n.toStringRadix(radix)
			fn<int<129>, string, usize> pair(int<129> n) = n.abs(), n.toStringRadix(16), n.trailingZeros()
			fn<int> main() {
			    println(receiver().clamp(bound(-100), bound(100)))
			    def<int<129>> n = -255
			    def<int<129>> change = { n = 1000; return -200 }
			    println(n.clamp(change, 200))
			    println(n)
			    def<int<129>?> maybe = -8
			    if (maybe != null) println(maybe.abs())
			    println((maybe ?: 0).signum())
			    def magnitude, digits, zeros = pair(-256)
			    println(magnitude); println(digits); println(zeros)
			    def saved = text(int<129>::min, 16)
			    text(12, 2)
			    println(saved)
			    println(text(-255, 16).substring(1, 3))
			    def<int<1>> bit = -1
			    println(bit.signum()); println(bit.leadingZeros()); println(bit.trailingZeros())
			    def<isize> signed = -8
			    def<usize> u = 8
			    println(signed.abs()::type); println(u.abs()::type)
			    println(signed.signum()::type); println(u.trailingZeros()::type)
			    return 0
			}
		"""
		val expected = "R-100100-100\n-200\n1000\n8\n-1\n256\n-100\n8\n-100000000000000000000000000000000\nff\n-1\n0\n0\nisize\nusize\nint<32>\nusize\n"
		for (abi in IntegerKernelProgram.targets()) assertEquals(expected + processCompletion().replace("\r\n", "\n"), execute(directory, "source_${abi.architecture}", source, abi))
	}

	@Test
	fun `runtime invalid calls evaluate operands once then fail with location and no completion`(@TempDir directory: Path) {
		val programs = listOf("fn main() { def<int<129>> n = int<129>::min; print(\"before:\"); n.abs(); print(\"BAD\") }" to "abs overflow", "fn main() { def<int<1>> n = -1; n.abs(); print(\"BAD\") }" to "abs overflow", "fn main() { def n = int::min; n.abs(); print(\"BAD\") }" to "abs overflow", "fn main() { def<int<65536>> n = int<65536>::min; n.abs(); print(\"BAD\") }" to "abs overflow", "fn<int> bound(int n) { print(n); return n }\nfn main() { def n = 0; n.clamp(bound(5), bound(4)); print(\"BAD\") }" to "54Integer clamp", "fn<int> radix() { print(\"base:\"); return -1 }\nfn main() { 1.toStringRadix(radix()); print(\"BAD\") }" to "base:Integer formatting radix")
		for (abi in IntegerKernelProgram.targets()) for ((index, fixture) in programs.withIndex()) {
			val output = execute(directory, "invalid_${abi.architecture}_$index", fixture.first, abi, 1)
			assertContains(output, fixture.second)
			assertContains(output, "integer-members.co:")
			assertFalse(output.contains("BAD"))
			assertFalse(output.contains("PEC:"))
		}
	}

	@Test
	fun `early exits and short circuits skip later operands and guards`(@TempDir directory: Path) {
		val source = """
			fn<int> beforeReceiver() { (if (true) { return 1 } else { 0 }).abs() }
			fn<int> firstBound() { 0.clamp(if (true) { return 2 } else { 0 }, if (true) { print("BAD"); 2 } else { 2 }) }
			fn<int> lastBound() { 0.clamp(0, if (true) { return 3 } else { 1 }) }
			fn<int> beforeRadix() { 0.toStringRadix(if (true) { return 4 } else { 10 }); return 0 }
			fn main() {
			    def n = int::min
			    def lower = 2
			    def base = 37
			    false && n.abs() == 0
			    true || 1.clamp(lower, 1) == 0
			    true || 0.toStringRadix(base).isEmpty()
			    println(beforeReceiver()); println(firstBound()); println(lastBound()); println(beforeRadix())
			}
		"""
		assertEquals("1\n2\n3\n4\n" + processCompletion().replace("\r\n", "\n"), execute(directory, "early", source, NativeCABI.native()))
	}

	@Test
	fun `same AST specializes target sized identities and emits objects for all nine profiles`(@TempDir directory: Path) {
		val ast = unit("""
			fn<string, usize, int, isize, usize> check(int<129> n, int<129> low, int<129> high, int base, isize s, usize u) {
			    n.abs(); n.clamp(low, high)
			    return n.toStringRadix(base), n.leadingZeros(), n.signum(), s.abs(), u.clamp(0, usize::max)
			}
			fn<usize> trail(int<65536> n) = n.trailingZeros()
			fn<int> scalar(int<7> n) = n.leadingZeros() -> int
		""")
		for ((index, triple) in IntegerKernelProgram.triples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emit(ast), "cloth.integer.radix")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("members_$index.o"))) > 0)
		}
		val bounded = unit("fn<usize> f() { def<usize> n = 4294967296; return n.leadingZeros() }")
		assertContains(LLVMEmitter(NativeCABI.forTarget("x86_64-pc-windows-msvc")).emit(bounded), "i64")
		assertEquals(DiagnosticCode.INTEGER_RANGE, assertFailsWith<ClothDiagnostic> { LLVMEmitter(NativeCABI.forTarget("i686-pc-windows-msvc")).emit(bounded) }.diagnosticCode)
		val parser = Parser(Lexer("(-8).abs()").scanTokens()).parseSnippet()
		assertEquals(ValueType.INT, SemanticAnalyzer().analyze(parser).typeOf((parser.statements.single() as cloth.parser.statement.ExpressionStatement).expression))
	}

	@Test
	fun `maintained example and full cap repeated temporaries execute on both host targets`(@TempDir directory: Path) {
		val source = Files.readString(Path.of("examples", "IntegerAttachments.co"))
		val stress = """
			fn<string> radix(int<65536> n, int base) = n.toStringRadix(base)
			fn<int> main() {
			    def<int<65536>> n = -8
			    def saved = radix(int<65536>::min, 16)
			    def first = saved
			    for (def i <- 0..2000) {
			        n.abs()
			        n.toStringRadix(2)
			        if (n.leadingZeros() != 0 || n.trailingZeros() != 3 || n.signum() != -1) return 1
			        n = n.clamp(-10, 10)
			    }
			    if (first != saved || saved.charAt(0) != '-' || n != -8) return 2
			    return 0
			}
		"""
		for (abi in IntegerKernelProgram.targets()) {
			assertEquals("255\n-1\n-100\n-ff\n4\n3\n-100000000000000000000000000000000\nusize\nint<32>\nusize\n" + processCompletion().replace("\r\n", "\n"), execute(directory, "example_${abi.architecture}", source, abi))
			assertEquals(processCompletion().replace("\r\n", "\n"), execute(directory, "stress_${abi.architecture}", stress, abi))
		}
	}

	@Test
	fun `first constant failure stops CLI before object or linker work`(@TempDir directory: Path) {
		val source = directory.resolve("invalid.co")
		val output = directory.resolve("invalid.o")
		Files.writeString(source, "fn main() { int::min.abs(); 1.toStringRadix(0) }")
		val diagnostic = StringBuilder()
		assertEquals(1, runCompiler(arrayOf("--emit", "object", source.toString(), output.toString(), "--linker", "missing-linker"), DiagnosticReporter(diagnostic)))
		assertContains(diagnostic.toString(), "C3072")
		assertFalse(diagnostic.toString().contains("C3074"))
		assertFalse(Files.exists(output))
	}

	private fun execute(directory: Path, name: String, source: String, abi: NativeCABI, expectedStatus: Int = 0): String {
		val executable = LLVMEmitter(abi).compile(unit(source), directory.resolve(name))
		val log = directory.resolve("$name.log")
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
		try {
			assertTrue(process.waitFor(60, TimeUnit.SECONDS), name)
			val output = Files.readString(log).replace("\r\n", "\n")
			assertEquals(expectedStatus, process.exitValue(), output.take(2000))
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
