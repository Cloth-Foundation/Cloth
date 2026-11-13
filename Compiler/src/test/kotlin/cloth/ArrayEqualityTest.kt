package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.settings.CompilerSettings
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ArrayEqualityTest {

	private fun compile(output: Path, source: String, vararg options: String): Path {
		val input = Files.writeString(output.resolveSibling("${output.fileName}.co"), source.trimIndent())
		return compileArtifact(CompilerSettings(arrayOf(input.toString(), output.toString(), *options)))
	}

	private fun output(directory: Path, name: String, source: String, expected: String) {
		assertEquals(expected.replace("\n", System.lineSeparator()) + processCompletion(), runExecutable(compile(directory.resolve(name), source)))
	}

	@Test
	fun `fixed and growable arrays compare by current shape and contents`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("current_shape"), """
			def fixed = [1, 2]
			def<int[]> growable = [1, 2]
			println(fixed == growable)
			growable.reserve(64)
			println(growable != fixed)
			growable.append(3)
			println(fixed != growable)
			println([[1, 2]] == [[1, 2, 3]])
			println([[1], [2]] == [[1], [2]])
			println([[1], [2]] != [[1], [3]])
		""", "true\nfalse\ntrue\nfalse\ntrue\ntrue\n")
	}

	@Test
	fun `shape checks include retained and unknown inner extents`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("witnesses"), """
			def<int[][]> two = [[1, 2]]
			def<int[][]> three = [[1, 2, 3]]
			def<int[][]> unknown = []
			two.clear()
			three.clear()
			println(two == three)
			println(two == unknown)
			println(unknown == [])
			def<int[]?[]> one = [null, [1]]
			def<int[]?[]> other = [null, [1, 2]]
			one[1] = null
			other[1] = null
			println(one == other)
			println(one == [null, null])
		""", "false\nfalse\ntrue\nfalse\nfalse\n")
	}

	@Test
	fun `literal context supplies exact elements and declared empty row widths`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("literal_context"), """
			def<int<8>[1]> small = [1]
			println(small == [1])
			println([1] == small)
			println(small == [])
			println([] == [1])
			def<float<16>[]> halves = [1.0001]
			println(halves == [1.0001])
			def<int[0][2]> empty = []
			println(empty == [])
			def<int[1][2]> rows = [[1, 2]]
			println(rows == [[1, 2, 3]])
			println(rows == [[1, 2]])
		""", "true\ntrue\nfalse\nfalse\ntrue\ntrue\nfalse\ntrue\n")
	}

	@Test
	fun `nullable arrays compare presence before checking payload shapes`(@TempDir directory: Path) {
		output(directory, "nullable", """
			fn<int[]?> absent() { print("L"); return null }
			fn<int[1]?> present() { print("R"); return [1] }
			fn<int[2]?> bothAbsent() = null
			fn main() {
			    println(absent() == present())
			    println(absent() == bothAbsent())
			    println([1] == present())
			    println(present() != [1, 2])
			    println(present() != null)
			    def<int?[]> a = [1, null]
			    println(a == [1, null])
			    println(a != [null, 1])
			    def<int[]?[]> rows = [null, [1, 2]]
			    println(rows == [null, [1, 2]])
			    println(rows == [[1, 2], null])
			}
		""", "LRfalse\nLtrue\nRtrue\nRtrue\nRtrue\ntrue\ntrue\ntrue\nfalse\n")
	}

	@Test
	fun `comparison uses the ordinary scalar rules`(@TempDir directory: Path) {
		output(directory, "scalars", """
			typedef<enum> Colour { RED, GREEN }
			typedef<enum> Level (def<int> value) {
			    LOW(1), ALSO_LOW(1);
			    operator ==(Level other) = self.value == other.value
			}
			fn main() {
			    def<int<128>?[]> wide = [9223372036854775808, null]
			    println(wide == [9223372036854775808, null])
			    println(wide != [9223372036854775809, null])
			    def<float<16>[]> halves = [0.0]
			    println(halves == [-0.0])
			    def<float[]> nan = [0.0 / 0.0]
			    println(nan == nan)
			    println(["é\0🙂"] == ["é\0🙂"])
			    println(["é\0🙂"] == ["é\0x"])
			    println([true, false] == [true, false])
			    println(['a'] == ['b'])
			    println([int::type] == [int<32>::type])
			    println([Colour.RED] != [Colour.GREEN])
			    println([Level.LOW] == [Level.ALSO_LOW])
			}
		""", "true\ntrue\ntrue\nfalse\ntrue\nfalse\ntrue\nfalse\ntrue\ntrue\ntrue\n")
	}

	@Test
	fun `custom equality runs in index order and inequality negates it`(@TempDir directory: Path) {
		output(directory, "operators", """
			typedef<struct> Point {
			    int value;
			    operator ==(Point other, self) { print(self.value); return self.value == other.value }
			    operator !=(Point other) { print("wrong"); return true }
			}
			fn main() {
			    def<Point[]> left = [{ 1 }, { 2 }, { 3 }]
			    def<Point[]> right = [{ 1 }, { 9 }, { 3 }]
			    println(left == right)
			    println(left != [{ 1 }, { 2 }, { 3 }])
			    println(left == [{ 1 }])
			}
		""", "19false\n123false\nfalse\n")
	}

	@Test
	fun `an array still serves as a peer for an ordinary equality context`(@TempDir directory: Path) {
		output(directory, "equality_context", """
			typedef<struct> Point {
			    int value;
			    operator<int[]> ==() -> int[] { return [self.value] }
			}
			fn main() {
			    def<Point> point = { 1 }
			    println(point == [1])
			    println([2] != point)
			}
		""", "true\ntrue\n")
	}

	@Test
	fun `array operands capture presence shape and elements before later effects`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("snapshots"), """
			def<int[]> left = [1]
			def<int[]> right = { print("R"); left[0] = 2; left.append(3); return [2] }
			println(left == right)
			println(left)
			def<int[]?[]> rows = [null]
			def<int[]?[]> later = { rows[0] = [1, 2]; rows[0] = null; return rows }
			println(rows == later)
			println(rows == [null])
			def<int[]?> maybe = [1]
			def<int[]?> drop = { maybe = null; return [1] }
			println(maybe == drop)
			println(maybe == null)
		""", "Rfalse\n[2, 3]\nfalse\nfalse\ntrue\ntrue\n")
	}

	@Test
	fun `conditional operands keep source order and early exits skip comparison`(@TempDir directory: Path) {
		output(directory, "completion", """
			fn<int[]> left() { print("L"); return [1] }
			fn<int[]> right() { print("R"); return [1] }
			fn<bool> leave(bool early) {
			    return left() == (if (early) { return false } else { right() })
			}
			fn main() {
			    println(leave(true))
			    println(leave(false))
			    def<int[]> values = [1]
			    def turns = 0
			    while (turns < 3) {
			        turns++
			        if (values == (if (turns < 3) { continue } else { values })) break
			    }
			    println(turns)
			}
		""", "Lfalse\nLRtrue\n3\n")
	}

	@Test
	fun `class and interface leaves use ordinary checked dispatch`(@TempDir directory: Path) {
		val source = """
			typedef<interface> INamed { mt<int> value() }
			typedef<class> Item (def<int> id) : INamed {
			    override: pub mt<int> value() = self.id
			    operator ==(Object other) {
			        print("eq")
			        if (other is Item) return self.id == other.value()
			        return false
			    }
			    operator !=(Object other) { print("wrong"); return true }
			}
			fn main() {
			    def left = Item::new(7)
			    def right = Item::new(7)
			    def<INamed> a = left
			    def<INamed> b = right
			    println([left] == [right])
			    println([a] != [b])
			    left::delete
			    println([left] == [right, right])
			    println([left] == [right])
			}
		"""
		val actual = runExecutable(compile(directory.resolve("class_elements"), source), status = 1)
		assertTrue(actual.replace("\r\n", "\n").startsWith("eqtrue\neqfalse\nfalse\n"), actual)
		assertContains(actual, "Class object is no longer live")
		assertFalse(actual.contains("wrong"))
	}

	@Test
	fun `invalid element contracts report the first located error`(@TempDir directory: Path) {
		val rejected = listOf(
			"fn<bool> same(int[] a, float[] b) = a == b" to DiagnosticCode.INCOMPARABLE_TYPES,
			"fn<bool> same(int?[] a, int[] b) = a != b" to DiagnosticCode.INCOMPARABLE_TYPES,
			"fn<bool> same(int[][] a, int[] b) = a == b" to DiagnosticCode.INCOMPARABLE_TYPES,
			"typedef<struct> Point { int x }\nfn<bool> same(Point[0] a) = a == []" to DiagnosticCode.INCOMPARABLE_TYPES,
			"typedef<struct> Point { int x; operator !=(Point other) = false }\nfn<bool> same(Point[0] a) = a != []" to DiagnosticCode.INCOMPARABLE_TYPES,
			"typedef<struct> Point { operator ==(Point other) = true; operator ==(Point other, self) = true }\nfn<bool> same(Point[] a) = a != a" to DiagnosticCode.OPERATOR_MATCH,
			"fn<bool> same(int[] a, int[] b) = a === b" to DiagnosticCode.INCOMPARABLE_TYPES,
			"fn<bool> same(int[] a) = a == null" to DiagnosticCode.INVALID_NULL_COMPARISON,
			"fn<bool> same() = [] == []" to DiagnosticCode.INVALID_ARRAY,
			"fn<bool> same(int<8>[] a) = a == [128]" to DiagnosticCode.INTEGER_RANGE,
			"""
				typedef<struct> Point { operator ==(Point other) = true }
				fn compare(Point[] a, opt<bool> same ?: a == [{}]) {}
			""".trimIndent() to DiagnosticCode.INVALID_PARAMETER_DEFAULT,
		)
		for ((index, case) in rejected.withIndex()) {
			val (source, code) = case
			val diagnostic = assertFailsWith<ClothDiagnostic> {
				compile(directory.resolve("invalid_$index"), "$source\nfn main() {}")
			}
			assertEquals(code, diagnostic.diagnosticCode, source)
			assertLocated(diagnostic)
		}

		val input = Files.writeString(directory.resolve("first.co"), """
			fn main() {
			    def ints = [1]
			    def floats = [1.0]
			    println(ints == floats)
			    println([1][true])
			}
		""".trimIndent())
		val report = StringBuilder()
		val output = directory.resolve("must-not-exist/program")
		assertEquals(1, runCompiler(arrayOf(input.toString(), output.toString()), DiagnosticReporter(report)))
		assertContains(report, DiagnosticCode.INCOMPARABLE_TYPES.id)
		assertFalse(report.contains(DiagnosticCode.INVALID_INDEX.id))
		assertFalse(Files.exists(output.parent))
	}

	@Test
	fun `element equality invalidates class field presence proofs`(@TempDir directory: Path) {
		val source = """
			typedef<class> Box { pub def<string?> name = "live" }
			typedef<struct> Probe {
			    Box box;
			    operator ==(Probe other) { self.box.name = null; return true }
			}
			fn<usize> inspect(Box box, Probe[] probes) {
			    if (box.name != null && probes == probes) return box.name::size
			    return 0
			}
			fn main() {}
		"""
		val diagnostic = assertFailsWith<ClothDiagnostic> { compile(directory.resolve("proof"), source) }
		assertEquals(DiagnosticCode.NULLABLE_PAYLOAD_ACCESS, diagnostic.diagnosticCode)
		assertLocated(diagnostic)
	}

	@Test
	fun `generic nested equality executes and emits startup objects across profiles`(@TempDir directory: Path) {
		val source = """
			fn<bool> same<T: Number>(T[]?[] left, T[]?[] right) = left == right
			fn main() {
			    def<int<128>[]?[]> a = [null, [9223372036854775808]]
			    def<int<128>[]?[]> b = [null, [9223372036854775809]]
			    println(same(a, a))
			    println(same(a, b))
			}
		"""
		for (abi in structRunners()) {
			val executable = compile(directory.resolve("generic_${abi.layout.sizeBits}"), source, "--target", abi.targetTriple)
			assertEquals("true\nfalse\n".replace("\n", System.lineSeparator()) + processCompletion(), runExecutable(executable))
		}
		val unit = structUnit(source)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emitExecutable(unit)
			assertContains(ir, "array_equality_check")
			val objectFile = directory.resolve("array_equality_$index.o")
			emitExecutableObject(emitter, ir, objectFile)
			assertTrue(Files.size(objectFile) > 0)
		}
	}
}
