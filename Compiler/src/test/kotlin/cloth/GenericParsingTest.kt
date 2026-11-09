package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.token.TokenSpan
import org.junit.jupiter.api.Test
import kotlin.test.*

class GenericParsingTest {
	private fun unit(source: String) = Parser(Lexer(SourceFile("generic-syntax.co", source.trimIndent())).scanTokens()).parse()
	private fun type(source: String) = (unit("fn<$source> value() = null").declarations.single() as FunctionDeclaration).returnTypes.single()
	private fun expression(source: String) = ((unit("fn main() { $source }").declarations.single() as FunctionDeclaration).body as BlockBody).statements.single().let { (it as ExpressionStatement).expression }
	private fun text(span: TokenSpan): String {
		val lines = assertNotNull(span.source).text.split('\n')
		fun offset(line: Int, column: Int) = lines.take(line - 1).sumOf { it.length + 1 } + column - 1
		return assertNotNull(span.source).text.substring(offset(span.startLine, span.startColumn), offset(span.endLine, span.endColumn))
	}

	@Test
	fun `class and struct owner parameters retain original names and delimiter spans`() {
		val declarations = unit("""
			pub final typedef<class> Box<A, B> (def<A> first) {}
			typedef<struct> Pair<A, B> { A first, B second }
		""").declarations
		val box = assertIs<ClassDeclaration>(declarations[0])
		val pair = assertIs<StructDeclaration>(declarations[1])
		for (parameters in listOf(assertNotNull(box.typeParameters), assertNotNull(pair.typeParameters))) {
			assertEquals(listOf("A", "B"), parameters.parameters.map { it.name.lexeme })
			assertEquals("<A, B>", text(parameters.span))
			assertEquals("<", text(parameters.opening.span))
			assertEquals(">", text(parameters.closing.span))
			assertEquals("B", text(parameters.parameters[1].span))
		}
		assertEquals("A", box.parameters.single().parameter.type.spelling)
	}

	@Test
	fun `nested arguments retain numeric widths nullable positions and fixed growable extents`() {
		val syntax = "Map<unsigned int<16>, Box<int[]?[]?>>[2]?"
		val annotation = type(syntax)
		assertEquals(syntax, annotation.spelling)
		assertNull(annotation.width)
		assertEquals(2, annotation.typeArguments.size)
		val integer = annotation.typeArguments[0]
		assertTrue(integer.unsigned)
		assertEquals("16", integer.width?.lexeme)
		assertNull(integer.application)
		assertEquals("int[]?[]?", annotation.typeArguments[1].typeArguments.single().spelling)
		assertEquals(listOf("2"), annotation.arraySizes.map { it?.lexeme })
		assertEquals(syntax, text(annotation.span))
		assertEquals("<unsigned int<16>, Box<int[]?[]?>>", text(assertNotNull(annotation.application).span))
	}

	@Test
	fun `multiline owner and argument lists retain complete UTF16 source spans`() {
		val declaration = assertIs<StructDeclaration>(unit("""
			// 😀 source prefix
			typedef<struct> Holder<
			    T,
			    U
			> { Pair<
			    T,
			    U[]?
			> value }
		""").declarations.single())
		assertContains(text(assertNotNull(declaration.typeParameters).span), "\n")
		val field = declaration.fields.single().type
		assertEquals("Pair<T, U[]?>", field.spelling)
		assertEquals("U[]?", text(field.typeArguments[1].span))
		assertSame(declaration.name.span.source, field.typeArguments[1].name.span.source)
	}

	@Test
	fun `type receivers retain generic applications through construction providers and postfix access`() {
		val read = assertIs<FieldAccessExpression>(expression("Box<List<int<129>[]>>::new(1).value"))
		val creation = assertIs<ClassCreationExpression>(read.receiver)
		assertEquals("Box<List<int<129>[]>>", assertIs<TypeReceiverExpression>(creation.receiver).annotation.spelling)
		assertEquals(1, creation.arguments.size)
		val metadata = assertIs<ProviderAccessExpression>(expression("Box<int[]>[2]?::type::rank"))
		val type = assertIs<ProviderAccessExpression>(metadata.receiver)
		assertEquals("Box<int[]>[2]?", assertIs<TypeReceiverExpression>(type.receiver).annotation.spelling)
	}

	@Test
	fun `generic base applications retain arguments separately from constructor arguments`() {
		val declaration = assertIs<ClassDeclaration>(unit("typedef<class> Derived<T> : Base<List<T>>(1) {}").declarations.single())
		val base = declaration.parents.single()
		assertEquals("Base<List<T>>", base.type.spelling)
		assertEquals("Base", base.type.name.lexeme)
		assertEquals(1, assertNotNull(base.constructorArguments).size)
		assertEquals(": Base<List<T>>(1)", text(base.span))
	}

