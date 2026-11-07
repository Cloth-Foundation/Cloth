package cloth

import cloth.ast.SourceUnit
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.EnumDeclaration
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class EnumPayloadDiagnosticsTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("enum-payload-diagnostics.co", source.trimIndent())).scanTokens()).parse()
	private fun failure(unit: SourceUnit, code: DiagnosticCode): ClothDiagnostic {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(unit) }
		assertEquals(code, error.diagnosticCode, error.summary)
		assertLocated(error)
		assertFalse(error.summary.contains("<payload."))
		return error
	}

	@Test
	fun `case payload mismatches identify the value field and case independently of case count`() {
		for (cases in listOf("BAD(\"badé😀\")", "BAD(\"badé😀\"), GOOD(1)", "GOOD(1), BAD(\"badé😀\")")) {
			val unit = parse("typedef<enum> E (pub def<int> code) {\r\n\t$cases\r\n}")
			val declaration = assertIs<EnumDeclaration>(unit.declarations.single())
			val field = declaration.fields.single()
			val case = declaration.cases.single { it.name.lexeme == "BAD" }
			val error = failure(unit, DiagnosticCode.INVALID_ENUM_MEMBER)
			assertContains(error.summary, "'E.code'")
			assertContains(error.summary, "'BAD'")
			assertContains(error.summary, "'int'")
			assertContains(error.summary, "'string'")
			assertEquals(listOf(case.values.single().span, field.type.span, case.name.span), error.sourceLabels.map { it.span })
			assertSame(case.values.single().span.source, error.sourceCode())
		}
	}

	@Test
	fun `invalid header defaults are checked even when every case overrides them`() {
		for (cases in listOf("A", "A, B", "A(1)", "A(1), B(2)")) {
			val unit = parse("typedef<enum> E (pub def<int> code = \"wrong\") { $cases }")
			val field = assertIs<EnumDeclaration>(unit.declarations.single()).fields.single()
			val error = failure(unit, DiagnosticCode.INVALID_ENUM_MEMBER)
			assertContains(error.summary, "Default")
			assertContains(error.summary, "'E.code'")
			assertEquals(listOf(checkNotNull(field.default).span, field.type.span), error.sourceLabels.map { it.span })
		}
	}

	@Test
	fun `payload field mismatches retain exact scalar nullable aggregate and array types`() {
		val inputs = listOf(
			"int" to "\"wrong\"",
			"bool" to "1",
			"int" to "null",
			"int?" to "\"wrong\"",
			"int<64>" to "1 -> int",
			"float<16>" to "1.5 -> float<32>",
			"int?[]" to "\"wrong\"",
			"S" to "1",
		)
		for ((type, value) in inputs) for (cases in listOf("A($value)", "A($value), B($value)")) {
			val unit = parse("typedef<struct> S { int code }\ntypedef<enum> E (pub def<$type> value) { $cases }")
			val declaration = unit.declarations.filterIsInstance<EnumDeclaration>().single()
			val error = failure(unit, DiagnosticCode.INVALID_ENUM_MEMBER)
			assertEquals(declaration.cases.first().values.single().span, error.sourceLabels.first().span)
			assertEquals(declaration.fields.single().type.span, error.sourceLabels[1].span)
		}
	}

	@Test
	fun `intrinsic literal cast array and struct failures retain their own diagnostic codes`() {
		val inputs = listOf(
			Triple("int<8>", "128", DiagnosticCode.INTEGER_RANGE),
			Triple("float<16>", "65520.0", DiagnosticCode.FLOAT_RANGE),
			Triple("int", "\"wrong\" -> int", DiagnosticCode.INVALID_CAST),
			Triple("int[2]", "[1]", DiagnosticCode.INVALID_ARRAY),
			Triple("int[2]", "[1, \"wrong\"]", DiagnosticCode.INVALID_ARRAY),
			Triple("S", "{ \"wrong\" }", DiagnosticCode.INVALID_STRUCT_FIELD),
			Triple("S", "{ 1, 2 }", DiagnosticCode.INVALID_STRUCT_LITERAL),
		)
		for ((type, value, code) in inputs) for (cases in listOf("A($value)", "A($value), B($value)")) {
			failure(parse("typedef<struct> S { int code }\ntypedef<enum> E (pub def<$type> value) { $cases }"), code)
		}
	}

	@Test
	fun `payload validation stops in header then case and argument order`() {
		val cases = parse("""
			typedef<enum> E (pub def<int> left = 1, pub def<int> right = 2) {
			    FIRST(1, "first"), SECOND("second", 2)
			}
		""")
		val declaration = assertIs<EnumDeclaration>(cases.declarations.single())
		val error = failure(cases, DiagnosticCode.INVALID_ENUM_MEMBER)
		assertContains(error.summary, "'FIRST'")
		assertContains(error.summary, "'E.right'")
		assertEquals(declaration.cases.first().values[1].span, error.sourceLabels.first().span)
		assertEquals(declaration.fields[1].type.span, error.sourceLabels[1].span)
		val defaults = parse("typedef<enum> E (pub def<int> left = 1, pub def<int> right = \"default\") { FIRST(\"case\", 2) }")
		val defaultField = assertIs<EnumDeclaration>(defaults.declarations.single()).fields[1]
		val defaultError = failure(defaults, DiagnosticCode.INVALID_ENUM_MEMBER)
		assertContains(defaultError.summary, "Default")
		assertEquals(checkNotNull(defaultField.default).span, defaultError.sourceLabels.first().span)
	}

	@Test
	fun `payload validation preserves contextual numerics structs nullable arrays and defaults`() {
		val unit = parse("""
			typedef<struct> Record { string label, int?[2] values }
			typedef<enum> E (
			    pub def<Record> entry = { "default", [null, 1] },
			    pub def<float<16>?> fraction = 1.5,
			    pub def<usize> count = 2
			) {
			    FIRST({ "é😀", [1, null] }, null, 0),
			    DEFAULT,
			    LAST({ "last", [null, 3] }, -1.5, 3)
			}
			fn<Record> record() = E.DEFAULT.entry
			fn<float<16>?> fraction(bool first) = if (first) { E.FIRST.fraction } else { E.LAST.fraction }
			fn<usize> count() = E.LAST.count
			fn<int?[2]> keys() = E.FIRST.entry.values
		""")
		for (abi in structRunners()) FunctionAnalyzer(abi.layout).analyze(unit)
	}

	@Test
	fun `CLI payload failures stop before output and linker work on every target`(@TempDir directory: Path) {
		for ((caseIndex, cases) in listOf("BAD(\"wrong\")", "BAD(\"wrong\"), OTHER(false)").withIndex()) {
			val source = Files.writeString(directory.resolve("enum_$caseIndex.co"), "typedef<enum> E (pub def<int> code) { $cases }\nfn main() {}")
			for ((targetIndex, triple) in structTargetTriples.withIndex()) {
				val parent = directory.resolve("output_${caseIndex}_$targetIndex")
				val output = parent.resolve("enum.o")
				val text = StringBuilder()
				val status = runCompiler(arrayOf("--emit", "object", "--target", triple, source.toString(), output.toString(), "--linker", "missing-linker"), DiagnosticReporter(text))
				assertEquals(1, status, text.toString())
				assertContains(text, "C3095")
				assertContains(text, "E.code")
				assertFalse(text.contains("C3032"))
				assertFalse(text.contains("C3016"))
				assertFalse(text.contains("<payload."))
				assertFalse(Files.exists(parent))
			}
		}
	}
}
