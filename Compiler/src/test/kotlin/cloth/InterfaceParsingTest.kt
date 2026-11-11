package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.parser.declaration.*
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.ResultListExpression
import cloth.token.TokenSpan
import org.junit.jupiter.api.Test
import kotlin.test.*

class InterfaceParsingTest {
	private fun text(span: TokenSpan): String =
		checkNotNull(span.source).text.lines()[span.startLine - 1].substring(span.startColumn - 1, span.endColumn - 1)

	@Test
	fun `interface declarations distinguish required signatures and explicit defaults`() {
		val declaration = assertIs<InterfaceDeclaration>(genericUnit("""
			pub typedef<interface> Reader<T> : Source<T>, Named {
			    mt<T> read(final opt<T?> value ?: null); mt done()
			    mt<T, int<8>> pair(T value) = default (value, 1);
			    mt<typeinfo> info() = default {
			        return T::type
			    }
			}
		""").declarations.single())

		assertEquals("interface", declaration.kind.lexeme)
		assertEquals("pub", declaration.visibility?.lexeme)
		assertEquals("T", declaration.typeParameters?.parameters?.single()?.name?.lexeme)
		assertEquals(listOf("Source<T>", "Named"), declaration.parents.map { it.spelling })
		val (required, done, pair, info) = declaration.methods
		assertEquals("mt<T> read(final opt<T?> value ?: null)", text(required.span))
		assertEquals("T?", required.parameters.single().type.spelling)
		assertTrue(required.parameters.single().optional)
		assertFalse(required.parameters.single().mutable)
		assertNull(required.fallback)
		assertNull(done.fallback)
		assertEquals(listOf("T", "int<8>"), pair.returnTypes.map { it.spelling })
		val fallback = assertNotNull(pair.fallback)
		assertEquals("default", fallback.keyword.lexeme)
		assertEquals(2, assertIs<ResultListExpression>(assertIs<ExpressionBody>(fallback.body).expression).values.size)
		assertIs<BlockBody>(info.fallback?.body)
	}

	@Test
	fun `class parents preserve order type arguments and written constructor arguments`() {
		val declaration = assertIs<ClassDeclaration>(genericUnit("""
			typedef<class> Child<T> (T value) : Base<T>(seed=value),
			    Reader<T>, Named {
			    override: pub mt<T> read() = value;
			    mt done() {}
			}
		""").declarations.single())

		assertEquals(listOf("Base<T>", "Reader<T>", "Named"), declaration.parents.map { it.type.spelling })
		assertEquals("seed", declaration.parents[0].constructorArguments?.single()?.label?.lexeme)
		assertNull(declaration.parents[1].constructorArguments)
		val (marked, ordinary) = declaration.members.filterIsInstance<MethodDeclaration>()
		assertEquals("override:", text(assertNotNull(marked.overridePrefix)))
		assertEquals("override: pub mt<T> read() = value", text(marked.span))
		assertEquals("pub", marked.visibility?.lexeme)
		assertNull(ordinary.overridePrefix)

		val explicit = assertIs<ClassDeclaration>(genericUnit("typedef<class> Child : Base()").declarations.single())
		assertEquals(emptyList(), explicit.parents.single().constructorArguments)
	}

	@Test
	fun `interface member errors stop at the first unsupported syntax`() {
		val cases = listOf(
			"typedef<interface> I { mt<int> read() { return 1 } }" to "{",
			"typedef<interface> I { mt<int> read() = (1) }" to "(",
			"typedef<interface> I { mt<int> read() = default { return 1 }; }" to ";",
			"typedef<interface> I { mt read() mt write() }" to "mt",
			"typedef<interface> I { pub mt read() }" to "pub",
			"typedef<interface> I : Parent() {}" to "(",
			"typedef<class> C { override mt read() {} }" to "mt",
			"typedef<class> C { override: def<int> value = 0 }" to "def",
			"typedef<class> C { pub override: mt read() {} }" to "override",
		)
		for ((source, token) in cases) {
			val failure = assertFailsWith<SyntaxException>(source) { genericUnit(source) }
			assertEquals(token, text(failure.sourceLabels.single().span), source)
		}
		val function = assertFailsWith<SyntaxException> { genericUnit("typedef<interface> I { pub fn read() {} }") }
		assertEquals(DiagnosticCode.FUNCTION_IN_TYPE, function.diagnosticCode)
	}
}
