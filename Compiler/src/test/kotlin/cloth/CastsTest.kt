package cloth

import cloth.data.CFloat
import cloth.data.CInt
import cloth.data.ValueType
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.CastExpression
import cloth.parser.expression.UnaryExpression
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.SemanticException
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class CastsTest {

	private fun expression(source: String) = assertIs<ExpressionStatement>(parseMain(source).mainStatements.single()).expression

	@Test
	fun `casts parse numeric targets widths and left associative chains`() {
		val cast = assertIs<CastExpression>(expression("-1 -> unsigned int<64> -> int"))
		assertEquals("int", cast.target.spelling)
		val first = assertIs<CastExpression>(cast.operand)
		assertEquals("unsigned int<64>", first.target.spelling)
		assertIs<UnaryExpression>(first.operand)
		assertEquals("float<64>", assertIs<CastExpression>(expression("(1 + 2) -> float<64>")).target.spelling)
		val tokens = Lexer("x->int\nx -> unsigned int<64>").scanTokens()
		assertEquals(2, tokens.count { it.lexeme == "->" })
	}

	@Test
	fun `casts bind below arithmetic and above comparisons`() {
		val product = assertIs<BinaryExpression>(expression("1 + -2 -> float * 3"))
		assertEquals("*", product.operator.lexeme)
		val sum = assertIs<BinaryExpression>(assertIs<CastExpression>(product.left).operand)
		assertEquals("+", sum.operator.lexeme)
		assertIs<UnaryExpression>(sum.right)
		val wholeSum = assertIs<BinaryExpression>(assertIs<CastExpression>(expression("1 + 2 * 3 -> float")).operand)
		assertEquals("+", wholeSum.operator.lexeme)
		assertEquals("*", assertIs<BinaryExpression>(wholeSum.right).operator.lexeme)
		for (source in listOf("1 -> int < 2", "1 -> int < other", "1 -> int<64> <= 2", "1 -> float > 0.0", "1 -> int == 1")) {
			assertIs<CastExpression>(assertIs<BinaryExpression>(expression(source)).left)
		}
		val grouped = assertIs<CastExpression>(expression("(1 + 2) -> float"))
		assertIs<BinaryExpression>(grouped.operand)
		for (source in listOf("1 ->", "-> int", "1 -> -> int", "1 -> unsigned", "1 -> int<>", "1 -> int<64>>", "1 -> float<wrong>", "1 -> int = 2", "1 -> (int)")) {
			assertFailsWith<SyntaxException>(source) { parseMain(source) }
		}
	}

	@Test
	fun `cast results carry their type into inference assignments and literal contexts`() {
		val ast = parseMain("""
			def u = -1 -> unsigned int
			def wide = u -> int<64>
		""")
		val model = FunctionAnalyzer(nativeTestLayout).analyze(ast)
		assertEquals(listOf(ValueType.UINT, CInt(64)), ast.mainStatements.map { model.typeOf(it) })
		val valid = parseMain("""
			def<unsigned int> a = 1
			def<int> b = a -> int
			a = b -> unsigned int
			def<float<64>> f = a -> float<64>
			def inferred := 1 + a -> int<64>
			def<bool> okay = (a -> int) == b
		""")
		val validModel = FunctionAnalyzer(nativeTestLayout).analyze(valid)
		assertEquals(CFloat(64), validModel.typeOf(valid.mainStatements[3]))
		assertEquals(CInt(64), validModel.typeOf(valid.mainStatements[4]))
		val declaration = assertIs<BindingDeclaration>(valid.mainStatements[1])
		assertEquals(ValueType.INT, validModel.typeOf(declaration.initializer))
	}

	@Test
	fun `casts preserve strict assignment rules and reject boolean numeric conversions`() {
		val cases = listOf("true -> int" to "Cannot cast 'bool' to 'int'", "false -> float<64>" to "Cannot cast 'bool' to 'float<64>'", "1 -> bool" to "Cannot cast 'int' to 'bool'", "0.0 -> bool" to "Cannot cast 'float' to 'bool'", "1 -> unsigned float" to "'unsigned' applies only to 'int'", "1 -> Widget" to "not supported yet", "1 -> int<65537>" to "supported integer widths are 1 through 65536", "1 -> float<128>" to "supported widths are 16, 32, 64", "missing -> int" to "Unknown name 'missing'", "def<int> a = 1 -> float" to "Cannot assign 'float' to 'int'", "def a = 1 -> float\na = 1" to "Cannot assign 'int' to 'float'", "def a = 1 -> unsigned int\ndef s = 1\na + s" to "use an explicit conversion", "def a := 1 -> float\na = 2 -> float" to "Cannot reassign immutable binding", "def value = 1.0\ndef<float<64>> a = value" to "Cannot assign 'float' to 'float<64>'", "def a = 1 -> float<64>\ndef value = 1.0\na = value" to "Cannot assign 'float' to 'float<64>'", "4294967295 -> unsigned int" to "out of range for 'int'", "false && (true -> int == 1)" to "Cannot cast 'bool' to 'int'")
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parseMain(source)) }
			assertContains(failure.message.orEmpty(), diagnostic)
			assertLocated(failure)
		}
	}

	@Test
	fun `native integer casts truncate sign extend and zero extend deliberately`(@TempDir directory: Path) {
		assertPrograms(directory, listOf("println(1 + 2 -> float)" to "3", "println(-1 -> unsigned int)" to "4294967295", "println(-1 -> unsigned int<64>)" to "18446744073709551615", "def<unsigned int> u = 4294967295\nprintln(u -> int)" to "-1", "def<unsigned int> u = 4294967295\nprintln(u -> int<64>)" to "4294967295", "def<unsigned int> u = 4294967295\nprintln(u -> unsigned int<64>)" to "4294967295", "def<unsigned int<64>> u = 18446744073709551615\nprintln(u -> int<64>)" to "-1", "def<unsigned int<64>> u = 18446744073709551615\nprintln(u -> unsigned int)" to "4294967295", "def<int<64>> wide = 4294967296\nprintln(wide -> int)" to "0", "def<int<64>> wide = -4294967297\nprintln(wide -> unsigned int)" to "4294967295", "def<int> s = -2\nprintln(s -> int<64>)" to "-2", "def<int<64>> s = -9223372036854775808\nprintln(s -> int)" to "0", "def a = -1 -> unsigned int\ndef s = 1\nprintln((a -> int) + s)" to "0", "println((-1 -> unsigned int) -> int<64>)" to "4294967295"))
	}

	@Test
	fun `native floating casts preserve precision when widening before arithmetic`(@TempDir directory: Path) {
		assertPrograms(directory, listOf("println(((2147483647 -> float<64>) * 2) -> int<64>)" to "4294967294", "def<float<64>> f = (2147483647 -> float<64>) * 2\nprintln(f -> int<64>)" to "4294967294", "println((16777217 -> float<64>) == 16777217)" to "true", "println((16777217 -> float) == 16777217)" to "true", "println(((16777217 -> float<64>) -> float) -> int)" to "16777216", "def<unsigned int> u = 4294967295\nprintln(u -> float<64>)" to "4294967295", "def<unsigned int> u = 4294967295\nprintln(u -> float<64> -> unsigned int)" to "4294967295", "println(-3 -> float<64>)" to "-3", "def<int<64>> x = -9007199254740991\nprintln(x -> float<64> -> int<64>)" to "-9007199254740991", "def<float<64>> f = 1 -> float<64>\ndef copy := f\nf = f / 2\nprintln(copy + f + 0.25)" to "1.75", "def<float<32>> f = 1.25\nprintln(f -> float<32>)" to "1.25", "println((2147483647 + 1) -> int<64>)" to "-2147483648"))
	}

	@Test
	fun `all numeric source and target type pairs convert natively`(@TempDir directory: Path) {
		val types = ValueType.MACHINE_INTEGER_WIDTHS.flatMap { listOf("int<$it>", "unsigned int<$it>") } + ValueType.FLOAT_WIDTHS.map { "float<$it>" }
		val cases = types.flatMap { source -> types.map { target -> "def<$source> value = 7 -> $source\nprintln(value -> $target)" to "7" } }
		assertPrograms(directory, cases)
	}

	@Test
	fun `float to integer casts truncate toward zero at valid boundaries`(@TempDir directory: Path) {
		assertPrograms(directory, listOf("println(3.75 -> int)" to "3", "println(-3.75 -> int)" to "-3", "println(-0.75 -> unsigned int)" to "0", "println(-0.75 -> unsigned int<64>)" to "0", "println(2147483520.0 -> int)" to "2147483520", "println(-2147483648.0 -> int)" to "-2147483648", "def<float<64>> f = (2147483647 -> float<64>) + 0.75\nprintln(f -> int)" to "2147483647", "def<float<64>> f = (-2147483648 -> float<64>) - 0.75\nprintln(f -> int)" to "-2147483648", "def<unsigned int> u = 4294967295\nprintln(((u -> float<64>) + 0.75) -> unsigned int)" to "4294967295", "def<int<64>> x = -9223372036854775808\nprintln(x -> float<64> -> int<64>)" to "-9223372036854775808", "def<int<64>> x = 9223372036854774784\nprintln(x -> float<64> -> int<64>)" to "9223372036854774784", "println(9223371487098961920.0 -> int<64>)" to "9223371487098961920", "def<unsigned int<64>> x = 18446744073709549568\nprintln(x -> float<64> -> unsigned int<64>)" to "18446744073709549568", "println(18446742974197923840.0 -> unsigned int<64>)" to "18446742974197923840", "println(3.75 -> unsigned int -> float<64> -> int<64>)" to "3"))
	}

	@Test
	fun `invalid float casts produce controlled runtime errors`(@TempDir directory: Path) {
		val cases = listOf("2147483648.0 -> int", "-2147483904.0 -> int", "-1.0 -> unsigned int", "4294967296.0 -> unsigned int", "9223372036854775808.0 -> int<64>", "18446744073709551616.0 -> unsigned int<64>", "def<float<64>> f = (-2147483648 -> float<64>) - 1\nf -> int", "def<int<64>> x = 9223372036854775807\nx -> float<64> -> int<64>", "def<int<64>> x = -9223372036854775808\n((x -> float<64>) - 2048) -> int<64>", "def<unsigned int<64>> x = 18446744073709551615\nx -> float<64> -> unsigned int<64>") + listOf("float", "float<64>").flatMap { type ->
			listOf("zero / zero", "1 / zero", "-1 / zero").flatMap { bad ->
				listOf("int", "unsigned int", "int<64>", "unsigned int<64>").map { target ->
					"def<$type> zero = 0 -> $type\n($bad) -> $target"
				}
			}
		}
		for ((index, source) in cases.withIndex()) {
			val output = runExecutable(compileMain(directory.resolve("invalid_$index"), source), status = 1)
			assertFalse(output.contains("PEC:"), output)
			assertContains(output, "Invalid numeric cast:")
			assertContains(output, " at ")
		}
	}

	@Test
	fun `casts compose with boolean storage short circuiting and subsequent statements`(@TempDir directory: Path) {
		assertPrograms(directory, listOf("def zero = 0.0\nprintln(false && ((1.0 / zero) -> int == 0))" to "false", "def zero = 0.0\nprintln(true || ((1.0 / zero) -> unsigned int == 0))" to "true", "def f = 3.75\ndef<bool> okay = (true && (f -> int == 3)) == ((f -> int == 3) || false)\nprintln(!(!okay))" to "true", "def a = 3.75 -> int\ndef copy := a\na = 5.25 -> int\nprintln(copy * 10 + a)" to "35", "def okay = 3.75 -> int == 3\ndef x = 1\nprintln(x + 2)" to "3", "println(true -> bool)" to "true", "println(false -> bool)" to "false", "println(1 -> int < 2)" to "true", "println(1 -> int<64> < 2)" to "true"))
	}

	@Test
	fun `float64 supports arithmetic ordering NaN and mixed promotion`(@TempDir directory: Path) {
		assertPrograms(directory, listOf("def f = 7 -> float<64>\nprintln(f % 2)" to "1", "def f = 3 -> float<64>\nprintln(-f)" to "-3", "def f = 16777217 -> float<64>\nprintln(f > 16777216.0 && 16777216.0 < f && f != 16777216.0)" to "true", "def z = 0 -> float<64>\ndef nan = z / z\nprintln(nan != nan && !(nan == nan) && !(nan < 0) && !(nan >= 0))" to "true", "def z = 0 -> float<64>\nprintln(1 / z > 0 && -1 / z < 0 && -z == z)" to "true"))
	}

	@Test
	fun `cast LLVM uses value conversions and guards unsafe float conversions`() {
		val ir = LLVMEmitter().emit(parseMain("""
			def<int> s = -1
			def<unsigned int> u = s -> unsigned int
			def wideSigned = s -> int<64>
			def wideUnsigned = u -> unsigned int<64>
			def narrow = wideSigned -> int
			def f = s -> float<64>
			def uf = u -> float
			def widerFloat = uf -> float<64>
			def smallerFloat = f -> float
			def a = f -> int
			def b = uf -> unsigned int
			println(a -> float<64>)
		"""))
		for (instruction in listOf("sext i32", "zext i32", "trunc i64", "sitofp i32", "uitofp i32", "fpext float", "fptrunc double", "fptosi double", "fptoui float", "cast_in_range", "cast_invalid", "@exit", "%.17g")) {
			assertContains(ir, instruction)
		}
		assertTrue(ir.indexOf("br i1 %cast_in_range") < ir.indexOf("fptosi double"))
		assertEquals(1, Regex("declare i32 @puts").findAll(ir).count())
		assertEquals(1, Regex("declare void @exit").findAll(ir).count())
	}

	private fun assertPrograms(directory: Path, cases: List<Pair<String, String>>) {
		for ((index, case) in cases.withIndex()) {
			val (source, expected) = case
			assertMainOutput(directory.resolve("program_$index"), source, "$expected\n")
		}
	}
}
