package cloth

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

internal fun genericAcceptanceSources(): List<String> = Regex("```cloth\\R(.*?)```", RegexOption.DOT_MATCHES_ALL)
	.findAll(Files.readString(Path.of(".agents/skills/cloth-generics/references/acceptance-fixtures.md")))
	.map { it.groupValues[1] }.toList()

internal fun genericSourceFiles(root: Path, sources: Map<String, String>): FileBindings {
	for ((name, text) in sources) {
		val path = root.resolve(name)
		Files.createDirectories(path.parent)
		Files.writeString(path, text.trimIndent())
	}
	return FileBindings.graph(SourceGraphLoader.load(CompilationRequest(root.resolve("Main.co"), root.resolve("out"), listOf(SourceModule("M", root)))))
}

class GenericEmissionTest {
	private val abiValues = genericAcceptanceSources()[0] + """
		typedef<enum> Shade { Light, Dark }
		typedef<struct> Packed<T> { T value, int<129>? wide }
		typedef<class> Base<T> (def<T> value) {
		    pub mt<T> read() = value
		    ~Base {}
		}
		typedef<class> Child<T> (T value) : Base<T>(value) { ~Child {} }
		fn<int> main() {
		    def wide = List<int<129>?>::new
		    wide.append(null); wide.append(18446744073709551617)
		    def<int<129>?> missing = wide.removeAt(0)
		    def<int<129>?> present = wide.get(0)
		    if (missing != null || present != 18446744073709551617) return 1
		    wide::delete
		    def sizes = List<usize>::new
		    sizes.append(usize::max)
		    if (sizes.get(0) != usize::max) return 2
		    sizes::delete
		    def offsets = List<isize>::new
		    offsets.append(isize::min)
		    if (offsets.get(0) != isize::min) return 3
		    offsets::delete
		    def shades = List<Shade>::new
		    shades.append(Shade.Dark)
		    if (shades.removeAt(0) != Shade.Dark) return 4
		    shades::delete
		    def metadata = List<typeinfo>::new
		    metadata.append(List<usize>::type)
		    if (metadata.get(0) != List<usize>::type) return 5
		    metadata::delete
		    def half = List<float<16>>::new
		    half.append(0.5)
		    if (half.get(0) != 0.5) return 6
		    half::delete
		    def characters = List<char>::new
		    characters.append('😀')
		    if (characters.get(0) != '😀') return 7
		    characters::delete
		    def flags = List<bool>::new
		    flags.append(true)
		    if (!flags.get(0)) return 8
		    flags::delete
		    def fixed = List<int[2]>::new
		    fixed.append([1, 2])
		    if (fixed.get(0)[1] != 2) return 9
		    fixed::delete
		    def structs = List<Packed<string>>::new
		    structs.append({ "kept", 18446744073709551617 })
		    def removed = structs.removeAt(0)
		    structs::delete
		    if (removed.value != "kept" || removed.wide != 18446744073709551617) return 10
		    def child = Child<string>::new("child")
		    def<Base<string>> base = child
		    if (base.read() != "child" || !(base is Child<string>)) return 11
		    base::delete
		    println("all arguments")
		    return 0
		}
	"""

	private fun native(directory: Path, name: String, source: String, output: String) {
		for (abi in structRunners()) assertEquals(output + processCompletion().replace("\r\n", "\n"), runStructProgram(abi, source, directory.resolve("${name}_${abi.layout.sizeBits}")))
	}

	@Test
	fun `ordinary generic value arguments cross the native callable ABI`(@TempDir directory: Path) {
		native(directory, "arguments", abiValues, "all arguments\n")
	}

	@Test
	fun `proved bounds allow closed functions and a self bounded Player to execute`(@TempDir directory: Path) {
		val source = """
			typedef<interface> IHealth<T: Number> { mt<T> echo(T value) = default (value) }
			typedef<class> Entity<T: Entity<T>> (pub def<float> health) : IHealth<float> {}
			typedef<class> Player : Entity<Player>(100f) {}
			fn<T> keep<T: Entity<T>>(T value) = value
			fn<T> number<T: Number>(T value) = value
			fn main() {
			    def player = Player::new
			    def kept = keep<Player>(player)
			    def<IHealth<float>> view = kept
			    if (view.echo(kept.health) == 100f && number<int>(7) == 7) println("bounds")
			    kept::delete
			}
		"""
		native(directory, "bounds", source, "bounds\n")
	}

