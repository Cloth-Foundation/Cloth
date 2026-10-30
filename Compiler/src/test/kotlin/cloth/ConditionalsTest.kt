package cloth

import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.SemanticAnalyzer
import cloth.semantic.SemanticException
import cloth.semantic.ValueType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ConditionalsTest {
	private fun parse(source: String) = Parser(Lexer(source.trimIndent()).scanTokens()).parse()

	@Test
	fun `inline and multiline chains preserve nesting and subsequent statements`() {
		val ast = parse("""
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
		assertEquals(2, ast.statements.size)
		val chain = assertIs<IfExpression>(assertIs<BindingDeclaration>(ast.statements.first()).initializer)
		assertEquals(2, chain.branches.size)
		assertEquals(1, chain.otherwise!!.size)
		assertIs<IfExpression>(assertIs<ExpressionStatement>(chain.branches[1].statements.single()).expression)
		assertEquals(ValueType.INT, SemanticAnalyzer().analyze(ast).typeOf(ast.statements.last()))
		assertEquals(2, parse("if (true) {}\n42").statements.size)
		assertEquals(2, parse("if (true) { if (false) {} }\n42").statements.size)
	}

	@Test
	fun `malformed conditionals have syntax diagnostics`() {
		for (source in listOf(
			"if true { 1 }", "if () {}", "if (true {}", "if (true) 1", "if (true) {",
			"if (true) { 1", "if (true) { 1 2 }", "if (true) {} else", "if (true) {} else 1",
			"if (true) {} else if (false)", "else {}", "if (true) {} else {} else {}",
			"if (true) {} if (false) {}", "if (true) { def x = }", "def x = if (true) { 1 } else { 2"
		)) {
			assertContains(assertFailsWith<SyntaxException>(source) { parse(source) }.message.orEmpty(), " at ", message = source)
		}
	}

	@Test
	fun `value contexts require exhaustive branches ending in compatible expressions`() {
		for (source in listOf(
			"def x = if (true) { 1 }", "def x = if (true) { 1 } else if (false) { 2 }",
			"def x = if (true) {} else { 2 }", "def x = if (true) { 1 } else {}",
			"def x = if (true) { def y = 1 } else { 2 }",
			"def y = 0\ndef x = if (true) { y = 1 } else { 2 }",
			"def x = if (true) { 1 } else { false }", "def x = if (false) { 1 } else { 2.0 }",
			"def x = if (true) { 1 -> unsigned int } else { 1 }",
			"def x = if (true) { 1 -> int<8> } else { 1 -> int<16> }",
			"def<int> x = if (true) { true } else { false }",
			"def x = if (true) { if (false) { 1 } } else { 2 }",
			"1 + if (true) { 2 }", "false && (if (true) { false })"
		)) assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parse(source)) }
	}

	@Test
	fun `all conditions and skipped branches are checked before emission`() {
		for (source in listOf(
			"if (1) {}", "if (1.0) {}", "if (\"yes\") {}", "if ('y') {}",
			"if (true) {} else if (1) {}", "if (false) { missing }",
			"if (true) {} else { missing }", "if (true) {} else if (missing) {}",
			"def x = if (true) { 1 } else { missing }", "if (false) { 1 + true }",
			"def x := 1\nif (false) { x = 2 }", "def x = 1\nif (false) { x = true }"
		)) assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parse(source)) }
	}

	@Test
	fun `branch scopes isolate names and preserve duplicate and immutable checks`() {
		for (source in listOf(
			"if (true) { def local = 1 }\nlocal",
			"if (true) { def local = 1 } else { local }",
			"if (true) { def local = 1 } else if (local == 1) {}",
			"if (true) { def local = 1\ndef local = 2 }",
			"def x = if (true) { def local = 1\nlocal } else { local }",
			"if (true) { def local := 1\nlocal = 2 }",
			"def x = if (true) { x } else { 1 }"
		)) assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parse(source)) }
		SemanticAnalyzer().analyze(parse("if (true) { def x = 1 } else { def x = false }\ndef x = 'a'"))
	}

	@Test
	fun `standalone conditionals yield void and produce no phi or automatic output`(@TempDir directory: Path) {
		val ast = parse("if (true) { 1 } else if (false) { \"unused\" } else {}")
		assertEquals(ValueType.VOID, SemanticAnalyzer().analyze(ast).typeOf(ast.statements.single()))
		val ir = LLVMEmitter().emit(ast, forcePrint = true)
		assertFalse(ir.contains("if_value"))
		assertFalse(ir.contains("@printf"))
		assertProgram(directory.resolve("no_output"), "42\nif (true) { 1 } else { false }", null)
		assertProgram(directory.resolve("empty"), "if (false) {}", null)
	}

	@Test
	fun `native chains select each alternative and preserve outer mutations`(@TempDir directory: Path) {
		for ((index, input) in listOf(10, 30, 0).withIndex()) {
			val expected = listOf("first", "second", "last")[index]
			assertProgram(directory.resolve("value_$index"), """
				def a = $input
				def result = if (a == 10) { "first" } else if (a > 25) { "second" } else { "last" }
				result
			""", expected)
			assertProgram(directory.resolve("statement_$index"), """
				def a = $input
				def result = "initial"
				if (a == 10) { result = "first" }
				else if (a > 25) { result = "second" }
				else { result = "last" }
				result
			""", expected)
		}
		assertProgram(directory.resolve("unchanged"), "def x = 42\nif (false) { x = 0 }\nx", "42")
		assertProgram(directory.resolve("continue"), "def x = 0\nif (true) { x = 41 }\nx = x + 1\nx", "42")
	}

	@Test
	fun `native value branches support all storage types inference reassignment and copies`(@TempDir directory: Path) {
		val cases = listOf(
			Triple("int<8>", "-128", "-128"), Triple("unsigned int<8>", "255", "255"),
			Triple("int<16>", "-32768", "-32768"), Triple("unsigned int<16>", "65535", "65535"),
			Triple("int", "-2147483648", "-2147483648"), Triple("unsigned int", "4294967295", "4294967295"),
			Triple("int<64>", "-9223372036854775808", "-9223372036854775808"),
			Triple("unsigned int<64>", "18446744073709551615", "18446744073709551615"),
			Triple("float<16>", "1.5 -> float<16>", "1.5"), Triple("float", "1.5", "1.5"),
			Triple("float<64>", "1.5 -> float<64>", "1.5"), Triple("bool", "true", "true"),
			Triple("char", "'😀'", "😀"), Triple("string", "\"é\\0中😀\" + \"!\"", "é\u0000中😀!")
		)
		for ((index, case) in cases.withIndex()) {
			val (type, value, expected) = case
			assertProgram(directory.resolve("type_$index"), """
				def flag = false
				def<$type> result = if (flag) { $value } else { $value }
				def copy := result
				result = if (!flag) { $value } else { $value }
				copy
			""", expected)
		}
		assertProgram(directory.resolve("inferred"), "def x := if (false) { 0 } else { 42 }\nx", "42")
		assertFailsWith<SemanticException> { LLVMEmitter().emit(parse("def x := if (true) { 1 } else { 2 }\nx = 3")) }
	}

	@Test
	fun `native branch locals shadow names and final expressions survive scope exit`(@TempDir directory: Path) {
		assertProgram(directory.resolve("scopes"), """
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
			value == "inner!local" && x == 10 && result == "inner!"
		""", "true")
	}

	@Test
	fun `native nested values compose with short circuits comparisons casts and concatenation`(@TempDir directory: Path) {
		assertProgram(directory.resolve("nested"), """
			def flag = true
			def value = if (flag && (if (false) { false } else { true })) {
			    if (false) { "wrong" } else { "a" + "b" }
			} else { "c" + "d" }
			def number = 1 + (if (value == "ab") { 20 } else { 0 }) * 2
			def converted = (if (flag) { number } else { 0 }) -> float<64>
			def same = (if (flag) { value } else { "" }) == "ab"
			same && converted == 41.0 && (if (flag) { true } else { false })
		""", "true")
		val ir = LLVMEmitter().emit(parse("def flag = true\ndef result = if (flag) { \"a\" + \"b\" } else { \"c\" + \"d\" }\nresult"))
		assertContains(ir, "phi { ptr, i64 }")
		assertContains(ir, "concat_valid")
	}

	@Test
	fun `native conditions execute once and later alternatives and unselected bodies are skipped`(@TempDir directory: Path) {
		assertProgram(directory.resolve("evaluation"), """
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
			checks == 2 && bodies == 1 && result == 42
		""", "true")
		assertProgram(directory.resolve("skipped_errors"), """
			def bad = 0.0 / 0.0
			def value = if (true) { 42 } else if ((bad -> int) == 0) { 0 } else { bad -> int }
			def safe = false && (if (true) { (bad -> int) == 0 } else { false })
			value == 42 && !safe
		""", "true")
	}

	@Test
	fun `primitive is checks parse with logical precedence and validate types`() {
		val ast = parse("def a = 1\na + 1 is unsigned int<64> == false && a is int")
		val logic = assertIs<BinaryExpression>(assertIs<ExpressionStatement>(ast.statements.last()).expression)
		val equality = assertIs<BinaryExpression>(logic.left)
		val check = assertIs<TypeCheckExpression>(equality.left)
		assertIs<BinaryExpression>(check.operand)
		assertEquals(ValueType.BOOL, SemanticAnalyzer().analyze(ast).typeOf(logic))
	}

	@Test
	fun `invalid is targets and operands fail before emission`() {
		for (source in listOf("1 is unsigned float", "1 is int<7>", "1 is string<32>", "1 is Player", "1 is void", "missing is int", "1 is int < true")) {
			assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parse(source)) }
		}
		for (source in listOf("1 is", "1 is unsigned", "1 is 1", "is int")) {
			assertFailsWith<SyntaxException>(source) { parse(source) }
		}
	}

	@Test
	fun `native primitive type checks compare exact widths and preserve operand side effects`(@TempDir directory: Path) {
		assertProgram(directory.resolve("user_example"), """
			def<unsigned int> a = 10
			def<string> isUnsigned = if (a is unsigned int) { "is unsigned" } else if (a > 25) { "is above 25" } else { ":(" }
			isUnsigned
		""", "is unsigned")
		assertProgram(directory.resolve("checks"), """
			def<unsigned int<8>> small = 1
			def<float<16>> half = 1.0 -> float<16>
			def count = 0
			def result = (if (true) { count = count + 1
			    1
			} else { 2 }) is float
			small is unsigned int<8> && !(small is unsigned int) && !(small is int<8>) && half is float<16> && !(half is float) && true is bool && '😀' is char && "hi" is string && 1 + 2 is int == true && !result && count == 1
		""", "true")
	}

	@Test
	fun `conditional example compiles through CLI`(@TempDir directory: Path) {
		val output = directory.resolve("conditional_example")
		main(arrayOf(Path.of("examples", "Conditionals.co").toAbsolutePath().toString(), output.toString()))
		val executable = if (Files.exists(output)) output else Path.of("$output.exe")
		assertOutput(executable, "is unsigned!")
	}

	private fun assertProgram(output: Path, source: String, expected: String?) {
		assertOutput(LLVMEmitter().compile(parse(source), output), expected)
	}

	private fun assertOutput(executable: Path, expected: String?) {
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Executable timed out: $executable")
			val bytes = process.inputStream.use { it.readBytes() }
			assertEquals(0, process.exitValue(), bytes.toString(Charsets.UTF_8))
			val expectedOutput = if (expected == null) "" else (expected + "\n").replace("\n", System.lineSeparator())
			assertContentEquals(expectedOutput.toByteArray(Charsets.UTF_8), bytes, executable.toString())
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
