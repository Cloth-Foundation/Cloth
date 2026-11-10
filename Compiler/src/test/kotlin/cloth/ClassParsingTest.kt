package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
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
	fun `nested states and bodyless contracts retain their syntax and spans`() {
		val declarations = parser("""
			pub typedef<class> Player(def<int> power) {
			    pub state<int, bool> ::attack(opt<int> amount ?: self.power);
			    state ::reset()
			    default: ({ power })
			    pub state<Damage> Alive {
			        init { println(state.value); println(self is state) }
			        pub mt<int, bool> attack(int amount) = amount, true
			    }
			}
			typedef<class> Waiting {
			    default:
			    state<void> Ready {}
			}
		""").parse().declarations
		val (explicit, voidContract, nested) = assertIs<ClassDeclaration>(declarations[0]).members
		val contract = assertIs<StateMethodContractDeclaration>(explicit)
		assertEquals("pub", contract.visibility?.lexeme)
		assertEquals("attack", contract.name.lexeme)
		assertEquals(listOf("int", "bool"), contract.returnTypes.map { it.name.lexeme })
		assertTrue(contract.parameters.single().optional)
		assertEquals("power", assertIs<FieldAccessExpression>(contract.parameters.single().defaultValue).name.lexeme)
		assertEquals(2, contract.span.endLine)
		assertEquals(5, contract.span.startColumn)
		assertTrue(assertIs<StateMethodContractDeclaration>(voidContract).returnTypes.isEmpty())

		val state = assertIs<NestedStateDeclaration>(nested)
		assertEquals("Alive", state.name.lexeme)
		assertEquals("Damage", state.payloadType.name.lexeme)
		assertEquals("pub", state.visibility?.lexeme)
		val initial = assertNotNull(state.initial)
		assertEquals("default", initial.keyword.lexeme)
		assertIs<StructLiteral>(initial.payload)
		assertEquals(4, initial.span.startLine)
		assertEquals(5 + "default: ({ power })".length, initial.span.endColumn)
		assertEquals(initial.span.startLine, state.span.startLine)
		assertEquals(8, state.span.endLine)
		val initializer = assertIs<StateInitializerDeclaration>(state.members[0])
		val (first, second) = initializer.body.statements.map { assertIs<CallExpression>(assertIs<ExpressionStatement>(it).expression) }
		val field = assertIs<FieldAccessExpression>(first.arguments.single())
		assertEquals("state", assertIs<NameExpression>(field.receiver).name.lexeme)
		assertEquals("value", field.name.lexeme)
		val valueCheck = assertIs<BinaryExpression>(second.arguments.single())
		assertEquals("state", assertIs<NameExpression>(valueCheck.right).name.lexeme)
		assertEquals("attack", assertIs<MethodDeclaration>(state.members[1]).name.lexeme)

		val ready = assertIs<NestedStateDeclaration>(assertIs<ClassDeclaration>(declarations[1]).members.single())
		assertEquals("void", ready.payloadType.name.lexeme)
		val voidDefault = assertNotNull(ready.initial)
		assertNull(voidDefault.payload)
		assertEquals(voidDefault.span.startLine, voidDefault.span.endLine)
		assertEquals(5 + "default:".length, voidDefault.span.endColumn)
	}

	@Test
	fun `arrow targets retain parenthesized payloads and preserve casts and checks`() {
		val body = assertIs<BlockBody>(assertIs<FunctionDeclaration>(parser("""
			fn sample() {
			    player -> Alive
			    player -> Alive()
			    player -> Dead({ 1, "message" })
			    player is Dead
			    player -> Base
			    1 + 2 -> int<64>
			    player::attack(source=source)
			    player::type
			}
		""").parse().declarations.single()).body)
		val expressions = body.statements.map { assertIs<ExpressionStatement>(it).expression }
		assertEquals("Alive", assertIs<CastExpression>(expressions[0]).target.name.lexeme)
		val voidTransition = assertIs<StateTransitionExpression>(expressions[1])
		assertEquals("Alive", voidTransition.target.lexeme)
		assertNull(voidTransition.payload)
		assertEquals(5 + "player -> Alive()".length, voidTransition.span.endColumn)
		val transition = assertIs<StateTransitionExpression>(expressions[2])
		assertEquals("Dead", transition.target.lexeme)
		assertEquals(2, assertIs<StructLiteral>(transition.payload).fields.size)
		assertEquals(15, transition.target.span.startColumn)
		assertEquals(5 + "player -> Dead({ 1, \"message\" })".length, transition.span.endColumn)
		assertEquals("Dead", assertIs<TypeCheckExpression>(expressions[3]).target.name.lexeme)
		assertEquals("Base", assertIs<CastExpression>(expressions[4]).target.name.lexeme)
		assertIs<BinaryExpression>(assertIs<CastExpression>(expressions[5]).operand)
		assertEquals("source", assertIs<StateCallExpression>(expressions[6]).suppliedArguments.single().label?.lexeme)
		assertIs<ProviderAccessExpression>(expressions[7])
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
			"typedef<class> C { default state<void> Ready {} }" to "state",
			"typedef<class> C { default: () state<void> Ready {} }" to ")",
			"typedef<class> C { default: state ::run() }" to "::",
			"typedef<class> C { default: pub mt run() {} }" to "mt",
			"typedef<class> C { state<int, bool> Ready {} }" to "bool",
			"typedef<class> C { state Ready {} }" to "{",
			"typedef<class> C { state<void> Ready { init } }" to "}",
			"typedef<class> C { state ::run() {} }" to "{",
			"typedef<state> Ready for C {}" to ">",
			"pub typedef<state<void>> Ready for C {}" to "pub",
			"typedef<state<void>> Ready C {}" to "C",
			"fn main() { value -> Ready(1, 2) }" to ",",
			"fn main() { state }" to "state",
		)) {
			val failure = assertFailsWith<SyntaxException>(source) { parser(source).parse() }
			val span = failure.sourceLabels.single().span
			assertEquals(offending, source.substring(span.startColumn - 1, span.endColumn - 1), source)
		}
		val failure = assertFailsWith<SyntaxException> { parser("typedef<class> C { pub fn bad() {} }").parse() }
		assertEquals(DiagnosticCode.FUNCTION_IN_TYPE, failure.diagnosticCode)
		val annotation = assertFailsWith<ClothDiagnostic> { parser("typedef<class> C { @Initial state<void> Ready {} }").parse() }
		assertEquals(DiagnosticCode.UNEXPECTED_CHARACTER, annotation.diagnosticCode)
		assertEquals(1, annotation.sourceLabels.single().span.let { it.endColumn - it.startColumn })
	}

	@Test
	fun `state dispatch and payload errors precede publication`(@TempDir directory: Path) {
		val text = """
			typedef<class> C {
			    default: pub state<void> Ready { pub mt run() {} }
			    pub state<void> Blocked {}
			}
			fn test(C item) { if (item is Blocked) { item::run(); item::run() } }
			fn main() {}
		""".trimIndent()
		val unit = parser(text).parse()
		val destination = directory.resolve("state-output/program")
		val failure = assertFailsWith<SemanticException> { LLVMEmitter().compileObject(unit, destination.resolveSibling("state.o")) }
		assertEquals(DiagnosticCode.INVALID_STATE_DISPATCH, failure.diagnosticCode)
		assertEquals(2, failure.sourceLabels.size)
		val span = failure.sourceLabels.first().span
		assertEquals("run", text.lines()[span.startLine - 1].substring(span.startColumn - 1, span.endColumn - 1))
		val source = Files.writeString(directory.resolve("dispatch.co"), text)
		val output = StringBuilder()
		val handler = GraphicalReportHandler.builder().withColours(false).withUnicode(false).withTerminalLinks(false).withCauseChain(false).buildFor(output)
		assertEquals(1, runCompiler(arrayOf("--no-stdlib", source.toString(), destination.toString(), "--linker", "missing-linker"), DiagnosticReporter(output, handler)))
		assertEquals(1, Regex(DiagnosticCode.INVALID_STATE_DISPATCH.id).findAll(output).count())
		assertFalse(output.contains("missing-linker"))
		assertFalse(Files.exists(destination.parent))
		val invalid = Files.writeString(directory.resolve("invalid.co"), """
			typedef<class> C { default: pub state<void> Ready {} }
			fn main() {
			    def value = C::new()
			    value -> Ready(1)
			    value -> Ready(2)
			}
		""".trimIndent())
		val payloadDestination = directory.resolve("invalid-output/program")
		val payloadOutput = StringBuilder()
		val payloadHandler = GraphicalReportHandler.builder().withColours(false).withUnicode(false).withTerminalLinks(false).withCauseChain(false).buildFor(payloadOutput)
		assertEquals(1, runCompiler(arrayOf("--no-stdlib", invalid.toString(), payloadDestination.toString()), DiagnosticReporter(payloadOutput, payloadHandler)))
		assertEquals(1, Regex(DiagnosticCode.INVALID_STATE_PAYLOAD.id).findAll(payloadOutput).count())
		assertFalse(Files.exists(payloadDestination.parent))
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
