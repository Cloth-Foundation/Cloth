package cloth

import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.expression.StringLiteral
import cloth.parser.expression.BinaryExpression
import cloth.parser.exception.SyntaxException
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.SemanticAnalyzer
import cloth.semantic.SemanticException
import cloth.semantic.ValueType
import cloth.token.TokenType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class StringsTest {
	private fun parse(source: String) = Parser(Lexer(source.trimIndent()).scanTokens()).parse()

	@Test
	fun `quoted strings decode escapes and preserve Unicode scalars`() {
		val cases = listOf(
			"\"\"" to "", "\"Hello World!\"" to "Hello World!", "\"é中😀é\"" to "é中😀é",
			"\"\\n\\r\\t\\0\\\\\\'\\\"\"" to "\n\r\t\u0000\\'\"",
			"\"// not a comment /* either */ {name} 100%\"" to "// not a comment /* either */ {name} 100%"
		)
		for ((source, expected) in cases) {
			val tokens = Lexer(source).scanTokens()
			assertEquals(TokenType.StringLiteral, tokens[0].type)
			assertEquals(source, tokens[0].lexeme)
			val ast = parse(source)
			val literal = assertIs<StringLiteral>(assertIs<ExpressionStatement>(ast.statements.single()).expression)
			assertEquals(expected, literal.value)
			assertEquals(ValueType.STRING, SemanticAnalyzer().analyze(ast).typeOf(literal))
		}
		assertFalse(ValueType.STRING.numeric)
		assertEquals("string", ValueType.STRING.spelling)
	}

	@Test
	fun `raw strings preserve quotes backslashes and newlines and resume statement scanning`() {
		val contents = "first\n\"quoted\" \\n 😀\nlast"
		val source = "\"\"\"$contents\"\"\"\n42"
		val tokens = Lexer(source).scanTokens()
		assertEquals(listOf(TokenType.RawStringLiteral, TokenType.NewLine, TokenType.IntegerLiteral, TokenType.EOF), tokens.map { it.type })
		assertEquals(4, tokens[2].span.startLine)
		val ast = parse(source)
		assertEquals(contents, assertIs<StringLiteral>(assertIs<ExpressionStatement>(ast.statements[0]).expression).value)
		assertEquals("", assertIs<StringLiteral>(assertIs<ExpressionStatement>(parse("\"\"\"\"\"\"").statements.single()).expression).value)
	}

	@Test
	fun `unterminated invalid escape multiline and malformed Unicode strings are rejected`() {
		val cases = listOf("\"", "\"hello", "\"hello\\", "\"\\x\"", "\"\\u0041\"", "\"\\q\"",
			"\"a\nb\"", "\"a\rb\"", "\"\\\n\"", "\"\\\r\"", "\"\"\"raw",
			"\"\uD800\"", "\"\uDC00\"", "\"\uD800x\"", "\"\"\"\uD800\"\"\"", "\"\"\"\uDC00\"\"\"")
		for (source in cases) {
			assertContains(assertFails(source) { Lexer(source).scanTokens() }.message.orEmpty(), " at ", message = source)
		}
	}

	@Test
	fun `explicit and inferred bindings retain string types and descriptors`() {
		val ast = parse("""
			def<string> text = "original"
			def original := text
			def<string> fixed := "fixed"
			def changing = ""
			text = "😀"
			changing = fixed
			original
		""")
		val model = SemanticAnalyzer().analyze(ast)
		for (statement in ast.statements) assertEquals(ValueType.STRING, model.typeOf(statement))
		val ir = LLVMEmitter().emit(ast, forcePrint = true)
		assertContains(ir, "%text = alloca { ptr, i64 }")
		assertContains(ir, "%original = alloca { ptr, i64 }")
		assertContains(ir, "private constant")
		assertContains(ir, "load { ptr, i64 }, ptr %original")
		assertContains(ir, "string_print_check")
		assertFalse(ir.contains("@malloc"))
	}

	@Test
	fun `strings reject invalid binding types mutations and modifiers`() {
		val cases = listOf(
			"def<string> s = 'a'", "def<string> s = 1", "def<string> s = 1.0", "def<string> s = true",
			"def s = \"text\"\ns = 'a'", "def s = 1\ns = \"text\"", "def<char> c = \"a\"",
			"def s := \"text\"\ns = \"other\"", "def<string> s = s", "def s = \"a\"\ndef s = \"b\"",
			"def<unsigned string> s = \"a\"", "def<string<8>> s = \"a\"", "def<string<32>> s = \"a\"")
		for (source in cases) {
			assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parse(source)) }
		}
	}

	@Test
	fun `unimplemented string operators and cross type conversions fail before emission`() {
		val cases = buildList {
			for (operator in listOf("-", "*", "/", "%", "<", ">", "<=", ">=", "&&", "||")) {
				add("\"a\" $operator \"b\"")
			}
			for (other in listOf("1", "1.0", "true", "'a'")) {
				add("\"a\" == $other")
				add("$other != \"a\"")
				add("\"a\" + $other")
				add("$other + \"a\"")
				add("$other -> string")
			}
			for (type in listOf("int", "unsigned int<64>", "float<16>", "float<64>", "bool", "char")) add("\"a\" -> $type")
			for (operator in listOf("+", "-", "!")) add("$operator\"a\"")
			add("false && (\"a\" == 1)")
		}
		for (source in cases) {
			assertContains(assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parse(source)) }.message.orEmpty(), " at ", message = source)
		}
	}

	@Test
	fun `concatenation uses addition precedence and equality returns bool`() {
		val ast = parse("""
			def<string> s = "a" + "b" + "c"
			def same := s == "a" + ("b" + "c")
			def<bool> different = s != "abc"
			def result = same && !different || "x" + "y" == "xy"
		""")
		val model = SemanticAnalyzer().analyze(ast)
		assertEquals(ValueType.STRING, model.typeOf(ast.statements[0]))
		for (statement in ast.statements.drop(1)) assertEquals(ValueType.BOOL, model.typeOf(statement))
		val concat = assertIs<BinaryExpression>(assertIs<BindingDeclaration>(ast.statements[0]).initializer)
		assertEquals("+", concat.operator.lexeme)
		assertIs<BinaryExpression>(concat.left) // Addition associates left to right.
		val equality = assertIs<BinaryExpression>(assertIs<BindingDeclaration>(ast.statements[1]).initializer)
		assertEquals("==", equality.operator.lexeme)
		assertEquals("+", assertIs<BinaryExpression>(equality.right).operator.lexeme)
		for (source in listOf("\"a\" +", "\"a\" ==", "\"a\" !=", "\"a\" + + \"b\"")) {
			assertFails(source) { LLVMEmitter().emit(parse(source)) }
		}
		assertFailsWith<SyntaxException> { parse("\"a\" == == \"b\"") }
	}

	@Test
	fun `string operations retain strict assignment and immutable binding rules`() {
		for (source in listOf(
			"def<bool> b = \"a\" + \"b\"", "def<string> s = \"a\" == \"a\"",
			"def s = \"a\"\ns = s != \"b\"", "def b = true\nb = \"a\" + \"b\"",
			"def s := \"a\"\ns = s + \"b\"", "def b := \"a\" == \"a\"\nb = false",
			"\"a\" == \"a\" == \"a\"", "true || (\"a\" + 1 == \"a1\")"
		)) assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parse(source)) }
	}

	@Test
	fun `native equality compares complete contents rather than addresses or C terminators`(@TempDir directory: Path) {
		val equal = listOf("\"\" == \"\"", "\"same\" == \"same\"", "\"é中😀\" == \"é中😀\"",
			"\"a\\0b\" == \"a\\0b\"", "\"a\" + \"b\" == \"ab\"", "\"\"\"\\n\"\"\" == \"\\\\n\"",
			"\"\" + \"\" == \"\"", "\"a\" + \"bc\" == \"ab\" + \"c\"")
		val unequal = listOf("\"\" != \"a\"", "\"a\" != \"\"", "\"a\" != \"ab\"", "\"ab\" != \"a\"",
			"\"ab\" != \"ac\"", "\"Ab\" != \"ab\"", "\"a\\0b\" != \"a\\0c\"", "\"a\\0\" != \"a\"",
			"\"😀\" != \"😁\"", "\"é\" != \"é\"", "\"a\" + \"b\" != \"ac\"")
		assertProgram(directory.resolve("contents"), (equal + unequal).joinToString(" && ") { "($it)" }, "true")
		assertProgram(directory.resolve("false_equality"), "\"a\\0b\" == \"a\\0c\"", "false")
		assertProgram(directory.resolve("false_inequality"), "\"é\" + \"😀\" != \"é😀\"", "false")
	}

	@Test
	fun `native concatenation preserves order Unicode empty operands raw text and embedded NUL`(@TempDir directory: Path) {
		val cases = listOf(
			"\"Hello, \" + \"Cloth!\"" to "Hello, Cloth!",
			"\"\" + \"\"" to "", "\"\" + \"😀\"" to "😀", "\"😀\" + \"\"" to "😀",
			"\"a\" + \"b\" + (\"c\" + \"d\")" to "abcd",
			"def a = \"é中\"\ndef b := \"😀\"\na + b" to "é中😀",
			"\"a\\0\" + \"b\\0tail\"" to "a\u0000b\u0000tail",
			"\"\"\"raw\\n\"\"\" + \"\\n\\t\\\"\"" to "raw\\n\n\t\"",
			"\"100% \" + \"%s %n\"" to "100% %s %n",
			"def<string> s = \"a\" + \"b\"\ns = s + \"c\"" to "abc"
		)
		for ((index, pair) in cases.withIndex()) assertProgram(directory.resolve("concat_$index"), pair.first, pair.second)
	}

	@Test
	fun `native concatenation retains snapshots through self assignment and nested expressions`(@TempDir directory: Path) {
		assertProgram(directory.resolve("snapshots"), """
			def a = "a"
			def b := "b"
			def joined = a + b
			def snapshot := joined
			a = "changed"
			joined = joined + joined
			def again = joined + (snapshot + "!")
			joined = ""
			def<bool> same = snapshot == "ab"
			def different := again != snapshot
			same && different && again == "ababab!" && snapshot == "ab" && a == "changed" && joined == ""
		""", "true")
		assertProgram(directory.resolve("result"), "def s = \"a\" + \"b\"\ndef copy := s\ns = s + s\ncopy", "ab")
		assertProgram(directory.resolve("identity_concat"), "(\"a\" + \"b\") -> string", "ab")
	}

	@Test
	fun `native string comparisons compose with short circuits and later numeric results`(@TempDir directory: Path) {
		assertProgram(directory.resolve("logic"), """
			def a = "a"
			def b = "b"
			def same = a + b == "ab"
			def opposite = !("a" != a)
			(same && opposite) == (false || (a + b == "ab" && a != b))
		""", "true")
		assertProgram(directory.resolve("skip_and"), "false && (\"a\" + \"b\" == \"ab\" && (0.0 / 0.0 -> int) == 0)", "false")
		assertProgram(directory.resolve("skip_or"), "true || (\"a\" + \"b\" != \"ab\" || (0.0 / 0.0 -> int) == 0)", "true")
		assertProgram(directory.resolve("numeric_last"), "def s = \"a\" + \"b\"\ndef same = s == \"ab\"\n21 * 2", "42")
	}

	@Test
	fun `native repeated concatenation handles runtime lengths beyond a stack page`(@TempDir directory: Path) {
		val seed = "é😀"
		val source = buildString {
			appendLine("def s = \"$seed\"")
			repeat(11) { appendLine("s = s + s") }
			append("s == \"${seed.repeat(2048)}\"")
		}
		assertProgram(directory.resolve("growing"), source, "true")
	}

	@Test
	fun `string IR guards lengths and places comparisons and allocations inside short circuit branches`() {
		val ir = LLVMEmitter().emit(parse("""
			def a = "a"
			def b = "b"
			false && (a + b == "ab")
		"""))
		assertContains(ir, "concat_length_fits")
		assertContains(ir, "concat_invalid")
		assertContains(ir, "@llvm.memcpy")
		assertContains(ir, "declare i32 @memcmp(ptr, ptr, i64)")
		assertTrue(ir.indexOf("logic_rhs:") < ir.indexOf("%concat_bytes = alloca"))
		assertTrue(ir.indexOf("%string_same_length") < ir.indexOf("call i32 @memcmp"))
		assertFalse(ir.contains("@strcmp"))
		assertFalse(ir.contains("@malloc"))
	}

	@Test
	fun `native string output preserves bytes lengths escapes and raw text`(@TempDir directory: Path) {
		val points = listOf(0x7f, 0x80, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x1f600, 0x10ffff)
		val unicode = points.joinToString("") { String(Character.toChars(it)) } + "é"
		val cases = listOf(
			"\"\"" to "", "def<string> s = \"\"\ns" to "",
			"\"$unicode\"" to unicode, "def s := \"$unicode\"\ns" to unicode,
			"\"a\\0b\\0tail\"" to "a\u0000b\u0000tail",
			"def s = \"\\0after\"\ns" to "\u0000after",
			"\"\\n\\r\\t\\\\\\'\\\"\"" to "\n\r\t\\'\"",
			"\"100% %s %n // text\"" to "100% %s %n // text",
			"\"\"\"raw\n\"quotes\" \\n 😀\"\"\"" to "raw\n\"quotes\" \\n 😀"
		)
		for ((index, pair) in cases.withIndex()) assertProgram(directory.resolve("output_$index"), pair.first, pair.second)
	}

	@Test
	fun `native copies and reassignments preserve earlier values alongside other storage`(@TempDir directory: Path) {
		assertProgram(directory.resolve("copy"), """
			def<string> text = "original 😀"
			def snapshot := text
			def number = 42
			def flag = true
			def character = '中'
			def fraction = 1.5
			text = "replacement of a different length"
			snapshot
		""", "original 😀")
		assertProgram(directory.resolve("reassign"), "def s = \"short\"\ndef t := \"longer 😀\\0tail\"\ns = t", "longer 😀\u0000tail")
		assertProgram(directory.resolve("empty"), "def s = \"long\"\ns = \"\"", "")
		assertProgram(directory.resolve("self"), "def s = \"same\"\ns = s\ns", "same")
		assertProgram(directory.resolve("identity"), "def<string> s := (\"identity 😀\" -> string)\ns", "identity 😀")
		assertProgram(directory.resolve("numeric_last"), "def s = \"text\"\ndef x = 3\nx + 4", "7")
	}

	private fun assertProgram(output: Path, source: String, expected: String) {
		val executable = LLVMEmitter().compile(parse(source), output)
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Executable timed out: $source")
			val bytes = process.inputStream.use { it.readBytes() }
			assertEquals(0, process.exitValue(), bytes.toString(Charsets.UTF_8))
			val expectedOutput = (expected + "\n").replace("\n", System.lineSeparator())
			assertContentEquals(expectedOutput.toByteArray(Charsets.UTF_8), bytes, source)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
