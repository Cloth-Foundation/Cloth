package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.SemanticException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoredRangeEmissionTest {

	private val transport = """
		typedef<struct> Window { range<int<257>> bounds }
		typedef<class> Schedule (pub def<Window> window)
		fn<range<T>> span<T: Number>(T start, T end) = start..=end
		fn<bool> contains<T: Number>(range<T> bounds, T value) = value == bounds
		fn<range<int<257>>, range<int<257>>?> pair(range<int<257>> bounds, range<int<257>>? other) = bounds, other
		fn<Window> wrap(range<int<257>> bounds) = { bounds }
		fn<range<int<257>>[]> windows(range<int<257>> bounds) = [bounds, 0..=1]
		fn<int> main() {
		    def<int<257>> start = int<257>::min
		    def original = span(start, 5)
		    def saved = original
		    original = 10..20
		    def schedule = Schedule::new(wrap(saved))
		    def copy = schedule.window
		    schedule.window.bounds = original
		    def<range<int<257>>[]> values = windows(saved)
		    def duplicate = values
		    values[0] = original
		    values.append(saved)
		    def removed = values.removeAt(0)
		    def<range<int<257>>, range<int<257>>?> received, maybe = pair(values[1], schedule.window.bounds)
		    schedule::delete
		    values.clear()
		    if (saved != copy.bounds || saved != duplicate[0]) return 1
		    if (removed != original || maybe != original || received != saved) return 2
		    if (!contains(received, start) || received != 5 || 6 == received) return 3
		    if (duplicate != [saved, 0..=1] || duplicate == [saved, 0..1]) return 4
		    def<range<int<257>>?> missing = null
		    if (missing == saved || missing != null) return 5
		    def present = missing ?: saved
		    if (present != saved) return 6
		    def wide = span(int<65536>::min, int<65536>::max)
		    if (wide != int<65536>::min..=int<65536>::max || wide != 0) return 7
		    def unsignedWide = span(unsigned int<257>::max, unsigned int<257>::max)
		    if (unsignedWide != unsigned int<257>::max || 0 == unsignedWide) return 8
		    return 0
		}
	"""

	private val completion get() = processCompletion().replace("\r\n", "\n")

	@Test
	fun `stored range snapshots survive fields array moves calls and multiple returns`(@TempDir root: Path) {
		for (abi in structRunners()) {
			assertEquals(completion, runStructProgram(abi, transport, root.resolve("copies_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `comparisons retain endpoint type inclusion and ordinary numeric rules`(@TempDir root: Path) {
		val source = """
			fn<bool> inline<T: Number>(T value, T start, T end) = value == start..=end
			fn<range<float<16>>> half(range<float<16>> bounds) = bounds
			fn<range<int<8>>?> choose(bool include) = if (include) 0..=10 else null
			fn<bool> compareConditional(range<int<8>> span, bool include) = span == (if (include) 0..=10 else 0..10)
			fn<bool> compareLiterals(bool include) = 0..=10 == (if (include) 0..=10 else 0..10)
			fn<int> main() {
			    def<range<int<8>>> narrow = 0..=10
			    if (narrow != 0..=10 || 0..=10 != narrow || narrow == 0..11) return 1
			    if (narrow != 10 || 10 != narrow || narrow == 11 || -1 == narrow) return 2
			    if (1..=1 != 1..=1 || 0..3 == 0..=2 || 1..1 == 1..=1) return 3
			    def exclusive = 1..1
			    def reversed = 10..=0
			    if (1 == exclusive || 5 == reversed || reversed == 0) return 4
			    def<range<usize>> sized = 0..=usize::max
			    if (sized != 0..=usize::max || usize::max != sized) return 5
			    def compact = half(-0.0..=1.0)
			    if (0.0..=1.0 != compact || 0.5 != compact) return 6
			    def<range<float<64>>> precise = 16777216.5..=16777217.5
			    if (precise != 16777217.0 || 16777218.0 == precise) return 7
			    if ((0.0 / 0.0) == precise || precise == (1.0 / 0.0)) return 8
			    if (choose(false) == 0..=10 || 0..=10 != choose(true)) return 9
			    if (!inline(5, 0, 5) || inline(6, 0, 5)) return 10
			    if (!compareConditional(narrow, true) || compareConditional(narrow, false)) return 11
			    if (!compareLiterals(true) || compareLiterals(false)) return 12
			    return 0
			}
		"""
		for (abi in structRunners()) {
			assertEquals(completion, runStructProgram(abi, source, root.resolve("comparisons_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `range construction and both comparison forms capture once in written order`(@TempDir root: Path) {
		val source = """
			fn<int> finish() { print("E"); return 5 }
			fn<range<int<257>>> report(range<int<257>> span) { print("R"); return span }
			fn<int> main() {
			    def<int<257>> start = 1
			    def<int<257>> first = { print("S"); return start }
			    def<int<257>> last = { start = 20; return finish() -> int<257> }
			    def span = first..=last
			    def saved = span
			    def<range<int<257>>> changeRange = { print("C"); span = 10..20; return span }
			    if (span == changeRange) return 1
			    def<int<257>> changeValue = { print("V"); span = 30..40; return 15 }
			    if (report(span) != changeValue) return 2
			    def<int<257>> reset = { print("N"); span = saved; return 2 }
			    if (reset != report(span)) return 3
			    return 0
			}
		"""
		assertEquals("SECRVNR" + completion, runStructProgram(NativeCABI.native(), source, root.resolve("order")))
	}

	@Test
	fun `dynamic nonfinite endpoints fail during creation even when discarded`(@TempDir root: Path) {
		val cases = listOf(
			"def unused = nan()..end()" to "S",
			"(start()..infinity()) -> void" to "AE",
		)
		for ((index, case) in cases.withIndex()) {
			val (expression, prefix) = case
			val source = """
				fn<float<16>> nan() { print("S"); return 0.0 / 0.0 }
				fn<float<16>> start() { print("A"); return 0.0 }
				fn<float<16>> end() { print("X"); return 2.0 }
				fn<float<16>> infinity() { print("E"); return 1.0 / 0.0 }
				fn main() { $expression; print("unreachable") }
			"""
			val output = runStructProgram(NativeCABI.native(), source, root.resolve("invalid_$index"), status = 1)
			assertTrue(output.startsWith(prefix + "Float range values must be finite"), output)
			assertFalse(output.contains("unreachable"))
			assertFalse(output.contains("PEC:"))
		}
	}

	@Test
	fun `stored comparisons require exact typed operands and check contextual literals`(@TempDir root: Path) {
		val cases = listOf(
			"def<range<int<16>>> other = 0..10; def ok = narrow == other" to DiagnosticCode.INCOMPARABLE_TYPES,
			"def<int<16>> end = 10; def ok = narrow == 0..end" to DiagnosticCode.INVALID_RANGE_VALUE,
			"def ok = 0..128 == narrow" to DiagnosticCode.INTEGER_RANGE,
			"def<int<16>> value = 1; def ok = narrow == value" to DiagnosticCode.INVALID_RANGE_MEMBERSHIP,
		)
		for ((index, case) in cases.withIndex()) {
			val (body, expected) = case
			val unit = parseMain("def<range<int<8>>> narrow = 0..10; $body")
			val destination = root.resolve("invalid_$index")
			val error = assertFailsWith<SemanticException> { LLVMEmitter().compile(unit, destination.resolve("program")) }
			assertEquals(expected, error.diagnosticCode, body)
			assertLocated(error)
			assertFalse(Files.exists(destination))
		}
	}

	@Test
	fun `stored range transport verifies IR and emits objects across all profiles`(@TempDir root: Path) {
		val unit = structUnit(transport)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(unit), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(unit, root.resolve("range_$index.o"))) > 0)
		}
	}
}