	@Test
	fun `class bounds preserve fields static overloads and virtual contracts across specialization`(@TempDir directory: Path) {
		val source = """
			typedef<class> Entity<T: Entity<T>> (pub def<int> health) {
			    pub mt<string> pick(Object other, opt<string> label ?: "base") = label
			    pub mt<int> pick(Player other, opt<int> rank ?: 7) = rank
			    virtual: pub mt<string> describe(opt<string> label ?: "default") = label
			    operator ===(Entity<T> other) = false
			}
			typedef<class> Player : Entity<Player>(10) {
			    pub def<int> health = 99
			    pub mt<int> pick(Player other, opt<int> rank ?: 8) = rank
			    override: pub mt<string> describe(string name) = #"Player {name}"
			    operator ===(Player other) = true
			    ~Player { println("deleted") }
			}
			fn<T> update<T: Entity<T>>(T value) { value.health += 1; return value }
			fn<int> read<T: Entity<T>>(T value) {
			    def<Entity<T>> entity = value
			    return entity.health
			}
			fn<string> pick<T: Entity<T>>(T value) = value.pick(value)
			fn<string> describe<T: Entity<T>>(T value) = value.describe() + value.describe(label="named")
			fn<bool> same<T: Entity<T>>(T left, T right) = left === right
			fn dispose<T: Entity<T>>(T value) { value::delete }
			fn<int> main() {
			    def player = Player::new
			    def<Player> kept = update<Player>(player)
			    if (kept.health != 99 || read<Player>(kept) != 11) return 1
			    if (pick<Player>(kept) != "base") return 2
			    if (describe<Player>(kept) != "Player defaultPlayer named") return 3
			    if (same<Player>(kept, kept)) return 4
			    dispose<Player>(kept)
			    println("class bounds")
			    return 0
			}
		"""
		native(directory, "class_bounds", source, "deleted\nclass bounds\n")
	}

	@Test
	fun `interface bounds keep their call contracts and use checked identity operations`(@TempDir directory: Path) {
		val source = """
			typedef<interface> IHealth<N: Number> {
			    mt<N> getHealth() = default (0 -> N)
			    mt<string> caption(opt<string> label ?: "default") = default (label)
			}
			typedef<interface> Extra : IHealth<int> {}
			typedef<class> Player : Extra {
			    override: mt<string> caption(string text) = #"via {text}"
			    pub mt<int> caption() = 7
			    operator ==(Object other) = true
			    operator ===(Player other) = true
			    operator ::str() = "formatted"
			    ~Player { println("released") }
			}
			fn<string> via<T: IHealth<int>>(T value) {
			    def<IHealth<int>> view = value
			    if (view.getHealth() != 0) return "wrong"
			    return value.caption() + value.caption(label="named")
			}
			fn<bool> equal<T: IHealth<int>>(T left, T right) = left == right && !(left != right)
			fn<bool> identity<T: IHealth<int>>(T left, T right) = left === right
			fn<bool> raw<T: Object>(T left, T right) = left === right
			fn<string> display<T: IHealth<int>>(T value) {
			    println(value)
			    println(#"{value}")
			    return value::str
			}
			fn dispose<T: IHealth<int>>(T value) { value::delete }
			fn<int> main() {
			    def first = Player::new
			    def second = Player::new
			    def<Extra> view = first
			    if (via<Player>(first) != "via defaultvia named" || via<Extra>(view) != "via defaultvia named") return 1
			    if (!equal<Player>(first, second) || identity<Player>(first, second)) return 2
			    if (!identity<Extra>(view, view) || !raw<Extra>(view, view) || raw<Player>(first, second)) return 3
			    if (display<Player>(first) != "formatted") return 4
			    dispose<Extra>(view)
			    dispose<Player>(second)
			    println("interface bounds")
			    return 0
			}
		"""
		native(directory, "interface_bounds", source, "formatted\nformatted\nreleased\nreleased\ninterface bounds\n")
	}

