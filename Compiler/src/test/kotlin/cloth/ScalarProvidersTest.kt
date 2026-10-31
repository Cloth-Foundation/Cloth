package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.types.EmitCast
import cloth.emitter.types.EmitString
import cloth.emitter.types.ProviderEmissionContext
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.expression.ProviderAccessExpression
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticAnalyzer
import cloth.semantic.SemanticModel
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ScalarProvidersTest {

	private fun snippet(source: String) = Parser(Lexer(SourceFile("scalar-providers.co", source.trimIndent())).scanTokens()).parseSnippet()
	private fun unit(source: String) = Parser(Lexer(SourceFile("scalar-providers.co", source.trimIndent())).scanTokens()).parse()

	@Test
	fun `finite bounds match exact IEEE limits rather than positive minimums or rounded host text`() {
		val references = listOf(16 to BigDecimal("65504"), 32 to BigDecimal(Float.MAX_VALUE.toDouble()), 64 to BigDecimal(Double.MAX_VALUE))
		for ((width, maximum) in references) {
			val type = CFloat(width)
			assertEquals(maximum, type.maximum)
			assertEquals(maximum.negate(), type.minimum)
			val high = assertIs<ProviderResult.FloatingConstant>(type.provider("max"))
			val low = assertIs<ProviderResult.FloatingConstant>(type.provider("min"))
			assertEquals(type, high.type)
			assertEquals(type, low.receiverType)
			assertEquals(maximum, high.value)
			assertEquals(maximum.negate(), low.value)
			assertEquals(setOf(ProviderReceiverForm.TYPE, ProviderReceiverForm.VALUE), high.receiverForms)
		}
	}

	@Test
	fun `LLVM constants contain exact maximum finite and minimum finite bit patterns`() {
		val context = LLVMContextCreate()
		val builder = LLVMCreateBuilderInContext(context)
		try {
			val abi = NativeCABI.native()
			val services = ProviderEmissionContext(context, builder, EmitString(context, builder, abi), EmitCast(context, builder, SemanticModel(), abi), "bounds-test")
			val formats = listOf(Triple(16, 0x7bffuL, 0xfbffuL), Triple(32, 0x7f7fffffuL, 0xff7fffffuL), Triple(64, 0x7fefffffffffffffuL, 0xffefffffffffffffuL))
			for ((width, maximum, minimum) in formats) {
				val type = CFloat(width)
				for ((name, expected) in listOf("max" to maximum, "min" to minimum)) {
					val value = assertNotNull(type.provider(name)).emit(services, ProviderReceiver.Type(type))
					val bits = LLVMConstBitCast(value, LLVMIntTypeInContext(context, width))
					assertEquals(expected, LLVMConstIntGetZExtValue(bits).toULong(), "$type::$name")
				}
			}
		} finally {
			LLVMDisposeBuilder(builder)
			LLVMContextDispose(context)
		}
	}

	@Test
	fun `source scalar providers preserve float widths and use unsigned 64 bit byte counts`() {
		for (width in listOf(16, 32, 64)) for (source in listOf("float<$width>::min::max", "(1 -> float<$width>)::min")) {
			val ast = snippet(source)
			assertEquals(CFloat(width), SemanticAnalyzer().analyze(ast).typeOf(ast.statements.single()), source)
		}
		for ((spelling, initializer, bytes) in scalars()) {
			val ast = snippet("def<$spelling> value = $initializer\n$spelling::size\nvalue::size")
			val semantics = SemanticAnalyzer().analyze(ast)
			for (statement in ast.statements.drop(1)) {
				val expression = (statement as ExpressionStatement).expression as ProviderAccessExpression
				assertEquals(CInt(64, true), semantics.typeOf(expression), spelling)
				assertEquals(BigDecimal(bytes).toBigIntegerExact(), assertIs<ProviderResult.IntegerConstant>(semantics.providerAccess(expression).result).value)
			}
		}
		assertEquals(1, CBool.bits)
		assertEquals(8, CBool.storageBits)
	}

	@Test
	fun `provider result types do not change to satisfy an enclosing annotation`() {
		for (source in listOf("def<float<64>> wide = float::max", "def<unsigned int> count = bool::size", "def<int<64>> count = char::size")) {
			val failure = assertFailsWith<ClothDiagnostic> { SemanticAnalyzer().analyze(snippet(source)) }
			assertEquals(DiagnosticCode.TYPE_MISMATCH, failure.diagnosticCode, source)
			assertLocated(failure)
		}
		val failure = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(unit("fn<float<64>> wrong() = float::min")) }
		assertEquals(DiagnosticCode.RETURN_TYPE, failure.diagnosticCode)
		for (source in listOf("float::MAX", "bool::SIZE", "char::max")) {
			val unknown = assertFailsWith<ClothDiagnostic> { SemanticAnalyzer().analyze(snippet(source)) }
			assertEquals(DiagnosticCode.UNKNOWN_PROVIDER, unknown.diagnosticCode)
			assertLocated(unknown)
		}
	}

	@Test
	fun `finite bounds survive half and floating function ABIs and stay finite at runtime`(@TempDir directory: Path) {
		val declarations = buildString {
			for (width in listOf(16, 32, 64)) {
				appendLine("fn<float<$width>> high$width() = float<$width>::max")
				appendLine("fn<float<$width>> low$width() = float<$width>::min")
			}
		}
		val checks = buildString {
			for (width in listOf(16, 32, 64)) {
				appendLine("def high$width = high$width()")
				appendLine("def low$width = low$width()")
				appendLine("if (high$width <= 0 || low$width >= 0 || high$width != high$width || high$width + low$width != 0 || high$width - high$width != 0) { return 1 }")
				appendLine("if (high$width::max != high$width || low$width::min != low$width || high$width + high$width <= high$width) { return 2 }")
			}
			appendLine("if ((high16 -> float<64>) != (65504 -> float<64>) || (low16 -> int) != -65504) { return 3 }")
		}
		native(directory, "finite_bounds", "$declarations\nfn<int> main() {\n$checks\nreturn 0\n}")
	}

	@Test
	fun `scalar byte sizes execute for all widths signedness and receiver forms`(@TempDir directory: Path) {
		val declarations = buildString {
			for ((index, scalar) in scalars().withIndex()) appendLine("fn<unsigned int<64>> bytes$index(${scalar.first} value) = value::size")
		}
		val checks = buildString {
			for ((index, scalar) in scalars().withIndex()) {
				val (type, initializer, bytes) = scalar
				appendLine("if ($type::size != $bytes || bytes$index($initializer) != $bytes) { return 1 }")
			}
			appendLine("def copied = bool::size")
			appendLine("copied += 1")
			appendLine("if (copied != 2 || bool::size != 1 || char::size != 4 || (int::size + 1) != 5) { return 2 }")
		}
		native(directory, "storage_sizes", "$declarations\nfn<int> main() {\n$checks\nreturn 0\n}")
	}

	@Test
	fun `constant bounds and sizes evaluate captured receivers once and ignore their current contents`(@TempDir directory: Path) {
		native(directory, "effects", """
			fn<int> main() {
			    def count = 0
			    def<float<16>> nextFloat = {
			        count += 1
			        return 1 -> float<16>
			    }
			    def<bool> nextBool = {
			        count += 1
			        return true
			    }
			    def<char> nextChar = {
			        count += 1
			        return '😀'
			    }
			    def upper = nextFloat::max
			    def lower = nextFloat::min
			    def floatBytes = nextFloat::size
			    def boolBytes = nextBool::size
			    def charBytes = nextChar::size
			    if (count != 5 || upper + lower != 0 || floatBytes != 2 || boolBytes != 1 || charBytes != 4) { return 1 }
			    def nan = 0.0 / 0.0
			    if (nan::max != float::max || nan::min != float::min || nan::size != 4) { return 2 }
			    return 0
			}
		""")
	}

	@Test
	fun `overload selection keeps new provider facts and typed numeric results`(@TempDir directory: Path) {
		native(directory, "overloads", """
			fn<int> pick(float<16> value) = 1
			fn<int> pick(float<64> value) = 2
			fn<int> sizes(unsigned int<32> value, int<8> other) = 3
			fn<int> sizes(unsigned int<64> value, int<64> other) = 4
			fn<int> main() {
			    if (pick(float<16>::max) != 1 || pick(float<64>::min) != 2 || sizes(bool::size, 1) != 4) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `scalar size access preserves indexed receiver bounds checks`(@TempDir directory: Path) {
		native(directory, "indexed_size", "fn<int> main() {\ndef a = [true]\nreturn a[8]::size -> int\n}", "Array index out of bounds")
	}

	private fun scalars(): List<Triple<String, String, Int>> = buildList {
		for (width in listOf(8, 16, 32, 64)) for (unsigned in listOf(false, true)) {
			val type = CInt(width, unsigned).spelling
			add(Triple(type, "1 -> $type", width / 8))
		}
		for (width in listOf(16, 32, 64)) add(Triple("float<$width>", "1 -> float<$width>", width / 8))
		add(Triple("bool", "true", 1))
		add(Triple("char", "'😀'", 4))
	}

	private fun native(directory: Path, name: String, source: String, expectedError: String? = null) {
		val executable = LLVMEmitter().compile(unit(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "$name timed out")
			val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
			if (expectedError == null) {
				assertEquals(0, process.exitValue(), "$name: $output"); assertEquals("", output)
			} else {
				assertNotEquals(0, process.exitValue(), name); assertContains(output, expectedError)
			}
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
