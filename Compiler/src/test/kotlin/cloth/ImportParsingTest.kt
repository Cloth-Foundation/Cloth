package cloth

import cloth.ast.ImportSelection
import cloth.ast.SourceUnit
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.ClassDeclaration
import cloth.parser.declaration.EnumDeclaration
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.declaration.StructDeclaration
import cloth.parser.exception.SyntaxException
import cloth.token.Keywords
import cloth.token.TokenSpan
import cloth.token.TokenType
import org.junit.jupiter.api.Test
import kotlin.test.*

class ImportParsingTest {

	private fun parse(text: String) = Parser(Lexer(SourceFile("imports.co", text)).scanTokens()).parse()
	private fun names(text: String) = assertIs<ImportSelection.Names>(parse(text).imports.single().selection).names.map { it.lexeme }
	private fun textAt(span: TokenSpan): String {
		val text = assertNotNull(span.source).text
		val lines = text.split('\n')
		fun offset(line: Int, column: Int) = lines.take(line - 1).sumOf { it.length + 1 } + column - 1
		return text.substring(offset(span.startLine, span.startColumn), offset(span.endLine, span.endColumn))
	}

	@Test
	fun `using is the canonical whole keyword and use has no keyword alias`() {
		val tokens = Lexer("using usingValue using_ USING use reuse").scanTokens().dropLast(1)
		assertEquals("using", Keywords.USING.toString())
		assertEquals(listOf(TokenType.Keyword) + List(5) { TokenType.Identifier }, tokens.map { it.type })
		assertEquals("usingValue", assertIs<FunctionDeclaration>(parse("fn usingValue() {}").declarations.single()).name.lexeme)
		assertEquals("use", assertIs<FunctionDeclaration>(parse("fn use() {}").declarations.single()).name.lexeme)
		assertFailsWith<SyntaxException> { parse("use token.Token : Token") }
		assertFailsWith<SyntaxException> { parse("fn using() {}") }
	}

	@Test
	fun `selective imports preserve mixed type and function names in written order`() {
		for (selection in listOf("Token, TokenType, TokenSpan, read", "read, TokenSpan, Token, TokenType", "read")) {
			assertEquals(selection.split(", "), names("using token.Token : $selection"))
		}
	}

	@Test
	fun `wildcards and single component file paths are explicit AST variants`() {
		val imported = parse("using Token : *").imports.single()
		assertEquals(listOf("Token"), imported.path.components.map { it.lexeme })
		assertTrue(imported.path.dots.isEmpty())
		assertEquals("Token", imported.path.spelling)
		assertEquals("*", assertIs<ImportSelection.Wildcard>(imported.selection).star.lexeme)
		assertEquals("using Token : *", textAt(imported.span))
		assertNull(imported.terminator)
	}

	@Test
	fun `headers and every ordinary declaration retain separate source order`() {
		val unit = parse("""
			// Header
			using token.Token : Token, read

			/* Another import */ using lexer.Lexer : *
			pub typedef<struct> Position { int x }
			typedef<class> Empty
			typedef<enum> Kind { One }
			fn main() {}
		""".trimIndent())
		assertEquals(listOf("token.Token", "lexer.Lexer"), unit.imports.map { it.path.spelling })
		assertEquals(4, unit.declarations.size)
		assertIs<StructDeclaration>(unit.declarations[0])
		assertIs<ClassDeclaration>(unit.declarations[1])
		assertIs<EnumDeclaration>(unit.declarations[2])
		assertEquals("main", assertIs<FunctionDeclaration>(unit.declarations[3]).name.lexeme)
	}

	@Test
	fun `one optional semicolon separates same line imports and following declarations`() {
		val unit = parse("using token.Token: Token; using lexer.Lexer: *; fn main() {}")
		assertEquals(2, unit.imports.size)
		assertTrue(unit.imports.all { it.terminator?.lexeme == ";" })
		assertEquals(listOf("using token.Token: Token", "using lexer.Lexer: *"), unit.imports.map { textAt(it.span) })
		assertEquals(1, unit.declarations.size)
		assertEquals(1, parse("using Token: Token;\n").imports.size)
		for (text in listOf("; using Token: Token", "using Token: Token;;", "using Token: Token\n;", "using Token: * using Other: *", "using Token: Token fn main() {}")) {
			assertFailsWith<SyntaxException>(text) { parse(text) }
		}
	}

	@Test
	fun `only colon and comma open multiline selection continuations`() {
		val imported = parse("""
			using token.Token : // Selection follows

			    Token, // More names
			    /* Skip this */ read,

			    TokenType
		""".trimIndent()).imports.single()
		val selected = assertIs<ImportSelection.Names>(imported.selection)
		assertEquals(listOf("Token", "read", "TokenType"), selected.names.map { it.lexeme })
		assertEquals(listOf(",", ","), selected.commas.map { textAt(it.span) })
		assertEquals(listOf(3, 4, 6), selected.names.map { it.span.startLine })
		assertIs<ImportSelection.Wildcard>(parse("using Token:\n\n *").imports.single().selection)
	}

