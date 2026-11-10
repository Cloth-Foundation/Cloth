package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.*
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.BindingGroupDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.declarations.DeclarationAnalyzer
import cloth.semantic.declarations.TypeResolver
import cloth.semantic.symbols.SignatureType
import cloth.semantic.typing.FunctionAnalyzer
import cloth.token.TokenSpan
import cloth.token.TokenType
import org.junit.jupiter.api.Test
import kotlin.test.*

class NullabilityParsingTest {

	private fun parser(source: String) = Parser(Lexer(SourceFile("nullable.co", source.trimIndent())).scanTokens())
	private fun unit(source: String) = parser(source).parse()
	private fun function(source: String) = assertIs<FunctionDeclaration>(unit(source).declarations.single())
	private fun expression(source: String) = assertIs<ExpressionStatement>(parseMain(source, "nullable.co").mainStatements.single()).expression
	private fun annotation(source: String) = function("fn<$source> value() = null").returnTypes.single()
	private fun text(span: TokenSpan): String {
		val source = assertNotNull(span.source).text
		val lines = source.split('\n')
		fun offset(line: Int, column: Int) = lines.take(line - 1).sumOf { it.length + 1 } + column - 1
		return source.substring(offset(span.startLine, span.startColumn), offset(span.endLine, span.endColumn))
	}

	@Test
	fun `null and Elvis have dedicated nodes with complete source spans`() {
		val absent = assertIs<NullLiteral>(expression("null"))
		assertEquals("null", text(absent.span))
		val fallback = assertIs<ElvisExpression>(expression("null ?: 25"))
		assertIs<NullLiteral>(fallback.left)
		assertIs<IntegerLiteral>(fallback.fallback)
		assertEquals("null ?: 25", text(fallback.span))
		assertEquals("?:", text(fallback.operator.span))
	}

	@Test
	fun `Elvis is right associative below logic comparisons and casts`() {
		val chain = assertIs<ElvisExpression>(expression("a ?: b ?: c"))
		assertIs<NameExpression>(chain.left)
		assertIs<ElvisExpression>(chain.fallback)
		val logical = assertIs<ElvisExpression>(expression("a || b ?: c && d"))
		assertEquals("||", assertIs<BinaryExpression>(logical.left).operator.lexeme)
		assertEquals("&&", assertIs<BinaryExpression>(logical.fallback).operator.lexeme)
		val comparison = assertIs<ElvisExpression>(expression("count ?: 0 == 1"))
		assertEquals("==", assertIs<BinaryExpression>(comparison.fallback).operator.lexeme)
		val compared = assertIs<BinaryExpression>(expression("(count ?: 0) == 1"))
		assertIs<ElvisExpression>(compared.left)
		val cast = assertIs<ElvisExpression>(expression("a -> int<64> ?: b -> int<64>"))
		assertIs<CastExpression>(cast.left)
		assertIs<CastExpression>(cast.fallback)
	}

	@Test
	fun `guarded hops own the remaining postfix chain up to a group or Elvis`() {
		val fallback = assertIs<ElvisExpression>(expression("a?.b.c()?.d[0] ?: 5"))
		val first = assertIs<GuardedAccessExpression>(fallback.left)
		val second = assertIs<GuardedAccessExpression>(first.continuation)
		val unguarded = assertIs<MemberCallExpression>(second.receiver)
		assertSame(first.payload, assertIs<FieldAccessExpression>(unguarded.receiver).receiver)
		assertSame(second.payload, assertIs<FieldAccessExpression>(assertIs<IndexExpression>(second.continuation).array).receiver)
		assertEquals("a?.b.c()?.d[0]", text(first.span))
		assertEquals("?.", text(second.operator.span))
		val outside = assertIs<FieldAccessExpression>(expression("(a?.b).c"))
		assertIs<GuardedAccessExpression>(outside.receiver)
	}

	@Test
	fun `fallback parsing preserves argument result array and interpolation boundaries`() {
		val call = assertIs<CallExpression>(expression("consume(a ?: b, null)"))
		assertEquals(2, call.arguments.size)
		assertIs<ElvisExpression>(call.arguments.first())
		assertIs<NullLiteral>(call.arguments.last())
		val returned = assertIs<ResultListExpression>(assertIs<ExpressionBody>(function("fn<int, int> pair() = a ?: b, c ?: d").body).expression)
		assertEquals(2, returned.values.size)
		assertTrue(returned.values.all { it is ElvisExpression })
		val array = assertIs<ArrayLiteral>(expression("[a ?: b, c ?: null]"))
		assertEquals(2, array.elements.size)
		val interpolation = assertIs<InterpolatedString>(expression("#\"value={a ?: b}\""))
		assertEquals(1, interpolation.parts.count { it is ElvisExpression })
		val conditional = assertIs<ElvisExpression>(expression("if (flag) { a } else { b } ?: c"))
		assertIs<IfExpression>(conditional.left)
		assertIs<IfExpression>(assertIs<ElvisExpression>(expression("a ?: if (flag) { b } else { c }")).fallback)
	}

	@Test
	fun `nullable type qualifiers retain their exact structural positions and spans`() {
		for (spelling in listOf("int?", "unsigned int<64>?", "usize?", "isize?", "bool?", "float<16>?", "typeinfo?", "string?[]", "string[]?", "string?[]?", "int[2]?[]", "int[2][]?")) {
			val type = annotation(spelling)
			assertTrue(type.hasNullability)
			assertEquals(spelling, type.spelling)
			assertEquals(spelling, text(type.span))
			assertEquals(spelling, SignatureType.of(type).spelling)
		}
		val elements = annotation("string?[]")
		val array = annotation("string[]?")
		assertNotNull(elements.nullable)
		assertNull(array.nullable)
		assertNull(elements.arrayNullability.single())
		assertNotNull(array.arrayNullability.single())
		assertNotEquals(SignatureType.of(elements), SignatureType.of(array))
		assertNotEquals(SignatureType.of(annotation("int[2]?[]")), SignatureType.of(annotation("int[2][]?")))
	}

