package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.parser.declaration.EnumDeclaration
import cloth.parser.expression.CastExpression
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class EnumConstantExpressionsTest {

	private val valid = """
		typedef<enum> Signed (pub def<int<8>> value) {
		    LOW(-128.99 -> int<8>), HIGH(127.99 -> int<8>),
		    WRAP(255 -> int<8>), CHAIN(258 -> int<8> -> float<16> -> int<8>)
		}
		typedef<enum> Unsigned (pub def<unsigned int<8>> value) {
		    ZERO(-0.75 -> unsigned int<8>), HIGH(255.99 -> unsigned int<8>), WRAP(-1 -> unsigned int<8>)
		}
		typedef<enum> Wide (pub def<int<129>> value) { BIG(100000000000000000000.0 -> int<129>) }
		typedef<enum> Boundary (pub def<unsigned int<65>> value) {
		    EXACT(-1 -> unsigned int<64> -> float<64> -> unsigned int<65>)
		}
		typedef<enum> Rounded (pub def<int<8>> value) { HALF(126.99 -> float<16> -> int<8>) }
		typedef<enum> Nullable (pub def<int<8>?> value) {
		    ABSENT(null -> float? -> int<8>?), PRESENT(127.5 -> float? -> int<8>?)
		}
		typedef<enum> Overflow (pub def<float<16>> value) { INFINITY(65536.0 -> float<16>) }
		typedef<struct> Data { int<8>?[2] values }
		typedef<enum> Nested (pub def<Data> data = { [null, 126.99 -> float<16> -> int<8>?] }) {
		    DEFAULT, OTHER({ [-0.75 -> int<8>?, null] })
		}
		fn<int> main() {
		    println([Signed.LOW.value, Signed.HIGH.value, Signed.WRAP.value, Signed.CHAIN.value])
		    println([Unsigned.ZERO.value, Unsigned.HIGH.value, Unsigned.WRAP.value])
		    println(Wide.BIG.value)
		    println(Boundary.EXACT.value)
		    println(Rounded.HALF.value)
		    println(Nullable.ABSENT.value)
		    println(Nullable.PRESENT.value)
		    println(Nested.DEFAULT.data.values)
		    println(Nested.OTHER.data.values)
		    if (Overflow.INFINITY.value <= 0.0) return 1
		    return 0
		}
	"""

	private fun failure(source: String, abi: NativeCABI = NativeCABI.native()): ClothDiagnostic {
		val error = assertFailsWith<ClothDiagnostic>(source) { FunctionAnalyzer(abi.layout).analyze(structUnit(source)) }
		assertEquals(DiagnosticCode.INVALID_CAST, error.diagnosticCode, error.summary)
		assertLocated(error)
		assertFalse(error.summary.contains("<payload."))
		return error
	}

	@Test
	fun `trapping casts are rejected in unread cases for every case count and integer width`() {
		val inputs = listOf(
			"int<8>" to "128.0 -> int<8>",
			"unsigned int<8>" to "-1.0 -> unsigned int<8>",
			"int<1>" to "1.0 -> int<1>",
			"int<8>" to "127.99 -> float<16> -> int<8>",
			"int" to "2147483647.0 -> int",
			"int<8>" to "65536.0 -> float<16> -> int<8>",
			"int<8>" to "-65536.0 -> float<16> -> int<8>",
			"int<64>" to "-1 -> unsigned int<64> -> float<64> -> int<64>",
			"unsigned int<64>" to "-1 -> unsigned int<64> -> float<64> -> unsigned int<64>",
			"int<65>" to "-1 -> unsigned int<65> -> float<64> -> int<65>",
			"int<65536>" to "-1 -> unsigned int<65536> -> float<64> -> int<65536>",
		)
		for (abi in structRunners()) for ((type, value) in inputs) {
			for (cases in listOf("BAD($value)", "BAD($value), OTHER($value)")) {
				failure("typedef<enum> E (pub def<$type> value) { $cases }", abi)
			}
		}
	}

	@Test
	fun `the first failing cast retains original case field and cast spans`() {
		val unit = structUnit("typedef<enum> E (pub def<int> value) {\r\n\tBAD(128.0 -> int<8> -> int), OTHER(256.0 -> int)\r\n}")
		val declaration = assertIs<EnumDeclaration>(unit.declarations.single())
		val outer = assertIs<CastExpression>(declaration.cases.first().values.single())
		val cast = assertIs<CastExpression>(outer.operand)
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(unit) }
		assertEquals(DiagnosticCode.INVALID_CAST, error.diagnosticCode)
		assertContains(error.summary, "E.value")
		assertEquals(listOf(cast.span, declaration.fields.single().type.span, declaration.cases.first().name.span), error.sourceLabels.map { it.span })
	}

	@Test
	fun `nested aggregates and overridden defaults validate every contained cast`() {
		failure("typedef<struct> S { int<8> code }\ntypedef<enum> E (pub def<S[1]> data) { A([{ 128.0 -> int<8> }]) }")
		failure("typedef<enum> E (pub def<int<8>?[2]> data) { A([null, 128.0 -> int<8>?]), B([1, null]) }")
		val error = failure("typedef<struct> S { int<8> code }\ntypedef<enum> E (pub def<S> data = { 128.0 -> int<8> }) { A({ 1 }), B({ 2 }) }")
		assertEquals(2, error.sourceLabels.size)
		assertContains(error.summary, "Default")
	}

	@Test
	fun `known nullable presence controls conversion while absence skips it`() {
		for (abi in structRunners()) {
			FunctionAnalyzer(abi.layout).analyze(structUnit("""
				typedef<enum> E (pub def<int<8>?> value = null -> float? -> int<8>?) {
				    ABSENT, PRESENT(127.5 -> float? -> int<8>?)
				}
			"""))
			failure("typedef<enum> E (pub def<int<8>?> value) { BAD(128.0 -> float? -> int<8>?) }", abi)
		}
	}

	@Test
	fun `target-sized cast bounds use the selected target`() {
		for (triple in structTargetTriples) {
			val abi = NativeCABI.forTarget(triple)
			for ((type, value) in listOf("usize" to "4294967296.0", "isize" to "2147483648.0")) {
				val source = "typedef<enum> E (pub def<$type> value) { A($value -> $type) }"
				if (abi.layout.sizeBits == 32) failure(source, abi)
				else FunctionAnalyzer(abi.layout).analyze(structUnit(source))
			}
		}
	}

	@Test
	fun `valid constant casts agree with native truncation wrapping rounding and copies`(@TempDir directory: Path) {
		val expected = "[-128, 127, -1, 2]\n[0, 255, 255]\n100000002004087734272\n18446744073709551616\n127\nnull\n127\n[null, 127]\n[0, null]\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) assertEquals(expected, runStructProgram(abi, valid, directory.resolve("enum_constants_${abi.layout.sizeBits}")))
	}

	@Test
	fun `validated constant payloads emit verified IR and objects for every target`(@TempDir directory: Path) {
		val unit = structUnit(valid)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(unit), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("enum_constants_$index.o"))) > 0)
		}
	}

	@Test
	fun `invalid constant payloads stop CLI compilation before output and linker work`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("invalid.co"), "typedef<enum> E (pub def<int<8>> value) { BAD(128.0 -> int<8>) }\nfn main() {}")
		for ((index, triple) in structTargetTriples.withIndex()) {
			val parent = directory.resolve("output_$index")
			val text = StringBuilder()
			assertEquals(1, runCompiler(arrayOf("--emit", "object", "--target", triple, source.toString(), parent.resolve("enum.o").toString(), "--linker", "missing-linker"), DiagnosticReporter(text)))
			assertContains(text, "C3005")
			assertContains(text, "E.value")
			assertFalse(Files.exists(parent))
		}
	}

	@Test
	fun `ordinary dynamic casts retain their runtime guards`(@TempDir directory: Path) {
		val source = "fn<int<8>> convert(float value) = value -> int<8>\nfn main() { println(convert(128.0)) }"
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("dynamic_cast_${abi.layout.sizeBits}"), 1)
			assertContains(output, "Invalid numeric cast")
			assertFalse(output.contains("PEC:"))
		}
	}
}
