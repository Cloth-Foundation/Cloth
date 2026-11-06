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

class OperandContextTest {

	private val arithmetic = """
		typedef<class> Points (pub def<int> value) {
		    operator<int<auto>> +() = self.value -> int<auto>
		    operator<int<8>> +() -> int<8> { return 7 }
		    operator<int<auto>> -() -> int<auto> {
		        def<int<auto>> amount = self.value -> int<auto>
		        return amount
		    }
		    operator<isize> +() = self.value -> isize
		    operator<usize> +() = self.value -> usize
		    operator<string> +() = #"p{self.value}"
		    operator +(bool bonus) = if (bonus) { self.value } else { 0 }
		    operator<int> <() = self.value
		}
		typedef<class> Full {
		    operator +(int other) = other + 10
		    operator +(int other, self) = other + 20
		    operator<int> +() -> int {
		        print("unexpected")
		        return 0
		    }
		}
		typedef<struct> First {
		    int value;
		    operator<Second> +() = self.value
		}
		typedef<struct> Second {
		    int value;
		    operator<First> +() = self.value
		}
		typedef<struct> Sum {
		    int value;
		    operator +(int amount) -> Sum { return { self.value + amount } }
		}
		typedef<struct> Wrapped {
		    Sum value;
		    operator<int> +() = self.value
		}
		fn<Points> marked(Points points) {
		    print("L")
		    return points
		}
		fn<int> change(Points points) {
		    print("R")
		    points.value = 10
		    return 3
		}
		fn<int> main() {
		    def points = Points::new(130)
		    def<int<7>> small = 1
		    def<int<8>> exact = 1
		    def<int<64>> wide = 4
		    def<int<129>> limbs = 5
		    def<isize> signed = 1
		    def<usize> size = 2
		    if (points + small != 3 || exact + points != 8 || points + wide != 134) return 1
		    if (points + limbs != 135 || points + signed != 131 || size + points != 132) return 2
		    if (points - wide != 126 || wide - points != -126 || points + 2 * 3 != 136) return 3
		    if (points + "!" != "p130!" || "!" + points != "!p130" || !(points < 131)) return 4
		    if (marked(points) + change(points) != 133) return 5
		    def full = Full::new
		    if (full + 1 != 11 || 1 + full != 21) return 6
		    def<First> first = { 2 }
		    def<Second> second = { 3 }
		    if (first + second != 5) return 7
		    def<Wrapped> wrapped = { { 4 } }
		    def sum = wrapped + 3
		    if (sum.value != 7) return 8
		    full::delete
		    points::delete
		    println(" contexts")
		    return 0
		}
	"""

	private val instanceChecks = """
		typedef<class> Match (pub def<int> group) {
		    operator is(Object other) {
		        if (other is Match) return self.group == other.group
		        return false
		    }
		}
		typedef<class> Right (pub def<int> group) {
		    operator is(Match other, self) = self.group == other.group
		}
		fn<Match> capture(Match value, string tag) {
		    print(tag)
		    return value
		}
		fn<int> main() {
		    def a = Match::new(4)
		    def b = Match::new(4)
		    def c = Match::new(5)
		    def right = Right::new(4)
		    def<Object> base = b
		    if (!(a is b) || a is c || !(a is right)) return 1
		    if (!(capture(a, "L") is (capture(b, "R")))) return 2
		    if (!(base is Match) || !(a is base)) return 3
		    if (base is Match) println(#" group {base.group}")
		    right::delete
		    c::delete
		    b::delete
		    a::delete
		    return 0
		}
	"""

	private val floatingContexts = """
		typedef<class> Player (def<float> health) {
		    operator<int<auto>> +() = health -> int<auto>
		    operator<float<auto>> +() = health -> float
		    operator<float<16>> +() = 7f -> float<16>
		    operator<float<auto>> -() -> float<auto> {
		        def<float<auto>> current = health -> float<auto>
		        return current
		    }
		}
		fn<int> main() {
		    def player = Player::new(100f)
		    def<float<16>> half = 10f -> float<16>
		    def<float<64>> wide = 10f -> float<64>
		    def<float> raised = player + 10f
		    def<float<16>> exact = player + half
		    def<float<64>> promoted = wide + player
		    def<float<16>> narrow = player - half
		    def<float<64>> reversed = wide - player
		    if (raised != 110f || exact != (17f -> float<16>) || promoted != (110f -> float<64>)) return 1
		    if (narrow != (90f -> float<16>) || reversed != (-90f -> float<64>) || player + 10 != 110) return 2
		    player::delete
		    return 0
		}
	"""

