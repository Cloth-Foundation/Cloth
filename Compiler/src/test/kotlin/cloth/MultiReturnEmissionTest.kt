package cloth

import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MultiReturnEmissionTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("multi-return-native.co", source.trimIndent())).scanTokens()).parse()
	private fun targets(): List<NativeCABI> {
		val native = NativeCABI.native()
		return if (native.windows) listOf(native, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple } else listOf(native)
	}

	private fun run(directory: Path, name: String, source: String, output: String = "", abi: NativeCABI = NativeCABI.native()): String {
		val unit = parse(source)
		val emitter = LLVMEmitter(abi)
		val ir = emitter.emitExecutable(unit)
		val executable = emitter.compile(unit, directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), name)
			val actual = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(0, process.exitValue(), actual)
			assertEquals(output + processCompletion(), actual)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
		return ir
	}

	@Test
	fun `named function results support inferred explicit discarded and reassigned reception`(@TempDir directory: Path) {
		run(directory, "names", """
			fn<string, int> pair(string name, int age) = name, age
			fn<string, int> braces(string name, int age) { return name, age }
			fn<int> main() {
				def<string, int> name, age = pair("Mike", 25)
				if (name != "Mike" || age != 25) { return 1 }
				def name1, age1 := braces("Sarah", 26)
				if (name1 != "Sarah" || age1 != 26) { return 2 }
				def<string, int> name2, _ = pair("John", 31)
				def _, age2 = pair("Bill", 14)
				if (name2 != "John" || age2 != 14) { return 3 }
				name, age = braces("Cloth", 7)
				if (name != "Cloth" || age != 7) { return 4 }
				def x, _ = if (true) { true, false } else { false, true }
				if (!x) { return 5 }
				_, _ = pair("ignored", 1)
				return 0
			}
		""")
	}

	@Test
	fun `result lists snapshot buffers before later captured mutations and assignment writes`(@TempDir directory: Path) {
		run(directory, "snapshots", """
			fn<int> main() {
				def a, b = 1, 2
				a, b = b, a
				if (a != 2 || b != 1) { return 1 }
				a, a = 3, 4
				if (a != 4) { return 2 }
				def source = [1, 2]
				def<int> mutate = { source[0] = 99
					return 7 }
				def saved, changed = source, mutate
				if (saved[0] != 1 || source[0] != 99 || changed != 7) { return 3 }
				def left, right = [10, 20], [30, 40]
				left, right = right, left
				if (left[0] != 30 || right[0] != 10) { return 4 }
				left[0] = 55
				if (right[0] != 10) { return 5 }
				def texts = ["old"]
				def<int> replace = { texts[0] = "new"
					return 0 }
				def old, _ = texts[0], replace
				if (old != "old" || texts[0] != "new") { return 6 }
				return 0
			}
		""")
	}

	@Test
	fun `conditional merges and whole result forwarding preserve nested buffers across recursion`(@TempDir directory: Path) {
		val source = """
			fn<string[][], string[][], string> recursive(int depth, string[][] source) {
				if (depth == 0) { return source, source, #"depth {depth}" }
				def first, second, text = recursive(depth - 1, source)
				first[0][0] = first[0][0] + "!"
				return first, second, #"depth {depth}"
			}
			fn<string[][], string[][], string> forward(int n, string[][] source) = recursive(n, source)
			fn<string, int[]> choose(bool flag) = if (flag) { "yes", [1, 2] } else { "no", [3, 4] }
			fn<int> main() {
				def source = [[#"input {5}"], ["tail"]]
				def first, second, text = forward(3, source)
				if (first[0][0] != "input 5!!!" || second[0][0] != "input 5" || source[0][0] != "input 5") { return 1 }
				if (text != "depth 3") { return 2 }
				first[0][0] = "changed"
				if (second[0][0] != "input 5") { return 3 }
				def yes, one = choose(true)
				def no, two = choose(false)
				if (yes != "yes" || no != "no" || one[0] != 1 || two[0] != 3) { return 4 }
				return 0
			}
		"""
		for ((index, target) in targets().withIndex()) run(directory, "recursion-$index", source, abi = target)
	}

	@Test
	fun `mixed result ABI preserves half booleans target integers and metadata on native widths`(@TempDir directory: Path) {
		val source = """
			fn<float<16>, bool, usize, isize, typeinfo> values(float<16> half, bool flag) = half, flag, usize::max, isize::min, int::type
			fn<int<8>, unsigned int<16>, float, float<64>, char> details() = -128, 65535, 1.25, 2.5 -> float<64>, '😀'
			fn<int> main() {
				def half, flag, count, offset, metadata = values(1.5 -> float<16>, true)
				if (half != (1.5 -> float<16>) || !flag || count != usize::max || offset != isize::min || metadata != int::type) { return 1 }
				half += 0.5 -> float<16>
				if (half != (2.0 -> float<16>)) { return 2 }
				def small, maximum, single, doubleValue, character = details()
				if (small != -128 || maximum != 65535 || single != 1.25 || doubleValue != (2.5 -> float<64>) || character != '😀') { return 3 }
				return 0
			}
		"""
		for ((index, target) in targets().withIndex()) {
			val ir = run(directory, "abi-$index", source, abi = target)
			assertContains(ir, "{ i16, i1, i${target.layout.sizeBits}, i${target.layout.sizeBits},")
		}
	}

	@Test
	fun `early returning branches and repeated reads evaluate only the executable results`(@TempDir directory: Path) {
		run(directory, "early", """
			fn<int, bool> choose(bool flag) = if (flag) { return 1, true } else { 2, false }
			fn<int, bool> allReturn(bool flag) = if (flag) { return 3, false } else { return 4, true }
			fn<bool> later() { print("later")
			    return false }
			fn<int, bool> listReturn() = if (true) { return 5, true } else { return 6, false }, later()
			fn<string[], bool> bufferedReturn() = ["unused"], if (true) { return ["kept"], true } else { false }
			fn<int> main() {
				def count = 0
				def<int> next = { count += 1
					return count }
				def first, second = next, next
				if (first != 1 || second != 2 || count != 2) { return 1 }
				def a, b = choose(true)
				def c, d = choose(false)
				def e, f = allReturn(false)
				if (a != 1 || !b || c != 2 || d || e != 4 || !f) { return 2 }
				def g, h = listReturn()
				def kept, valid = bufferedReturn()
				if (g != 5 || !h || kept[0] != "kept" || !valid) { return 3 }
				return 0
			}
		""")
	}

	@Test
	fun `multiple result functions support native mutual recursion`(@TempDir directory: Path) {
		run(directory, "mutual", """
			fn<int, bool> even(int n) = if (n == 0) { 0, true } else { odd(n - 1) }
			fn<int, bool> odd(int n) = if (n == 0) { 0, false } else { even(n - 1) }
			fn<int> main() {
			    def a, first = even(10)
			    def b, second = odd(10)
			    if (a != 0 || b != 0 || !first || second) { return 1 }
			    return 0
			}
		""")
	}

	private fun runtimeFailure(directory: Path, name: String, source: String, message: String): String {
		val executable = LLVMEmitter().compile(parse(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS))
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertNotEquals(0, process.exitValue(), output)
			assertContains(output.lowercase(), message.lowercase())
			assertFalse(output.contains("PEC:"), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	@Test
	fun `runtime failure in an ignored result evaluates earlier effects once and skips later slots`(@TempDir directory: Path) {
		val output = runtimeFailure(directory, "division", """
			fn<int> first() { print("first\n")
			    return 1 }
			fn<int> later() { print("later\n")
			    return 3 }
			fn<int> zero() = 0
			fn<int, int, int> values(int divisor) = first(), 10 / divisor, later()
			fn main() { def _, _, _ = values(zero()) }
		""", "division by zero")
		assertEquals(1, output.lines().count { it == "first" }, output)
		assertFalse(output.lines().contains("later"), output)
	}

	@Test
	fun `positional assignment shape guards run after all producer effects and before completion`(@TempDir directory: Path) {
		val output = runtimeFailure(directory, "shapes", """
			fn<int> marker() { print("all rhs\n")
			    return 1 }
			fn<int[], int[][], int> values(bool bad) = if (bad) { [1], [[2, 3]], marker() } else { [1], [[2]], 0 }
			fn main() {
			    def a, b, n = values(false)
			    a, b, n = values(true)
			    print("after\n")
			}
		""", "Array shape mismatch")
		assertEquals(1, output.lines().count { it == "all rhs" }, output)
		assertFalse(output.lines().contains("after"), output)
	}
}
