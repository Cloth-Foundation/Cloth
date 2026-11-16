package cloth

import cloth.api.ClothCompiler
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.typing.FunctionAnalyzer
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClassUpdateOperatorsTest {

	private val meter = """
		typedef<class> Meter (pub def<int> value) {
		    operator +=(int delta) = value += delta
		    pub operator -=(int delta) { value -= delta }
		    pub operator *=(int factor) { value *= factor }
		    pub operator /=(int divisor) { value /= divisor }
		    pub operator %=(int divisor) { value %= divisor }
		    operator ++() { value++ }
		    pub operator --() { value-- }
		    operator +(string label) -> Meter { return Meter::new(value + 100) }
		}
	"""

	private val types = """
		$meter
		typedef<class> Special (int value) : Meter(value)
		typedef<class> Holder (pub def<Meter> meter) {
		    pub mt adjust() { meter *= 2 }
		}
		typedef<struct> Slot { Meter meter }
		typedef<class> Box (def<Meter> item) {
		    def<bool> present = true
		    pub mt remove() { present = false }
		    operator []?(usize i) -> bool { print("check "); return present && i == 0 }
		    pub operator [](usize i) -> Meter { print("get "); return item }
		    pub operator []=(usize i, Meter next) { println("set"); item = next }
		}
	"""

	private val program = """
		$types
		fn<int> leave(Meter meter) {
		    def local = meter
		    local += if (true) { return 8 } else { 99 }
		}
		fn<int> main() {
		    def first = Special::new(10)
		    def second = Meter::new(20)
		    def<Meter> selected = first
		    def<int> rebind = { selected = second; return 2 }
		    selected += rebind
		    if (first.value != 12 || !(selected === second)) return 1
		    def holder = Holder::new(first)
		    holder.adjust()
		    def<int> replace = { holder.meter = second; return 4 }
		    holder.meter -= replace
		    if (first.value != 20 || !(holder.meter === second)) return 2
		    def<Slot> slot = { second }
		    slot.meter /= 2
		    def<Meter[]> values := [first, second]
		    def<int> clear = { values.clear(); return 6 }
		    values[0] %= clear
		    if (values::size != 0 || first.value != 2) return 3
		    [first][0]++
		    def slots = [slot]
		    slots[0].meter--
		    if (first.value != 3 || second.value != 9 || leave(first) != 8) return 4
		    values.append(first)
		    values[0] += "fallback"
		    def replacement = values[0]
		    if (replacement.value != 103 || first.value != 3) return 5
		    def<Meter?> maybe = first
		    if (maybe != null) maybe++
		    if (first.value != 4) return 6
		    def box = Box::new(first)
		    box[0]++
		    if (first.value != 5) return 7
		    box::delete
		    replacement::delete
		    holder::delete
		    first::delete
		    second::delete
		    return 0
		}
	"""

	@Test
	fun `locals fields and array elements update captured identities with ordinary fallback`(@TempDir root: Path) {
		for (abi in structRunners()) assertEquals("check get " + completion, runStructProgram(abi, program, root.resolve("meter_ops_${abi.layout.sizeBits}")))
	}

	@Test
	fun `dedicated updates verify IR and emit objects on all target profiles`(@TempDir root: Path) {
		val unit = structUnit(program)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(unit), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(unit, root.resolve("update_$index.o"))) > 0)
		}
	}

	@Test
	fun `class brackets validate and read once and skip the setter on dedicated updates`(@TempDir root: Path) {
		val source = """
			$types
			fn<Box> receiver(Box box) { print("receiver "); return box }
			fn<usize> index() { print("index "); return 0 }
			fn main() {
			    def item = Meter::new(10)
			    def box = Box::new(item)
			    def<int> rhs = { print("rhs "); box.remove(); return 3 }
			    receiver(box)[index()] += rhs
			    println(item.value)
			    def other = Box::new(item)
			    other[0]++
			    println(item.value)
			    other[0] += "fallback"
			    def next = other[0]
			    println(next.value)
			    println(item.value)
			    other::delete
			    box::delete
			    next::delete
			    item::delete
			}
		"""
		val expected = "receiver index check get rhs 13\ncheck get 14\ncheck get check set\ncheck get 114\n14\n" + completion
		for (abi in structRunners()) assertEquals(expected, runStructProgram(abi, source, root.resolve("bracket_${abi.layout.sizeBits}")))
	}

	@Test
	fun `imported List can lose its slot or be deleted after capturing a live element`(@TempDir root: Path) {
		val source = """
			$meter
			fn<int> release(List<Meter> list) { list.removeAt(0); return 2 }
			fn<int> dispose(List<Meter> list) { list::delete; return 3 }
			fn main() {
			    def item = Meter::new(10)
			    def list = List<Meter>::new
			    list.append(item)
			    list[0] += release(list)
			    println(item.value)
			    list.append(item)
			    list[0] += dispose(list)
			    println(item.value)
			    item::delete
			}
		"""
		for (abi in structRunners()) {
			val request = standardListRequest(root.resolve("list_${abi.layout.sizeBits}"), source, abi.targetTriple)
			assertEquals("12\n15\n" + completion, runExecutable(ClothCompiler.compile(request).artifact).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `initial bounds precede the RHS and a deleted captured object stops dispatch`(@TempDir root: Path) {
		val cases = listOf(
			"values[1] += remove(item)" to "Array index out of bounds\n",
			"values[0] += remove(item)" to "rhs\nClass object is no longer live\n",
			"box[1] += remove(item)" to "check Index out of bounds\n",
			"box[0] += remove(item)" to "check get rhs\nClass object is no longer live\n",
		)
		for ((index, test) in cases.withIndex()) {
			val (operation, expected) = test
			val source = """
				$types
				fn<int> remove(Meter item) { item::delete; println("rhs"); return 2 }
				fn main() {
				    def item = Meter::new(10)
				    def values = [item]
				    def box = Box::new(item)
				    $operation
				    println("unreachable")
				}
			"""
			assertEquals(expected, runStructProgram(NativeCABI.native(), source, root.resolve("invalid_$index"), status = 1))
		}
	}

	@Test
	fun `mutating updates invalidate presence and deletion authority proofs`(@TempDir root: Path) {
		val types = """
			typedef<class> Base { pub def<int?> value = 1 }
			typedef<class> Counter : Base {
			    operator ++() { value = null }
			    operator +=(int delta) { value = null }
			}
		"""
		for (operation in listOf("alias++", "alias += 1")) {
			val source = """
				$types
				fn check(Base source) {
				    def alias = source
				    if (alias is Counter && source.value != null) {
				        $operation
				        println(source.value + 1)
				    }
				}
			"""
			val failure = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(source)) }
			assertEquals(DiagnosticCode.NULLABLE_PAYLOAD_ACCESS, failure.diagnosticCode)
		}

		val input = standardListRequest(root, mapOf("Main.co" to """
			using cloth.collections.OwningList : OwningList
			typedef<class> Item { ~Item { println("released") } }
			typedef<class> Releaser (def<OwningList<Item>> owner) {
			    operator ++() { owner.removeAt(0) }
			}
			fn main() {
			    def owner = OwningList<Item>::new
			    def item = Item::new
			    owner.append(item)
			    def releaser = Releaser::new(owner)
			    releaser++
			    item::delete
			    releaser::delete
			    owner::delete
			}
		"""))
		assertEquals("released\n" + completion, runExecutable(ClothCompiler.compile(input).artifact).replace("\r\n", "\n"))
	}

	@Test
	fun `dedicated updates keep declaration writable target and overload diagnostics`() {
		val cases = listOf(
			"typedef<class> Bad { operator +=(int n) -> int = n }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<class> Bad { operator ++(int n) {} }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<class> Bad { operator +=(int n, self) {} }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<struct> Bad { int value; operator ++() {} }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"fn check() { def locked := Meter::new(1); locked += 1 }" to DiagnosticCode.IMMUTABLE_BINDING,
			"fn check() { def locked := Meter::new(1); locked++ }" to DiagnosticCode.IMMUTABLE_BINDING,
			"""
				typedef<class> ReadOnly (def<Meter> item) {
				    pub operator [](usize i) = item
				}
				fn check(ReadOnly box) { box[0] += 1 }
			""" to DiagnosticCode.INVALID_INDEX,
			"""
				typedef<class> Base { operator []?(usize i) = true; pub operator []=(usize i, Meter item) {} }
				typedef<class> Child (def<Meter> item) : Base {
				    operator []?(usize i) = true
				    pub operator [](usize i) = item
				}
				fn check(Child box) { box[0]++ }
			""" to DiagnosticCode.INVALID_INDEX,
			"""
				typedef<interface> Left { mt left() }
				typedef<interface> Right { mt right() }
				typedef<class> Both : Left, Right {
				    override: mt left() {}
				    override: mt right() {}
				}
				typedef<class> Choice {
				    pub operator +=(Left n) {}
				    pub operator +=(Right n) {}
				    operator +(Both n) = self
				}
				fn check() { def choice = Choice::new; choice += Both::new }
			""" to DiagnosticCode.OPERATOR_MATCH,
			"fn check() { def item = Meter::new(1); item::delete; item++ }" to DiagnosticCode.DELETED_CLASS_ACCESS,
			"fn check() { def item = Meter::new(1); item += if (true) { item::delete; 1 } else 2 }" to DiagnosticCode.DELETED_CLASS_ACCESS,
		)
		for ((source, code) in cases) {
			val failure = assertFailsWith<ClothDiagnostic>(source) {
				FunctionAnalyzer(nativeTestLayout).analyze(structUnit("$meter\n$source"))
			}
			assertEquals(code, failure.diagnosticCode, failure.summary)
			assertLocated(failure)
		}
	}

	private val completion get() = processCompletion().replace("\r\n", "\n")
}