	@Test
	fun `nullable annotations work in parameters returns receptions casts and type receivers`() {
		val declaration = function("""
			fn<int?, bool?> sample(final unsigned int<8>? input) {
				def<int?, bool?> value, _ = source()
				def<usize?> count = null
				return value, null
			}
		""")
		assertEquals(listOf("int?", "bool?"), declaration.returnTypes.map { it.spelling })
		assertEquals("unsigned int<8>?", declaration.parameters.single().type.spelling)
		assertFalse(declaration.parameters.single().mutable)
		val statements = assertIs<BlockBody>(declaration.body).statements
		assertEquals(listOf("int?", "bool?"), assertIs<BindingGroupDeclaration>(statements[0]).types.map { it.spelling })
		assertIs<NullLiteral>(assertIs<BindingDeclaration>(statements[1]).initializer)
		assertEquals("int?", assertIs<CastExpression>(expression("value -> int?")).target.spelling)
		val receiver = assertIs<TypeReceiverExpression>(assertIs<ProviderAccessExpression>(expression("int?::type")).receiver)
		assertEquals("int?", receiver.annotation.spelling)
		val lexemes = Lexer(SourceFile("tokens.co", "int?::type a?:b null")).scanTokens().filter { it.type != TokenType.EOF }.map { it.lexeme }
		assertEquals(listOf("int", "?", "::", "type", "a", "?:", "b", "null"), lexemes)
	}

	@Test
	fun `signature identity normalizes widths without erasing nullable placement`() {
		val nullable = SignatureType.of(annotation("int?"))
		assertEquals(nullable, SignatureType.of(annotation("int<32>?")))
		assertNotEquals(nullable, SignatureType.of(annotation("int")))
		val declarations = DeclarationAnalyzer(nativeTestLayout).analyze(unit("fn take(int value) {}\nfn take(int? value) {}"))
		assertEquals(2, declarations.functions.size)
		val duplicate = assertFailsWith<ClothDiagnostic> { DeclarationAnalyzer(nativeTestLayout).analyze(unit("fn take(int? value) {}\nfn take(int<32>? value) {}")) }
		assertEquals(DiagnosticCode.DUPLICATE_FUNCTION, duplicate.diagnosticCode)
		assertLocated(duplicate)
	}

	@Test
	fun `scalar nullable descriptors preserve target integer and metadata identity`() {
		for (triple in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc")) {
			val target = NativeCABI.forTarget(triple).layout
			val resolver = TypeResolver(target)
			assertEquals(CNullable(CInt(64, unsigned = true)), resolver.resolve(annotation("unsigned int<64>?")))
			assertEquals(CNullable(CUSize(target.sizeBits)), resolver.resolve(annotation("usize?")))
			assertEquals(CNullable(CISize(target.sizeBits)), resolver.resolve(annotation("isize?")))
		}
		val nullable = CNullable(ValueType.INT)
		assertEquals("int?", nullable.spelling)
		assertEquals("int<32>?", nullable.metadataSpelling)
		assertFalse(nullable.numeric)
		assertTrue(nullable.containsNullability)
		assertTrue(nullable.accepts(ValueType.INT))
		assertTrue(nullable.accepts(CNullable(ValueType.INT)))
		assertFalse(nullable.accepts(CInt(64)))
		assertFalse(ValueType.INT.accepts(nullable))
		assertNull(nullable.provider("str", nativeTestLayout))
		assertNotNull(nullable.provider("type", nativeTestLayout))
		assertFailsWith<IllegalArgumentException> { CNullable(ValueType.VOID) }
		assertFailsWith<IllegalArgumentException> { CNullable(ValueType.NEVER) }
		assertFailsWith<IllegalArgumentException> { CNullable(nullable) }
	}

	@Test
	fun `invalid markers and nullable void fail as located first diagnostics`() {
		for (source in listOf("fn<int??> invalid() = null", "fn invalid(int?? value) {}", "fn invalid() { def<int[]??> value = null }", "fn invalid() { value ?: }")) {
			val failure = assertFailsWith<ClothDiagnostic> { unit(source) }
			assertLocated(failure)
		}
		for (source in listOf("fn<void?> invalid() = null", "fn invalid(void? value) {}", "fn invalid() { def<void?> value = null }", "fn invalid() { println(void?::type) }", "fn invalid() { value -> void? }")) {
			// A cast's operand name still follows ordinary name-resolution precedence.
			val resolved = source.replace("value -> void?", "null -> void?")
			val failure = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(unit(resolved)) }
			assertEquals(DiagnosticCode.INVALID_NULLABLE_TYPE, failure.diagnosticCode, source)
			assertLocated(failure)
		}
	}

	@Test
	fun `nullable scalars buffers and rows resolve in functions`() {
		for (source in listOf("fn<string?> value() = null", "fn main(string? value) {}", "fn main() { def<int[]?> value = null }", "fn main() { def<int?[]> value = [1] }")) FunctionAnalyzer(nativeTestLayout).analyze(unit(source))
		FunctionAnalyzer(nativeTestLayout).analyze(unit("fn f(int[]?[2] rows) {}"))
		val program = parseMain("def<int?> n = null", "nullable.co")
		assertEquals(CNullable(CInt()), FunctionAnalyzer(nativeTestLayout).analyze(program).typeOf(program.mainStatements.single()))
	}
}