	@Test
	fun `contexts select once from original operands and specialize their widths`(@TempDir directory: Path) {
		val expected = "LR contexts\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			assertEquals(expected, runStructProgram(abi, arithmetic, directory.resolve("context_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `value checks dispatch with ordinary operand order and type checks still narrow`(@TempDir directory: Path) {
		val expected = "LR group 4\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			assertEquals(expected, runStructProgram(abi, instanceChecks, directory.resolve("is_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `floating selectors capture peer width and permit a fixed result`(@TempDir directory: Path) {
		val expected = processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			assertEquals(expected, runStructProgram(abi, floatingContexts, directory.resolve("float_context_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `operator contexts and value checks emit across target profiles`(@TempDir directory: Path) {
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			for ((sourceIndex, source) in listOf(arithmetic, instanceChecks, floatingContexts).withIndex()) {
				val unit = structUnit(source)
				assertContains(emitter.emitExecutable(unit), "cloth.method.")
				assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("operator_${index}_$sourceIndex.o"))) > 0)
			}
		}
	}

	@Test
	fun `nullable proofs between operands follow context effects and full overload priority`() {
		val fixture = """
			typedef<class> Item (pub def<int> value)
			typedef<class> Box (pub def<Item?> item)
			typedef<class> Change (def<Box> target) {
			    operator<int> +() -> int {
			        self.target.item = null
			        return 0
			    }
			    FULL
			}
			fn<int> test(Change change, Box box) {
			    if (box.item != null) return change + box.item.value
			    return 0
			}
		"""
		val failure = assertFailsWith<SemanticException> { FunctionAnalyzer().analyze(structUnit(fixture.replace("FULL", ""))) }
		assertEquals(DiagnosticCode.NULLABLE_PAYLOAD_ACCESS, failure.diagnosticCode)
		FunctionAnalyzer().analyze(structUnit(fixture.replace("FULL", "operator +(int value) = value")))
	}

	@Test
	fun `unmatched projections mismatched widths and unproven narrowing fail at compile time`() {
		val cases = listOf(
			"typedef<enum> E { A; operator<int<auto>> +() = 1 }\nfn<int> test(int<8> n) = E.A + n" to DiagnosticCode.MIXED_INTEGERS,
			"typedef<enum> E { A; operator<int<auto>> +() = 1 }\nfn<int> test(unsigned int n) = E.A + n" to DiagnosticCode.BINARY_OPERANDS,
			"typedef<enum> E { A; operator<int<auto>> +() = 1 }\nfn<int> test(isize n) = E.A + n" to DiagnosticCode.BINARY_OPERANDS,
			"typedef<enum> E { A; operator<float<auto>> +() = 1f }\nfn<float> test(int n) = E.A + n" to DiagnosticCode.BINARY_OPERANDS,
			"typedef<enum> E { A; operator<int> +() = 1; operator<int<32>> +() = 2 }" to DiagnosticCode.DUPLICATE_METHOD,
			"typedef<enum> A { X; operator<int> +() = B.X }\ntypedef<enum> B { X; operator<int> +() = 1 }\nfn<int> test() = A.X + 1" to DiagnosticCode.BINARY_OPERANDS,
			"typedef<enum> A { X; operator +(B other) = 1; operator<B> +() = 0 }\ntypedef<enum> B { X; operator +(A other, self) = 2 }\nfn<int> test() = A.X + B.X" to DiagnosticCode.OPERATOR_MATCH,
			"typedef<class> A\nfn<bool> test(A a, A b) = a is b" to DiagnosticCode.OPERATOR_MATCH,
			"typedef<class> A { operator is(int other) = true }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
		)
		for ((source, code) in cases) {
			val failure = assertFailsWith<SemanticException>(source) { FunctionAnalyzer().analyze(structUnit(source)) }
			assertEquals(code, failure.diagnosticCode, source)
		}
		val noNarrowing = """
			typedef<class> A {
			    pub def<int> field = 1
			    operator is(Object other) = true
			}
			fn<int> test(A a, Object b) {
			    if (a is b) return b.field
			    return 0
			}
		"""
		assertFailsWith<SemanticException> { FunctionAnalyzer().analyze(structUnit(noNarrowing)) }
	}
}
