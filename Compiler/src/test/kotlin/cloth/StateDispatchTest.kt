package cloth

import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateDispatchTest {

	private val ordered = """
		fn<int> note(int marker, int value) { print(marker); return value }
		fn<Player> pick(Player item) { print(1); return item }
		fn<int> shift(Player item, int power) {
		    print(3)
		    item -> Stunned
		    return power + 1
		}
		typedef<class> Player (def<int> power) {
		    pub state<int, bool> ::attack(Player target, opt<int> left ?: shift(target, self.power), opt<int> right ?: note(4, left + 1))
		    pub state<int> ::status(opt<int> seen ?: self.power)
		    default: pub state<void> Alive {
		        mt<int, bool> attack(Player target, int a, int b) { print(5); return a + b, true }
		        mt<int> status(int read) = read + self.power
		    }
		    pub state<void> Stunned {
		        mt<int, bool> attack(Player target, int a, int b) { print(6); return a + b, false }
		    }
		    pub state<void> Dead {}
		    ~Player { println(self::status()) }
		}
		fn<int> main() {
		    def item = Player::new(10)
		    def<int, bool> one, alive = pick(item)::attack(item, right=note(2, 7))
		    if (one != 18 || alive || !(item is Stunned)) return 11
		    item -> Dead
		    if (item is Dead) {
		        def<int, bool> two, ready = pick(item)::attack(item)
		        if (two != 23 || ready || !(item is Stunned)) return 12
		    }
		    item -> Alive()
		    def<int, bool> three, again = item::attack(item, right=note(7, 3), left=note(8, 2))
		    if (three != 5 || !again) return 13
		    println("")
		    item::delete
		    return 0
		}
	"""

	@Test
	fun `supplied operands and defaults finish before state selection`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val output = runStructProgram(abi, ordered, directory.resolve("state_call_order_${abi.layout.sizeBits}"))
			assertEquals("12361346785\n20\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `state calls verify IR and objects across all target profiles`(@TempDir directory: Path) {
		val unit = structUnit(ordered)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(unit), "cloth.state.dispatch.")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("dispatch_$index.o"))) > 0, triple)
		}
		val source = Files.writeString(directory.resolve("dispatch.co"), ordered.trimIndent())
		val output = directory.resolve("cli.o")
		assertEquals(0, runCompiler(arrayOf("--no-stdlib", "--emit", "object", "--target", "aarch64-unknown-linux-gnu", source.toString(), output.toString())))
		assertTrue(Files.size(output) > 0)
	}

	@Test
	fun `missing active bodies fail after supplied argument or default effects`(@TempDir directory: Path) {
		val types = """
			typedef<class> C {
			    pub state ::hit(C target, opt<int> power ?: change(target))
			    default: pub state<void> Ready { mt hit(C other, int amount) { exit(12) } }
			    pub state<void> Dead {}
			}
			fn<int> change(C item) {
			    item -> Dead
			    println("changed")
			    return 1
			}
		""".trimIndent()
		val cases = listOf(
			"$types\nfn main() { def item = C::new; if (item is Ready) item::hit(item, change(item)) }" to "changed\nInvalid state for 'hit(C, int)'\n",
			"$types\nfn main() { def item = C::new; if (item is Ready) item::hit(item) }" to "changed\nInvalid state for 'hit(C, int)'\n",
			"typedef<class> C { pub state ::run() }\nfn main() { def item = C::new; item::run() }" to "Invalid state for 'run()'\n",
		)
		for (abi in structRunners()) for ((index, pair) in cases.withIndex()) {
			val (source, expected) = pair
			assertEquals(expected, runStructProgram(abi, source, directory.resolve("state_call_invalid_${abi.layout.sizeBits}_$index"), status = 1))
		}
	}

	@Test
	fun `stale receivers fail at the first actual access after argument effects`(@TempDir directory: Path) {
		val erased = """
			fn<int> note(string marker) { println(marker); return 1 }
			fn<int> erase(C item) { item::delete; println("argument"); return 5 }
			typedef<class> C (def<int> value ?: 7) {
			    pub state ::run(int required, opt<int> early ?: note("default"), opt<int> checked ?: self.value, opt<int> late ?: note("too late"))
			    default: pub state<void> Ready { mt run(int x, int y, int z, int w) { exit(13) } }
			}
			fn main() { def item = C::new; item::run(erase(item)) }
		"""
		val destructorAlias = """
			typedef<class> C {
			    default: pub state<void> Ready {
			        pub mt throughAlias() { def alias = self; alias::onlyElsewhere() }
			    }
			    pub state<void> Elsewhere { pub mt onlyElsewhere() { exit(13) } }
			    ~C { self::throughAlias() }
			}
			fn main() { def item = C::new; item::delete }
		"""
		val terminatingDefault = """
			typedef<class> C {
			    pub state ::run(opt<int> value ?: exit(7))
			    default: state<void> Ready { mt run(int value) {} }
			}
			fn main() { def item = C::new; item::delete; item::run() }
		"""
		for (abi in structRunners()) {
			assertEquals("argument\ndefault\nClass object is no longer live\n", runStructProgram(abi, erased, directory.resolve("state_call_stale_${abi.layout.sizeBits}"), status = 1))
			assertEquals("Class object is no longer live\n", runStructProgram(abi, destructorAlias, directory.resolve("state_call_destructor_alias_${abi.layout.sizeBits}"), status = 1))
			assertEquals(processCompletion(7).replace("\r\n", "\n"), runStructProgram(abi, terminatingDefault, directory.resolve("state_call_exit_${abi.layout.sizeBits}"), status = 7))
		}
	}

	@Test
	fun `defaults compose state and interface dispatch across closed inheritance`(@TempDir directory: Path) {
		val source = """
			typedef<class> Box<T> (def<T> data) {
			    default: (self.data)
			    pub state<T> Ready { pub mt<T> value() = state }
			}
			typedef<class> Child (def<string> text) : Box<string>("base") {
			    default: (self.text)
			    pub state<string> Later { pub mt<string> value() = state }
			}
			typedef<interface> Reader {
			    mt<string> read(Box<string> item, opt<string> result ?: sameBox(item)::value()) = default (result)
			}
			typedef<class> ReaderImpl : Reader
			typedef<class> Dispatch {
			    pub state<string> ::run(Reader service, Box<string> item, opt<string> value ?: sameReader(service).read(item))
			    default: state<void> Active { mt<string> run(Reader service, Box<string> item, string value) = value }
			}
			fn<Box<string>> sameBox(Box<string> item) = item
			fn<Reader> sameReader(Reader service) = service
			fn main() {
			    def item = Child::new("child")
			    def reader = ReaderImpl::new
			    def host = Dispatch::new
			    println(host::run(reader, item))
			    item -> Ready("base")
			    println(item::value())
			    host::delete
			    reader::delete
			    item::delete
			}
		"""
		for (abi in structRunners()) {
			assertEquals("child\nbase\n" + processCompletion().replace("\r\n", "\n"), runStructProgram(abi, source, directory.resolve("state_call_generic_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `a dispatched method retains its payload and returned values outlive activation release`(@TempDir directory: Path) {
		val source = """
			typedef<struct> Payload { string message, int[] values }
			typedef<class> C {
			    default: ({ "old" + " payload", [7] })
			    pub state<Payload> Ready {
			        pub mt<Payload, string> depart() {
			            self -> Next({ "next" + " payload", [9] })
			            return state, self::message()
			        }
			        pub mt<string> expire() { self::delete; return state.message }
			        pub mt<int> snapshot(int[] values, int ignored) = values[0]
			    }
			    pub state<Payload> Next { pub mt<string> message() = state.message }
			}
			fn<usize> liveBytes() = 0
			fn<bool> cycle() {
			    def item = C::new
			    def<int[]> values = [1]
			    def<int> change = { values[0] = 8; return 0 }
			    if (item::snapshot(values, change) != 1) return false
			    def<Payload, string> saved, next = item::depart()
			    item::delete
			    def other = C::new
			    def last = other::expire()
			    return saved.message == "old payload" && saved.values[0] == 7 && next == "next payload" && last == "old payload"
			}
			fn<bool> sample() {
			    def before = liveBytes()
			    for (def i <- 0..1000) if (!cycle() || liveBytes() != before) return false
			    return true
			}
		"""
		for (abi in structRunners()) {
			GrowableArrayStorageTest().probe(abi, directory.resolve("state_call_owners_${abi.layout.sizeBits}"), source) {
				exposeLiveBytes()
				expect(checkNotNull(sourceCall("sample", null)), "State dispatch lost a payload or retained its storage")
			}
		}
	}
}
