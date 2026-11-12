package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterfaceIntegrationTest {

	private val example = Path.of("examples/Interfaces.co")
	private val project = Path.of("examples/Interfaces").toAbsolutePath()
	private val completion get() = processCompletion().replace("\r\n", "\n")
	private fun importedRequest(output: Path, abi: NativeCABI): CompilationRequest {
		val app = project.resolve("app")
		val modules = listOf(SourceModule("App", app), SourceModule("Library", project.resolve("library")))
		return CompilationRequest(app.resolve("Main.co"), output, modules, targetTriple = abi.targetTriple)
	}

	private fun runNative(directory: Path, name: String, source: String, expected: String) {
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("${name}_${abi.layout.sizeBits}"))
			assertEquals(expected + completion, output)
		}
	}

	@Test
	fun `maintained fixtures verify executable IR and startup objects for every supported profile`(@TempDir directory: Path) {
		val source = Files.readString(example)
		runNative(directory, "views", source, "label: first\n[first, second]\nSlot<Value<string>>\n")
		val unit = structUnit(source)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val abi = NativeCABI.forTarget(triple)
			val emitter = LLVMEmitter(abi)
			val graph = SourceGraphLoader.load(importedRequest(directory.resolve("unused"), abi))
			val program = ProgramAnalyzer(abi.layout).analyze(FileBindings.graph(graph))
			val standalone = emitter.emitExecutable(unit)
			assertContains(standalone, "@cloth.objects.format")
			for ((name, ir) in listOf("standalone" to standalone, "imported" to emitter.emitExecutable(program))) {
				assertContains(ir, "target triple = \"$triple\"")
				assertContains(ir, "@cloth.interface.dispatch.")
				assertContains(ir, "@cloth.interface.fallback.")
				val entry = if (abi.windows) "wmain" else "main"
				assertEquals(1, ir.lineSequence().count { it.startsWith("define i32 @$entry(") })
				val objectFile = directory.resolve("${name}_$index.o")
				emitExecutableObject(emitter, ir, objectFile)
				assertTrue(Files.size(objectFile) > 0)
				System.getProperty("cloth.test.interfacePublicationArtifacts")?.let {
					val evidence = Files.createDirectories(Path.of(it))
					Files.writeString(evidence.resolve("$name-$triple.ll"), ir)
					Files.copy(objectFile, evidence.resolve("$name-$triple.o"), REPLACE_EXISTING)
				}
			}
		}
	}

	@Test
	fun `interface handles survive aggregate copies mutation result forwarding and deferred reads`(@TempDir directory: Path) {
		runNative(directory, "storage", """
			typedef<interface> Read { mt<int> read() }
			typedef<class> Cell (def<int> value) : Read {
			    override: mt<int> read() = value
			    ~Cell { println(value) }
			}
			typedef<struct> Entry<T> { T item }
			typedef<class> Shelf (pub def<Read> item, pub def<Read?> maybe, pub def<Entry<Read>[]> entries, pub def<Read[2]> fixed)
			fn<Read?, Read[]> pair(Shelf shelf) = shelf.maybe, [shelf.item, shelf.fixed[1]]
			fn<Read?, Read[]> forward(Shelf shelf) = pair(shelf)
			fn<int> main() {
			    def first = Cell::new(1)
			    def second = Cell::new(2)
			    def third = Cell::new(3)
			    def shelf = Shelf::new(first, null, [{ first }, { second }], [first, second])
			    def<Entry<Read>[]> original = shelf.entries
			    shelf.entries[0].item = third
			    shelf.fixed[1] = third
			    def count = 0
			    for (def entry <- shelf.entries) {
			        count += entry.item.read()
			        shelf.entries.clear()
			    }
			    if (count != 5 || original[0].item.read() != 1) return 1
			    def<Read> deferred = { return shelf.item }
			    if (deferred.read() != 1) return 2
			    shelf.item = second
			    if (deferred.read() != 2) return 3
			    shelf.maybe = third
			    if (shelf.maybe is Read) {
			        if (shelf.maybe.read() != 3) return 4
			    } else { return 5 }
			    def<Read?, Read[]> optional, values = forward(shelf)
			    def<Read?, Read[]> _, kept = forward(shelf)
			    optional, values = forward(shelf)
			    shelf::delete
			    if (!(optional === third) || values[0].read() != 2 || kept[1].read() != 3) return 6
			    if (values::type != Read[]::type) return 9
			    def<Read?[][]> rows = [[first, null]]
			    rows.append([second, third])
			    def<Read?[]> removed = rows.removeAt(0)
			    rows.clear()
			    if (!(removed[0] === first) || removed[1] != null) return 7
			    if (Read?[][]::type::element::element != Read?::type || removed[0]::type != Read?::type) return 8
			    println(count)
			    first::delete
			    second::delete
			    third::delete
			    return 0
			}
		""", "5\n1\n2\n3\n")
	}

	@Test
	fun `Object widening and class protocols preserve equality identity and text through views`(@TempDir directory: Path) {
		runNative(directory, "objects", """
			typedef<interface> Read { mt<int> read() }
			typedef<interface> Tag { mt<int> tag() }
			typedef<class> Item (pub def<int> value) : Read, Tag {
			    override: mt<int> read() = value
			    override: mt<int> tag() = value
			    operator ==(Object other) {
			        if (other is Item) return value == other.value
			        return false
			    }
			    operator ===(Item other) = true
			    operator ::str() { print(">"); return value::str }
			}
			typedef<class> Plain : Read { override: mt<int> read() = 0 }
			fn<int> main() {
			    def first = Item::new(5)
			    def second = Item::new(5)
			    def<Read> left = first
			    def<Tag> right = second
			    def<Object> root = left
			    def<Read?> some = left
			    def<Object?> widened = some -> Object?
			    def<Read?> none = null
			    def<Tag?> absent = null
			    def<Plain> plain = Plain::new
			    def<Read> plainView = plain
			    if (left != right || !(left == second) || !(root == right) || left == plainView) return 1
			    if (left === right || !(left === root) || !(first === second)) return 2
			    if (none != absent || !(none === absent) || some == absent || none != null || some === null) return 3
			    if (!(widened is Tag && widened.tag() == 5)) return 4
			    if (left::type != Read::type || root::type != Object::type || widened::type != Object?::type) return 5
			    println(left::str)
			    println(#"item {right}")
			    def<Read?[]> values = [left, null, second]
			    println(values)
			    println(plainView)
			    root::delete
			    second::delete
			    plain::delete
			    return 0
			}
		""", ">5\n>item 5\n>>[5, null, 5]\nPlain\n")
	}

	@Test
	fun `compound membership preserves a shared parent and leaves declared metadata intact`(@TempDir directory: Path) {
		runNative(directory, "narrow", """
			typedef<interface> Read { mt<int> read() }
			typedef<interface> Left : Read {}
			typedef<interface> Right : Read {}
			typedef<class> One : Left { override: mt<int> read() = 1 }
			typedef<class> Two : Right { override: mt<int> read() = 2 }
			fn<int> read(Object? input) {
			    if ((input is Left || input is Right) && input.read() > 0) {
			        if (input::type != Object?::type) return -1
			        return input.read()
			    }
			    return 0
			}
			fn<int> main() {
			    def<Left> first = One::new
			    def<Right> second = Two::new
			    def<Object?> absent = null
			    if (read(first) != 1 || read(second) != 2 || read(absent) != 0) return 1
			    first::delete
			    second::delete
			    println("read")
			    return 0
			}
		""", "read\n")
	}

	@Test
	fun `redundant class tests leave interface membership available for narrowing`(@TempDir directory: Path) {
		runNative(directory, "compound_membership", """
			typedef<interface> Read<T> { mt<T> read() }
			typedef<interface> Extended : Read<int> { mt<int> extra() = default (8) }
			typedef<class> Base
			typedef<class> Item<T> (def<T> value) : Base(), Read<T> {
			    override: mt<T> read() = value
			    pub mt<T> fromClass() = value
			}
			typedef<class> Child : Item<int>(7), Extended
			fn<int> rootFirst(Object? view) {
			    if (view is Object && view is Read<int>) return view.read()
			    return 0
			}
			fn<int> rootLast(Object? view) {
			    if (view is Read<int> && view is Object) return view.read()
			    return 0
			}
			fn<int> baseFirst(Base? view) {
			    if (view is Base && view is Read<int>) return view.read()
			    return 0
			}
			fn<int> concrete(Object? view) {
			    if (view is Read<int> && view is Item<int>) return view.fromClass()
			    return 0
			}
			fn<int> keepInterface(Extended? view) {
			    if (view is Read<int> && view is Object) return view.extra()
			    return 0
			}
			fn<int> main() {
			    def item = Child::new
			    if (rootFirst(item) != 7 || rootLast(item) != 7 || baseFirst(item) != 7 || concrete(item) != 7) return 1
			    if (rootFirst(null) != 0 || rootLast(null) != 0 || baseFirst(null) != 0 || concrete(null) != 0) return 2
			    if (keepInterface(item) != 8 || keepInterface(null) != 0) return 3
			    item::delete
			    println("read")
			    return 0
			}
		""", "read\n")
	}

	@Test
	fun `interface proofs are invalidated by writes and user formatting and equality`() {
		val declarations = """
			typedef<interface> Read { mt<int> read() }
			typedef<interface> Left : Read {}
			typedef<interface> Right : Read {}
			typedef<class> Holder (pub def<Read?> item)
			typedef<class> Mutator (def<Holder> holder) : Read {
			    override: mt<int> read() = 0
			    operator ::str() { holder.item = null; return "item" }
			    operator ==(Object other) { holder.item = null; return false }
			}
		""".trimIndent()
		for (effect in listOf("println(view)", "view::str", "#\"view {view}\"", "view == peer", "view != peer", "view.read()")) {
			interfaceFailure("""
				$declarations
				fn bad(Holder holder, Read view, Read peer) {
				    if (holder.item is Read) {
				        $effect
				        def<Read> current = holder.item
				    }
				}
			""", DiagnosticCode.TYPE_MISMATCH)
		}
		for (body in listOf("value = other; value.read()", "while (again) { value.read(); value = other }")) {
			interfaceFailure("""
				$declarations
				fn bad(Object value, Object other, bool again) {
				    if (value is Left || value is Right) { $body }
				}
			""", DiagnosticCode.UNKNOWN_MEMBER)
		}
		interfaceFailure("$declarations\nfn<string> bad(Read view, opt<string> text ?: view::str) = text", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
	}

	@Test
	fun `stored interface handles retain generation and checked protocols reject dead aliases`(@TempDir directory: Path) {
		for (abi in structRunners()) for ((index, operation) in listOf("println(slot.item)", "println(slot.item == replacement)").withIndex()) {
			val output = runStructProgram(abi, """
				typedef<interface> Read {}
				typedef<class> Cell : Read
				typedef<struct> Slot<T> { T item }
				fn main() {
				    def first = Cell::new
				    def<Slot<Read>> slot = { first }
				    first::delete
				    def replacement = Cell::new
				    println(slot.item === replacement)
				    $operation
				}
			""", directory.resolve("stale_${abi.layout.sizeBits}_$index"), 1)
			assertEquals("false\nClass object is no longer live\n", output)
		}
	}

	@Test
	fun `destructor formatting keeps immediate self access and rejects an alias`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val output = runStructProgram(abi, """
				typedef<interface> Read {}
				typedef<class> Cell : Read {
				    operator ::str() = "direct"
				    ~Cell { println((self -> Read)::str); throughAlias(self) }
				}
				fn throughAlias(Read view) { println(view) }
				fn main() { def<Read> view = Cell::new; view::delete }
			""", directory.resolve("destructor_${abi.layout.sizeBits}"), 1)
			assertEquals("direct\nClass object is no longer live\n", output)
		}
	}

	@Test
	fun `production requests execute transitive generic defaults and preserve colliding nominal identities`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val request = importedRequest(directory.resolve("imports_${abi.layout.sizeBits}"), abi)
			val output = runExecutable(ClothCompiler.compile(request).artifact).replace("\r\n", "\n")
			assertEquals("from library: ready\nSlot<Value<string>>\n" + completion, output)
		}
	}
}
