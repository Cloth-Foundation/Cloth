package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.*
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.*
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.SemanticException
import cloth.semantic.typing.FunctionAnalyzer
import com.opencastsoftware.yvette.handlers.graphical.GraphicalReportHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ClassParsingTest {

	private fun parser(source: String) = Parser(Lexer(SourceFile("classes.co", source.trimIndent())).scanTokens())

	@Test
	fun `class headers distinguish fields from parameters and retain visibility and defaults`() {
		val declaration = assertIs<ClassDeclaration>(parser("""
			pub final typedef<class> Counter (
			    int seed,
			    pub def<string?> name ?: ("Unknown"),
			    def<int> value,
			    opt<int> step ?: 1
			) {
			    pub def<int> start = seed;
			    def<int> count = value
			    pub mt<int> read() = self.count
			}
		""").parse().declarations.single())
		assertEquals("Counter", declaration.name.lexeme)
		assertEquals("pub", declaration.visibility?.lexeme)
		assertEquals("final", declaration.finalKeyword?.lexeme)
		val (seed, name, value, step) = declaration.parameters
		assertEquals(listOf("seed", "name", "value", "step"), declaration.parameters.map { it.parameter.name.lexeme })
		assertNull(seed.fieldKeyword)
		assertEquals("def", name.fieldKeyword?.lexeme)
		assertEquals("pub", name.visibility?.lexeme)
		assertEquals("string?", name.parameter.type.spelling)
		assertTrue(name.parameter.optional)
		assertEquals("Unknown", assertIs<StringLiteral>(name.parameter.defaultValue).value)
		assertNull(name.parameter.optKeyword)
		assertNull(value.visibility)
		assertFalse(value.parameter.optional)
		assertNull(step.fieldKeyword)
		assertTrue(step.parameter.optional)
		val (start, count, read) = declaration.members
		assertEquals("pub", assertIs<ClassFieldDeclaration>(start).visibility?.lexeme)
		assertNull(assertIs<ClassFieldDeclaration>(count).visibility)
		assertEquals("pub", assertIs<MethodDeclaration>(read).visibility?.lexeme)
	}

	@Test
	fun `bodyless declarations leave the next declaration and its span intact`() {
		val declarations = parser("""
			typedef<class> Empty
			final typedef<class> Explicit()
			pub typedef<class> Braced
			{
			}
			typedef<struct> Marker {}
			fn main() {}
		""").parse().declarations
		val classes = declarations.filterIsInstance<ClassDeclaration>()
		assertEquals(listOf("Empty", "Explicit", "Braced"), classes.map { it.name.lexeme })
		assertEquals(listOf(1, 2, 5), classes.map { it.span.endLine })
		assertTrue(classes.all { it.parameters.isEmpty() && it.members.isEmpty() })
		assertIs<StructDeclaration>(declarations[3])
		assertIs<FunctionDeclaration>(declarations[4])
	}

	@Test
	fun `named constructors have primary delegation and a separate body`() {
		val declaration = assertIs<ClassDeclaration>(parser("""
			typedef<class> Counter (def<int> value ?: 0) {
			    Counter(float input) : (value=input -> int) { self.value += 1 }
			    Counter(bool ignored) : () {}
			    ~Counter { println(self.value) }
			}
		""").parse().declarations.single())
		val (converted, defaulted, destructor) = declaration.members
		val constructor = assertIs<ConstructorDeclaration>(converted)
		assertEquals("Counter", constructor.name.lexeme)
		assertEquals("input", constructor.parameters.single().name.lexeme)
		val argument = constructor.delegation.suppliedArguments.single()
		assertEquals("value", argument.label?.lexeme)
		assertIs<CastExpression>(argument.expression)
		assertEquals(1, constructor.body.statements.size)
		assertTrue(assertIs<ConstructorDeclaration>(defaulted).delegation.arguments.isEmpty())
		assertEquals("Counter", assertIs<DestructorDeclaration>(destructor).name.lexeme)
	}

	@Test
	fun `new and delete are distinct from state calls and compiler data`() {
		val function = assertIs<FunctionDeclaration>(parser("""
			fn lifecycle() {
			    def a = Counter::new
			    def b = Counter::new()
			    def c = Counter::new(value=1)
			    c::delete
			    c::type
			    c::activate()
			}
		""").parse().declarations.single())
		val statements = assertIs<BlockBody>(function.body).statements
		val creations = statements.take(3).map { assertIs<ClassCreationExpression>(assertIs<BindingDeclaration>(it).initializer) }
		assertEquals(listOf(0, 0, 1), creations.map { it.arguments.size })
		assertEquals("value", creations.last().suppliedArguments.single().label?.lexeme)
		val accesses = statements.drop(3).map { assertIs<ExpressionStatement>(it).expression }
		assertIs<ClassDeletionExpression>(accesses[0])
		assertIs<ProviderAccessExpression>(accesses[1])
		assertIs<StateCallExpression>(accesses[2])
	}

	@Test
	fun `malformed class syntax reports the first offending token`() {
		for ((source, offending) in listOf(
			"typedef<class> C { Wrong() : () {} }" to "Wrong",
			"typedef<class> C { ~Wrong {} }" to "Wrong",
			"typedef<class> C { C() {} }" to "{",
			"typedef<class> C { C() : self() {} }" to "self",
			"typedef<class> C { ctor() : () {} }" to "ctor",
			"typedef<class> C (pub int input)" to "int",
			"typedef<class> C (def<int> input = 7)" to "=",
			"final typedef<struct> S {}" to "struct",
			"fn main() { value::delete() }" to "(",
		)) {
			val failure = assertFailsWith<SyntaxException>(source) { parser(source).parse() }
			val span = failure.sourceLabels.single().span
			assertEquals(offending, source.substring(span.startColumn - 1, span.endColumn - 1), source)
		}
		val failure = assertFailsWith<SyntaxException> { parser("typedef<class> C { pub fn bad() {} }").parse() }
		assertEquals(DiagnosticCode.FUNCTION_IN_TYPE, failure.diagnosticCode)
	}

	@Test
	fun `class creation requires a declared class type`() {
		val failure = assertFailsWith<SemanticException> { FunctionAnalyzer(nativeTestLayout).analyze(parseMain("Counter::new()", "classes.co")) }
		assertEquals(DiagnosticCode.INVALID_CLASS_LIFECYCLE, failure.diagnosticCode)
		assertLocated(failure)
	}

	@Test
	fun `CLI emits one proven stale class diagnostic before output`(@TempDir directory: Path) {
		val source = directory.resolve("classes.co")
		Files.writeString(source, "typedef<class> Counter (def<string> text)\nfn main() { def item = Counter::new(\"buffer\"); item::delete; item::delete }")
		val destination = directory.resolve("output/program")
		val output = StringBuilder()
		val handler = GraphicalReportHandler.builder().withColours(false).withUnicode(false).withTerminalLinks(false).withCauseChain(false).buildFor(output)
		assertEquals(1, runCompiler(arrayOf(source.toString(), destination.toString(), "--linker", "missing-linker"), DiagnosticReporter(output, handler)))
		assertEquals(1, Regex(DiagnosticCode.DELETED_CLASS_ACCESS.id).findAll(output).count())
		assertFalse(output.contains("C9000"))
		assertFalse(output.contains("missing-linker"))
		assertFalse(Files.exists(destination.parent))
	}
}
