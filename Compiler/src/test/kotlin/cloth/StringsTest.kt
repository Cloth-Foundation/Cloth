package cloth

import cloth.data.ValueType
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.StringLiteral
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.SemanticException
import cloth.semantic.typing.FunctionAnalyzer
import cloth.token.TokenType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class StringsTest {

	@Test
	fun `quoted strings decode escapes and preserve Unicode scalars`() {
		val cases = listOf("\"\"" to "", "\"Hello World!\"" to "Hello World!", "\"é中😀é\"" to "é中😀é", "\"\\n\\r\\t\\0\\\\\\'\\\"\"" to "\n\r\t\u0000\\'\"", "\"// not a comment /* either */ {name} 100%\"" to "// not a comment /* either */ {name} 100%")
		for ((source, expected) in cases) {
			val tokens = Lexer(source).scanTokens()
			assertEquals(TokenType.StringLiteral, tokens[0].type)
			assertEquals(source, tokens[0].lexeme)
			val ast = parseMain(source)
			val literal = assertIs<StringLiteral>(assertIs<ExpressionStatement>(ast.mainStatements.single()).expression)
			assertEquals(expected, literal.value)
			assertEquals(ValueType.STRING, FunctionAnalyzer(nativeTestLayout).analyze(ast).types.typeOf(literal))
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
		val ast = parseMain(source)
		assertEquals(contents, assertIs<StringLiteral>(assertIs<ExpressionStatement>(ast.mainStatements[0]).expression).value)
		assertEquals("", assertIs<StringLiteral>(assertIs<ExpressionStatement>(parseMain("\"\"\"\"\"\"").mainStatements.single()).expression).value)
	}

	@Test
	fun `unterminated invalid escape multiline and malformed Unicode strings are rejected`() {
		val cases = listOf("\"", "\"hello", "\"hello\\", "\"\\x\"", "\"\\u0041\"", "\"\\q\"", "\"a\nb\"", "\"a\rb\"", "\"\\\n\"", "\"\\\r\"", "\"\"\"raw", "\"\uD800\"", "\"\uDC00\"", "\"\uD800x\"", "\"\"\"\uD800\"\"\"", "\"\"\"\uDC00\"\"\"")
		for (source in cases) {
			assertLocated(assertFails(source) { Lexer(source).scanTokens() })
		}
	}

	@Test
	fun `explicit and inferred bindings retain string types and descriptors`() {
		val ast = parseMain("""
			def<string> text = "original"
			def original := text
			def<string> fixed := "fixed"
			def changing = ""
			text = "😀"
			changing = fixed
			println(original)
		""")
		val model = FunctionAnalyzer(nativeTestLayout).analyze(ast).types
		for (statement in ast.mainStatements.dropLast(1)) assertEquals(ValueType.STRING, model.typeOf(statement))
		val ir = LLVMEmitter().emit(ast)
		assertContains(ir, "%text = alloca { ptr, i64 }")
		assertContains(ir, "%original = alloca { ptr, i64 }")
		assertContains(ir, "private constant")
		assertContains(ir, "load { ptr, i64 }, ptr %original")
		assertContains(ir, "define internal void @cloth.runtime.write.stdout(")
		assertContains(ir, "declare ptr @malloc")
		assertContains(ir, "cloth.rt.string.release")
	}

	@Test
	fun `strings reject invalid binding types mutations and modifiers`() {
		val cases = listOf("def<string> s = 'a'", "def<string> s = 1", "def<string> s = 1.0", "def<string> s = true", "def s = \"text\"\ns = 'a'", "def s = 1\ns = \"text\"", "def<char> c = \"a\"", "def s := \"text\"\ns = \"other\"", "def<string> s = s", "def s = \"a\"\ndef s = \"b\"", "def<unsigned string> s = \"a\"", "def<string<8>> s = \"a\"", "def<string<32>> s = \"a\"")
		for (source in cases) {
			assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parseMain(source)) }
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
			assertLocated(assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parseMain(source)) })
		}
	}

	@Test
	fun `concatenation uses addition precedence and equality returns bool`() {
		val ast = parseMain("""
			def<string> s = "a" + "b" + "c"
			def same := s == "a" + ("b" + "c")
			def<bool> different = s != "abc"
			def result = same && !different || "x" + "y" == "xy"
		""")
		val model = FunctionAnalyzer(nativeTestLayout).analyze(ast).types
		assertEquals(ValueType.STRING, model.typeOf(ast.mainStatements[0]))
		for (statement in ast.mainStatements.drop(1)) assertEquals(ValueType.BOOL, model.typeOf(statement))
		val concat = assertIs<BinaryExpression>(assertIs<BindingDeclaration>(ast.mainStatements[0]).initializer)
		assertEquals("+", concat.operator.lexeme)
		assertIs<BinaryExpression>(concat.left) // Addition associates left to right.
		val equality = assertIs<BinaryExpression>(assertIs<BindingDeclaration>(ast.mainStatements[1]).initializer)
		assertEquals("==", equality.operator.lexeme)
		assertEquals("+", assertIs<BinaryExpression>(equality.right).operator.lexeme)
		for (source in listOf("\"a\" +", "\"a\" ==", "\"a\" !=", "\"a\" + + \"b\"")) {
			assertFails(source) { LLVMEmitter().emit(parseMain(source)) }
		}
		assertFailsWith<SyntaxException> { parseMain("\"a\" == == \"b\"") }
	}

	@Test
	fun `string operations retain strict assignment and immutable binding rules`() {
		for (source in listOf("def<bool> b = \"a\" + \"b\"", "def<string> s = \"a\" == \"a\"", "def s = \"a\"\ns = s != \"b\"", "def b = true\nb = \"a\" + \"b\"", "def s := \"a\"\ns = s + \"b\"", "def b := \"a\" == \"a\"\nb = false", "\"a\" == \"a\" == \"a\"", "true || (\"a\" + 1 == \"a1\")")) assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parseMain(source)) }
	}

	@Test
	fun `native equality compares complete contents rather than addresses or C terminators`(@TempDir directory: Path) {
		val equal = listOf("\"\" == \"\"", "\"same\" == \"same\"", "\"é中😀\" == \"é中😀\"", "\"a\\0b\" == \"a\\0b\"", "\"a\" + \"b\" == \"ab\"", "\"\"\"\\n\"\"\" == \"\\\\n\"", "\"\" + \"\" == \"\"", "\"a\" + \"bc\" == \"ab\" + \"c\"")
		val unequal = listOf("\"\" != \"a\"", "\"a\" != \"\"", "\"a\" != \"ab\"", "\"ab\" != \"a\"", "\"ab\" != \"ac\"", "\"Ab\" != \"ab\"", "\"a\\0b\" != \"a\\0c\"", "\"a\\0\" != \"a\"", "\"😀\" != \"😁\"", "\"é\" != \"é\"", "\"a\" + \"b\" != \"ac\"")
		assertMainOutput(directory.resolve("contents"), (equal + unequal).joinToString(" && ", "println(", ")") { "($it)" }, "true\n")
		assertMainOutput(directory.resolve("false_equality"), "println(\"a\\0b\" == \"a\\0c\")", "false\n")
		assertMainOutput(directory.resolve("false_inequality"), "println(\"é\" + \"😀\" != \"é😀\")", "false\n")
	}

	@Test
	fun `native concatenation preserves order Unicode empty operands raw text and embedded NUL`(@TempDir directory: Path) {
		val cases = listOf("println(\"Hello, \" + \"Cloth!\")" to "Hello, Cloth!", "println(\"\" + \"\")" to "", "println(\"\" + \"😀\")" to "😀", "println(\"😀\" + \"\")" to "😀", "println(\"a\" + \"b\" + (\"c\" + \"d\"))" to "abcd", "def a = \"é中\"\ndef b := \"😀\"\nprintln(a + b)" to "é中😀", "println(\"a\\0\" + \"b\\0tail\")" to "a\u0000b\u0000tail", "println(\"\"\"raw\\n\"\"\" + \"\\n\\t\\\"\")" to "raw\\n\n\t\"", "println(\"100% \" + \"%s %n\")" to "100% %s %n", "def<string> s = \"a\" + \"b\"\ns = s + \"c\"\nprintln(s)" to "abc")
		for ((index, pair) in cases.withIndex()) assertMainOutput(directory.resolve("concat_$index"), pair.first, pair.second + "\n")
	}

	@Test
	fun `native concatenation retains snapshots through self assignment and nested expressions`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("snapshots"), """
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
			println(same && different && again == "ababab!" && snapshot == "ab" && a == "changed" && joined == "")
		""", "true\n")
		assertMainOutput(directory.resolve("result"), "def s = \"a\" + \"b\"\ndef copy := s\ns = s + s\nprintln(copy)", "ab\n")
		assertMainOutput(directory.resolve("identity_concat"), "println((\"a\" + \"b\") -> string)", "ab\n")
	}

	@Test
	fun `native string comparisons compose with short circuits and later numeric results`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("logic"), """
			def a = "a"
			def b = "b"
			def same = a + b == "ab"
			def opposite = !("a" != a)
			println((same && opposite) == (false || (a + b == "ab" && a != b)))
		""", "true\n")
		assertMainOutput(directory.resolve("skip_and"), "println(false && (\"a\" + \"b\" == \"ab\" && (0.0 / 0.0 -> int) == 0))", "false\n")
		assertMainOutput(directory.resolve("skip_or"), "println(true || (\"a\" + \"b\" != \"ab\" || (0.0 / 0.0 -> int) == 0))", "true\n")
		assertMainOutput(directory.resolve("numeric_last"), "def s = \"a\" + \"b\"\ndef same = s == \"ab\"\nprintln(21 * 2)", "42\n")
	}

	@Test
	fun `native repeated concatenation handles runtime lengths beyond a stack page`(@TempDir directory: Path) {
		val seed = "é😀"
		val source = buildString {
			appendLine("def s = \"$seed\"")
			repeat(11) { appendLine("s = s + s") }
			append("println(s == \"${seed.repeat(2048)}\")")
		}
		assertMainOutput(directory.resolve("growing"), source, "true\n")
	}

	@Test
	fun `string IR guards lengths and places comparisons and allocations inside short circuit branches`() {
		val source = """
			fn guarded(bool flag) {
				def a = "a"
				def b = "b"
				flag && (a + b == "ab")
			}
		""".trimIndent()
		val ir = LLVMEmitter().emit(Parser(Lexer(source).scanTokens()).parse())
		assertContains(ir, "byte_sum_fits")
		assertContains(ir, "memory_invalid")
		assertContains(ir, "@llvm.memcpy")
		assertContains(ir, "declare i32 @memcmp(ptr, ptr, i64)")
		assertContains(ir, "call ptr @cloth.rt.buffer.allocate.align1")
		assertTrue(ir.indexOf("logic_rhs:") < ir.indexOf("call ptr @cloth.rt.buffer.allocate.align1"))
		assertTrue(ir.indexOf("%string_same_length") < ir.indexOf("call i32 @memcmp"))
		assertFalse(ir.contains("@strcmp"))
		assertContains(ir, "declare ptr @malloc")
		assertContains(ir, "cloth.rt.string.release")
	}

	@Test
	fun `native string output preserves bytes lengths escapes and raw text`(@TempDir directory: Path) {
		val points = listOf(0x7f, 0x80, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x1f600, 0x10ffff)
		val unicode = points.joinToString("") { String(Character.toChars(it)) } + "é"
		val cases = listOf("println(\"\")" to "", "def<string> s = \"\"\nprintln(s)" to "", "println(\"$unicode\")" to unicode, "def s := \"$unicode\"\nprintln(s)" to unicode, "println(\"a\\0b\\0tail\")" to "a\u0000b\u0000tail", "def s = \"\\0after\"\nprintln(s)" to "\u0000after", "println(\"\\n\\r\\t\\\\\\'\\\"\")" to "\n\r\t\\'\"", "println(\"100% %s %n // text\")" to "100% %s %n // text", "println(\"\"\"raw\n\"quotes\" \\n 😀\"\"\")" to "raw\n\"quotes\" \\n 😀")
		for ((index, pair) in cases.withIndex()) assertMainOutput(directory.resolve("output_$index"), pair.first, pair.second + "\n")
	}

	@Test
	fun `native copies and reassignments preserve earlier values alongside other storage`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("copy"), """
			def<string> text = "original 😀"
			def snapshot := text
			def number = 42
			def flag = true
			def character = '中'
			def fraction = 1.5
			text = "replacement of a different length"
			println(snapshot)
		""", "original 😀\n")
		assertMainOutput(directory.resolve("reassign"), "def s = \"short\"\ndef t := \"longer 😀\\0tail\"\ns = t\nprintln(s)", "longer 😀\u0000tail\n")
		assertMainOutput(directory.resolve("empty"), "def s = \"long\"\ns = \"\"\nprintln(s)", "\n")
		assertMainOutput(directory.resolve("self"), "def s = \"same\"\ns = s\nprintln(s)", "same\n")
		assertMainOutput(directory.resolve("identity"), "def<string> s := (\"identity 😀\" -> string)\nprintln(s)", "identity 😀\n")
		assertMainOutput(directory.resolve("numeric_last"), "def s = \"text\"\ndef x = 3\nprintln(x + 4)", "7\n")
	}
}
