package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.UnaryExpression
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.SemanticException
import cloth.semantic.typing.FunctionAnalyzer
import cloth.token.TokenType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class AssignmentOperatorsTest {

	@Test
	fun `lexer recognizes update tokens and retains line comments`() {
		val tokens = Lexer("+= -= /= *= %= ++ -- ** // comment\n/").scanTokens()
		assertEquals(listOf("+=", "-=", "/=", "*=", "%=", "++", "--", "**", "/"), tokens.filter { it.type == TokenType.Operator }.map { it.lexeme })
	}

	@Test
	fun `compound assignments enforce binding types and mutability`() {
		val cases = listOf("missing += 1" to DiagnosticCode.UNKNOWN_NAME, "def x := 1\nx += 1" to DiagnosticCode.IMMUTABLE_BINDING, "def x := 1\nx++" to DiagnosticCode.IMMUTABLE_BINDING, "def x = true\nx--" to DiagnosticCode.UNARY_OPERAND, "def x = 1\nx += 0.5" to DiagnosticCode.TYPE_MISMATCH, "def<unsigned int> x = 1\ndef y = 2\nx *= y" to DiagnosticCode.MIXED_INTEGERS, "def<int<8>> x = 1\nx += 128" to DiagnosticCode.INTEGER_RANGE, "def x = true\nx += false" to DiagnosticCode.BINARY_OPERANDS, "def x = 'a'\nx -= 1" to DiagnosticCode.BINARY_OPERANDS, "def x = \"a\"\nx *= 2" to DiagnosticCode.BINARY_OPERANDS, "def x = 1\nx += firstMissing + secondMissing" to DiagnosticCode.UNKNOWN_NAME)
		for ((source, code) in cases) {
			val failure = assertFailsWith<SemanticException>(source) { FunctionAnalyzer(nativeTestLayout).analyze(parseMain(source)) }
			assertEquals(code, failure.diagnosticCode, source)
			assertTrue(failure.sourceLabels.isNotEmpty())
		}
	}

	@Test
	fun `compound assignment requires a target and right operand`() {
		for (source in listOf("x +=", "1 += 2", "(x) += 1", "def x += 1", "x += 1 += 2", "++x", "--x", "def y = x++", "x++ + 1", "x += x++", "x++++")) {
			assertFailsWith<SyntaxException>(source) { parseMain(source) }
		}
	}

	@Test
	fun `native compound operations preserve precedence signedness widths and copies`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("arithmetic"), """
			def x = 10
			def original := x
			x += 2 * 3
			x -= 1
			x *= 4
			x /= 3
			x %= 7
			println(original * 100 + x)
		""", "1006\n")
		assertMainOutput(directory.resolve("widths"), """
			def<unsigned int<8>> x = 255
			x += 1
			x -= 1
			x /= 2
			x %= 100
			def<int<8>> y = 127
			y += 1
			println(x == 27 && y == -128)
		""", "true\n")
	}

	@Test
	fun `native compounds evaluate rhs once after reading target and honor scopes`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("order"), """
			def x = 10
			def visits = 0
			x += if (true) {
				visits += 1
				x = 100
				2
			} else { 0 }
			if (true) {
				def x = 50
				x *= 2
				visits += x
			}
			println(x * 1000 + visits)
		""", "12101\n")
	}

	@Test
	fun `native floating and string compound assignments use ordinary operations`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("float"), """
			def x = 1.5
			x += 2
			x *= 2
			x -= 1
			x /= 4
			x %= 1
			println(x)
		""", "0.5\n")
		assertMainOutput(directory.resolve("string"), """
			def text = "é"
			def original := text
			text += text + "😀"
			println(original == "é" && text == "éé😀")
		""", "true\n")
	}

	@Test
	fun `native single statement void bodies use ordinary compound assignments`(@TempDir directory: Path) {
		val source = """
			typedef<class> Player (pub def<float> health) {
			    pub mt<void> add(float amount) = health += amount; pub mt subtract(float amount) = self.health -= amount
			    pub mt multiply(float amount) = health *= amount
			    pub mt divide(float amount) = self.health /= amount
			    pub mt remainder(float amount) = if (amount != 0f) health %= amount
			}
			typedef<struct> Counter {
			    int value;
			    mt increment() = value++;
			}
			fn<void> run(Player player) = while (player.health < 100f) player.add(10f);
			fn<void> finish(Player player) = player::delete
			fn<int> main() {
			    def player = Player::new(90f)
			    run(player)
			    player.add(10f)
			    player.subtract(2f)
			    player.multiply(2f)
			    player.divide(3f)
			    player.remainder(7f)
			    def<Counter> count = { 1 }
			    count.increment()
			    if (player.health != 2f || count.value != 2) return 1
			    finish(player)
			    return 0
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("statement_bodies_${abi.layout.sizeBits}"))
			assertEquals(processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `native increment and decrement statements update numeric bindings`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("updates"), """
			def x = 10
			x++
			x++
			x--
			def<unsigned int<8>> wrapped = 255
			wrapped++
			wrapped--
			def half = 1.5 -> float<16>
			half++
			half--
			println(x == 11 && wrapped == 255 && half == (1.5 -> float<16>))
		""", "true\n")
	}

	@Test
	fun `powers associate right and bind above multiplication and unary signs`() {
		val power = assertIs<BinaryExpression>((parseMain("2 ** 3 ** 2").mainStatements.single() as ExpressionStatement).expression)
		assertEquals("**", (power.right as BinaryExpression).operator.lexeme)
		val negative = assertIs<UnaryExpression>((parseMain("-2 ** 2").mainStatements.single() as ExpressionStatement).expression)
		assertIs<BinaryExpression>(negative.operand)
		for (source in listOf("2 **", "** 2", "2 *** 3")) {
			assertFailsWith<SyntaxException>(source) { parseMain(source) }
		}
	}

	@Test
	fun `powers reject nonnumeric operands mixed integers and literal negative exponents`() {
		for ((source, code) in listOf("2 ** -1" to DiagnosticCode.NEGATIVE_EXPONENT, "false && (2 ** -1 == 0)" to DiagnosticCode.NEGATIVE_EXPONENT, "true ** 2" to DiagnosticCode.BINARY_OPERANDS, "2 ** 'a'" to DiagnosticCode.BINARY_OPERANDS, "\"a\" ** 2" to DiagnosticCode.BINARY_OPERANDS, "def<unsigned int> x = 2\ndef y = 3\nx ** y" to DiagnosticCode.MIXED_INTEGERS, "def<int<8>> x = 2\nx ** 128" to DiagnosticCode.INTEGER_RANGE)) {
			val failure = assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parseMain(source)) }
			assertEquals(code, failure.diagnosticCode, source)
		}
	}

	@Test
	fun `native integer powers preserve exact widths and arithmetic grouping`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("power_integer"), """
			def<unsigned int<64>> base = 3
			def<unsigned int<64>> huge = 18446744073709551615
			def<unsigned int<8>> small = 16
			def<int<8>> signed = -2
			def<int<64>> wide = 2
			println(base ** 40 == 12157665459056928801 && huge ** 2 == 1 &&
			(small ** 2 == 0) && signed ** 7 == -128 &&
			wide ** 62 == 4611686018427387904)
		""".replace("&&\n", "&& "), "true\n")
		assertMainOutput(directory.resolve("power_grouping"), """
			println(2 ** 3 ** 2 == 512 && -2 ** 2 == -4 && (-2) ** 2 == 4 && 2 * 3 ** 2 == 18 && 2 ** 0 == 1 && 0 ** 0 == 1 && 0 ** 4 == 0)
		""", "true\n")
	}

	@Test
	fun `integer powers preserve every signed and unsigned width`(@TempDir directory: Path) {
		val source = buildString {
			for (width in listOf(8, 16, 32, 64)) {
				appendLine("def<int<$width>> signed$width = -2")
				appendLine("def<unsigned int<$width>> positive$width = 3")
				appendLine("def signedResult$width = signed$width ** 3")
				appendLine("def positiveResult$width = positive$width ** 3")
			}
			append(listOf(8, 16, 32, 64).joinToString(" && ", prefix = "println(", postfix = ")") { width ->
				"signedResult$width is int<$width> && signedResult$width == -8 && positiveResult$width is unsigned int<$width> && positiveResult$width == 27"
			})
		}
		assertMainOutput(directory.resolve("power_widths"), source, "true\n")
	}

	@Test
	fun `native floating powers promote and round with existing numeric rules`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("power_float"), """
			def half = 2.0 -> float<16>
			def wide = 2.0 -> float<64>
			def<unsigned int> positive = 4
			def narrow = half ** half
			def mixed = half ** 2
			def result64 = wide ** -2
			println(narrow is float<16> && mixed is float && result64 is float<64> && narrow == 4 && mixed == 4 && result64 == 0.25 && positive ** 0.5 == 2 && 2.0 ** -2 == 0.25)
		""", "true\n")
	}

	@Test
	fun `floating powers handle special values and dynamic CRT`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("power_special"), """
			def nan = (-1.0) ** 0.5
			def infinity = 2.0 ** 128
			def negativeZero = (-0.0) ** 3
			println(nan != nan && infinity > 1.0 && (1.0 / negativeZero) < 0.0 && 0.0 ** 0.0 == 1.0)
		""", "true\n", "--runtime=dynamic")
	}

	@Test
	fun `power operands run once left to right and compose with nested control flow`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("power_order"), """
			def visits = 0
			def negative = -1
			def result = (if (true) {
				visits += 1
				2
			} else { 0 }) ** (if (visits == 1) {
				visits += 10
				3
			} else { 0 }) ** 2
			def skipped = false && 2 ** negative == 0
			println(result == 512 && visits == 11 && !skipped && (if (true) { 3 ** 2 } else { 0 }) == 9)
		""", "true\n")
		val executable = compileMain(directory.resolve("power_invalid"), "def exponent = -1\n2 ** exponent")
		val output = runExecutable(executable, status = 1)
		assertContains(output, "Integer exponentiation requires a nonnegative exponent")
		assertFalse(output.contains("PEC:"))
	}
}
