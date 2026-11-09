package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterfaceDefaultsTest {
	private val completion get() = processCompletion().replace("\r\n", "\n")

	private val closedDefaults = """
		typedef<interface> Root<T> {
		    mt<T> read()
		    mt<T> pick(T supplied, opt<T> result ?: supplied) = default (result)
		    mt<T> repeat() = default (pick(self.read()))
		}
		typedef<interface> Left<U> : Root<U> {}
		typedef<interface> Right<V> : Root<V> {}
		typedef<interface> Diamond<W> : Left<W>, Right<W> {}
		typedef<class> Holder<T> (def<T> value) : Diamond<T> {
		    override: mt<T> read() = value
		}
		typedef<interface> Countdown {
		    mt<int> even(int n) = default {
		        if (n == 0) return 1
		        return self.odd(n - 1)
		    }
		    mt<int> odd(int n) = default (if (n == 0) { 0 } else { self.even(n - 1) })
		    mt<int> depth(int n, opt<int> count ?: if (n == 0) { 1 } else {
		        def<Countdown> inner = Counter::new
		        def answer = inner.depth(n - 1)
		        inner::delete
		        answer + 1
		    }) = default (count)
		}
		typedef<class> Counter : Countdown
		fn<int> main() {
		    def<Root<int>> numbers = Holder<int>::new(27)
		    def<Diamond<string>> words = Holder<string>::new("hé" + "😀")
		    def<Left<string>> left = words
		    def<Right<string>> right = words
		    def text = words.repeat()
		    if (numbers.repeat() != 27 || left.repeat() != text || right.repeat() != text) return 1
		    numbers::delete
		    words::delete
		    if (text != "hé😀") return 2
		    def<Countdown> counter = Counter::new
		    if (counter.even(4) != 1 || counter.odd(4) != 0 || counter.depth(3) != 4) return 3
		    counter::delete
		    println(text)
		    return 0
		}
	"""

	@Test
	fun `static labels and defaults survive class and most specific fallback precedence`(@TempDir directory: Path) {
		val source = """
			fn<int> trace(int n) { print(n); return n }
			typedef<interface> Parent {
			    mt<int> combine(int n, opt<int> tail ?: trace(n + 1), opt<int> more ?: trace(tail + 1), int end) = default (-1)
			    mt<int> note() = default (5)
			    mt<int?> nullable(opt<int?> value ?: 7) = default (value)
			}
			typedef<interface> Child : Parent {
			    mt<int> combine(int value, opt<int> suffix ?: trace(6), opt<int> extra ?: trace(suffix + 1), int stop) = default {
			        print("c")
			        return value * 1000 + suffix * 100 + extra * 10 + stop
			    }
			}
			typedef<interface> Other { mt<int> note() = default (8) }
			typedef<class> Derived : Child, Other { override: mt<int> note() = 99 }
			typedef<class> Overridden : Child {
			    override: pub mt<int> combine(int a, opt<int> b ?: trace(8), opt<int> c ?: trace(b + 1), int d) {
			        print("o")
			        return a * 1000 + b * 100 + c * 10 + d
			    }
			}
			fn<Parent> select(Derived item) { print("r"); return item }
			fn<int> main() {
			    def item = Derived::new
			    def<Parent> parent = item
			    def<Child> child = item
			    def<Other> other = item
			    if (select(item).combine(end=trace(4), n=trace(1)) != 1234) return 1
			    if (child.combine(stop=4, value=1) != 1674) return 2
			    if (parent.note() != 99 || other.note() != 99) return 3
			    if (parent.nullable() != 7 || parent.nullable(null) != null) return 4
			    def replaced = Overridden::new
			    def<Parent> replacedView = replaced
			    if (replacedView.combine(end=4, n=1) != 1234) return 5
			    if (replaced.combine(d=4, a=1) != 1894) return 6
			    parent::delete
			    replaced::delete
			    println(" ok")
			    return 0
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("precedence_${abi.layout.sizeBits}"))
			assertEquals("r4123c67c23o89o ok\n" + completion, output)
		}
	}

	@Test
	fun `merged requirements prefer an inherited class implementation in either parent order`(@TempDir directory: Path) {
		val source = """
			typedef<interface> Required<T> { mt<T> read() }
			typedef<interface> WithDefault { mt<int> read() = default (2) }
			typedef<interface> RequiredFirst : Required<int>, WithDefault {}
			typedef<interface> DefaultFirst : WithDefault, Required<int> {}
			typedef<class> Base<T> (def<T> value) : Required<T> { override: mt<T> read() = value }
			typedef<class> First : Base<int>(1), RequiredFirst
			typedef<class> Second : Base<int>(1), DefaultFirst
			fn main() {
			    def<RequiredFirst> first = First::new
			    def<DefaultFirst> second = Second::new
			    def<Required<int>> required = second
			    def<WithDefault> fallback = second
			    println(first.read())
			    println(second.read())
			    println(required.read())
			    println(fallback.read())
			    first::delete
			    second::delete
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("merged_${abi.layout.sizeBits}"))
			assertEquals("1\n1\n1\n2\n" + completion, output)
		}
	}

	@Test
	fun `closed diamond fallbacks and recursive bodies and omissions execute`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val output = runStructProgram(abi, closedDefaults, directory.resolve("generic_${abi.layout.sizeBits}"))
			assertEquals("hé😀\n" + completion, output)
		}
	}

	@Test
	fun `fallback and dispatch frames retain supplied snapshots and buffered results`(@TempDir directory: Path) {
		val source = """
			typedef<class> Source (pub def<string[]> values)
			typedef<interface> Gather {
			    mt<string, string[]> collect(string label, string[] data, opt<string[]> values ?: data, opt<string> title ?: label + values[0], int marker) = default (self.pack(title, values, marker))
			    mt<string, string[]> pack(string title, string[] values, int marker)
			}
			typedef<class> Bag : Gather {
			    override: mt<string, string[]> pack(string title, string[] values, int marker) {
			        values[1] = "changed"
			        return title + marker::str, values
			    }
			}
			fn<int> main() {
			    def source = Source::new(["hé" + "😀", "before"])
			    def<Gather> bag = Bag::new
			    def<int> consume = { source::delete; print("m"); return 7 }
			    def<string, string[]> title, snapshot = bag.collect(data=source.values, marker=consume, label="prefix ")
			    bag::delete
			    if (title != "prefix hé😀7" || snapshot[0] != "hé😀" || snapshot[1] != "changed") return 1
			    println(" ok")
			    return 0
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("snapshots_${abi.layout.sizeBits}"))
			assertEquals("m ok\n" + completion, output)
		}
	}

	@Test
	fun `dispatch checks liveness before and after omitted defaults`(@TempDir directory: Path) {
		for (abi in structRunners()) for ((index, argument) in listOf("1", "expire(item, 2)").withIndex()) {
			val source = """
				typedef<interface> View {
				    mt<int> use(int input, opt<int> ticket ?: expire(self, 1)) = default {
				        println("body ran")
				        return input + ticket
				    }
				}
				typedef<class> Item : View
				fn<int> expire(View view, int stage) { println(stage); view::delete; return 0 }
				fn main() { def<View> item = Item::new; item.use($argument) }
			"""
			val output = runStructProgram(abi, source, directory.resolve("liveness_${abi.layout.sizeBits}_$index"), 1)
			assertEquals("${index + 1}\nClass object is no longer live\n", output)
		}
	}

	@Test
	fun `destructor self can dispatch through a fallback to a requirement`(@TempDir directory: Path) {
		val source = """
			typedef<interface> Read {
			    mt<int> read()
			    mt<int> report(opt<int> prefix ?: 1) = default (prefix + self.read())
			}
			typedef<class> Item (def<int> value) : Read {
			    override: mt<int> read() = value
			    ~Item { println((self -> Read).report()); throughAlias(self) }
			}
			fn throughAlias(Read view) { println(view.report()) }
			fn main() { def<Read> view = Item::new(7); view::delete }
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("destructor_${abi.layout.sizeBits}"), 1)
			assertEquals("8\nClass object is no longer live\n", output)
		}
	}

	@Test
	fun `imported fallback bodies and omissions resolve helpers in the declaring file`(@TempDir directory: Path) {
		Files.writeString(directory.resolve("Library.co"), """
			fn<int> seed() = 4
			fn<int> offset() = 10
			pub typedef<interface> Read<T> {
			    mt<T> read(opt<int> marker ?: seed())
			    mt<T, int> again() = default (self.read(), offset())
			}
		""".trimIndent())
		val main = Files.writeString(directory.resolve("Main.co"), """
			using Library : Read
			fn<int> seed() = 9
			fn<int> offset() = 20
			typedef<class> Item : Read<int> { override: mt<int> read(int marker) = marker }
			fn<int> main() {
			    def<Read<int>> item = Item::new
			    def<int, int> value, extra = item.again()
			    item::delete
			    if (value != 4 || extra != 10) return 1
			    println(value + extra)
			    return 0
			}
		""".trimIndent())
		for (abi in structRunners()) {
			val output = directory.resolve("imported_${abi.layout.sizeBits}")
			val request = CompilationRequest(main, output, listOf(SourceModule("M", directory)), targetTriple = abi.targetTriple)
			assertEquals("14\n" + completion, runExecutable(ClothCompiler.compile(request).artifact).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `closed fallback bodies and recursive omission entries verify across targets`(@TempDir directory: Path) {
		val unit = structUnit(closedDefaults)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emitExecutable(unit)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "@cloth.interface.fallback.")
			assertContains(ir, "parameter_default")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("defaults_$index.o"))) > 0)
		}
	}
}
