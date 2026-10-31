package cloth

import cloth.data.ValueType
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.expression.CharacterLiteral
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.SemanticAnalyzer
import cloth.semantic.SemanticException
import cloth.token.TokenType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CharactersTest {

	private fun parse(source: String) = Parser(Lexer(source.trimIndent()).scanTokens()).parseSnippet()

	@Test
	fun `literals decode one Unicode scalar and the existing escapes`() {
		val cases = listOf("'n'" to 110, "'é'" to 0xe9, "'中'" to 0x4e2d, "'😀'" to 0x1f600, "'\\n'" to 10, "'\\r'" to 13, "'\\t'" to 9, "'\\0'" to 0, "'\\\\'" to 92, "'\\''" to 39, "'\\\"'" to 34)
		for ((source, expected) in cases) {
			val tokens = Lexer(source).scanTokens()
			assertEquals(TokenType.CharacterLiteral, tokens[0].type)
			assertEquals(source, tokens[0].lexeme)
			assertEquals(source.length + 1, tokens[1].span.startColumn)
			val ast = parse(source)
			val literal = assertIs<CharacterLiteral>(assertIs<ExpressionStatement>(ast.statements.single()).expression)
			assertEquals(expected, literal.value, source)
			assertEquals(ValueType.CHAR, SemanticAnalyzer().analyze(ast).typeOf(literal))
		}
		assertEquals(32, ValueType.CHAR.bits)
		assertEquals(32, ValueType.CHAR.storageBits)
		assertFalse(ValueType.CHAR.numeric)
		val strings = Lexer("\"text\" \"\\\"\" \"\"\"raw\"\"\"").scanTokens()
		assertEquals(listOf(TokenType.StringLiteral, TokenType.StringLiteral, TokenType.RawStringLiteral, TokenType.EOF), strings.map { it.type })
	}

	@Test
	fun `empty multiple unterminated and invalid scalar literals are rejected`() {
		val cases = listOf("''", "'ab'", "'😀a'", "'a😀'", "'é'", "'", "'a", "'\\", "'\\x'", "'\\nX'", "'\n'", "'\r'", "'\\\n'", "'\uD800'", "'\uDC00'", "'\uD800x'")
		for (source in cases) {
			val failure = assertFails(source) { Lexer(source).scanTokens() }
			assertLocated(failure)
		}
	}

	@Test
	fun `bindings preserve character types mutability and independent storage`() {
		val ast = parse("""
			def<char> letter = 'n'
			def copy := letter
			def<bool> alive = true
			def<int> number = 1
			def<float> fraction = 2.5
			letter = '😀'
			copy
		""")
		val model = SemanticAnalyzer().analyze(ast)
		assertEquals(ValueType.CHAR, model.typeOf(ast.statements[0]))
		assertEquals(ValueType.CHAR, model.typeOf(ast.statements[1]))
		assertEquals(ValueType.CHAR, model.typeOf(ast.statements.last()))
		val ir = LLVMEmitter().emitSnippet(ast)
		assertContains(ir, "%letter = alloca i32")
		assertContains(ir, "%copy = alloca i32")
		assertContains(ir, "store i32 128512, ptr %letter")
		assertContains(ir, "load i32, ptr %copy")
	}

	@Test
	fun `characters reject type changes unsupported modifiers and invalid bindings`() {
		val cases = listOf("def<char> c = 110", "def<char> c = 1.0", "def<char> c = true", "def c = 'n'\nc = 1", "def c = 1\nc = 'n'", "def<bool> b = 'n'", "def c := 'n'\nc = 'x'", "def<char> c = c", "def c = 'n'\ndef c = 'x'", "def<unsigned char> c = 'n'", "def<char<8>> c = 'n'", "def<char<32>> c = 'n'")
		for (source in cases) {
			assertFailsWith<SemanticException>(source) { LLVMEmitter().emitSnippet(parse(source)) }
		}
	}

	@Test
	fun `characters remain separate from numbers booleans and numeric operators`() {
		val cases = buildList {
			for (operator in listOf("+", "-", "*", "/", "%", "<", ">", "<=", ">=", "&&", "||")) {
				add("'a' $operator 'b'")
			}
			for (other in listOf("1", "1.0", "true", "1 -> unsigned int<64>")) {
				for (operator in listOf("==", "!=", "+", "<")) {
					add("'a' $operator ($other)")
					add("($other) $operator 'a'")
				}
			}
			for (operator in listOf("+", "-", "!")) add("${operator}'a'")
			for (type in listOf("int<8>", "unsigned int<16>", "int", "int<64>", "float<16>", "float", "float<64>", "bool")) {
				add("'a' -> $type")
			}
			for (value in listOf("97", "97.0", "true")) add("$value -> char")
			add("false && ('a' == 97)")
			add("true || ('a' == true)")
		}
		for (source in cases) {
			val failure = assertFailsWith<SemanticException>(source) { LLVMEmitter().emitSnippet(parse(source)) }
			assertLocated(failure)
		}
	}

	@Test
	fun `native copies assignments equality and logical composition work`(@TempDir directory: Path) {
		assertProgram(directory.resolve("bindings"), """
			def<char> c = '中'
			def copy := c
			def same = c == copy
			def different = c != copy
			def number = 42
			def flag = true
			def fraction = 1.5
			c = '😀'
			same && !different && c != copy && copy == '中' && c == '😀' && flag && number == 42 && fraction == 1.5
		""", "true")
		assertProgram(directory.resolve("false_equality"), "def c = 'a'\nc == 'b'", "false")
		assertProgram(directory.resolve("false_inequality"), "def c = '😀'\nc != '😀'", "false")
		assertProgram(directory.resolve("composition"), "def c = 'a'\n(c == 'a' || false) == !(c != 'a')", "true")
		assertProgram(directory.resolve("assignment"), "def c = 'a'\nc = '😀'", "😀")
		assertProgram(directory.resolve("snapshot"), "def c = 'a'\ndef copy := c\nc = '😀'\ncopy", "a")
		assertProgram(directory.resolve("identity"), "def<char> c = '😀' -> char\nc", "😀")
	}

	@Test
	fun `native UTF8 output preserves boundaries escapes and supplementary characters`(@TempDir directory: Path) {
		val points = listOf(0, 0x7f, 0x80, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x1f600, 0x10ffff)
		for (point in points) {
			val character = String(Character.toChars(point))
			val source = if (point == 0) "'\\0'" else "'$character'"
			assertProgram(directory.resolve("literal_$point"), source, character)
			assertProgram(directory.resolve("binding_$point"), "def<char> c = $source\nc", character)
		}
		for ((index, pair) in listOf("'\\n'" to "\n", "'\\r'" to "\r", "'\\t'" to "\t", "'\\\\'" to "\\", "'\\''" to "'", "'\\\"'" to "\"").withIndex()) {
			assertProgram(directory.resolve("escape_$index"), pair.first, pair.second)
		}
	}

	private fun assertProgram(output: Path, source: String, expected: String) {
		val executable = LLVMEmitter().compileSnippet(parse(source), output)
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
