package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.SemanticException
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClassLifetimeTest {

	private val lifecycle = """
		fn<int> note(int marker, int value) { print(marker); return value }
		pub final typedef<class> Counter (int seed, def<int> step ?: note(2, 2)) {
		    def<int> value = note(3, seed + self.step)
		    Counter(float seed) : (note(1, seed -> int)) { print(4); return }
		    pub mt<int> add(opt<int> delta ?: step) {
		        self.value += delta
		        return read()
		    }
		    mt<int> read() = value
		    ~Counter { println(self.add()) }
		}
		typedef<class> Optional (pub def<int> value ?: if (true) { while (true) {} } else { 0 })
		fn<Counter> identity(Counter value) = value
		fn<int> main() {
		    def<Counter?> absent = null
		    absent::delete
		    def value = Counter::new(10f)
		    def alias := value
		    if (alias.add() != 14) return 1
		    alias::delete
		    def<Counter?> stale = identity(value)
		    if (stale == null) return 2
		    def supplied = Optional::new(9)
		    if (supplied.value != 9) return 3
		    supplied::delete
		    return 0
		}
	"""

	@Test
	fun `construction runs once before the secondary body and destructor self can dispatch`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val output = runStructProgram(abi, lifecycle, directory.resolve("lifecycle_${abi.layout.sizeBits}"))
			assertEquals("123416\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `class lifecycle IR verifies and emits objects on every target profile`(@TempDir directory: Path) {
		val unit = structUnit(lifecycle)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emitExecutable(unit)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "define internal i64 @cloth.objects.allocate")
			assertContains(ir, "define internal ptr @cloth.objects.lookup")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("class_$index.o"))) > 0)
		}
	}

	@Test
	fun `a stale alias stays invalid after slot reuse`(@TempDir directory: Path) {
		val source = """
			pub typedef<class> Item (pub def<int> value)
			fn<Item> throughCall(Item item) = item
			fn main() {
			    def first = Item::new(1)
			    def stale = throughCall(first)
			    first::delete
			    def second = Item::new(2)
			    println(second.value)
			    println(stale.value)
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("reuse_${abi.layout.sizeBits}"), 1)
			assertEquals("2\nClass object is no longer live\n", output)
		}
	}

	@Test
	fun `a destructor can grow the table while old instances remain live`(@TempDir directory: Path) {
		val source = """
			pub typedef<class> Node (pub def<int> value, pub def<Node?> previous)
			typedef<class> Chain {
			    ~Chain {
			        def<Node?> head = null
			        for (def i <- 0..80) { head = Node::new(i, head) }
			        def total = 0
			        while (head != null) {
			            def item = head
			            total += item.value
			            head = item.previous
			            item::delete
			        }
			        println(total)
			    }
			}
			fn main() {
			    def chain = Chain::new
			    chain::delete
			}
		"""
		for (abi in structRunners()) {
			assertEquals("3160\n" + processCompletion().replace("\r\n", "\n"), runStructProgram(abi, source, directory.resolve("grow_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `calls and writes recheck identities after evaluating operands`(@TempDir directory: Path) {
		for ((index, access) in listOf("item.write(dispose(item))", "item.value += dispose(item)", "item.defaulted()").withIndex()) {
			val source = """
				pub typedef<class> Item (pub def<int> value) {
				    pub mt write(int next) { println("body ran"); value = next }
				    pub mt defaulted(opt<int> n ?: dispose(self)) { println("body ran") }
				}
				fn<int> dispose(Item item) { item::delete; return 1 }
				fn main() { def item = Item::new(0); $access }
			"""
			assertEquals("Class object is no longer live\n", runStructProgram(NativeCABI.native(), source, directory.resolve("operand_$index"), 1))
		}
	}

	@Test
	fun `destructor privileges do not travel with a copied identity`(@TempDir directory: Path) {
		for ((index, action) in listOf("println(item.value)", "item::delete").withIndex()) {
			val source = """
				pub typedef<class> Item (pub def<int> value) {
				    ~Item { println(self.value); throughAlias(self) }
				}
				fn throughAlias(Item item) { $action }
				fn main() { def item = Item::new(7); item::delete }
			"""
			assertEquals("7\nClass object is no longer live\n", runStructProgram(NativeCABI.native(), source, directory.resolve("destruction_$index"), 1))
		}
	}

	@Test
	fun `proven dead aliases fail at compile time through local copies and joins`() {
		val type = "pub typedef<class> Item (pub def<int> value)\n"
		for (source in listOf(
			"fn bad() { def first = Item::new(1); def alias = first; first::delete; println(alias.value) }",
			"fn bad(bool flag) { def item = Item::new(1); if (flag) { item::delete } else { item::delete }; item::delete }",
			"fn bad(bool flag) { def item = Item::new(1); if (flag) return; item::delete; println(item.value) }",
			"fn bad() { def item = Item::new(1); def<Item?> alias = item; item::delete; alias::delete }",
			"fn bad() { def item = Item::new(1); do { item::delete } while (false); println(item.value) }",
			"fn bad() { def item = Item::new(1); do { item::delete; break } while (false); println(item.value) }",
			"fn bad() { def item = Item::new(1); do { item::delete; continue } while (false); println(item.value) }",
		)) {
			val failure = assertFailsWith<SemanticException>(source) { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(type + source)) }
			assertEquals(DiagnosticCode.DELETED_CLASS_ACCESS, failure.diagnosticCode)
		}
		val recursive = assertFailsWith<SemanticException> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit("typedef<class> Item { ~Item { self::delete } }")) }
		assertEquals(DiagnosticCode.DELETED_CLASS_ACCESS, recursive.diagnosticCode)
	}

	@Test
	fun `ordered reception skips later positions when an earlier operand returns`(@TempDir directory: Path) {
		val source = """
			typedef<class> Item (pub def<int> value)
			fn<int> define() {
			    def item = Item::new(1)
			    item::delete
			    def<int, int> a, b = if (true) { return 7 } else { 0 }, item.value
			}
			fn<int> assign() {
			    def item = Item::new(1)
			    item::delete
			    def a, b = 0, 0
			    a, b = if (true) { return 8 } else { 0 }, item.value
			}
			fn<int> main() = define() + assign() - 15
		"""
		assertEquals(processCompletion().replace("\r\n", "\n"), runStructProgram(NativeCABI.native(), source, directory.resolve("early-results")))
	}

	@Test
	fun `a live exit keeps a post loop access valid`(@TempDir directory: Path) {
		val source = """
			typedef<class> Item (pub def<int> value)
			fn<int> read(bool drop) {
			    def item = Item::new(7)
			    do {
			        if (drop) { item::delete; break }
			        continue
			    } while (false)
			    if (drop) return 0
			    def value = item.value
			    item::delete
			    return value
			}
			fn<int> main() = read(true) + read(false) - 7
		"""
		assertEquals(processCompletion().replace("\r\n", "\n"), runStructProgram(NativeCABI.native(), source, directory.resolve("live-exit")))
	}
}
