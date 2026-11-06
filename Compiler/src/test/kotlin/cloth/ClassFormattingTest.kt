package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.SemanticException
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ClassFormattingTest {
	private val formatting = """
		typedef<class> Tick (def<string> label) {
		    def<int> calls = 0
		    operator ::str() {
		        self.calls += 1
		        return #"{self.label}:{self.calls}"
		    }
		    pub mt<int> count() = calls
		    ~Tick {
		        print(#"drop {self}")
		        println(self)
		        println(self::str)
		    }
		}
		typedef<class> Inherited (string label) : Tick(label)
		typedef<class> Loud : Tick("unused") {
		    operator ::str() = "loud"
		}
		typedef<class> Root
		typedef<class> Plain : Root
		fn<Object> upcast(Object value) = value
		fn<int> early(Tick value) {
		    println(if (true) { return 7 } else { value })
		    return 0
		}
		fn<int> main() {
		    def plain = Plain::new
		    println(upcast(plain))
		    def item = Inherited::new("é😀\0")
		    def<Object> object = item
		    println(object::str)
		    print(object)
		    println()
		    println(#"seen {object}")
		    def<Object?> some = item
		    def<Object?> none = null
		    println(some)
		    println(none)
		    println(#"{some}/{none}")
		    if (some != null) println(some::str)
		    def<Object?[3]> flat = [some, none, some]
		    println(flat)
		    println(flat::str)
		    def<Object?[]?[]> rows = [[some, none], null, [some, some]]
		    println(#"{rows}")
		    def<Object[0]> empty = []
		    println(empty)
		    if (early(item) != 7 || item.count() != 13) return 1
		    def loud = Loud::new
		    println(upcast(loud))
		    loud::delete
		    item::delete
		    plain::delete
		    return 0
		}
	"""

	@Test
	fun `formats dynamically and captures class array leaf text once`(@TempDir directory: Path) {
		val label = "é😀\u0000"
		val expected = listOf(
			"Plain", "$label:1", "$label:2", "seen $label:3", "$label:4", "null", "$label:5/null", "$label:6",
			"[$label:7, null, $label:8]", "[$label:9, null, $label:10]", "[[$label:11, null], null, [$label:12, $label:13]]", "[]",
			"loud", "drop loudloud", "loud", "drop $label:14$label:15", "$label:16",
		).joinToString("\n", postfix = "\n") + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			assertEquals(expected, runStructProgram(abi, formatting, directory.resolve("format_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `formatter effects do not change the captured array traversal`(@TempDir directory: Path) {
		val source = """
			typedef<class> Old
			typedef<class> Replacement
			typedef<class> Bag (pub def<Object?[2]> entries) {
			    pub mt replace(Object next) { self.entries[1] = next }
			}
			typedef<class> Change (def<Bag> bag, def<Object> next) {
			    operator ::str() {
			        self.bag.replace(self.next)
			        return "first"
			    }
			}
			fn main() {
			    def old = Old::new
			    def replacement = Replacement::new
			    def bag = Bag::new([null, old])
			    def first = Change::new(bag, replacement)
			    bag.entries[0] = first
			    println(bag.entries)
			    println(bag.entries[1])
			    first::delete
			    bag::delete
			    old::delete
			    replacement::delete
			}
		"""
		val expected = "[first, Old]\nReplacement\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			assertEquals(expected, runStructProgram(abi, source, directory.resolve("snapshot_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `class formatting emits valid objects for all profiles`(@TempDir directory: Path) {
		val unit = structUnit(formatting)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(unit), "@cloth.objects.format")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("format_$index.o"))) > 0)
		}
	}

	@Test
	fun `formatting a stale alias fails before output`(@TempDir directory: Path) {
		val source = """
			typedef<class> Item { operator ::str() = "item" }
			fn<Object> escape(Object value) = value
			fn main() {
			    def item = Item::new
			    def alias = escape(item)
			    item::delete
			    println(alias)
			}
		"""
		for (abi in structRunners()) {
			assertEquals("Class object is no longer live\n", runStructProgram(abi, source, directory.resolve("stale_${abi.layout.sizeBits}"), 1))
		}
	}

	@Test
	fun `formatter declarations and receiver contracts report source errors`() {
		val cases = listOf(
			"typedef<class> Item { operator ::str(int n) = \"item\" }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<class> Item { operator ::str() -> int = 1 }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<class> Item\nfn<string> text() = Item::str" to DiagnosticCode.INVALID_PROVIDER_RECEIVER,
			"typedef<class> Item\nfn<string> text(Item? item) = item::str" to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS,
			"typedef<class> Item { operator ::str() = \"item\" }\nfn<string> text(Item item, opt<string> result ?: item::str) = result" to DiagnosticCode.INVALID_PARAMETER_DEFAULT,
			"typedef<class> Item\nfn main() { def item = Item::new; item::delete; println(item) }" to DiagnosticCode.DELETED_CLASS_ACCESS,
			"typedef<class> Item\nfn main() { def item = Item::new; def<Item?> alias = item; item::delete; println(alias) }" to DiagnosticCode.DELETED_CLASS_ACCESS,
		)
		for ((source, code) in cases) {
			val error = assertFailsWith<SemanticException> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(source)) }
			assertEquals(code, error.diagnosticCode, source)
		}
	}

	@Test
	fun `effectful formatter invalidates aliased presence proofs between holes`() {
		val source = """
			typedef<class> Item (pub def<int> number)
			typedef<class> Holder (pub def<Item?> item)
			typedef<class> Change (def<Holder> holder) {
			    operator ::str() {
			        self.holder.item = null
			        return "change"
			    }
			}
			fn<string> describe(Object text, Holder holder) {
			    if (holder.item != null) return #"{text}{holder.item.number}"
			    return "absent"
			}
		"""
		val error = assertFailsWith<SemanticException> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(source)) }
		assertEquals(DiagnosticCode.NULLABLE_PAYLOAD_ACCESS, error.diagnosticCode)
	}
}
