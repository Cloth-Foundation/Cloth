package cloth

import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructMethodEmissionTest {

	private val calls = """
		typedef<struct> Meter {
		    int amount,
		    int step,
		    int<129> total,
		    float<16> weight;

		    mt<int> amount() = amount
		    mt<int> add(opt<int> delta ?: step, int extra) {
		        amount += delta + extra
		        return amount
		    }
		    mt<int> repeatAdd(int remaining) {
		        if (remaining == 0) return amount
		        amount++
		        return repeatAdd(remaining - 1)
		    }
		    mt<Meter, int<129>, float<16>> capture() = self, total, weight
		    mt<Meter, int<129>, float<16>> forward() = self.capture()
		}
		typedef<struct> Row { Meter item }
		fn<Meter> make() = { 7, 2, 18446744073709551617, 1.5 }
		fn<int> main() {
		    def<Meter> live = { 10, 2, 18446744073709551617, 1.5 }
		    def<int> reset = { live = { 100, 3, 18446744073709551617, 1.5 }; return 5 }
		    if (live.add(extra=reset) != 108 || live.amount() != 108) return 1
		    def fixed := live
		    if (fixed.repeatAdd(2) != 110 || live.amount != 108) return 2
		    def<Meter, int<129>, float<16>> saved, total, weight = fixed.forward()
		    if (fixed.add(extra=0, delta=1) != 111 || saved.amount != 110) return 3
		    if (total != 18446744073709551617 || weight != (1.5 -> float<16>)) return 4

		    def<Row[2]> rows := [{ live }, { fixed }]
		    def selected = 0
		    def trace = ""
		    def<int> index = { trace += "i"; return selected }
		    def<int> argument = { trace += "a"; selected = 1; return 2 }
		    if (rows[index].item.add(extra=argument) != 113 || trace != "ia") return 5
		    if (rows[0].item.amount != 113 || rows[1].item.amount != 111) return 6

		    def<Meter?> maybe = make()
		    if (maybe == null) return 7
		    if (maybe.add(extra=3) != 12 || maybe.amount != 12) return 8
		    def<Meter> later = { return live }
		    if (later.add(extra=2) != 113 || later.amount != 108) return 9
		    if (make().add(extra=0) != 9 || (if (true) { live } else { fixed }).add(extra=1) != 112) return 10
		    if (live.amount != 108 || fixed.amount != 111) return 11
		    return 0
		}
	"""

	@Test
	fun `stored and temporary receivers share ordinary method call contracts`(@TempDir directory: Path) {
		for (abi in structRunners()) assertEquals(
			processCompletion().replace("\r\n", "\n"),
			runStructProgram(abi, calls, directory.resolve("methods_${abi.layout.sizeBits}")),
		)
	}

	@Test
	fun `method private ABI verifies and emits objects across supported profiles`(@TempDir directory: Path) {
		val unit = structUnit(calls)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emitExecutable(unit)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "define internal i32 @cloth.method.")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("methods_$index.o"))) > 0)
		}
	}

	@Test
	fun `receiver bounds fail before a supplied argument runs`(@TempDir directory: Path) {
		val source = """
			typedef<struct> Row { int value; mt add(int n) { value += n } }
			fn<int> argument() { println("argument ran"); return 1 }
			fn<int> main() {
			    def<Row[1]> rows = [{ 0 }]
			    rows[1].add(argument())
			    return 0
			}
		"""
		val output = runStructProgram(NativeCABI.native(), source, directory.resolve("method_bounds"), 1)
		assertContains(output, "Array index out of bounds")
		assertFalse("argument ran" in output)
		assertFalse("PEC:" in output)
	}
}
