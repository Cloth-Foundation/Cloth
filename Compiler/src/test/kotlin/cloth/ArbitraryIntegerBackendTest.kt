package cloth

import cloth.backend.IntegerBackendProbe
import cloth.backend.IntegerProbeIR
import cloth.emitter.NativeCABI
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stage 1 verifies backend experiments, without admitting new Cloth source types. */
class ArbitraryIntegerBackendTest {

	@Test
	fun `all planned width and operation experiments verify before target codegen`() {
		val context = LLVMContextCreate()
		try {
			for (bits in IntegerProbeIR.widths + 31) for (group in IntegerProbeIR.groups) {
				for (words in if (group == "limbs") listOf(32, 64) else listOf(64)) {
					val source = IntegerProbeIR.source(bits, group, words)
					val module = LLVMModuleRef()
					val error = BytePointer(null as Pointer?)
					try {
						assertEquals(0, LLVMParseIRInContext(context, LLVMCreateMemoryBufferWithMemoryRangeCopy(source, source.toByteArray().size.toLong(), "probe"), module, error), "$bits/$group/$words: ${if (error.isNull) "" else error.string}")
						assertEquals(0, LLVMVerifyModule(module, LLVMReturnStatusAction, error), "$bits/$group/$words: ${if (error.isNull) "" else error.string}")
					} finally {
						if (!module.isNull) LLVMDisposeModule(module)
						if (!error.isNull) LLVMDisposeMessage(error)
					}
				}
			}
		} finally {
			LLVMContextDispose(context)
		}
	}

	@Test
	fun `odd width arithmetic executes in an isolated JVM with full value checks`() = scratch { directory ->
		val triple = NativeCABI.native().targetTriple
		val result = launch(triple, 31, "core", directory.resolve("core31"))
		assertEquals("NATIVE_PASS", result[3], result.joinToString("\t"))
		assertEquals("4", result[8])
	}

	@Test
	fun `limb prototype carries masks and recursively copies full cap on runnable targets`() = scratch { directory ->
		val host = NativeCABI.native()
		val targets = if (host.windows && host.architecture == NativeCABI.Architecture.X86_64) listOf(host, NativeCABI.forTarget("i686-pc-windows-msvc")) else listOf(host)
		for (target in targets) for (bits in listOf(65, 65535, 65536, 131072)) {
			val result = launch(target.targetTriple, bits, "limbs", directory.resolve("${target.architecture}-$bits"))
			assertEquals("NATIVE_PASS", result[3], result.joinToString("\t"))
			val expectedBytes = ((bits + target.pointerBits - 1) / target.pointerBits) * (target.pointerBits / 8)
			assertEquals(expectedBytes.toString(), result[8])
			assertTrue(result[5].toLong() < 200_000, "Limb-loop object unexpectedly large: $result")
		}
	}

	@Test
	fun `failed experiment records phase and later experiments can still execute`() = scratch { directory ->
		val triple = NativeCABI.native().targetTriple
		val failed = launch(triple, 12, "invalid", directory.resolve("invalid"))
		assertEquals("FAIL", failed[3])
		assertEquals("parse", failed[4])
		assertTrue(failed.last().contains("requirement", ignoreCase = true))
		val valid = launch(triple, 12, "indirect", directory.resolve("valid"))
		assertEquals("NATIVE_PASS", valid[3], valid.joinToString("\t"))
	}

	private fun launch(triple: String, bits: Int, group: String, directory: Path): List<String> = IntegerBackendProbe.launch(triple, bits, group, directory, 30, false, checkNotNull(System.getProperty("cloth.test.runtimeClasspath"))).split('\t')

	private fun scratch(action: (Path) -> Unit) {
		val root = Path.of("tmp").toAbsolutePath().normalize()
		Files.createDirectories(root)
		val directory = Files.createTempDirectory(root, "integer-backend-test-")
		try {
			action(directory)
		} finally {
			check(directory.startsWith(root) && directory != root)
			Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
		}
	}
}
