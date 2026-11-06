package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.declaration.StructDeclaration
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.token.TokenSpan
import org.junit.jupiter.api.Test
import kotlin.test.*

class StructParsingTest {

	private fun parse(text: String) = Parser(Lexer(SourceFile("structs.co", text.trimIndent())).scanTokens()).parse()
	private fun body(text: String) = assertIs<BlockBody>(assertIs<FunctionDeclaration>(parse(text).declarations.single()).body).statements
	private fun textAt(span: TokenSpan): String {
		val text = assertNotNull(span.source).text
		fun offset(line: Int, column: Int) = text.lineSequence().take(line - 1).sumOf { it.length + 1 } + column - 1
		return text.substring(offset(span.startLine, span.startColumn), offset(span.endLine, span.endColumn))
	}

	@Test
	fun `struct headers retain visibility field types source order and spans`() {
		val declarations = parse("""
			fn<Position> origin() = { 0, 0 };
			pub typedef<struct> Position {
			    int x,
			    int[2][3]? grid
			}
			typedef<struct> Marker {}
		""").declarations
		assertIs<FunctionDeclaration>(declarations[0])
		val position = assertIs<StructDeclaration>(declarations[1])
		assertEquals("Position", position.name.lexeme)
		assertEquals("pub", position.visibility?.lexeme)
		assertEquals(listOf("x", "grid"), position.fields.map { it.name.lexeme })
		assertEquals("int[2][3]?", position.fields[1].type.spelling)
		assertEquals("int[2][3]? grid", textAt(position.fields[1].span))
		assertEquals("pub typedef<struct> Position {\n    int x,\n    int[2][3]? grid\n}", textAt(position.span))
		val marker = assertIs<StructDeclaration>(declarations[2])
		assertNull(marker.visibility)
		assertTrue(marker.fields.isEmpty())
	}

	@Test
	fun `named binding braces distinguish lists from deferred blocks`() {
		val statements = body("""
			fn build(bool flag) {
			    def<Marker> empty = {}
			    def<Score?> one := { 7 }
			    def<Position> pair = {
			        1
			        , 2
			    }
			    def<Position> deferred = {
			        if (flag) { return { 1, 2 } } else { return origin() }
			    }
			    def<void> unchanged = {}
			}
		""").map { assertIs<BindingDeclaration>(it) }
		assertTrue(assertIs<StructLiteral>(statements[0].initializer).fields.isEmpty())
		assertEquals("{ 7 }", textAt(assertIs<StructLiteral>(statements[1].initializer).span))
		assertFalse(statements[1].mutable)
		assertEquals(2, assertIs<StructLiteral>(statements[2].initializer).fields.size)
		val deferred = assertIs<FunctionBlockExpression>(statements[3].initializer)
		val branch = assertIs<IfExpression>(assertIs<ExpressionStatement>(assertIs<BlockBody>(deferred.declaration.body).statements.single()).expression)
		assertIs<StructLiteral>(assertIs<ReturnStatement>(branch.branches.single().statements.single()).value)
		assertIs<FunctionBlockExpression>(statements[4].initializer)
	}

	@Test
	fun `nested deferred returns do not change the enclosing literal into a block`() {
		val binding = assertIs<BindingDeclaration>(body("""
			fn build(bool flag) {
			    def<Score> score = { if (flag) { def<int> later = { return 7 }; later } else { 8 } }
			}
		""").single())
		val literal = assertIs<StructLiteral>(binding.initializer)
		val field = assertIs<IfExpression>(literal.fields.single())
		assertIs<FunctionBlockExpression>(assertIs<BindingDeclaration>(field.branches.single().statements.first()).initializer)
	}

	@Test
	fun `literals stay single values in calls returns assignments defaults and result lists`() {
		val unit = parse("""
			fn<Position> make(opt<Position> value ?: { 1, 2 }) = { 3, 4 }
			fn<Position, int> build() {
			    def<Position, int> position, number = { 5, 6 }, 7
			    position = { 8, 9 }
			    consume({ { 1, 2 }, [ { 3 }, {} ] })
			    return { 10, 11 }, number
			}
		""")
		val make = assertIs<FunctionDeclaration>(unit.declarations[0])
		assertIs<StructLiteral>(make.parameters.single().defaultValue)
		assertIs<StructLiteral>(assertIs<ExpressionBody>(make.body).expression)
		val statements = assertIs<BlockBody>(assertIs<FunctionDeclaration>(unit.declarations[1]).body).statements
		val group = assertIs<BindingGroupDeclaration>(statements[0])
		assertIs<StructLiteral>(assertIs<ResultListExpression>(group.initializer).values.first())
		assertIs<StructLiteral>(assertIs<AssignmentStatement>(statements[1]).value)
		val argument = assertIs<CallExpression>(assertIs<ExpressionStatement>(statements[2]).expression).arguments.single()
		val fields = assertIs<StructLiteral>(argument).fields
		assertIs<StructLiteral>(fields[0])
		assertTrue(assertIs<ArrayLiteral>(fields[1]).elements.all { it is StructLiteral })
		assertIs<StructLiteral>(assertIs<ResultListExpression>(assertIs<ReturnStatement>(statements[3]).value).values.first())
	}

