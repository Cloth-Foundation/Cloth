package cloth

import cloth.data.ValueType
import cloth.emitter.LLVMEmitter
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.IfExpression
import cloth.parser.expression.TypeCheckExpression
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.SemanticException
import cloth.semantic.typing.FunctionAnalyzer
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class ConditionalsTest {

	@Test
	fun `inline and multiline chains preserve nesting and subsequent statements`() {
		val program = parseMain("""
			def result = if (true) { 1 }
			// The else can follow comments and blank lines.

			else if (
			    false
			)
			{ if (true) { 2 } else { 3 } }
			else
			{ 4 }
			result
		""")
		val statements = program.mainStatements
		assertEquals(2, statements.size)
		val chain = assertIs<IfExpression>(assertIs<BindingDeclaration>(statements.first()).initializer)
		assertEquals(2, chain.branches.size)
		assertEquals(1, chain.otherwise!!.size)
		assertIs<IfExpression>(assertIs<ExpressionStatement>(chain.branches[1].statements.single()).expression)
		assertEquals(ValueType.INT, FunctionAnalyzer(nativeTestLayout).analyze(program).typeOf(statements.last()))
		assertEquals(2, parseMain("if (true) {}\n42").mainStatements.size)
		assertEquals(2, parseMain("if (true) { if (false) {} }\n42").mainStatements.size)
	}

	@Test
	fun `malformed conditionals have syntax diagnostics`() {
		for (source in listOf("if true { 1 }", "if () {}", "if (true {}", "if (true)", "if (true) {", "if (true) { 1", "if (true) { 1 2 }", "if (true) {} else", "if (true) {} else if (false)", "else {}", "if (true) {} else {} else {}", "if (true) {} if (false) {}", "if (true) { def x = }", "def x = if (true) { 1 } else { 2")) {
			assertLocated(assertFailsWith<SyntaxException>(source) { parseMain(source) })
		}
	}

	@Test
	fun `value contexts require exhaustive branches ending in compatible expressions`() {
		for (source in listOf("def x = if (true) { 1 }", "def x = if (true) { 1 } else if (false) { 2 }", "def x = if (true) {} else { 2 }", "def x = if (true) { 1 } else {}", "def x = if (true) { def y = 1 } else { 2 }", "def y = 0\ndef x = if (true) { y = 1 } else { 2 }", "def x = if (true) { 1 } else { false }", "def x = if (false) { 1 } else { 2.0 }", "def x = if (true) { 1 -> unsigned int } else { 1 }", "def x = if (true) { 1 -> int<8> } else { 1 -> int<16> }", "def<int> x = if (true) { true } else { false }", "def x = if (true) { if (false) { 1 } } else { 2 }", "1 + if (true) { 2 }", "false && (if (true) { false })")) assertFailsWith<SemanticException>(source) { FunctionAnalyzer(nativeTestLayout).analyze(parseMain(source)) }
	}

	@Test
	fun `all conditions and skipped branches are checked before emission`() {
		for (source in listOf("if (1) {}", "if (1.0) {}", "if (\"yes\") {}", "if ('y') {}", "if (true) {} else if (1) {}", "if (false) { missing }", "if (true) {} else { missing }", "if (true) {} else if (missing) {}", "def x = if (true) { 1 } else { missing }", "if (false) { 1 + true }", "def x := 1\nif (false) { x = 2 }", "def x = 1\nif (false) { x = true }")) assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parseMain(source)) }
	}

	@Test
	fun `branch scopes isolate names and preserve duplicate and immutable checks`() {
		for (source in listOf("if (true) { def local = 1 }\nlocal", "if (true) { def local = 1 } else { local }", "if (true) { def local = 1 } else if (local == 1) {}", "if (true) { def local = 1\ndef local = 2 }", "def x = if (true) { def local = 1\nlocal } else { local }", "if (true) { def local := 1\nlocal = 2 }", "def x = if (true) { x } else { 1 }")) assertFailsWith<SemanticException>(source) { FunctionAnalyzer(nativeTestLayout).analyze(parseMain(source)) }
		FunctionAnalyzer(nativeTestLayout).analyze(parseMain("if (true) { def x = 1 } else { def x = false }\ndef x = 'a'"))
	}

	@Test
	fun `standalone conditionals yield void and produce no phi or automatic output`(@TempDir directory: Path) {
		val program = parseMain("if (true) { 1 } else if (false) { \"unused\" } else {}")
		assertEquals(ValueType.VOID, FunctionAnalyzer(nativeTestLayout).analyze(program).typeOf(program.mainStatements.single()))
		val ir = LLVMEmitter().emit(program)
		assertFalse(ir.contains("if_value"))
		assertFalse(ir.contains("@printf"))
		assertMainOutput(directory.resolve("no_output"), "42\nif (true) { 1 } else { false }")
		assertMainOutput(directory.resolve("empty"), "if (false) {}")
	}

	@Test
	fun `native chains select each alternative and preserve outer mutations`(@TempDir directory: Path) {
		for ((index, input) in listOf(10, 30, 0).withIndex()) {
			val expected = listOf("first", "second", "last")[index]
			assertMainOutput(directory.resolve("value_$index"), """
				def a = $input
				def result = if (a == 10) { "first" } else if (a > 25) { "second" } else { "last" }
				println(result)
			""", "$expected\n")
			assertMainOutput(directory.resolve("statement_$index"), """
				def a = $input
				def result = "initial"
				if (a == 10) { result = "first" }
				else if (a > 25) { result = "second" }
				else { result = "last" }
				println(result)
			""", "$expected\n")
		}
		assertMainOutput(directory.resolve("unchanged"), "def x = 42\nif (false) { x = 0 }\nprintln(x)", "42\n")
		assertMainOutput(directory.resolve("continue"), "def x = 0\nif (true) { x = 41 }\nx = x + 1\nprintln(x)", "42\n")
	}

	@Test
	fun `native value branches support all storage types inference reassignment and copies`(@TempDir directory: Path) {
		val cases = listOf(Triple("int<8>", "-128", "-128"), Triple("unsigned int<8>", "255", "255"), Triple("int<16>", "-32768", "-32768"), Triple("unsigned int<16>", "65535", "65535"), Triple("int", "-2147483648", "-2147483648"), Triple("unsigned int", "4294967295", "4294967295"), Triple("int<64>", "-9223372036854775808", "-9223372036854775808"), Triple("unsigned int<64>", "18446744073709551615", "18446744073709551615"), Triple("float<16>", "1.5 -> float<16>", "1.5"), Triple("float", "1.5", "1.5"), Triple("float<64>", "1.5 -> float<64>", "1.5"), Triple("bool", "true", "true"), Triple("char", "'😀'", "😀"), Triple("string", "\"é\\0中😀\" + \"!\"", "é\u0000中😀!"))
		for ((index, case) in cases.withIndex()) {
			val (type, value, expected) = case
			assertMainOutput(directory.resolve("type_$index"), """
				def flag = false
				def<$type> result = if (flag) { $value } else { $value }
				def copy := result
				result = if (!flag) { $value } else { $value }
				println(copy)
			""", "$expected\n")
		}
		assertMainOutput(directory.resolve("inferred"), "def x := if (false) { 0 } else { 42 }\nprintln(x)", "42\n")
		assertFailsWith<SemanticException> { FunctionAnalyzer(nativeTestLayout).analyze(parseMain("def x := if (true) { 1 } else { 2 }\nx = 3")) }
	}

	@Test
	fun `native branch locals shadow names and final expressions survive scope exit`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("scopes"), """
			def x = 10
			def result = "start"
			if (false) { def x = 200
			    result = "wrong"
			} else {
			    def x = x + 2
			    if (true) { def x = "inner"
			        result = x
			    }
			    x = x + 1
			    result = result + if (x == 13) { "!" } else { "?" }
			}
			def value := if (true) {
			    def x = "local"
			    if (false) { result = "wrong" }
			    result + x
			} else { "wrong" }
			println(value == "inner!local" && x == 10 && result == "inner!")
		""", "true\n")
	}

	@Test
	fun `native nested values compose with short circuits comparisons casts and concatenation`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("nested"), """
			def flag = true
			def value = if (flag && (if (false) { false } else { true })) {
			    if (false) { "wrong" } else { "a" + "b" }
			} else { "c" + "d" }
			def number = 1 + (if (value == "ab") { 20 } else { 0 }) * 2
			def converted = (if (flag) { number } else { 0 }) -> float<64>
			def same = (if (flag) { value } else { "" }) == "ab"
			println(same && converted == 41.0 && (if (flag) { true } else { false }))
		""", "true\n")
		val ir = LLVMEmitter().emit(parseMain("def flag = true\ndef result = if (flag) { \"a\" + \"b\" } else { \"c\" + \"d\" }\nresult"))
		assertContains(ir, "phi { ptr, i64 }")
		assertContains(ir, "memory_valid")
	}

	@Test
	fun `native conditions execute once and later alternatives and unselected bodies are skipped`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("evaluation"), """
			def checks = 0
			def bodies = 0
			def result = if (if (true) { checks = checks + 1
			    false
			} else { true }) { bodies = 100
			    0
			} else if (if (true) { checks = checks + 1
			    true
			} else { false }) { bodies = bodies + 1
			    42
			} else if (if (true) { checks = 100
			    true
			} else { false }) { 0 } else { 0 }
			println(checks == 2 && bodies == 1 && result == 42)
		""", "true\n")
		assertMainOutput(directory.resolve("skipped_errors"), """
			def bad = 0.0 / 0.0
			def value = if (true) { 42 } else if ((bad -> int) == 0) { 0 } else { bad -> int }
			def safe = false && (if (true) { (bad -> int) == 0 } else { false })
			println(value == 42 && !safe)
		""", "true\n")
	}

	@Test
	fun `primitive is checks parse with logical precedence and validate types`() {
		val program = parseMain("def a = 1\na + 1 is unsigned int<64> == false && a is int")
		val logic = assertIs<BinaryExpression>(assertIs<ExpressionStatement>(program.mainStatements.last()).expression)
		val equality = assertIs<BinaryExpression>(logic.left)
		val check = assertIs<TypeCheckExpression>(equality.left)
		assertIs<BinaryExpression>(check.operand)
		assertEquals(ValueType.BOOL, FunctionAnalyzer(nativeTestLayout).analyze(program).typeOf(logic))
	}

	@Test
	fun `invalid is targets and operands fail before emission`() {
		for (source in listOf("1 is unsigned float", "1 is int<0>", "1 is string<32>", "1 is Player", "1 is void?", "missing is int", "1 is int < true")) {
			assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parseMain(source)) }
		}
		for (source in listOf("1 is", "1 is unsigned", "1 is 1", "is int")) {
			assertFailsWith<SyntaxException>(source) { parseMain(source) }
		}
	}

	@Test
	fun `native primitive type checks compare exact widths and preserve operand side effects`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("user_example"), """
			def<unsigned int> a = 10
			def<string> isUnsigned = if (a is unsigned int) { "is unsigned" } else if (a > 25) { "is above 25" } else { ":(" }
			println(isUnsigned)
		""", "is unsigned\n")
		assertMainOutput(directory.resolve("checks"), """
			def<unsigned int<8>> small = 1
			def<float<16>> half = 1.0 -> float<16>
			def count = 0
			def result = (if (true) { count = count + 1
			    1
			} else { 2 }) is float
			println(small is unsigned int<8> && !(small is unsigned int) && !(small is int<8>) && half is float<16> && !(half is float) && true is bool && '😀' is char && "hi" is string && 1 + 2 is int == true && !(1 is void) && !result && count == 1)
		""", "true\n")
	}

	@Test
	fun `conditional example compiles through CLI`(@TempDir directory: Path) {
		val output = directory.resolve("conditional_example")
		assertEquals(0, runCompiler(arrayOf(Path.of("examples", "Conditionals.co").toAbsolutePath().toString(), output.toString())))
		assertEquals(processCompletion(), runExecutable(ExecutableFormat.current.executablePath(output)))
	}
}
