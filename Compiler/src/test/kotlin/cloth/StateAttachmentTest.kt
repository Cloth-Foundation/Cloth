package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.data.CInterface
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.parser.declaration.ClassDeclaration
import cloth.parser.declaration.NestedStateDeclaration
import cloth.parser.declaration.StateAttachmentDeclaration
import cloth.parser.declaration.TopLevelStateDeclaration
import cloth.parser.expression.StructLiteral
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
import cloth.semantic.typing.FunctionAnalyzer
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class StateAttachmentTest {

	private fun write(root: Path, sources: Map<String, String>) {
		for ((name, source) in sources) Files.writeString(root.resolve(name), source.trimIndent())
	}

	private fun request(root: Path, output: String = "unused.o", kind: OutputKind = OutputKind.OBJECT, triple: String? = null) = CompilationRequest(root.resolve("Main.co"), root.resolve(output), listOf(SourceModule("Game", root)), outputKind = kind, targetTriple = triple)

	private val sources = mapOf(
		"Contract.co" to """
			pub typedef<interface> Host { mt<string> name() }
		""",
		"Reuse.co" to """
			using Contract : Host
			pub typedef<struct> Label { string message, int number }
			fn<string> decorate(string value) = value + "!"
			typedef<state<Label>> pub Traveling for Host {
			    init {
			        println(self.name())
			        println(self::summary())
			    }
			    pub mt<string> summary() = decorate(state.message)
			    pub mt<Label> finish() {
			        if (!(self is Traveling)) exit(10)
			        self -> Ready({ 7 })
			        return state
			    }
			}
		""",
		"First.co" to """
			using Contract : Host
			using Reuse : Traveling
			pub typedef<struct> Marker { int tag }
			fn<string> decorate(string value) = "first:" + value
			pub typedef<class> First (def<string> title) : Host {
			    override: mt<string> name() = self.title
			    default: ({ decorate(self.title), 3 }) state Traveling
			    pub state<Marker> Ready {}
			}
			pub typedef<class> Leaf : First("leaf")
		""",
		"Second.co" to """
			using Contract : Host
			using Reuse : Traveling
			using Reuse : *
			pub typedef<struct> Marker { int tag }
			pub typedef<class> Parent {
			    default: state<void> BaseIdle {}
			    pub state<Marker> Ready {}
			}
			pub typedef<class> Second<T> (T seed) : Parent(), Host {
			    override: mt<string> name() = "second"
			    default: state<void> Idle {}
			    state Traveling
			}
		""",
		"Main.co" to """
			using First : Leaf
			using Second : Second
			fn<int> main() {
			    def first = Leaf::new
			    if (!(first is Traveling)) return 11
			    def saved = first::finish()
			    if (!(first is Ready) || saved.number != 3) return 12
			    first::delete
			    println(saved.message)
			    def second = Second<int>::new(0)
			    second -> Traveling({ "explicit", 5 })
			    def another = second::finish()
			    if (!(second is Ready) || another.number != 5) return 13
			    second::delete
			    println(another.message)
			    return 0
			}
		""",
	)

	@Test
	fun `standalone declarations and both attachment forms parse alongside nested states`() {
		val unit = structUnit("""
			typedef<state<void>> pub Empty for Host {}
			typedef<state<int>> Loaded for Host { pub mt<int> read() = state }
			typedef<class> Host {
			    default: (42) state Loaded; state Empty
			    state<void> Other {}
			}
		""")
		val (empty, loaded) = unit.declarations.filterIsInstance<TopLevelStateDeclaration>()
		assertEquals("pub", empty.visibility?.lexeme)
		assertEquals("Host", empty.host.name.lexeme)
		assertNull(loaded.visibility)
		assertEquals("int", loaded.payloadType.name.lexeme)
		val (initial, additional, nested) = unit.declarations.filterIsInstance<ClassDeclaration>().single().members
		assertNotNull(assertIs<StateAttachmentDeclaration>(initial).initial?.payload)
		assertEquals("Empty", assertIs<StateAttachmentDeclaration>(additional).name.lexeme)
		assertEquals("Other", assertIs<NestedStateDeclaration>(nested).name.lexeme)
	}

	@Test
	fun `one imported definition binds its body and targets independently for each host`(@TempDir root: Path) {
		write(root, sources)
		val model = FunctionAnalyzer(nativeTestLayout).analyze(SourceGraphLoader.load(request(root)))
		val declarations = model.declarations
		fun owner(name: String) = declarations.classDefinitions.keys.single { it.spelling == name }
		val first = declarations.states[owner("First")]!!.initial!!
		val second = declarations.states[owner("Second<int>")]!!.supported.getValue("Traveling")
		assertSame(first, declarations.states[owner("Leaf")]!!.initial)
		assertSame(assertNotNull(first.origin), second.origin)
		assertNotSame(first.declaration, second.declaration)
		assertSame(first.owner, first.initialPayload?.receiver?.owner)
		assertIs<CInterface>(first.activation?.receiver?.owner)
		assertSame(first.activation?.receiver?.owner, second.activation?.receiver?.owner)
		val targets = model.stateTransitions.values.filter { it.name.lexeme == "Ready" }
		assertEquals(setOf(owner("First"), owner("Parent")), targets.map { it.owner }.toSet())
		assertEquals(2, targets.map { it.payloadType }.distinct().size)
		val initial = assertIs<StructLiteral>(first.initial?.payload)
		assertSame(first.payloadType, model.typeOf(initial))
	}

	@Test
	fun `imported attachments run natively and verify IR and objects on every profile`(@TempDir root: Path) {
		write(root, sources)
		for (abi in structRunners()) {
			val executable = ClothCompiler.compile(request(root, "attached_state_${abi.layout.sizeBits}", OutputKind.EXECUTABLE, abi.targetTriple)).artifact
			val expected = "leaf\nfirst:leaf!\nfirst:leaf\nsecond\nexplicit!\nexplicit\n" + processCompletion().replace("\r\n", "\n")
			assertEquals(expected, runExecutable(executable).replace("\r\n", "\n"))
		}
		val graph = SourceGraphLoader.load(request(root))
		for ((index, triple) in structTargetTriples.withIndex()) {
			val abi = NativeCABI.forTarget(triple)
			val program = ProgramAnalyzer(abi.layout).analyze(FileBindings.graph(graph))
			val ir = LLVMEmitter(abi).emitExecutable(program)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "cloth.state.init.")
			val objectFile = ClothCompiler.compile(request(root, "attached_$index.o", triple = triple)).artifact
			assertTrue(Files.size(objectFile) > 0, triple)
		}
	}

	@Test
	fun `closed class contracts accept subclasses and private void attachments can be default`() {
		val model = FunctionAnalyzer(nativeTestLayout).analyze(structUnit("""
			typedef<class> Host<T> (pub def<T> exposed)
			typedef<state<void>> Closed for Host<string> { pub mt<string> read() = self.exposed }
			typedef<class> Child : Host<string>("base") { default: state Closed }
			typedef<class> Leaf : Child()
			fn<string> read(Leaf item) = item::read()
		"""))
		val classes = model.declarations.classDefinitions.keys.associateBy { it.name.lexeme }
		val child = model.declarations.states[classes.getValue("Child")]!!.initial!!
		assertSame(child, model.declarations.states[classes.getValue("Leaf")]!!.initial)
		assertNull(child.initialPayload)
		assertEquals("Host<string>", child.methods.values.single().receiver.owner.spelling)
	}

	@Test
	fun `attachment declarations diagnose incompatible hosts conflicts and invalid defaults`() {
		val cases = listOf(
			"typedef<class> C { default: state Missing }" to DiagnosticCode.INVALID_STATE_ATTACHMENT,
			"typedef<state<void>> Empty for int {}" to DiagnosticCode.INVALID_STATE_ATTACHMENT,
			"typedef<class> Host {}\ntypedef<state<void>> Empty for Host {}\ntypedef<class> Other { default: state Empty }" to DiagnosticCode.INVALID_STATE_ATTACHMENT,
			"typedef<interface> Host { mt<int> read() }\ntypedef<state<void>> Empty for Host {}\ntypedef<class> Other { pub mt<int> read() = 1; default: state Empty }" to DiagnosticCode.INVALID_STATE_ATTACHMENT,
			"typedef<state<void>> Empty for Host {}\ntypedef<class> Host { default: state Empty; state<void> Empty {} }" to DiagnosticCode.INVALID_STATE_DECLARATION,
			"typedef<state<void>> Empty for Host {}\ntypedef<class> Host { default: state Empty }\ntypedef<class> Child : Host() { default: state Empty }" to DiagnosticCode.INVALID_STATE_DECLARATION,
			"typedef<state<void>> Empty for Host {}\ntypedef<class> Host { state Empty }" to DiagnosticCode.INVALID_STATE_DECLARATION,
			"typedef<state<void>> Empty for Host {}\ntypedef<class> Host { default: (1) state Empty }" to DiagnosticCode.INVALID_STATE_PAYLOAD,
			"typedef<state<int>> Loaded for Host {}\ntypedef<class> Host { default: state Loaded }" to DiagnosticCode.INVALID_STATE_PAYLOAD,
		)
		for ((source, code) in cases) {
			val error = assertFailsWith<ClothDiagnostic>(source) { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(source)) }
			assertEquals(code, error.diagnosticCode, error.summary)
			assertLocated(error)
		}
	}

	@Test
	fun `state bodies use the host contract and check transition support for every attachment`() {
		val cases = listOf(
			"""
				typedef<class> Host (def<int> hidden) { default: state Empty }
				typedef<state<void>> Empty for Host { pub mt<int> read() = self.hidden }
			""" to DiagnosticCode.PRIVATE_CLASS_MEMBER,
			"""
				typedef<interface> Host {}
				typedef<class> C : Host { default: state Empty; pub mt<int> extra() = 1 }
				typedef<state<void>> Empty for Host { pub mt<int> read() = self.extra() }
			""" to DiagnosticCode.UNKNOWN_MEMBER,
			"""
				typedef<interface> Host {}
				typedef<state<void>> pub Empty for Host { pub mt go() { self -> Ready } }
				typedef<class> Good : Host {
				    default: state Empty
				    pub state<void> Ready {}
				}
				typedef<class> Bad : Host { default: state Empty }
			""" to DiagnosticCode.INVALID_STATE_TARGET,
			"""
				typedef<class> Host { default: state Empty }
				typedef<state<void>> Empty for Host {}
				fn change(Host item) { item -> Empty }
			""" to DiagnosticCode.PRIVATE_CLASS_MEMBER,
		)
		for ((source, code) in cases) {
			val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(source)) }
			assertEquals(code, error.diagnosticCode, error.summary)
			assertLocated(error)
		}
	}

	@Test
	fun `imports enforce state visibility and identity without attaching the state`(@TempDir root: Path) {
		val cases = listOf(
			"using Private : Empty\nfn main() {}" to DiagnosticCode.IMPORT_PRIVATE_NAME,
			"using First : Empty\nusing Second : *\nfn main() {}" to DiagnosticCode.IMPORT_NAME_CONFLICT,
			"using First : Empty\ntypedef<class> C {}\nfn enter(C item) { item -> Empty }" to DiagnosticCode.INVALID_STATE_TARGET,
		)
		write(root, mapOf(
			"Private.co" to "typedef<state<void>> Empty for Object {}",
			"First.co" to "typedef<state<void>> pub Empty for Object {}",
			"Second.co" to "typedef<state<void>> pub Empty for Object {}",
		))
		for ((source, code) in cases) {
			write(root, mapOf("Main.co" to source))
			val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(SourceGraphLoader.load(request(root))) }
			assertEquals(code, error.diagnosticCode, error.summary)
			assertLocated(error)
		}
	}
}