	@Test
	fun `a completed selection ends at its newline and never absorbs another name`() {
		val unit = parse("using Token: Token\nusing Other: other\nfn main() {}")
		assertEquals(2, unit.imports.size)
		for (text in listOf("using Token: Token\nTokenType", "using Token: Token\n, TokenType", "using Token: *\n, TokenType")) {
			val failure = assertFailsWith<SyntaxException> { parse(text) }
			assertEquals(2, failure.sourceLabels.single().span.startLine)
		}
	}

	@Test
	fun `keyword dotted path and colon stay on one physical line`() {
		for (text in listOf(
			"using\ntoken.Token: Token", "using token\n.Token: Token", "using token.\nToken: Token",
			"using token.Token\n: Token", "using token /* across\nlines */ .Token: Token",
			"using token.Token /* across\nlines */ : Token",
		)) {
			assertFailsWith<SyntaxException>(text) { parse(text) }
		}
		assertEquals(listOf("Token"), names("using /* inline */ token . /* inline */ Token : Token"))
	}

	@Test
	fun `paths require identifier components and reject superseded dotted selections`() {
		for (text in listOf(
			"using : Token", "using .Token: Token", "using token..Token: Token", "using token.: Token",
			"using token.*: Token", "using token.1: Token", "using token.fn: Token", "using _: Token",
			"using token.Token.Token", "using token.Token.*", "using token/Token: Token", "using \"Token.co\": Token",
		)) {
			assertFailsWith<SyntaxException>(text) { parse(text) }
		}
	}

	@Test
	fun `empty mixed trailing comma alias and nested member selections are errors`() {
		for (text in listOf(
			"using Token:", "using Token: ;", "using Token: , Token", "using Token: Token,",
			"using Token: Token,;", "using Token: Token,\nfn main() {}", "using Token: *, Token",
			"using Token: Token, *", "using Token: Token read", "using Token: Token.read",
			"using Token: Token as Other", "using Token: Token = Other", "using Token: fn", "using Token: _",
		)) {
			assertFailsWith<SyntaxException>(text) { parse(text) }
		}
	}

	@Test
	fun `incomplete imports report the exact zero width EOF without advancing beyond it`() {
		for (text in listOf("using", "using token.", "using token.Token", "using token.Token:", "using Token: Token,", "using Token:\n")) {
			val parser = Parser(Lexer(SourceFile("unfinished.co", text)).scanTokens())
			val failure = assertFailsWith<SyntaxException> { parser.parse() }
			assertEquals(DiagnosticCode.EXPECTED_TOKEN, failure.diagnosticCode)
			assertEquals(TokenType.EOF, parser.peek().type)
			val span = failure.sourceLabels.single().span
			assertEquals(text.count { it == '\n' } + 1, span.startLine)
			assertEquals(text.substringAfterLast('\n').length + 1, span.startColumn)
			assertEquals(span.startLine, span.endLine)
			assertEquals(span.startColumn, span.endColumn)
		}
	}

	@Test
	fun `duplicate selectors stop at the repeat and label the original selection`() {
		val text = "using token.Token: Token,\n read, Token\nusing Broken: * , Another"
		val parser = Parser(Lexer(SourceFile("duplicate.co", text)).scanTokens())
		val failure = assertFailsWith<SyntaxException> { parser.parse() }
		assertEquals(DiagnosticCode.DUPLICATE_IMPORT_SELECTION, failure.diagnosticCode)
		assertEquals(listOf(2, 1), failure.sourceLabels.map { it.span.startLine })
		assertEquals(listOf("Token", "Token"), failure.sourceLabels.map { textAt(it.span) })
		assertEquals(TokenType.NewLine, parser.peek().type)
		val rendered = StringBuilder()
		DiagnosticReporter(rendered).report(failure)
		assertContains(rendered.toString(), "C2010")
		assertContains(rendered.toString(), "First selection here")
	}

	@Test
	fun `case distinct names and repeated imports are preserved for later binding`() {
		assertEquals(listOf("Token", "token"), names("using Token: Token, token"))
		val unit = parse("using Token: Token\nusing Token: Token\nusing Token: *")
		assertEquals(3, unit.imports.size)
		assertEquals(listOf("Token", "Token", "Token"), unit.imports.map { it.path.spelling })
	}

