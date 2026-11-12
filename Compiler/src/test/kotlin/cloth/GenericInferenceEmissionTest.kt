package cloth

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class GenericInferenceEmissionTest {

	@Test
	fun `partial arguments and type defaults lower to closed call contracts`(@TempDir directory: Path) {
		val source = """
			typedef<class> Box<T = int, U = T> (pub def<T> value, pub def<U> label) {
			    pub mt<V[]> empty<V = U>() = []
			}
			fn<T> echo<T = int<8>>(T value) = value
			fn main() {
			    def box = Box<int, _>::new(7, "label")
			    println(box.value)
			    println(box.label)
			    def<string[]> empty = box.empty<>()
			    println(empty)
			    def another = Box<>::new(2, 3)
			    println(another.label)
			    println(echo(300))
			    println(echo<>(12))
			    box::delete
			    another::delete
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("type_defaults_${abi.layout.sizeBits}"))
			assertEquals("7\nlabel\n[]\n3\n300\n12\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `inferred constructors run primary defaults and secondary delegation in source order`(@TempDir directory: Path) {
		val source = """
			fn<string> defaultTag() { print("default "); return "primary" }
			fn<string> label() { print("label "); return "named" }
			fn<int> value() { print("value "); return 7 }
			typedef<class> Crate<T> (pub def<T> item, pub def<string> tag ?: defaultTag()) {
			    Crate(string label, T input) : (item=input, tag=label) {
			        self.tag = self.tag + " secondary"
			    }
			    ~Crate { println(self.tag) }
			}
			fn main() {
			    def first = Crate::new(item=value())
			    println(first.item)
			    first::delete
			    def second = Crate::new(input=value(), label=label())
			    println(second.item)
			    second::delete
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("constructors_${abi.layout.sizeBits}"))
			assertEquals("value default 7\nprimary\nvalue label 7\nnamed secondary\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `inferred calls run with contextual literals recursion defaults and ordered results`(@TempDir directory: Path) {
		val source = """
			fn<T> choose<T>(T left, T right) = right
			fn<T> repeat<T>(int remaining, T value) {
			    if (remaining == 0) return value
			    return repeat(remaining - 1, value)
			}
			fn<T, string> label<T>(T value, opt<string> tag ?: "default") = value, tag
			fn<T> first<T>(T[] values) = values[0]
			fn<int> produce() { print("value "); return 7 }
			fn main() {
			    def<int<8>> small = 4
			    println(choose(1, small))
			    println(repeat(2, "recursive"))
			    def<int, string> value, tag = label(value=produce())
			    println(value)
			    println(tag)
			    println(first([2, 3]))
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("inference_${abi.layout.sizeBits}"))
			assertEquals("4\nrecursive\nvalue 7\ndefault\n2\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}
}
