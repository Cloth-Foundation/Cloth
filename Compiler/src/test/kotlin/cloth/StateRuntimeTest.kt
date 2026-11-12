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

class StateRuntimeTest {

	private val lifecycle = """
		typedef<struct> Entry { string label, int[] scores }
		fn<Entry> initial(Parent item, string label) {
		    if (item is Ready) exit(11)
		    return { label + "!", [4, 7] }
		}
		typedef<class> Parent (pub def<string> label) {
		    pub def<bool> early = self is Ready
		    default: (initial(self, self.label))
		    pub state<Entry> Ready {
		        init {
		            if (!(self is Ready)) exit(12)
		            println(state.label)
		            println(state.scores[1])
		        }
		    }
		    pub state<void> Idle { init { println("idle") } }
		    ~Parent { println(self is Ready) }
		}
		typedef<class> Inherited (string label) : Parent(label)
		typedef<class> Child (pub def<string> extra) : Parent("base") {
		    Child(int marker) : ("early") {
		        if (self is Loaded || self is Ready) exit(13)
		        self.extra = "late"
		        return
		    }
		    default: (self.extra)
		    pub state<string> Loaded { init { println(state) } }
		    ~Child { println(self is Idle) }
		}
		fn<Parent> receiver(Parent item) { print("receiver "); return item }
		fn<Entry> incoming() { print("payload "); return { "changed", [5, 8] } }
		fn main() {
		    def child = Child::new(1)
		    if (child.early || !(child is Loaded) || child is Ready) exit(14)
		    receiver(child) -> Ready(incoming())
		    def<Parent> base = child
		    if (!(base is Ready)) exit(15)
		    base -> Ready({ "again", [6, 9] })
		    base -> Idle
		    base -> Idle()
		    child::delete
		    def inherited = Inherited::new("inherited")
		    if (inherited.early || !(inherited is Ready)) exit(16)
		    inherited::delete
		}
	"""

