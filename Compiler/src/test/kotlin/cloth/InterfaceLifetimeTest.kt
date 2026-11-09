package cloth

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class InterfaceLifetimeTest {
	private val item = """
		typedef<interface> Read { mt<int> read(int extra) }
		typedef<class> Item (pub def<int> value) : Read {
		    override: mt<int> read(int extra) { print("body "); return value + extra }
		}
		fn<Read> viewOf(Item item) = item
	""".trimIndent()

	@Test
	fun `stale interface calls and membership fail after slot reuse before arguments`(@TempDir directory: Path) {
		for (abi in structRunners()) for ((index, operation) in listOf("stale.read(argument())", "println(stale is Read)").withIndex()) {
			val source = """
				$item
				fn<int> argument() { println("argument ran"); return 0 }
				fn main() {
				    def first = Item::new(1)
				    def stale = viewOf(first)
				    first::delete
				    def replacement = Item::new(2)
				    println(replacement.value)
				    $operation
				}
			"""
			val output = runStructProgram(abi, source, directory.resolve("stale_${abi.layout.sizeBits}_$index"), 1)
			assertEquals("2\nClass object is no longer live\n", output)
		}
	}

	@Test
	fun `interface dispatch revalidates after a supplied argument deletes the object`(@TempDir directory: Path) {
		val source = """
			$item
			fn<int> dispose(Read view) { view::delete; println("deleted"); return 0 }
			fn main() {
			    def item = Item::new(1)
			    def<Read> view = item
			    view.read(dispose(view))
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("argument_${abi.layout.sizeBits}"), 1)
			assertEquals("deleted\nClass object is no longer live\n", output)
		}
	}

	@Test
	fun `receiver capture survives reassignment and instance table growth during arguments`(@TempDir directory: Path) {
		val source = """
			$item
			typedef<class> Link (pub def<Link?> previous)
			fn<int> grow() {
			    def<Link?> head = null
			    for (def index <- 0..80) { head = Link::new(head) }
			    while (head != null) {
			        def node = head
			        head = node.previous
			        node::delete
			    }
			    return 3
			}
			fn<int> main() {
			    def first = Item::new(10)
			    def second = Item::new(20)
			    def<Read> selected = first
			    def<int> change = { selected = second; return grow() }
			    println(selected.read(change))
			    println(selected.read(1))
			    first::delete
			    selected::delete
			    return 0
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("growth_${abi.layout.sizeBits}"))
			assertEquals("body 13\nbody 21\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `construction dispatch sees the initialized base and deletion runs the concrete chain`(@TempDir directory: Path) {
		val source = """
			typedef<interface> Read { mt<int> read() }
			typedef<interface> Extra {}
			typedef<class> Base (pub def<int> value) : Read {
			    Base(float seed) : (seed -> int) {
			        def<Read> view = self
			        println(view.read())
			        if (view is Child || view is Extra) println("derived too soon")
			    }
			    override: mt<int> read() = value
			    ~Base { print("B"); println((self -> Read).read()) }
			}
			typedef<class> Child (float seed, def<int> add) : Base(seed), Extra {
			    override: mt<int> read() = self.value + add
			    ~Child { print("D") }
			}
			fn main() {
			    def<Read> view = Child::new(5f, 8)
			    println(view.read())
			    view::delete
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("constructor_${abi.layout.sizeBits}"))
			assertEquals("5\n13\nDB13\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `destructor self privilege stays with the immediate receiver`(@TempDir directory: Path) {
		val source = """
			typedef<interface> Read { mt<int> read() }
			typedef<class> Item (def<int> value) : Read {
			    override: mt<int> read() = value
			    ~Item { println((self -> Read).read()); throughAlias(self) }
			}
			fn throughAlias(Read view) { println(view.read()) }
			fn main() { def<Read> view = Item::new(7); view::delete }
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("destructor_${abi.layout.sizeBits}"), 1)
			assertEquals("7\nClass object is no longer live\n", output)
		}
	}

	@Test
	fun `interface deletion rejects an object still under construction`(@TempDir directory: Path) {
		val source = """
			typedef<interface> View {}
			typedef<class> Base : View {
			    Base(bool abort) : () { if (abort) { def<View> view = self; view::delete } }
			}
			typedef<class> Child : Base(true) {
			    ~Child { println("destructor ran") }
			}
			fn main() { def child = Child::new }
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("partial_${abi.layout.sizeBits}"), 1)
			assertEquals("Class object is still under construction\n", output)
		}
	}
}
