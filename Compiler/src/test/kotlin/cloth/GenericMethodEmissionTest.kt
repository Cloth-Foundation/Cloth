package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.emitter.LLVMEmitter
import cloth.semantic.ProgramAnalyzer
import cloth.settings.OutputKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericMethodEmissionTest {

	@Test
	fun `generic interface overrides keep constructor guarantees from an imported shared ancestor`(@TempDir directory: Path) {
		val files = genericSourceFiles(directory, mapOf(
			"Lib.co" to """
				fn<N> seed<N: Number>() = 7 -> N
				pub typedef<interface> Factory<A: Number> {
				    mt<T> make<S: Number, T: new(opt<A> first ?: seed<A>(), opt<S> second ?: seed<S>())>()
				    mt<int> tag() = default (3)
				}
				pub typedef<interface> Left<A: Number> : Factory<A> {}
				pub typedef<interface> Right<A: Number> : Factory<A> {}
				pub fn<T> forward<V: Factory<int>, T: new(int first, float second)>(V view) = view.make<float, T>()
			""",
			"Main.co" to """
				using Lib: Factory, Left, Right, forward
				fn<int> seed<N>() = 0
				typedef<class> Maker : Left<int>, Right<int> {
				    override: pub mt<U> make<R, U>() = U::new()
				}
				typedef<class> Local (pub def<int> left, pub def<float> right) {}
				fn main() {
				    def maker = Maker::new
				    def<Factory<int>> view = maker
				    def item = forward<Factory<int>, Local>(view)
				    println(item.left)
				    println(item.right)
				    println(view.tag())
				    item::delete
				    maker::delete
				}
			""",
		))
		for (abi in structRunners()) {
			val program = ProgramAnalyzer(abi.layout).analyze(files.fork())
			val binary = LLVMEmitter(abi).compile(program, directory.resolve("interface_constructor_${abi.layout.sizeBits}"))
			assertEquals("7\n7\n3\n" + processCompletion().replace("\r\n", "\n"), runExecutable(binary).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `generic interface views keep static defaults and dispatch ordered results`(@TempDir directory: Path) {
		val source = """
			fn<int> step(int value) { print(value); return value }
			typedef<interface> Parent {
			    mt<T, string> pack<T: Number = int<8>>(int start, opt<T> value ?: step(start + 1) -> T, opt<string> label ?: "parent", int end)
			}
			typedef<interface> Child : Parent {
			    mt<U, string> pack<U: Number = float>(int index, opt<U> amount ?: step(6) -> U, opt<string> text ?: "child", int done) = default {
			        print("c")
			        return amount + amount, text + "!"
			    }
			}
			typedef<class> Fallback : Child {}
			typedef<class> Custom : Child {
			    override: pub mt<V, string> pack<V = int>(int pos, opt<V> input ?: step(9) -> V, opt<string> tag ?: "class", int stop) {
			        print("o")
			        return input, tag + "#"
			    }
			}
			fn<int> main() {
			    def item = Fallback::new
			    def<Parent> parent = item
			    def<Child> child = item
			    def<int<8>, string> first, firstText = parent.pack(end=step(4), start=step(1))
			    if (first != 4 || firstText != "parent!") return 1
			    def<float, string> second, secondText = child.pack(done=4, index=1)
			    if (second != 12f || secondText != "child!") return 2
			    def<int<8>> small = 5
			    def<int<8>, string> inferred, inferredText = child.pack<_>(index=1, done=4, amount=small, text="hole")
			    if (inferred != 10 || inferredText != "hole!") return 3
			    def<Parent> custom = Custom::new
			    def<float, string> third, thirdText = custom.pack<float>(start=step(1), end=step(4), label="explicit")
			    if (third != 2f || thirdText != "explicit#") return 4
			    item::delete
			    custom::delete
			    println(" ok")
			    return 0
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("interface_defaults_${abi.layout.sizeBits}"))
			assertEquals("412c6cc142o ok\n" + processCompletion().replace("\r\n", "\n"), output)
		}
		val file = Files.writeString(directory.resolve("Main.co"), source.trimIndent())
		for ((index, triple) in structTargetTriples.withIndex()) {
			val request = CompilationRequest(file, directory.resolve("interface_$index.o"), outputKind = OutputKind.OBJECT, targetTriple = triple)
			assertTrue(Files.size(ClothCompiler.compile(request).artifact) > 0, triple)
		}
	}

	@Test
	fun `recursive interface fallbacks and late class discovery share specializations`(@TempDir directory: Path) {
		val source = """
			typedef<interface> Loop {
			    mt<T> repeat<T: Number>(T value, int depth) = default {
			        if (depth == 0) return value
			        return double<T>(value, depth - 1)
			    }
			    mt<T> double<T: Number>(T value, int depth) = default (self.repeat(value + value, depth))
			}
			typedef<interface> View : Loop {}
			typedef<class> Counter : View {}
			typedef<class> Booster<E> : Loop {
			    override: mt<U> repeat<U>(U value, int depth) = value + value + value
			}
			fn<Loop> actual<E>(E marker) = Booster<E>::new
			fn<int> later<E>(E marker) {
			    def loop = actual(marker)
			    def result = loop.repeat(3, 2)
			    loop::delete
			    return result
			}
			fn main() {
			    def<View> counter = Counter::new
			    println(counter.repeat(3, 2))
			    println(later("late"))
			    counter::delete
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("interface_late_${abi.layout.sizeBits}"))
			assertEquals("12\n9\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `generic interface dispatch rechecks liveness after arguments and defaults`(@TempDir directory: Path) {
		val contract = """
			typedef<interface> Read {
			    mt<T> read<T: Number>(Read alias, T value, opt<int> tail ?: discard(alias))
			}
			typedef<class> Item : Read {
			    override: mt<U> read<U>(Read alias, U value, int tail) { println("body ran"); return value }
			}
			fn<int> discard(Read value) { value::delete; println("deleted"); return 1 }
		"""
		for (abi in structRunners()) for ((index, value) in listOf("1", "discard(view)").withIndex()) {
			val source = """
				$contract
				fn main() {
				    def<Read> view = Item::new
				    view.read<int>(view, $value)
				}
			"""
			val output = runStructProgram(abi, source, directory.resolve("interface_stale_${abi.layout.sizeBits}_$index"), status = 1)
			assertEquals("deleted\nClass object is no longer live\n", output)
		}
	}

	@Test
	fun `generic virtual specializations gain targets from later discovered subclasses`(@TempDir directory: Path) {
		val source = """
			typedef<class> Entity {
			    virtual: pub mt<T> echo<T: Number>(T value, int depth) {
			        if (depth == 0) return value
			        return self.echo(value, depth - 1)
			    }
			}
			typedef<class> Player<E> : Entity {
			    override: pub mt<U> echo<U>(U item, int depth) {
			        if (depth == 0) return item
			        return self.echo(item + item, depth - 1)
			    }
			}
			typedef<class> Visitor<E> : Player<E> {}
			fn<Entity> actual<E>(E marker) = Visitor<E>::new
			fn<Entity> later<E>(E marker) = actual(marker)
			fn main() {
			    def entity = Entity::new
			    println(entity.echo(3, 2))
			    def player = later("late")
			    println(player.echo(3, 2))
			    entity::delete
			    player::delete
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("virtual_late_${abi.layout.sizeBits}"))
			assertEquals("3\n12\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `generic method receivers defaults results and recursion use the native ABI`(@TempDir directory: Path) {
		val source = """
			typedef<class> Base<T> (def<T> saved) {
			    virtual: pub mt<U> pair<U: Number>(U value, int left, int right) = value
			    pub mt<T, U> pair<U>(U value, opt<T> first ?: self.saved) = first, value
			    pub mt<int> count<U>(int remaining, U marker) {
			        if (remaining == 0) return 0
			        return 1 + count(remaining - 1, marker)
			    }
			    pub mt<string> route<U>(U value) = "base"
			}
			typedef<class> Child : Base<int>(7) {
			    override: pub mt<N> pair<N>(N item, int first, int second) = item + item
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
