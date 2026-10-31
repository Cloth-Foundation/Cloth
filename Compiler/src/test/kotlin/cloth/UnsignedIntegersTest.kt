package cloth

import cloth.data.CInt
import cloth.data.ValueType
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.exception.SyntaxException
import cloth.parser.statement.BindingDeclaration
import cloth.semantic.SemanticAnalyzer
import cloth.semantic.SemanticException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class UnsignedIntegersTest {

	private fun parse(source: String) = Parser(Lexer(source.trimIndent()).scanTokens()).parseSnippet()

	@Test
	fun `unsigned modifier and nested width annotations preserve their syntax`() {
		val ast = parse("""
			def<unsigned int> a = 10
			def<unsigned int<32>> b := a
			def<unsigned int<64>> c = 18446744073709551615
			def<int<64>> d := -9223372036854775808
		""")
		val declarations = ast.statements.map { assertIs<BindingDeclaration>(it) }
		assertTrue(declarations[0].type!!.unsigned)
		assertNull(declarations[0].type!!.width)
		assertEquals("32", declarations[1].type!!.width!!.lexeme)
		assertEquals("unsigned int<64>", declarations[2].type!!.spelling)
		assertFalse(declarations[3].type!!.unsigned)
		val model = SemanticAnalyzer().analyze(ast)
		assertEquals(listOf(ValueType.UINT, ValueType.UINT, CInt(64, true), CInt(64)), declarations.map { model.typeOf(it) })
		for (source in listOf("def<unsigned> a = 1", "def<unsigned unsigned int> a = 1", "def<int unsigned> a = 1", "def<unsigned int<>> a = 1", "def<unsigned int<64> a = 1", "def<unsigned int<64>>> a = 1", "def<unsigned int<-32>> a = 1", "def<unsigned int<32.0>> a = 1")) assertFailsWith<SyntaxException>(source) { parse(source) }
	}

	@Test
	fun `integer literal context preserves inferred signedness and fixed binding types`() {
		val ast = parse("""
			def<unsigned int> a = 10
			def copy := a
			def inferred = 1 + a * (2 + 3)
			a = 4294967295
			inferred = inferred + 1
			def<int> ordinary = 10
			def<unsigned int<64>> wide = 10
			def wideCopy := wide / 2
		""")
		val model = SemanticAnalyzer().analyze(ast)
		assertEquals(listOf(ValueType.UINT, ValueType.UINT, ValueType.UINT, ValueType.UINT, ValueType.UINT, ValueType.INT, CInt(64, true), CInt(64, true)), ast.statements.map { model.typeOf(it) })
	}

	@Test
	fun `unsigned values reject implicit binding conversions invalid modifiers and out of range literals`() {
		val cases = listOf("def<unsigned float> a = 1.0" to "'unsigned' applies only to 'int'", "def<unsigned bool> a = true" to "'unsigned' applies only to 'int'", "def<unsigned Widget> a = 1" to "'unsigned' applies only to 'int'", "def<unsigned int<7>> a = 1" to "supported widths are 8, 16, 32, 64", "def<unsigned int<128>> a = 1" to "supported widths are 8, 16, 32, 64", "def<float<128>> a = 1.0" to "supported widths are 16, 32, 64", "def<unsigned int> a = -1" to "out of range for 'unsigned int'", "def<unsigned int> a = 4294967296" to "out of range for 'unsigned int'", "def<int> a = 2147483648" to "out of range for 'int'", "def a = 4294967295" to "out of range for 'int'", "def<int<64>> a = 9223372036854775808" to "out of range for 'int<64>'", "def<int<64>> a = -9223372036854775809" to "out of range for 'int<64>'", "def<unsigned int> a = 0\na = -1" to "out of range for 'unsigned int'", "def<unsigned int> a = 1.5" to "Cannot assign 'float' to 'unsigned int'", "def<unsigned int> a = true" to "Cannot assign 'bool' to 'unsigned int'", "def s = 1\ndef<unsigned int> u = s" to "Cannot assign 'int' to 'unsigned int'", "def<unsigned int> u = 1\ndef<int> s = u" to "Cannot assign 'unsigned int' to 'int'", "def<unsigned int> u = 1\ndef<float> f = u" to "Cannot assign 'unsigned int' to 'float'", "def<unsigned int> u = 1\nu = 2.0" to "Cannot assign 'float' to 'unsigned int'", "def<unsigned int> u := 1\nu = 2" to "Cannot reassign immutable binding 'u'", "def<unsigned int> u = u" to "Unknown binding 'u'", "def<unsigned int> u = 1\ndef u = 2" to "already declared", "def<unsigned int<64>> u = 1\ndef<unsigned int> small = u" to "Cannot assign 'unsigned int<64>' to 'unsigned int'", "def<unsigned int> u = 1\n!u" to "requires a boolean operand", "def<unsigned int> u = 1\nfalse && u" to "requires boolean operands", "def<unsigned int> u = 1\nu == true" to "Cannot compare", "def<unsigned int> u = 1\nu < false" to "requires numeric operands")
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) { LLVMEmitter().emitSnippet(parse(source)) }
			assertContains(failure.message.orEmpty(), diagnostic)
			assertLocated(failure)
		}
		assertFailsWith<SyntaxException> { parse("def<unsigned int<64>> a = 18446744073709551616") }
	}

	@Test
	fun `signed unsigned and different width integer expressions require explicit conversion`() {
		for (operator in listOf("+", "-", "*", "/", "%", "==", "!=", "<", ">", "<=", ">=")) {
			for ((leftType, rightType) in listOf("int" to "unsigned int", "unsigned int" to "int", "int<64>" to "unsigned int<64>", "unsigned int<64>" to "unsigned int", "int" to "int<64>")) {
				val source = "def<$leftType> left = 1\ndef<$rightType> right = 2\nleft $operator right"
				val failure = assertFailsWith<SemanticException>(source) { LLVMEmitter().emitSnippet(parse(source)) }
				assertContains(failure.message.orEmpty(), "use an explicit conversion")
			}
		}
		assertFailsWith<SemanticException> {
			LLVMEmitter().emitSnippet(parse("def<unsigned int> u = 1\ndef s = 1\nfalse && (u == s)"))
		}
	}

	@Test
	fun `LLVM retains unsigned operations float conversions and typed output`() {
		val ir = LLVMEmitter().emitSnippet(parse("""
			def<unsigned int> a = 4294967295
			def<unsigned int> b = 2
			def quotient = a / b
			def remainder = a % b
			def less = a < b
			def greater = a > b
			def lessEqual = a <= b
			def greaterEqual = a >= b
			def mixed = a + 0.0
			def mixedRight = 0.0 + a
			a
		"""), forcePrint = true)
		for (instruction in listOf("udiv i32", "urem i32", "icmp ult i32", "icmp ugt i32", "icmp ule i32", "icmp uge i32", "uitofp i32", "%u")) assertContains(ir, instruction)
		assertFalse(ir.contains("sitofp"))
		val wide = LLVMEmitter().emitSnippet(parse("def<unsigned int<64>> a = 18446744073709551615\na / 2"), forcePrint = true)
		assertContains(wide, "alloca i64")
		assertContains(wide, "udiv i64")
		assertContains(wide, "%llu")
	}

	@Test
	fun `native output preserves high bits for declarations assignments reads and expressions`(@TempDir directory: Path) {
		assertPrograms(directory, listOf("def<unsigned int> a = 10" to "10", "def<unsigned int> a = 4294967295" to "4294967295", "def<unsigned int> a = 2147483648\na" to "2147483648", "def<unsigned int> a = 0\na = 4294967295" to "4294967295", "def<unsigned int> a = 4294967295\n+a" to "4294967295", "def<unsigned int> a = 4294967294\na + 1" to "4294967295", "def<unsigned int<64>> a := 18446744073709551615\na" to "18446744073709551615", "def<unsigned int<64>> a = 0\na = 9223372036854775808" to "9223372036854775808", "def<int<64>> a = 9223372036854775807\na" to "9223372036854775807", "def<int<64>> a = -9223372036854775808\na" to "-9223372036854775808", "-2147483648" to "-2147483648"))
	}

	@Test
	fun `native unsigned arithmetic wraps and divides using the full value`(@TempDir directory: Path) {
		for ((index, type) in listOf("unsigned int", "unsigned int<64>").withIndex()) {
			val maximum = if (index == 0) "4294967295" else "18446744073709551615"
			val half = if (index == 0) "2147483647" else "9223372036854775807"
			val cases = listOf("a + 1" to "0", "1 + a" to "0", "a - 1" to (maximum.toBigInteger() - java.math.BigInteger.ONE).toString(), "zero - 1" to maximum, "-one" to maximum, "a * 2" to (maximum.toBigInteger() - java.math.BigInteger.ONE).toString(), "a / 2" to half, "a % 2" to "1", "5 / one" to "5", "5 % one" to "0", "a + (1 + 2)" to "2", "(1 + 2) + a" to "2")
			assertPrograms(directory.resolve("width_$index"), cases.map { (expression, expected) ->
				"def<$type> a = $maximum\ndef<$type> zero = 0\ndef<$type> one = 1\n$expression" to expected
			})
		}
	}

	@Test
	fun `native unsigned comparisons remain exact above the signed range`(@TempDir directory: Path) {
		for ((index, type) in listOf("unsigned int", "unsigned int<64>").withIndex()) {
			val maximum = if (index == 0) "4294967295" else "18446744073709551615"
			val cases = listOf("a > 1 && 1 < a && a >= 1 && 1 <= a" to "true", "a < 1 || 1 > a || a <= 1 || 1 >= a" to "false", "a == $maximum && a != 1 && a >= $maximum && a <= $maximum" to "true", "a == a - 1 || a < a || a > a" to "false", "a - 1 < a && a > a - 1 && $maximum == a" to "true")
			assertPrograms(directory.resolve("width_$index"), cases.map { (expression, expected) -> "def<$type> a = $maximum\n$expression" to expected })
		}
	}

	@Test
	fun `unsigned float promotion uses positive values and existing float32 rounding`(@TempDir directory: Path) {
		assertPrograms(directory, listOf("def<unsigned int> a = 4294967295\na / 2.0" to "2.14748365e+09", "def<unsigned int> a = 4294967295\n4294967296.0 / a" to "1", "def<unsigned int> a = 4294967295\na > 0.0 && 0.0 < a && a == 4294967296.0" to "true", "def<unsigned int> a = 16777217\na == 16777216.0 && 16777216.0 == a" to "true", "def<unsigned int<64>> a = 18446744073709551615\na > 0.0 && 0.0 < a" to "true", "def<unsigned int> a = 10\ndef<float> f = 0.5\ndef mixed = f + a\nf = a * f\nmixed + f" to "15.5"))
	}

	@Test
	fun `unsigned bindings compose with inference copies booleans and short circuiting`(@TempDir directory: Path) {
		assertPrograms(directory, listOf("""
				def<unsigned int> a = 4294967295
				def snapshot := a
				def unsignedCopy = a
				def<bool> flag = a > 1
				def<int> ordinary = 1
				def<float> fraction := 0.5
				def<unsigned int<64>> wide := 18446744073709551615
				a = a + 1
				unsignedCopy = unsignedCopy / 2
				flag = !flag || (snapshot > unsignedCopy && a == 0)
				flag && ordinary == 1 && fraction == 0.5 && wide > 0
			""" to "true", "def<unsigned int> zero = 0\nzero != 0 && 10 / zero > 1" to "false", "def<unsigned int<64>> zero = 0\nzero == 0 || 10 / zero <= 1" to "true", "def<unsigned int> a = 2\ndef copy := 1 + a * (2 + 3)\na = 0\ncopy" to "11", "def<unsigned int> a = 1 + 2 * 3\na" to "7"))
	}

	@Test
	fun `signed wide integers retain signed division ordering and float conversion`(@TempDir directory: Path) {
		assertPrograms(directory, listOf("def<int<64>> a = -9223372036854775808\na / 2" to "-4611686018427387904", "def<int<64>> a = -5\na % 2" to "-1", "def<int<64>> a = -9223372036854775808\na < 0 && 0 > a && a < 0.0" to "true", "def<int<64>> a = 9223372036854775807\na > a - 1 && a != a - 1" to "true"))
	}

	private fun assertPrograms(directory: Path, cases: List<Pair<String, String>>) {
		for ((index, case) in cases.withIndex()) {
			val (source, expected) = case
			val executable = LLVMEmitter().compileSnippet(parse(source), directory.resolve("program_$index"))
			assertTrue(Files.isRegularFile(executable))
			val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
			try {
				assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Executable timed out: $source")
				val output = process.inputStream.bufferedReader().use { it.readText().trim() }
				assertEquals(0, process.exitValue(), output)
				assertEquals(expected, output, source)
			} finally {
				if (process.isAlive) process.destroyForcibly()
			}
		}
	}
}