	@Test
	fun `return lists optional wrappers selectors casts and checks preserve type applications`() {
		val declaration = assertIs<FunctionDeclaration>(unit("fn<Box<int>, Pair<string, int[]>> f(opt<Box<int>?> value ?: null) = null, null").declarations.single())
		assertEquals(listOf("Box<int>", "Pair<string, int[]>"), declaration.returnTypes.map { it.spelling })
		assertEquals("Box<int>?", declaration.parameters.single().type.spelling)
		assertTrue(declaration.parameters.single().optional)
		val cast = assertIs<CastExpression>(expression("value -> Box<List<int>>"))
		assertEquals("Box<List<int>>", cast.target.spelling)
		val check = assertIs<TypeCheckExpression>(assertIs<BinaryExpression>(expression("value is Box<int> && flag")).left)
		assertEquals("Box<int>", check.target.spelling)
		val box = assertIs<ClassDeclaration>(unit("typedef<class> Box<T> { operator<int<auto>> +() = 1 }").declarations.single())
		assertEquals("int<auto>", assertIs<MethodDeclaration>(box.members.single()).operator?.selector?.spelling)
	}

	@Test
	fun `ordinary comparisons and numeric casts do not become generic applications`() {
		val chain = assertIs<BinaryExpression>(expression("left < right > limit"))
		assertEquals(">", chain.operator.lexeme)
		assertEquals("<", assertIs<BinaryExpression>(chain.left).operator.lexeme)
		val numeric = assertIs<BinaryExpression>(expression("value -> int<8> < limit"))
		assertEquals("8", assertIs<CastExpression>(numeric.left).target.width?.lexeme)
		val named = assertIs<BinaryExpression>(expression("value -> Named < low > high"))
		assertEquals(">", named.operator.lexeme)
		assertNull(assertIs<CastExpression>(assertIs<BinaryExpression>(named.left).left).target.application)
	}

	@Test
	fun `empty malformed and trailing generic lists fail at the first syntax error`() {
		for (source in listOf(
			"typedef<class> Box<> {}", "typedef<struct> Box<T,> {}", "typedef<class> Box<T U> {}",
			"fn<Box<>> f() = null", "fn<Box<int,>> f() = null", "fn<Box<int> f() = null",
			"fn main() { Box<>::new }", "fn main() { Box<int,>::type }"
		)) {
			val error = assertFailsWith<ClothDiagnostic>(source) { unit(source) }
			assertEquals(DiagnosticCode.EXPECTED_TOKEN, error.diagnosticCode, source)
			assertLocated(error)
		}
	}

	@Test
	fun `constraints defaults and generic functions methods enums retain an explicit syntax boundary`() {
		for (source in listOf(
			"typedef<class> Box<T: Number> {}", "typedef<struct> Box<T = int> {}",
			"fn work<T>() {}", "typedef<class> Box { mt work<T>() {} }", "typedef<enum> Mode<T> { One }"
		)) {
			val error = assertFailsWith<ClothDiagnostic>(source) { unit(source) }
			assertEquals(DiagnosticCode.UNSUPPORTED_GENERIC_SYNTAX, error.diagnosticCode, source)
			assertLocated(error)
		}
	}

	@Test
	fun `nullable argument spelling is retained without admitting duplicate markers or opt arguments`() {
		assertEquals("Maybe<int?>?", type("Maybe<int?>?").spelling)
		val duplicate = assertFailsWith<ClothDiagnostic> { type("Maybe<int??>") }
		assertEquals(DiagnosticCode.EXPECTED_TOKEN, duplicate.diagnosticCode)
		val optional = assertFailsWith<ClothDiagnostic> { type("Box<opt<int>>") }
		assertEquals(DiagnosticCode.INVALID_GENERIC_ARGUMENT, optional.diagnosticCode)
		assertLocated(optional)
	}

	@Test
	fun `generic nesting permits 256 layers and rejects 257 before host recursion fails`() {
		val allowed = "Box<".repeat(256) + "int" + ">".repeat(256)
		assertEquals(allowed, type(allowed).spelling)
		for (source in listOf("fn<${"Box<".repeat(257)}int${">".repeat(257)}> f() = null", "fn main() { ${"Box<".repeat(257)}int${">".repeat(257)}::type }")) {
			val error = assertFailsWith<ClothDiagnostic> { unit(source) }
			assertEquals(DiagnosticCode.GENERIC_ARGUMENT_NESTING, error.diagnosticCode)
			assertLocated(error)
		}
	}
}