	@Test
	fun `outer construction installs one effective activation and destruction preserves it`(@TempDir directory: Path) {
		val expected = "late\nreceiver payload changed\n8\nagain\n9\nidle\nidle\ntrue\nfalse\ninherited!\n7\ntrue\n"
		for (abi in structRunners()) {
			val output = runStructProgram(abi, lifecycle, directory.resolve("state_lifecycle_${abi.layout.sizeBits}"))
			assertEquals(expected + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `state lowering verifies and emits objects across supported profiles`(@TempDir directory: Path) {
		val unit = structUnit(lifecycle)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emitExecutable(unit)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "define internal void @cloth.states.release")
			assertContains(ir, "define internal void @cloth.state.initial.")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("state_$index.o"))) > 0, triple)
		}
		val source = Files.writeString(directory.resolve("states.co"), lifecycle.trimIndent())
		val output = directory.resolve("cli.o")
		assertEquals(0, runCompiler(arrayOf("--no-stdlib", "--emit", "object", "--target", "aarch64-unknown-linux-gnu", source.toString(), output.toString())))
		assertTrue(Files.size(output) > 0)
	}

	@Test
	fun `transition captures its receiver before payload effects`(@TempDir directory: Path) {
		val source = """
			typedef<class> Spare
			typedef<class> C {
			    pub def<int> value = 0
			    default: pub state<void> Empty {}
			    pub state<int> Full { init { self.value = state } }
			}
			fn<int> growTable() {
			    def<Spare[]> spares = []
			    for (def i <- 0..40) spares.append(Spare::new)
			    for (def spare <- spares) spare::delete
			    return 13
			}
			fn<int> main() {
			    def first = C::new
			    def second = C::new
			    def chosen = first
			    def<int> prepare = { chosen = second; return growTable() }
			    chosen -> Full(prepare)
			    if (!(first is Full) || !(second is Empty) || first.value != 13) return 1
			    first::delete
			    second::delete
			    return 0
			}
		"""
		for (abi in structRunners()) {
			assertEquals(processCompletion().replace("\r\n", "\n"), runStructProgram(abi, source, directory.resolve("state_capture_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `closed generic activations keep their own payload layouts`(@TempDir directory: Path) {
		val source = """
			typedef<class> Slot<T> (pub def<T> value) {
			    default: (self.value)
			    pub state<T> Full { init { self.value = state } }
			    pub state<void> Empty {}
			}
			typedef<class> Text : Slot<string>("initial")
			fn<int> main() {
			    def number = Slot<int>::new(7)
			    def text = Text::new
			    if (!(number is Full) || !(text is Full)) return 1
			    number -> Empty
			    text -> Full("changed" + " text")
			    if (!(number is Empty) || !(text is Full) || text.value != "changed text") return 2
			    number::delete
			    text::delete
			    return 0
			}
		"""
		for (abi in structRunners()) {
			assertEquals(processCompletion().replace("\r\n", "\n"), runStructProgram(abi, source, directory.resolve("state_generic_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `explicit transition requires a live receiver with an active state`(@TempDir directory: Path) {
		val inactive = """
			typedef<class> C {
			    default: state<void> Ready {}
			    C(int mode) : () { self -> Ready() }
			}
			fn main() { C::new(1) }
		"""
		val stale = """
			typedef<class> C {
			    default: pub state<void> Ready {}
			    pub state<int> Full {}
			}
			fn<int> payload(C item) {
			    item::delete
			    def replacement = C::new
			    if (!(replacement is Ready)) exit(2)
			    println("payload evaluated")
			    return 7
			}
			fn main() {
			    def item = C::new
			    item -> Full(payload(item))
			}
		"""
		val destroying = """
			typedef<class> C {
			    default: state<void> Ready {}
			    state<int> Full {}
			    mt transition() { self -> Full(payload()) }
			    ~C { if (self is Ready) transition() }
			}
			fn<int> payload() { println("destructor payload"); return 7 }
			fn main() { def item = C::new; item::delete }
		"""
		val staleTest = """
			typedef<class> C { default: pub state<void> Ready {} }
			fn remove(C item) { item::delete }
			fn main() { def item = C::new; remove(item); println(item is Ready) }
		"""
		val cases = listOf(
			inactive to "Class object has no active state\n",
			stale to "payload evaluated\nClass object is no longer live\n",
			destroying to "destructor payload\nClass object is no longer live\n",
			staleTest to "Class object is no longer live\n",
		)
		for (abi in structRunners()) for ((index, pair) in cases.withIndex()) {
			val (source, expected) = pair
			assertEquals(expected, runStructProgram(abi, source, directory.resolve("state_error_${abi.layout.sizeBits}_$index"), status = 1))
		}
	}

	@Test
	fun `old activations survive reentry and deletion while init still uses the payload`(@TempDir directory: Path) {
		val source = """
			typedef<class> Token (pub def<int> number)
			typedef<struct> Payload { string label, int[] values, Token token }
			typedef<class> C {
			    default: pub state<void> Ready {}
			    pub state<Payload> Armed {
			        init {
			            if (state.label == "outer") {
			                state.values.append(10)
			                def saved = state
			                self -> Armed({ "inner", [4, 5], state.token })
			                if (state.label != "outer" || state.values[1] != 10 || saved.values[1] != 10) exit(21)
			            }
			            if (state.label == "remove") {
			                self::delete
			                if (state.label != "remove" || state.values[0] != 31 || state.token.number != 5) exit(22)
			            }
			        }
			    }
			    ~C { if (!(self is Armed)) exit(23) }
			}
			fn<usize> liveBytes() = 0
			fn<bool> sample() {
			    def item = C::new
			    def token = Token::new(5)
			    def before = liveBytes()
			    for (def i <- 0..1000) {
			        item -> Armed({ "out" + "er", [3], token })
			        item -> Ready
			        if (liveBytes() != before) return false
			    }
			    item -> Armed({ "remove", [31], token })
			    def result = token.number == 5
			    token::delete
			    return result
			}
		"""
		for (abi in structRunners()) {
			GrowableArrayStorageTest().probe(abi, directory.resolve("state_owners_${abi.layout.sizeBits}"), source) {
				exposeLiveBytes()
				expect(checkNotNull(sourceCall("sample", null)), "Activation storage did not return to its live-byte baseline")
			}
		}
	}

	@Test
	fun `activation allocation failure stops before entry`(@TempDir directory: Path) {
		val source = """
			typedef<class> C { default: state<void> Ready { init { exit(17) } } }
			fn sample() { C::new }
		"""
		for (abi in structRunners()) {
			GrowableArrayStorageTest().probe(abi, directory.resolve("state_alloc_${abi.layout.sizeBits}"), source, failAt = 2L, expectedFailure = "Unable to allocate state activation") {
				sourceCall("sample", null)
			}
		}
	}
}
