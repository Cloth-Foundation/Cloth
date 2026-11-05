package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.declaration.StructDeclaration
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.FieldAccessExpression
import cloth.parser.expression.NameExpression
import cloth.parser.statement.FieldWriteStatement
import cloth.token.Keywords
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StructMethodSyntaxTest {

	@Test
	fun `methods share function bodies and annotations and retain their spans`() {
		val unit = structUnit("""
			pub typedef<struct> Meter {
			    int value;
			    mt<int> value() = value;
			    mt<int, int> set(final opt<int> amount ?: self.value) {
			        self.value = amount
			        return self.value, amount
			    }
			}
			fn main() {}
		""")
		val struct = assertIs<StructDeclaration>(unit.declarations.first())
		assertIs<FunctionDeclaration>(unit.declarations.last())
		assertEquals("pub", struct.visibility?.lexeme)
		assertEquals(listOf("value"), struct.fields.map { it.name.lexeme })
		assertEquals(listOf("value", "set"), struct.methods.map { it.name.lexeme })
		val (getter, setter) = struct.methods
		assertIs<NameExpression>(assertIs<ExpressionBody>(getter.body).expression)
		assertEquals(3, getter.span.startLine)
		assertEquals(3, getter.span.endLine)
		assertEquals(4, setter.span.startLine)
		assertEquals(7, setter.span.endLine)
		assertEquals(2, setter.returnTypes.size)
		val parameter = setter.parameters.single()
		assertTrue(parameter.optional && !parameter.mutable)
		val fallback = assertIs<FieldAccessExpression>(parameter.defaultValue)
		assertEquals(Keywords.SELF.toString(), assertIs<NameExpression>(fallback.receiver).name.lexeme)
		assertIs<FieldWriteStatement>(assertIs<BlockBody>(setter.body).statements.first())
	}

	@Test
	fun `method only structs start directly with a method`() {
		val struct = assertIs<StructDeclaration>(structUnit("typedef<struct> Empty { mt tag() {} }").declarations.single())
		assertTrue(struct.fields.isEmpty())
		assertEquals("tag", struct.methods.single().name.lexeme)
	}

	@Test
	fun `struct member boundaries reject misplaced declarations and separators`() {
		val invalid = listOf(
			"mt loose() {}" to DiagnosticCode.METHOD_OUTSIDE_TYPE,
			"pub mt loose() {}" to DiagnosticCode.METHOD_OUTSIDE_TYPE,
			"fn work() { mt nested() {} }" to DiagnosticCode.METHOD_OUTSIDE_TYPE,
			"typedef<struct> S { int x mt work() {} }" to DiagnosticCode.EXPECTED_TOKEN,
			"typedef<struct> S { int x,; mt work() {} }" to DiagnosticCode.EXPECTED_TOKEN,
			"typedef<struct> S { ; mt work() {} }" to DiagnosticCode.EXPECTED_TOKEN,
			"typedef<struct> S { mt work() {}\n int x }" to DiagnosticCode.EXPECTED_TOKEN,
			"typedef<struct> S { pub mt work() {} }" to DiagnosticCode.EXPECTED_TOKEN,
			"typedef<struct> S { int x; fn work() {} }" to DiagnosticCode.FUNCTION_IN_TYPE,
			"typedef<struct> S { mt ready() {}\n pub fn work() {} }" to DiagnosticCode.FUNCTION_IN_TYPE,
		)
		for ((source, expected) in invalid) {
			val failure = assertFailsWith<SyntaxException>(source) { structUnit(source) }
			assertEquals(expected, failure.diagnosticCode, source)
			assertTrue(failure.sourceLabels.single().span.startColumn > 0)
		}
	}
}
