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

class ValueOperatorsTest {

	private val structEquality = """
		typedef<struct> Record {
		    string label,
		    int[2] keys;
		    operator ==(Record other) = self.label == other.label && self.keys[0] == other.keys[0]
		}
		typedef<struct> Right {
		    int value;
		    operator ==(Right other, self) = self.value > other.value
		    operator !=(Right other) = self.value == other.value
		}
		typedef<class> Box (pub def<Record> entry)
		fn<Record?> maybe(bool present, Record value) = if (present) { value } else { null }
		fn<int> main() {
		    def<Record> original = { "é😀", [1, 2] }
		    def<Record> expected = { "é😀", [1, 3] }
		    def<Record> later = {
		        original.label = "changed"
		        original.keys[0] = 99
		        return expected
		    }
		    if (!(original == later) || original == expected || !(original != expected)) return 1
		    if (maybe(false, original) != maybe(false, expected)) return 2
		    if (maybe(true, original) == maybe(false, expected)) return 3
		    if (!(maybe(true, expected) == expected)) return 4
		    def<Right> one = { 1 }
		    def<Right> two = { 2 }
		    if (!(one == two) || two == one || one != two || !(one != one)) return 5
		    def box = Box::new(expected)
		    if (!(box.entry == expected)) return 6
		    box::delete
		    println("struct equality")
		    return 0
		}
	"""

	private val enumFormatting = """
		typedef<enum> Damage (pub def<int> number = 1, pub def<string> text = "é😀") {
		    ATTACK,
		    FALL,
		    ENVIRONMENT(10.0 -> int),
		    UNKNOWN(-1, "other")
		}
		typedef<enum> Tone (def<int> group, pub def<string> text) {
		    FIRST(2, "é😀\0"),
		    SECOND(2, "two"),
		    THIRD(3, "three");
		    operator ==(Tone other) = group == other.group
		    operator ::str() {
		        print("!")
		        return self.text
		    }
		}
		typedef<enum> Palette (pub def<int[2]> channels = [1, 2]) { FIRST, SECOND([3, 4]) }
		typedef<class> Source (pub def<Damage> damage)
		fn<Tone?> maybe(bool present, Tone value) = if (present) { value } else { null }
		fn<int> main() {
		    if (Damage.ATTACK == Damage.FALL || Damage.ATTACK != Damage.ATTACK) return 1
		    if (Damage.ATTACK.number != Damage.FALL.number || Damage.FALL -> int != 1) return 2
		    if (Damage.ENVIRONMENT.number != 10 || Damage.UNKNOWN.number != -1) return 3
		    if (!(Tone.FIRST == Tone.SECOND) || Tone.FIRST != Tone.SECOND || !(Tone.FIRST != Tone.THIRD)) return 4
		    if (maybe(false, Tone.FIRST) == Tone.SECOND || !(maybe(true, Tone.FIRST) == Tone.SECOND)) return 5
		    def source = Source::new(Damage.FALL)
		    def damage = source.damage
		    source::delete
		    if (damage::type != Damage::type || !(damage is Damage)) return 6
		    def channels = Palette.FIRST.channels
		    channels[0] = 9
		    if (Palette.FIRST.channels[0] != 1 || Palette.SECOND.channels[1] != 4) return 7
		    println(damage)
		    println(damage.text)
		    println(Tone.FIRST::str)
		    println(#"{Tone.SECOND}/{maybe(false, Tone.FIRST)}")
		    def<Tone?[]?[]> rows = [[Tone.THIRD, null], null, [Tone.SECOND, Tone.THIRD]]
		    println(rows)
		    def<Damage[]> empty = []
		    println(empty::str)
		    return 0
		}
	"""

	@Test
	fun `struct equality snapshots each buffered operand before the next expression`(@TempDir directory: Path) {
		val expected = "struct equality\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			assertEquals(expected, runStructProgram(abi, structEquality, directory.resolve("struct_equality_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `enum ordinal equality and formatting agree across nullable and array values`(@TempDir directory: Path) {
		val expected = "FALL\né😀\n!é😀\u0000\n!two/null\n!!![[three, null], null, [two, three]]\n[]\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			assertEquals(expected, runStructProgram(abi, enumFormatting, directory.resolve("enum_operators_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `value operators emit valid objects for all profiles`(@TempDir directory: Path) {
		for ((targetIndex, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			for ((sourceIndex, source) in listOf(structEquality, enumFormatting).withIndex()) {
				val unit = structUnit(source)
				assertTrue(emitter.emitExecutable(unit).contains("value_comparison"), triple)
				assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("value_${targetIndex}_$sourceIndex.o"))) > 0)
			}
		}
	}

	@Test
	fun `rejects invalid value operators and enum payload access`() {
		val cases = listOf(
			"typedef<struct> S { operator ==(int other) = true }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<enum> E { A; operator !=(E? other) = false }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<enum> E { A; operator ::str() -> int = 1 }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
			"typedef<enum> E { A; operator ==(E other) = true; operator ==(E other, self) = true }\nfn<bool> test() = E.A == E.A" to DiagnosticCode.OPERATOR_MATCH,
			"typedef<enum> E { A }\nfn<E> test() = E.MISSING" to DiagnosticCode.INVALID_ENUM_MEMBER,
			"typedef<enum> E (pub def<int> code) { A }" to DiagnosticCode.INVALID_ENUM_MEMBER,
			"typedef<enum> E (pub def<int> code = 0) { A; operator ::str() { code = 1; return \"bad\" } }" to DiagnosticCode.INVALID_ENUM_MEMBER,
			"typedef<enum> E (def<int> code = 0) { A }\nfn<int> test(E e) = e.code" to DiagnosticCode.INVALID_ENUM_MEMBER,
			"typedef<enum> E (pub def<int> code = 0) { A }\nfn test(E e) { e.code = 1 }" to DiagnosticCode.INVALID_ENUM_MEMBER,
			"typedef<enum> E (pub def<int[1]> code = [0]) { A }\nfn test() { E.A.code[0] = 1 }" to DiagnosticCode.INVALID_ENUM_MEMBER,
			"typedef<enum> E (pub def<int> code) { A(make()) }\nfn<int> make() = 1" to DiagnosticCode.INVALID_ENUM_MEMBER,
		)
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) { FunctionAnalyzer().analyze(structUnit(source)) }
			assertEquals(diagnostic, failure.diagnosticCode, source)
		}
		for (source in listOf(
			"typedef<enum> E { A }\ntypedef<enum> F { A }\nfn<bool> test() = E.A == F.A",
			"typedef<enum> E { A }\nfn<bool> test() = E.A == 0",
			"typedef<struct> S {}\nfn<bool> test(S a, S b) = a == b",
		)) {
			assertFailsWith<SemanticException>(source) { FunctionAnalyzer().analyze(structUnit(source)) }
		}
	}
}
