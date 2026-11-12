package cloth

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class GenericMethodEmissionTest {
	@Test
	fun `generic method receivers defaults results and recursion use the native ABI`(@TempDir directory: Path) {
		val source = """
			typedef<class> Base<T> (def<T> saved) {
			    pub mt<T, U> pair<U>(U value, opt<T> first ?: self.saved) = first, value
			    pub mt<int> count<U>(int remaining, U marker) {
			        if (remaining == 0) return 0
			        return 1 + count(remaining - 1, marker)
			    }
			    pub mt<string> route<U>(U value) = "base"
			}
			typedef<class> Child : Base<int>(7) {
			    pub mt<string> route<N: Number>(N value) = "child"
			}
			typedef<struct> Slot<T> {
			    T item;
			    mt<T> replace<U>(U label, T replacement) {
			        def<T> previous = self.item
			        self.item = replacement
			        return previous
			    }
			}
			fn<int> argument() {
			    println("argument")
			    return 1
			}
			fn main() {
			    def child = Child::new
			    def<Base<int>> base = child
			    def<int, string> first, label = child.pair(value="pair")
			    println(first)
			    println(label)
			    println(child.route(1))
			    println(base.route<string>("label"))
			    println(child.count(3, "marker"))
			    def<Slot<string>> slot = { "old" }
			    println(slot.replace(label=4, replacement="new"))
			    println(slot.item)
			    def<Child?> present = child
			    println(present?.route(argument()) ?: "missing")
			    def<Child?> absent = null
			    println(absent?.route(argument()) ?: "skipped")
			    child::delete
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("methods_${abi.layout.sizeBits}"))
			assertEquals("7\npair\nchild\nbase\n3\nold\nnew\nargument\nchild\nskipped\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}
}