	@Test
	fun `comma lists without type annotations remain literals and deferred broadcasts keep distinct bodies`() {
		val statements = body("""
			fn build() {
			    def point = { 1, 2 }
			    def<Position> first, second = { return origin() }
			}
		""")
		assertIs<StructLiteral>(assertIs<BindingDeclaration>(statements[0]).initializer)
		val first = assertIs<FunctionBlockExpression>(assertIs<BindingDeclaration>(statements[1]).initializer)
		val second = assertIs<FunctionBlockExpression>(assertIs<BindingDeclaration>(statements[2]).initializer)
		assertEquals("first", first.declaration.name.lexeme)
		assertEquals("second", second.declaration.name.lexeme)
		assertNotSame(first.declaration.body, second.declaration.body)
	}

	@Test
	fun `member chains retain field call index and provider nodes`() {
		val statements = body("""
			fn access() {
			    items[0].record.name.trim()
			    items[0].record.name::type
			    make({ 1, 2 }).x
			}
		""").map { assertIs<ExpressionStatement>(it).expression }
		val call = assertIs<MemberCallExpression>(statements[0])
		val name = assertIs<FieldAccessExpression>(call.receiver)
		val record = assertIs<FieldAccessExpression>(name.receiver)
		assertIs<IndexExpression>(record.receiver)
		assertEquals("items[0].record.name", textAt(name.span))
		assertEquals("trim", call.name.lexeme)
		assertIs<FieldAccessExpression>(assertIs<ProviderAccessExpression>(statements[1]).receiver)
		assertIs<CallExpression>(assertIs<FieldAccessExpression>(statements[2]).receiver)
	}

	@Test
	fun `field writes retain their path operator and single RHS evaluation`() {
		val writes = body("""
			fn update() {
			    rows[index()].position.x = 10
			    record.items[index()] += step()
			    record.position.x++
			    record.items[1]--
			}
		""").map { assertIs<FieldWriteStatement>(it) }
		assertEquals(listOf("=", "+=", "++", "--"), writes.map { it.operator.lexeme })
		assertEquals("rows[index()].position.x = 10", textAt(writes[0].span))
		val indexed = assertIs<IndexExpression>(writes[1].target)
		assertIs<FieldAccessExpression>(indexed.array)
		assertIs<CallExpression>(indexed.index)
		assertIs<CallExpression>(writes[1].value)
		assertNull(writes[2].value)
		assertNull(writes[3].value)
	}

	@Test
	fun `malformed fields lists and writes fail at their original tokens`() {
		for ((source, offending) in listOf(
			"typedef<struct> S { int }" to "}",
			"typedef<struct> S { int x, }" to "}",
			"typedef<struct> S { int x\n int y }" to "int",
			"fn test() { def<S> s = { 1, } }" to "}",
			"fn test() { def<S> s = { 1\n 2 } }" to "2",
			"fn test() { useValue({ 1, }) }" to "}",
			"fn test() { s.x := 1 }" to ":=",
			"typedef<struct> S {};" to ";",
		)) {
			val failure = assertFailsWith<SyntaxException>(source) { parse(source) }
			assertEquals(offending, textAt(failure.sourceLabels.single().span), source)
		}
	}

	@Test
	fun `functions in structs are rejected and other typedef kinds remain unsupported`() {
		for (visibility in listOf("", "pub ")) {
			val failure = assertFailsWith<SyntaxException> { parse("typedef<struct> S { ${visibility}fn forbidden() {} }") }
			assertEquals(DiagnosticCode.FUNCTION_IN_TYPE, failure.diagnosticCode)
			assertEquals("fn", textAt(failure.sourceLabels.single().span))
			assertContains(assertNotNull(failure.guidance), "mt")
		}
		for (kind in listOf("interface", "state")) {
			val failure = assertFailsWith<SyntaxException> { parse("pub typedef<$kind> S {}") }
			assertEquals(kind, textAt(failure.sourceLabels.single().span))
		}
	}
}
