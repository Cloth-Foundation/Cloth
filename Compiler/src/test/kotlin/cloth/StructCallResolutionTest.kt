package cloth

import cloth.data.CStruct
import cloth.data.ProviderReceiverForm
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.CallExpression
import cloth.parser.expression.ProviderAccessExpression
import cloth.parser.expression.StructLiteral
import cloth.parser.statement.BindingGroupDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class StructCallResolutionTest {

	private fun analyze(source: String) = FunctionAnalyzer().analyze(Parser(Lexer(SourceFile("struct-calls.co", source.trimIndent())).scanTokens()).parse())

	@Test
	fun `candidate field contexts select a unique overload and commit its nested plans`() {
		val model = analyze("""
			typedef<struct> Number { int value }
			typedef<struct> Text { string value }
			typedef<struct> A { Text item }
			typedef<struct> B { Number item }
			fn choose(A a) {}
			fn choose(B b) {}
			fn work() { choose({ { 5 } }) }
		""")
		val body = assertIs<BlockBody>(model.declarations.functions.last().declaration.body)
		val call = assertIs<CallExpression>(assertIs<ExpressionStatement>(body.statements.single()).expression)
		assertEquals("B", model.typeOf(model.selectedFunction(call)).parameters.single().spelling)
		val outer = assertIs<StructLiteral>(call.arguments.single())
		val inner = assertIs<StructLiteral>(outer.fields.single())
		assertEquals("B", model.types.structLiterals[outer]?.spelling)
		assertEquals("Number", model.types.structLiterals[inner]?.spelling)
	}

	@Test
	fun `equally viable nominal contexts stay ambiguous without a default struct type`() {
		val prefix = """
			typedef<struct> A { int x }
			typedef<struct> B { int<64> x }
			fn draw(A a) {}
			fn draw(B b) {}
		""".trimIndent()
		val failure = assertFailsWith<SemanticException> { analyze("$prefix\nfn work() { draw({ 10 }) }") }
		assertEquals(DiagnosticCode.AMBIGUOUS_CALL, failure.diagnosticCode)
		analyze("$prefix\nfn work() { def<A> point = { 10 }; draw(point) }")
	}

	@Test
	fun `default named conditional deferred and ordered results retain their contexts`() {
		val model = analyze("""
			typedef<struct> Point { int x, int y }
			fn<Point> identity(opt<Point> point ?: { 1, 2 }) = point
			fn<Point, int> pair(bool flag) {
			    def<Point> next = { return if (flag) { { 3, 4 } } else { { 5, 6 } } }
			    return identity(point = { next.x, next.y }), 7
			}
			fn work() {
			    def<Point, int> p, n = pair(true)
			    p = identity()
			}
		""")
		val body = assertIs<BlockBody>(model.declarations.functions.last().declaration.body)
		val group = assertIs<BindingGroupDeclaration>(body.statements.first())
		assertIs<CStruct>(model.types.receptionOf(group).slots.first().type)
		assertEquals("int", model.types.receptionOf(group).slots.last().type.spelling)
	}

	@Test
	fun `an unavailable parameter cannot shadow a named type in its default`() {
		val model = analyze("""
			typedef<struct> Point { int x }
			fn<typeinfo> describe(opt<typeinfo> Point ?: Point::type) = Point
		""")
		val fallback = assertIs<ProviderAccessExpression>(model.declarations.functions.single().parameters.single().declaration.defaultValue)
		assertEquals(ProviderReceiverForm.TYPE, model.types.providerAccess(fallback).receiverForm)
		assertSame(model.declarations.structs.getValue("Point"), model.types.metadata[fallback])
	}
}