	@Test
	fun `generic argument ABI matrix verifies executable IR and objects on every target`(@TempDir directory: Path) {
		val ast = structUnit(abiValues)
		for ((i, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(ast), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("arguments_$i.o"))) > 0)
		}
	}

	@Test
	fun `Number arithmetic uses the concrete format of each specialization`(@TempDir directory: Path) {
		native(directory, "numberArithmetic", """
			typedef<struct> Counter<T: Number> {
			    T value;
			    mt<T> step(T amount) {
			        value += amount
			        value++
			        return value
			    }
			}
			fn<T> fraction<T: Number>(T value) = -value / (2 -> T)
			fn<T> convert<T: Number, U: Number>(U value) = value -> T
			fn<bool> less<T: Number>(T left, T right) = left < right
			fn<bool> same<T: Number>(T? left, T? right) = left == right
			fn show<T: Number>(T value) { println(#"{value::str}: {value}"); println(value) }
			fn main() {
			    def<Counter<int<129>>> wide = { 18446744073709551616 }
			    show<int<129>>(wide.step(2))
			    println(fraction<int>(5) == -2)
			    println(fraction<float<16>>(5f) == -2.5f)
			    def<usize> count = 7
			    println(convert<float<64>, usize>(count) == 7f)
			    println(less<usize>(count, 8))
			    println(same<int>(7, 7))
			}
		""", "18446744073709551619: 18446744073709551619\n18446744073709551619\ntrue\ntrue\ntrue\ntrue\ntrue\n")
	}

	@Test
	fun `unused checked templates emit no open owner bodies`(@TempDir directory: Path) {
		val source = "typedef<class> Unused<T> (def<T> value) { pub mt<T> read() = value }\nfn main() { println(1) }"
		val ir = LLVMEmitter().emit(structUnit(source))
		assertFalse(ir.contains("cloth.method."))
		assertFalse(ir.contains("cloth.constructor."))
		native(directory, "unused", source, "1\n")
	}

	@Test
	fun `maintained imported generic List fixture executes without concrete source generation`(@TempDir directory: Path) {
		val sources = genericAcceptanceSources()
		val files = genericSourceFiles(directory, mapOf("collections/List.co" to sources[0], "Main.co" to sources[1]))
		for (abi in structRunners()) {
			val program = ProgramAnalyzer(abi.layout).analyze(files.fork())
			val binary = LLVMEmitter(abi).compile(program, directory.resolve("list_${abi.layout.sizeBits}"))
			assertEquals("1\n[2, 9]\n3\né😀\n[1, 9]\n[2, 3]\n[1]\n[2, 3]\ntrue\nfalse\n" + processCompletion().replace("\r\n", "\n"), runExecutable(binary).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `maintained nullable substitution fixture uses one carrier`(@TempDir directory: Path) {
		native(directory, "maybe", genericAcceptanceSources()[2], "null\nnull\n7\ntrue\n")
	}

	@Test
	fun `closed constructors defaults deferred results and operators execute independently`(@TempDir directory: Path) {
		native(directory, "callables", """
			typedef<class> Box<T> (def<T> value) {
			    pub mt<T, T[]> pair(T input, opt<T> other ?: input) {
			        def<T> deferred = { def<T> local = other; return local }
			        return deferred, [value, deferred]
			    }
			}
			typedef<struct> Slot<T> {
			    T value;
			    operator +(Slot<T> other) = other.value
			    mt<typeinfo> metadata() = T::type
			}
			fn main() {
			    def a = Box<int>::new(1)
			    def b = Box<string>::new("hello")
			    def<int, int[]> x, xs = a.pair(2)
			    def<string, string[]> y, ys = b.pair(input="new", other="old")
			    a::delete
			    b::delete
			    println(x); println(xs); println(y); println(ys)
			    def<Slot<int>> first = { 7 }
			    def<Slot<string>> second = { "word" }
			    println(first + first); println(second + second)
			    println(first.metadata()); println(second.metadata())
			}
		""", "2\n[1, 2]\nold\n[hello, old]\n7\nword\nint<32>\nstring\n")
	}

	@Test
	fun `generic base construction secondary delegation and destructor chains use concrete owners`(@TempDir directory: Path) {
		native(directory, "inheritance", """
			typedef<class> Base<T> (def<T> value) {
			    Base(T value, bool marker) : (value) {}
			    pub mt<T> read() = value
			    ~Base { println(T::type) }
			}
			typedef<class> Child<T> (T value) : Base<T>(value, true) {
			    ~Child { println("child") }
			}
			typedef<class> Node<T> (pub def<T> value, pub def<Node<T>?> next ?: null)
			fn<bool> matches(Object value) = value is Base<int> && !(value is Base<string>)
			fn main() {
			    def child = Child<int>::new(7)
			    def<Object> alias = child
			    def node = Node<string>::new("kept")
			    node.next = node
			    println(child.read()); println(matches(alias)); println(node.next == node)
			    node::delete; alias::delete
			}
		""", "7\ntrue\ntrue\nchild\nint<32>\n")
	}

	@Test
	fun `same spelled private actuals keep distinct metadata inside a shared generic template`(@TempDir directory: Path) {
		val files = genericSourceFiles(directory, mapOf(
			"Box.co" to "pub typedef<class> Box<T> { pub mt<typeinfo> argument() = T::type }",
			"A.co" to "using Box : Box\ntypedef<struct> Hidden { int value }\npub fn<typeinfo> a() = Box<Hidden>::type\npub fn<typeinfo> aa() = Box<Hidden>[]?::type",
			"B.co" to "using Box : Box\ntypedef<struct> Hidden { int value }\npub fn<typeinfo> b() = Box<Hidden>::type\npub fn<typeinfo> bb() = Box<Hidden>[]?::type",
			"Main.co" to """
				using A : a, aa
				using B : b, bb
				fn main() {
				    println(a() == b()); println(aa() == bb())
				    println(a() == a()); println(a()); println(b())
				}
			"""
		))
		for (abi in structRunners()) {
			val binary = LLVMEmitter(abi).compile(ProgramAnalyzer(abi.layout).analyze(files.fork()), directory.resolve("private_${abi.layout.sizeBits}"))
			assertEquals("false\nfalse\ntrue\nBox<Hidden>\nBox<Hidden>\n" + processCompletion().replace("\r\n", "\n"), runExecutable(binary).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `exact numeric array and nested arguments retain metadata identity`(@TempDir directory: Path) {
		native(directory, "metadata", """
			typedef<class> Box<T> { pub mt<typeinfo> argument() = T::type }
			fn main() {
			    def first = Box<usize>::new
			    def nested = Box<Box<int[]>>::new
			    println(first.argument() == usize::type)
			    println(nested.argument() == Box<int[]>::type)
			    println(Box<int>::type == Box<int<32>>::type)
			    println(Box<int>::type == Box<unsigned int>::type)
			    println(Box<usize>::type == Box<unsigned int<64>>::type)
			    println(Box<isize>::type == Box<int<64>>::type)
			    println(Box<int[2]>::type == Box<int[]>::type)
			    println(Box<int[2]?[]>::type)
			    first::delete; nested::delete
			}
		""", "true\ntrue\ntrue\nfalse\nfalse\nfalse\nfalse\nBox<int<32>[2]?[]>\n")
	}

	@Test
	fun `imported List fixture verifies executable IR and objects on every ABI`(@TempDir directory: Path) {
		val sources = genericAcceptanceSources()
		val files = genericSourceFiles(directory, mapOf("collections/List.co" to sources[0], "Main.co" to sources[1]))
		for ((i, triple) in structTargetTriples.withIndex()) {
			val abi = NativeCABI.forTarget(triple)
			val program = ProgramAnalyzer(abi.layout).analyze(files.fork())
			val emitter = LLVMEmitter(abi)
			val ir = emitter.emitExecutable(program)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "cloth.rt.array.remove")
			val output = directory.resolve("list_$i.o")
			emitter.compileObject(program.functions, output)
			assertTrue(Files.size(output) > 0)
		}
	}
}
