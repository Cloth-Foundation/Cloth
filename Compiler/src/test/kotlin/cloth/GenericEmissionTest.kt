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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun genericAcceptanceSources(): List<String> = Regex("```cloth\\R(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(Files.readString(Path.of(".agents/skills/cloth-generics/references/acceptance-fixtures.md"))).map { it.groupValues[1] }.toList()

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
	fun `combined bounds preserve the concrete type through inferred and defaulted native calls`(@TempDir directory: Path) {
		val source = """
			typedef<interface> Named<N> {}
			typedef<interface> Tagged<N> : Named<N> {}
			typedef<class> Entity<T> (pub def<int> health) : Named<int> {}
			typedef<class> Player : Entity<Player>(100), Tagged<int> {}
			typedef<class> Box<T: Entity<T> & Tagged<int> = Player> (pub def<T> value) {}
			fn<T> keep<T: Entity<T> & Tagged<int> = Player>(T value) = value
			fn<T> forward<T: Tagged<int> & Entity<T>>(T value) {
			    def copy = value
			    return keep(copy)
			}
			fn main() {
			    def player = Player::new
			    def<Player> fromDefault = keep<>(player)
			    def<Player> forwarded = forward(fromDefault)
			    def box = Box::new(forwarded)
			    println(box.value.health)
			    box::delete
			    player::delete
			}
		"""
		native(directory, "combined_bounds", source, "100\n")
	}

	@Test
	fun `constructor proof filters a native specialization while retaining its exact result`(@TempDir directory: Path) {
		val source = """
			typedef<class> Player (pub def<int> health) {}
			fn<T> keep<T: new(opt<int> amount ?: 100)>(T value) {
			    def<Object> view = value
			    return value
			}
			fn main() {
			    def player = Player::new(80)
			    def<Player> retained = keep(player)
			    println(retained.health)
			    retained::delete
			}
		"""
		native(directory, "constructor_proof", source, "80\n")
	}

	@Test
	fun `guaranteed construction evaluates imported defaults before delegating and activating`(@TempDir directory: Path) {
		val files = genericSourceFiles(directory, mapOf(
			"Lib.co" to """
				fn<string> mark(string label) { println(label); return label }
				pub fn<T> make<T: new(string head, opt<string> middle ?: mark(head + "M"), opt<string> next ?: mark(middle + "N"), string tail)>() {
				    return T::new(tail=mark("B"), head=mark("A"))
				}
				pub fn<T> forward<T: new(string head, string middle, string next, string tail)>() = make<T>()
			""",
			"Main.co" to """
				using Lib: forward
				fn<string> unexpected() { println("concrete default"); return "wrong" }
				typedef<class> LocalOnly (pub def<string> text) {
				    LocalOnly(string first, opt<string> second ?: unexpected(), opt<string> third ?: unexpected(), string last) : (first + second + third + last) {
				        if (self is Ready) exit(1)
				        println("constructor")
				    }
				    default: (self.text)
				    pub state<string> Ready { init { println(state) } }
				    ~LocalOnly { println(self is Ready) }
				}
				fn main() {
				    def<LocalOnly> value = forward<LocalOnly>()
				    if (!(value is Ready) || value.text != "AAMAMNB") exit(2)
				    value::delete
				}
			""",
		))
		for (abi in structRunners()) {
			val binary = LLVMEmitter(abi).compile(ProgramAnalyzer(abi.layout).analyze(files.fork()), directory.resolve("guarantee_${abi.layout.sizeBits}"))
			assertEquals("B\nA\nAM\nAMN\nconstructor\nAAMAMNB\ntrue\n" + processCompletion().replace("\r\n", "\n"), runExecutable(binary).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `guaranteed calls keep their source contract when signatures or receivers coincide`(@TempDir directory: Path) {
		native(directory, "guarantee_selection", """
			typedef<class> Ticket (int id, pub def<string> text) {
			    Ticket() : (0, "zero") {}
			}
			fn<T> choose<A, B, T: new(A first, opt<string> tag ?: "left") & new(B second, opt<string> label ?: "right")>(B value) = T::new(value)
			fn<T> bare<T: new()>() = T::new
			fn<T> parens<T: new()>() = T::new()
			typedef<class> Factory<T: new(int value, opt<string> label ?: "owner")> {
			    pub mt<T> make<U: new(int other, opt<string> title ?: "method")>(int value) = T::new(value)
			    pub mt<U> other<U: new(int number, string title)>() = U::new(title="explicit", number=8)
			}
			fn main() {
			    def<Ticket> picked = choose<int, int, Ticket>(7)
			    def<Ticket> empty = bare<Ticket>()
			    def<Ticket> emptyAgain = parens<Ticket>()
			    def factory = Factory<Ticket>::new
			    def<Ticket> fromOwner = factory.make<Ticket>(9)
			    def<Ticket> explicit = factory.other<Ticket>()
			    println(picked.text); println(empty.text); println(emptyAgain.text)
			    println(fromOwner.text); println(explicit.text)
			    picked::delete; empty::delete; emptyAgain::delete
			    fromOwner::delete; explicit::delete; factory::delete
			}
		""", "right\nzero\nzero\nowner\nexplicit\n")
	}

	@Test
	fun `nullable bound payloads retain exact types and declaration site dispatch`(@TempDir directory: Path) {
		native(directory, "nullable_bounds", """
			typedef<interface> Named {
			    mt<string> name(opt<string> label ?: "interface") = default (label)
			}
			typedef<class> Base (pub def<int> id) {
			    virtual: pub mt<string> describe(opt<string> label ?: "base") = label
			    pub mt<U> echo<U>(U value) = value
			}
			typedef<class> Player : Base(19), Named {
			    pub def<int> id = 99
			    override: pub mt<string> describe(string text) = "Player " + text
			    pub mt<string> describe() = "overload"
			}
			typedef<class> Box<T: Number?> (pub def<T> value) {
			    pub mt<U> doubled<U: Number?>(U number) = twice(number)
			}
			fn<string> show<T: Base? & Named?>(T source) {
			    if (source == null) return "none"
			    def payload = source
			    println(payload::type)
			    println(payload.id)
			    return payload.describe() + " " + payload.name()
			}
			fn<string> throughInterface<T: Named?>(T value) {
			    if (value == null) return "none"
			    return value.name(label="view")
			}
			fn<int> throughMethod<T: Base?, N: Number?>(T value, N number) {
			    if (value == null || number == null) return 0
			    return value.echo(number) -> int
			}
			fn<T> twice<T: Number?>(T value) {
			    if (value == null) return null
			    def payload = value
			    return payload + payload
			}
			fn<int> choose<T: Number?>(T value) = 1
			fn<int> choose<T: Number>(T value, opt<bool> unused ?: false) = 2
			fn report(int<8>? amount, Player? player, Named? view) {
			    def box = Box::new(amount)
			    println(box.doubled(box.value))
			    println(choose(amount))
			    println(show(player))
			    println(throughInterface(view))
			    println(throughMethod(player, amount))
			    box::delete
			}
			fn main() {
			    def player = Player::new
			    report(4, player, player)
			    report(null, null, null)
			    println(choose(4))
			    player::delete
			}
		""", "8\n1\nPlayer\n19\nPlayer base interface\nview\n4\nnull\n1\nnone\nnone\n0\n2\n")
	}

	@Test
	fun `array bound shapes use ordinary mutation and preserve the exact numeric element`(@TempDir directory: Path) {
		native(directory, "array_bounds", """
			typedef<class> Base { pub mt<U> echo<U: Number>(U value) = value }
			typedef<class> Writer<T: int[]> (pub def<T> data) {
			    pub mt add(int value) { self.data.append(value) }
			}
			fn<T> bump<T: Number[]>(T values) {
			    def copy = values
			    def first = copy[0]
			    copy[0] = first + first
			    copy.append(first)
			    return copy
			}
			fn<T> fixed<T: int[4]>(T values) {
			    values[3] = values[0] + values[1]
			    return values
			}
			fn<T> elements<T: Number?[]>(T values) {
			    def first = values[0] ?: exit(3)
			    values[0] = null
			    values.append(first)
			    return values
			}
			fn<T> rows<T: Number[]?[2]?>(T values) {
			    if (values == null) return null
			    def row = values[0] ?: exit(4)
			    row[1] = row[0] + row[1]
			    values.append(row)
			    values[0] = null
			    return values
			}
			fn<int> through<R: Base, T: Number[]?>(R receiver, T values) {
			    if (values == null) return 0
			    values.append(values[0])
			    return receiver.echo(values[1]) -> int
			}
			fn<int> choose<T: Number[]>(T values) = 1
			fn<int> choose<T: Number[2]>(T values, opt<bool> unused ?: false) = 2
			fn main() {
			    def<int<8>[]> narrow = [3]
			    def<float[]> real = [1.5f]
			    def<int[]> ordinary = [8]
			    def writer = Writer::new(ordinary)
			    writer.add(9)
			    println(writer.data)
			    println(ordinary)
			    println(bump(narrow))
			    println(narrow)
			    println(bump(real))
			    println(fixed([2, 3, 1, 0]))
			    println(elements<int<8>?[]>([4, null]))
			    println(rows<int<8>[]?[2]?>([[1, 2], null]))
			    println(rows<int<8>[]?[2]?>(null))
			    def base = Base::new
			    println(through<Base, int<8>[]?>(base, narrow))
			    println(choose(narrow))
			    println(choose([1, 2]))
			    base::delete
			    writer::delete
			}
		""", "[8, 9]\n[8]\n[6, 3]\n[3]\n[3, 1.5]\n[2, 3, 1, 5]\n[null, null, 4]\n[null, null, [1, 3]]\nnull\n3\n1\n2\n")
	}

	@Test
	fun `array bound mutation checks retained row shape at runtime`(@TempDir directory: Path) {
		val source = """
			fn<int[]> short() = [9]
			fn edit<T: Number[]?[]>(T rows, T incoming) {
			    rows[0] = null
			    rows.append(incoming[0])
			    println("unreachable")
			}
			fn main() {
			    def<int[]?[]> rows = [[1, 2]]
			    def<int[]?[]> incoming = [short()]
			    edit(rows, incoming)
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("bound_shape_${abi.layout.sizeBits}"), status = 1)
			assertContains(output, "Array shape mismatch")
			assertFalse("unreachable" in output)
		}
	}

	@Test
	fun `combined bound calls rank ordinary contracts and retain the selected contract when specialized`(@TempDir directory: Path) {
		val source = """
			typedef<interface> Choice<N> {
			    mt<string> choose(N value, opt<string> label ?: "interface") = default (label)
			    mt<int> rank(Player value) = default (1)
			    mt<int> omission(opt<int> label ?: 5) = default (label)
			    mt<string> echo(string text) = default (text)
			}
			typedef<interface> Left<N> : Choice<N> {}
			typedef<interface> Right<N> : Choice<N> {}
			typedef<class> Base<N> (pub def<int> count) {
			    virtual: pub mt<string> choose(N value, opt<string> text ?: "base") = text
			    pub mt<int> rank(Object value) = 2
			    pub mt<int> omission(opt<int> first ?: 6, opt<int> second ?: 7) = second
			    pub mt<U> echo<U: Number>(U value) = value
			}
			typedef<class> Player : Base<int>(10), Left<int>, Right<int> {
			    pub def<int> count = 99
			    override: pub mt<string> choose(int value, string name) = #"Player {name}"
			    pub mt<int> rank(Player value, opt<int> extra ?: 3) = extra
			}
			fn<string> viaClass<A, B, T: Choice<B> & Base<A>>(T value, A arg) = value.choose(arg) + value.choose(arg, text="named")
			fn<string> viaInterface<A, B, T: Base<A> & Choice<B>>(T value, B arg) = value.choose(arg) + value.choose(arg, label="named")
			fn<string> diamond<T: Left<int> & Right<int>>(T value) = value.echo("diamond")
			fn<int> score<T: Choice<int> & Base<int>>(T value, Player player) {
			    value.count += 1
			    if (value.rank(player) != 1 || value.omission() != 5) return 0
			    if (value.echo("echo") != "echo" || value.echo<int>(4) != 4 || value.echo(7) != 7) return 0
			    return value.count
			}
			fn<int> main() {
			    def player = Player::new
			    if (viaClass<int, int, _>(player, 4) != "Player basePlayer named") return 1
			    if (viaInterface<int, int, _>(player, 4) != "Player interfacePlayer named") return 2
			    if (score(player, player) != 11 || player.count != 99) return 3
			    if (diamond(player) != "diamond") return 4
			    player::delete
			    println("combined calls")
			    return 0
			}
		"""
		native(directory, "combined_calls", source, "combined calls\n")
	}

	@Test
	fun `specialization keeps an inherited interface contract when two slots merge`(@TempDir directory: Path) {
		val source = """
			typedef<interface> First<N> { mt<N> echo(N value) }
			typedef<interface> Second<N> { mt<N> echo(N value) }
			typedef<interface> Both<A, B> : First<A>, Second<B> {}
			typedef<interface> Tag {}
			typedef<class> Player : Both<int, int>, Tag {
			    override: pub mt<int> echo(int value) = value + 1
			}
			fn<B> through<A, B, T: Tag & Both<A, B>>(T value, B arg) = value.echo(arg)
			fn main() {
			    def player = Player::new
			    println(through<int, int, _>(player, 7))
			    player::delete
			}
		"""
		native(directory, "inherited_bound", source, "8\n")
	}

	@Test
	fun `combined bounds check identity fallback and preserve ordinary object operations`(@TempDir directory: Path) {
		val source = """
			typedef<interface> Left {}
			typedef<interface> Right {}
			typedef<interface> Both : Left, Right {}
			typedef<class> Base { operator ===(Base other) = false }
			typedef<class> Player : Base, Both {
			    operator ===(Player other) = true
			    operator ==(Object other) = true
			    operator ::str() = "combined"
			    ~Player { println("released") }
			}
			fn<bool> classIdentity<T: Left & Base>(T left, T right) = left === right
			fn<bool> identity<T: Right & Left>(T? left, T? right) = left === right
			fn<bool> equal<T: Left & Right>(T left, T right) = left == right && !(left != right)
			fn display<T: Right & Left>(T value) {
			    println(value)
			    println(#"{value}")
			    println(value::str)
			}
			fn release<T: Left & Right>(T value) { value::delete }
			fn<Both> escape(Both value) = value
			fn<int> main() {
			    def first = Player::new
			    def second = Player::new
			    def<Both> view = first
			    if (classIdentity(first, first) || !identity<Player>(first, first) || identity<Player>(first, second)) return 2
			    if (!equal(first, second) || !identity<Both>(view, view)) return 3
			    display(first)
			    def stale = escape(view)
			    release(first)
			    release(second)
			    println(identity<Both>(stale, null))
			    println(identity<Both>(stale, stale))
			    return 0
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("combined_identity_${abi.layout.sizeBits}"), 1)
			assertEquals("combined\ncombined\ncombined\nreleased\nreleased\nfalse\nClass object is no longer live\n", output)
		}
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
		val files = genericSourceFiles(directory, mapOf("Box.co" to "pub typedef<class> Box<T> { pub mt<typeinfo> argument() = T::type }", "A.co" to "using Box : Box\ntypedef<struct> Hidden { int value }\npub fn<typeinfo> a() = Box<Hidden>::type\npub fn<typeinfo> aa() = Box<Hidden>[]?::type", "B.co" to "using Box : Box\ntypedef<struct> Hidden { int value }\npub fn<typeinfo> b() = Box<Hidden>::type\npub fn<typeinfo> bb() = Box<Hidden>[]?::type", "Main.co" to """
				using A : a, aa
				using B : b, bb
				fn main() {
				    println(a() == b()); println(aa() == bb())
				    println(a() == a()); println(a()); println(b())
				}
			"""))
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
