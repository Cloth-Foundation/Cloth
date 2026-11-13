package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.api.SourceModuleRole
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
import cloth.semantic.typing.FunctionAnalyzer
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CollectionOwnershipRuntimeTest {

	// Exercise the registered private bindings before the source list is implemented.
	private val library = """
		pub final typedef<class> OwningList<T: Object> {
		    def<Object?> child = null
		    mt acquire(Object item) = intrinsic "collections.ownership.acquire"
		    mt release(Object item) = intrinsic "collections.ownership.release"
		    mt deleteChild(Object item) = intrinsic "collections.ownership.delete"
		    pub mt claim(T item) = acquire(item)
		    pub mt relinquish(T item) = release(item)
		    pub mt destroy(T item) = deleteChild(item)
		    pub mt hold(T item) {
		        acquire(item)
		        child = item
		    }
		    ~OwningList {
		        def<Object?> held = child
		        if (held != null) deleteChild(held)
		    }
		}
	""".trimIndent()

	private val objects = """
		typedef<interface> Readable { mt<int> read() }
		typedef<class> Entry (pub def<int> value) : Readable {
		    default: state<void> Ready {}
		    operator []?(usize index) -> bool = index == 0
		    pub operator [](usize index) -> int = value
		    override: mt<int> read() = value
		    ~Entry { println(value) }
		}
	""".trimIndent()

	private val lifecycle = """
		$objects
		fn<Readable> viewOf(Entry item) = item
		fn main() {
		    def item = Entry::new(1)
		    def view = viewOf(item)
		    def first = OwningList<Object>::new
		    def second = OwningList<Readable>::new
		    first.claim(item)
		    item.value++
		    println(view.read())
		    first.relinquish(view)
		    second.claim(view)
		    second.relinquish(item)
		    view::delete
		    def<Object?> absent = null
		    absent::delete
		    def owned = Entry::new(3)
		    second.claim(owned)
		    second.destroy(owned)
		    def recycled = Entry::new(4)
		    recycled::delete
		    first.hold(second)
		    second.hold(Entry::new(5))
		    first::delete
		}
	""".trimIndent()

	@Test
	fun `authority follows the object across class and interface views and resets on slot reuse`(@TempDir root: Path) {
		val expected = "2\n2\n3\n4\n5\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			assertEquals(expected, run(root.resolve("live_${abi.layout.sizeBits}"), abi, lifecycle))
		}
	}

	@Test
	fun `runtime rejects duplicate ownership cycles and unauthorized deletion before destruction`(@TempDir root: Path) {
		val cases = listOf(
			"first.claim(view)" to "Class object already has an owner",
			"second.claim(view)" to "Class object already has an owner",
			"item::delete" to "Class object deletion requires ownership authority",
			"view::delete" to "Class object deletion requires ownership authority",
			"second.destroy(view)" to "Class object deletion requires ownership authority",
			"second.relinquish(view)" to "Only the owning collection can release this object",
			"first.claim(first)" to "Collection insertion would create an ownership cycle",
			"first.claim(second); second.claim(third); third.claim(first)" to "Collection insertion would create an ownership cycle",
		)
		for (abi in structRunners()) for ((index, test) in cases.withIndex()) {
			val (operation, message) = test
			val source = """
				$objects
				fn main() {
				    def item = Entry::new(1)
				    def<Readable> view = item
				    def first = OwningList<Object>::new
				    def second = OwningList<Object>::new
				    def third = OwningList<Object>::new
				    first.claim(item)
				    $operation
				    println("unreachable")
				}
			""".trimIndent()
			assertEquals("$message\n", run(root.resolve("invalid_${abi.layout.sizeBits}_$index"), abi, source, status = 1), operation)
		}
	}

	@Test
	fun `a stale alias cannot claim a replacement in the same slot`(@TempDir root: Path) {
		val source = """
			$objects
			fn main() {
			    def list = OwningList<Object>::new
			    def item = Entry::new(1)
			    list.claim(item)
			    list.destroy(item)
			    def recycled = Entry::new(2)
			    println(recycled[0])
			    list.claim(item)
			    println("unreachable")
			}
		""".trimIndent()
		for (abi in structRunners()) {
			assertEquals("1\n2\nClass object is no longer live\n", run(root.resolve("stale_${abi.layout.sizeBits}"), abi, source, status = 1))
		}
	}

	@Test
	fun `ownership bindings require the selected library origin private methods and exact signature`(@TempDir root: Path) {
		val rejects = listOf(
			request(root.resolve("regular"), "fn main() {}", role = SourceModuleRole.REGULAR) to DiagnosticCode.INVALID_INTRINSIC_BINDING,
			request(root.resolve("wrong_path"), "fn main() {}", moduleName = "Lookalike") to DiagnosticCode.INVALID_INTRINSIC_BINDING,
			request(root.resolve("public"), "fn main() {}", binding = library.replace("mt acquire(", "pub mt acquire(")) to DiagnosticCode.INVALID_INTRINSIC_BINDING,
			request(root.resolve("wrong_type"), "fn main() {}", binding = library.replace("mt acquire(Object item)", "mt acquire(T item)")) to DiagnosticCode.INVALID_INTRINSIC_BINDING,
			request(root.resolve("external_call"), "fn main() { def list = OwningList<Object>::new; list.acquire(list) }") to DiagnosticCode.PRIVATE_CLASS_MEMBER,
		)
		for ((input, code) in rejects) {
			val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(SourceGraphLoader.load(input)) }
			assertEquals(code, error.diagnosticCode, error.summary)
			assertLocated(error)
		}
	}

	@Test
	fun `ownership runtime verifies executable IR and emits objects on every target profile`(@TempDir root: Path) {
		val graph = SourceGraphLoader.load(request(root, lifecycle))
		for ((index, triple) in structTargetTriples.withIndex()) {
			val abi = NativeCABI.forTarget(triple)
			val program = ProgramAnalyzer(abi.layout).analyze(FileBindings.graph(graph))
			val emitter = LLVMEmitter(abi)
			val ir = emitter.emitExecutable(program)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "define internal void @cloth.objects.acquire_ownership(i64")
			val output = root.resolve("ownership_$index.o")
			emitter.compileObject(program.functions, output)
			assertTrue(Files.size(output) > 0)
		}
	}

	private fun run(root: Path, abi: NativeCABI, source: String, status: Int = 0): String {
		val input = request(root, source, target = abi.targetTriple)
		return runExecutable(ClothCompiler.compile(input).artifact, status).replace("\r\n", "\n")
	}

	private fun request(
		root: Path, source: String, target: String? = null, binding: String = library,
		moduleName: String = "OwningList", role: SourceModuleRole = SourceModuleRole.STANDARD_LIBRARY,
	): CompilationRequest {
		val selected = root.resolve("library")
		val collections = Files.createDirectories(selected.resolve("cloth/collections"))
		Files.writeString(collections.resolve("$moduleName.co"), binding)
		val application = Files.createDirectories(root.resolve("app"))
		val main = Files.writeString(application.resolve("Main.co"), "using cloth.collections.$moduleName : OwningList\n" + source)
		return CompilationRequest(main, root.resolve("out/program"), listOf(SourceModule("StandardLibrary", selected, role)), targetTriple = target)
	}
}
