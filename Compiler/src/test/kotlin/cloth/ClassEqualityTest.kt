package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ClassEqualityTest {
	private val equality = """
		typedef<class> Record (def<int> id) {
		    operator ==(Object other) {
		        print(3)
		        if (other is Record) return self.id == other.id
		        return false
		    }
		    pub mt<int> read() = id
		}
		typedef<class> First (int id) : Record(id)
		typedef<class> Second (int id) : Record(id) {
		    operator !=(Object other) = true
		}
		typedef<class> Plain
		fn<Object> trace(int marker, Object value) { print(marker); return value }
		fn<int> read(Record? value) {
		    if (value === null) return 0
		    return value.read()
		}
		fn<int> main() {
		    def first = First::new(7)
		    def second = First::new(7)
		    def sibling = Second::new(7)
		    def<Object> a = first
		    def<Object> b = second
		    def<Object> c = sibling
		    if (!(trace(1, a) == trace(2, b))) return 1
		    if (a != b || a === b) return 2
		    if (!(a == first) || !(a === first)) return 3
		    if (a == c || c == a || !(c != c)) return 4
		    def<Object?> absent = null
		    def<Record?> present = first
		    if (!(absent == absent) || !(absent === absent)) return 5
		    if (absent == present || present === absent || !(present == b)) return 6
		    if (read(present) != 7 || read(null) != 0) return 7
		    def plain = Plain::new
		    def other = Plain::new
		    if (plain == other || !(plain != other) || !(plain == plain)) return 8
		    println()
		    a::delete
		    if (!(a === first) || present === null || present == absent) return 9
		    b::delete
		    c::delete
		    plain::delete
		    other::delete
		    return 0
		}
	"""

	private val identity = """
		typedef<class> Left {
		    operator ===(Left other) = false
		}
		typedef<class> Right {
		    operator ===(Left other, self) = true
		}
		fn<int> main() {
		    def a = Left::new
		    def b = Right::new
		    def<Left?> maybe = a
		    if (a === a || maybe === a) return 1
		    if (!(a === b) || b === a) return 2
		    if (!((a -> Object) === (maybe -> Object?))) return 3
		    a::delete
		    if (!((a -> Object) === (maybe -> Object?))) return 4
		    if (maybe === null) return 5
		    b::delete
		    return 0
		}
	"""

	@Test
	fun `class equality dispatches through root and nullable bindings`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val output = runStructProgram(abi, equality, directory.resolve("equality_${abi.layout.sizeBits}"))
			assertEquals("12333\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `identity overrides preserve raw alias identity and use both sides`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val output = runStructProgram(abi, identity, directory.resolve("identity_${abi.layout.sizeBits}"))
			assertEquals(processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `comparison fixtures verify and emit for all target profiles`(@TempDir directory: Path) {
		val programs = listOf(structUnit(equality), structUnit(identity))
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			for ((fixture, unit) in programs.withIndex()) {
				assertContains(emitter.emitExecutable(unit), "target triple = \"$triple\"")
				assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("comparison_${index}_$fixture.o"))) > 0)
			}
		}
	}

	@Test
	fun `logical equality checks escaped stale aliases while raw identity distinguishes reused slots`(@TempDir directory: Path) {
		val source = """
			typedef<class> Item
			fn<Object> escape(Object value) = value
			fn main() {
			    def a = Item::new
			    def stale = escape(a)
			    a::delete
			    def replacement = Item::new
			    println(stale === replacement)
			    println(stale == replacement)
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("stale_${abi.layout.sizeBits}"), 1)
			assertEquals("false\nClass object is no longer live\n", output)
		}
	}

	@Test
	fun `invalid comparison declarations and proved stale checks stop compilation`() {
		for (member in listOf("operator ==(Item other) = true", "operator !=(Object other, self) = false", "operator ===(int other) = true")) {
			val failure = assertFailsWith<SemanticException> { FunctionAnalyzer().analyze(structUnit("typedef<class> Item { $member }")) }
			assertEquals(DiagnosticCode.INVALID_OPERATOR_DECLARATION, failure.diagnosticCode)
		}
		for (op in listOf("==", "!=", "===")) {
			val source = """
				typedef<class> Item { operator ===(Object other) = true }
				fn main() {
				    def a = Item::new
				    a::delete
				    println(a $op a)
				}
			"""
			val failure = assertFailsWith<SemanticException> { FunctionAnalyzer().analyze(structUnit(source)) }
			assertEquals(DiagnosticCode.DELETED_CLASS_ACCESS, failure.diagnosticCode)
		}
	}
}