	@Test
	fun `imports after declarations and inside bodies are rejected at the import keyword`() {
		for (text in listOf(
			"fn main() {}\nusing Token: Token", "typedef<class> Empty\nusing Token: Token",
			"fn<int> value() = 1; using Token: Token", "fn main() { using Token: Token }",
			"fn main() { if (true) using Token: Token }", "fn main() { def<int> work := { using Token: Token } }",
			"typedef<struct> S { using Token: Token }", "typedef<class> C { using Token: Token }",
			"typedef<enum> E { using Token: Token }", "pub using Token: Token",
		)) {
			val failure = assertFailsWith<SyntaxException>(text) { parse(text) }
			assertEquals("using", textAt(failure.sourceLabels.single().span))
		}
		val failure = assertFailsWith<SyntaxException> { parse("fn main() {}\nusing") }
		assertEquals(DiagnosticCode.IMPORT_OUTSIDE_HEADER, failure.diagnosticCode)
	}

	@Test
	fun `ordinary function and type terminator restrictions still hold after an import header`() {
		for (declaration in listOf("fn main() {};", "typedef<struct> S {};", "typedef<class> C {};", "typedef<enum> E { One };")) {
			assertFailsWith<SyntaxException> { parse("using Token: *\n$declaration") }
		}
		assertEquals(2, parse("using Token: *; fn<int> first() = 1; fn second() {}").declarations.size)
	}

	@Test
	fun `empty and declaration only units preserve EOF snapshots and constructor compatibility`() {
		for (text in listOf("", "// No imports\n", "fn main() {}")) {
			val source = SourceFile("plain.co", text)
			val unit = Parser(Lexer(source).scanTokens()).parseDeclarations()
			assertTrue(unit.imports.isEmpty())
			assertSame(source, unit.source)
			assertSame(source, unit.end?.source)
		}
		val legacy = SourceUnit(emptyList())
		assertTrue(legacy.imports.isEmpty())
		assertNull(legacy.end)
		assertNull(legacy.source)
	}

	@Test
	fun `all path selection punctuation and import spans retain CRLF tabs and original source`() {
		val text = "// Heading\r\n\tusing λ.Token :\r\n\tToken, read;\r\nusing Other: *;\r\n"
		val source = SourceFile("original λ.co", text)
		val unit = Parser(Lexer(source).scanTokens()).parse()
		val imported = unit.imports.first()
		val selection = assertIs<ImportSelection.Names>(imported.selection)
		assertEquals("λ.Token", textAt(imported.path.span))
		assertEquals(listOf("λ", "Token"), imported.path.components.map { textAt(it.span) })
		assertEquals(".", textAt(imported.path.dots.single().span))
		assertEquals(2, imported.keyword.span.startColumn)
		assertEquals(":", textAt(imported.colon.span))
		assertEquals("Token, read", textAt(selection.span))
		assertEquals(",", textAt(selection.commas.single().span))
		assertEquals(";", textAt(assertNotNull(imported.terminator).span))
		assertEquals("using λ.Token :\r\n\tToken, read", textAt(imported.span))
		val wildcard = assertIs<ImportSelection.Wildcard>(unit.imports.last().selection)
		assertEquals("*", textAt(wildcard.span))
		val tokens = imported.path.components + imported.path.dots + selection.names + selection.commas + listOf(imported.keyword, imported.colon, imported.terminator, wildcard.star)
		assertTrue(tokens.all { it.span.source === source })
		assertSame(source, unit.source)
		assertEquals(TokenSpan(5, 1, 5, 1, source), unit.end)
	}

	@Test
	fun `multiline block comments preserve import boundaries without changing the lexer`() {
		val unit = parse("using Token: Token /* boundary\ncomment */ using Other: * /* boundary\ncomment */ fn main() {}")
		assertEquals(2, unit.imports.size)
		assertEquals(1, unit.declarations.size)
		assertEquals(listOf("Token", "read"), names("using Token: /* selection\ncomment */ Token, /* continuation\ncomment */ read"))
		val failure = assertFailsWith<SyntaxException> { parse("using Token: Token /* boundary\ncomment */ , read") }
		assertEquals(",", textAt(failure.sourceLabels.single().span))
	}

	@Test
	fun `a malformed first import aborts before the next import or declaration`() {
		val text = "using Token: *, Token\nusing Other: Other, Other\nfn main() {}"
		val parser = Parser(Lexer(SourceFile("fail-first.co", text)).scanTokens())
		val failure = assertFailsWith<SyntaxException> { parser.parse() }
		assertEquals(DiagnosticCode.EXPECTED_NEWLINE, failure.diagnosticCode)
		assertEquals(",", textAt(failure.sourceLabels.single().span))
		assertEquals(1, parser.peek().span.startLine)
		assertEquals(",", parser.peek().lexeme)
	}
}
