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

class InterfaceDispatchTest {
	private val dispatch = """
		fn<int> unexpected() { println("default ran"); return 100 }
		typedef<interface> Read {
		    mt<int> read(opt<int> offset ?: unexpected())
		    mt<int> later() = default (self.read())
		}
		typedef<interface> Extended : Read {}
		typedef<class> Base (pub def<int> value) : Extended {
		    override: pub mt<int> read(opt<int> add ?: unexpected()) = self.value + add
		}
		typedef<class> Shifted (int value, def<int> bump) : Base(value) {
		    override: pub mt<int> read(int delta) = self.value + bump + delta
		    pub mt<int> bonus() = bump
		}
		typedef<class> Other (def<int> value) : Read {
		    override: mt<int> read(int amount) = value - amount
		}
		typedef<interface> Value<T> { mt<T> get() }
		typedef<class> Box<T> (def<T> value) : Value<T> { override: mt<T> get() = value }
		fn<int> read(Read value, int amount) = value.read(offset=amount)
		fn<int> inherited(Extended value, int amount) = value.read(amount)
		fn<int> main() {
		    def first = Shifted::new(10, 5)
		    def<Base> base = first
		    if (base.read(1) != 11) return 1
		    def<Read> view = base
		    if (read(view, 2) != 17 || inherited(first, 3) != 18) return 2
		    def other = Other::new(20)
		    if (read(other, 4) != 16) return 3
		    if (view is Shifted) {
		        if (view.bonus() != 5) return 4
		    } else { return 5 }
		    if (!(view is Extended)) return 6
		    def<Object?> maybe = first
		    if (maybe is Read) {
		        if (maybe.read(5) != 20) return 7
		    } else { return 8 }
		    def<Object?> absent = null
		    if (absent is Read) return 9
		    def<Read?> missing = null
		    missing::delete
		    def<Read?> optional = other -> Read?
		    if (optional != null) {
		        if (optional.read(6) != 14) return 10
		    } else { return 11 }
		    optional::delete
		    def text = Box<string>::new("ready")
		    def number = Box<int>::new(42)
		    def<Value<string>> textView = text
		    def<Value<int>> numberView = number
		    if (textView.get() != "ready" || numberView.get() != 42) return 12
		    if (textView is Value<int> || !(textView is Value<string>)) return 13
		    textView::delete
		    numberView::delete
		    view::delete
		    println("dispatch")
		    return 0
		}
	"""

	private val transport = """
		typedef<interface> Transport {
		    mt<string, int[]> collect(string label, int[] values, int marker)
		    mt<float<16>?> half(float<16>? input)
		}
		typedef<class> Worker : Transport {
		    override: mt<string, int[]> collect(string title, int[] snapshot, int check) {
		        if (check != 7) return "wrong", snapshot
		        return title, snapshot
		    }
		    override: mt<float<16>?> half(float<16>? actual) = actual
		}
		fn<Transport> select(Worker worker) { print("r"); return worker }
		fn<int> main() {
		    def worker = Worker::new
		    def data = [1, 2]
		    def label = "old"
		    def<int> update = { print("m"); data[0] = 9; label = "new"; return 7 }
		    def<string, int[]> title, snapshot = select(worker).collect(values=data, marker=update, label=label)
		    if (title != "new" || snapshot[0] != 1 || data[0] != 9) return 1
		    def<Transport> view = worker
		    def<float<16>?> half = view.half(0.5)
		    if (half != (0.5 -> float<16>)) return 2
		    if (view.half(null) != null) return 3
		    view::delete
		    println("ok")
		    return 0
		}
	"""

	@Test
	fun `native required calls select concrete and generic targets through each static view`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val output = runStructProgram(abi, dispatch, directory.resolve("views_${abi.layout.sizeBits}"))
			assertEquals("dispatch\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `dispatch snapshots supplied values in written order and returns buffered results`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val output = runStructProgram(abi, transport, directory.resolve("transport_${abi.layout.sizeBits}"))
			assertEquals("rmok\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `interface dispatch verifies IR and emits objects for every target`(@TempDir directory: Path) {
		for ((name, source) in listOf("dispatch" to dispatch, "transport" to transport)) {
			val unit = structUnit(source)
			for ((index, triple) in structTargetTriples.withIndex()) {
				val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
				val ir = emitter.emitExecutable(unit)
				assertContains(ir, "target triple = \"$triple\"")
				assertContains(ir, "@cloth.interface.dispatch.")
				assertContains(ir, "initialized_class")
				assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("${name}_$index.o"))) > 0)
			}
		}
	}
}
