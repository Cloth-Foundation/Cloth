package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.IfExpression
import cloth.parser.statement.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SemicolonsTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("semicolons.co", source.trimIndent())).scanTokens()).parse()
	private fun functions(source: String) = parse(source).declarations.map { assertIs<FunctionDeclaration>(it) }
	private fun body(source: String) = assertIs<BlockBody>(functions(source).single().body).statements

	@Test
	fun `explicit and bare returns accept terminators without extending source spans`() {
		val source = "fn<int> value() { return 10; }\nfn stop() { return; }"
		val functions = functions(source)
		val value = assertIs<ReturnStatement>(assertIs<BlockBody>(functions[0].body).statements.single())
		val bare = assertIs<ReturnStatement>(assertIs<BlockBody>(functions[1].body).statements.single())
		assertNotNull(value.value)
		assertNull(bare.value)
		assertEquals("return 10", source.substring(value.span.startColumn - 1, value.span.endColumn - 1))
		assertEquals("return", source.lines()[1].substring(bare.span.startColumn - 1, bare.span.endColumn - 1))
	}

	@Test
	fun `all statement forms can mix semicolons newlines and same line separators`() {
		val statements = body("""
			fn<int> sample() {
			    def x = 1; x += 2; x++;
			    def values = [x]; values[0] = 4;
			    print(values); return values[0];
			}
		""")
		assertEquals(7, statements.size)
		assertIs<BindingDeclaration>(statements[0])
		assertIs<AssignmentStatement>(statements[1])
		assertIs<UpdateStatement>(statements[2])
		assertIs<ElementAssignmentStatement>(statements[4])
		assertIs<ExpressionStatement>(statements[5])
		assertIs<ReturnStatement>(statements[6])
	}

	@Test
	fun `only expression bodies accept a declaration terminator`() {
		val functions = functions("fn<int> first() = 1; fn second() {;;;}\r\n// comment\r\nfn third() {}")
		assertEquals(listOf("first", "second", "third"), functions.map { it.name.lexeme })
		assertIs<ExpressionBody>(functions[0].body)
		assertTrue(assertIs<BlockBody>(functions[1].body).statements.isEmpty())
		assertTrue(parse("\n// empty file\n").declarations.isEmpty())
		assertIs<ExpressionBody>(functions("fn<int> value() = if (true) { 1; } else { 2; };").single().body)
	}

	@Test
	fun `conditional chains keep else attached and binding blocks accept a terminator`() {
		val statements = body("""
			fn<int> sample() {
			    def<int> work := { return 10; };
			    if (true) { return work; } else if (false) { return 20; }
			    else { return 30; }
			}
		""")
		assertEquals(2, statements.size)
		val chain = assertIs<IfExpression>(assertIs<ExpressionStatement>(statements[1]).expression)
		assertEquals(2, chain.branches.size)
		assertNotNull(chain.otherwise)
		assertEquals(2, body("fn sample() { if (true) {};; print(1); }").size)
	}

	@Test
	fun `result lists and final conditional values retain their meaning`() {
		val module = LLVMEmitter().emit(parse("""
			fn<int, int> pair() { return 1, 2; }
			fn<int> sample(bool flag) {
			    def a, b = pair(); a, b = b, a;
			    def result = if (flag) { a; } else { b; };
			    return result;
			}
		"""))
		assertContains(module, "cloth.fn.0.pair")
		assertContains(module, "phi i32")
	}

	@Test
	fun `function bodies accept empty statements and mixed separators`() {
		assertEquals(3, parseMain(";;def x = 1; x++;\nx;").mainStatements.size)
		assertTrue(parseMain(";;;").mainStatements.isEmpty())
	}

	@Test
	fun `brace functions and file level empty statements reject semicolons`() {
		for (source in listOf(
			"fn main() {};", "fn main() {}\n;", "fn main() {} /* comment */ ;",
			"fn first() {}; fn second() {}", "; fn main() {}", ";\n;;",
			"fn<int> value() = 10;;", "fn<int> value() = 10;\n;",
		)) {
			val failure = assertFailsWith<SyntaxException>(source) { parse(source) }
			assertEquals(DiagnosticCode.EXPECTED_TOKEN, failure.diagnosticCode)
			val span = failure.sourceLabels.single().span
			assertEquals(';', source.lines()[span.startLine - 1][span.startColumn - 1])
		}
	}

	@Test
	fun `array printing example rejects only the misplaced function terminator`() {
		val source = """
			fn main () {
			    def<int[3]> x = [ 1, 2, 3 ];
			    def<int[][]> y = [[1, 2, 3], [4, 5, 6], [7, 8, 9]];
			    println(x);
			    println(y);
			};
		""".trimIndent()
		val failure = assertFailsWith<SyntaxException> { parse(source) }
		assertEquals(6, failure.sourceLabels.single().span.startLine)
		assertEquals(2, failure.sourceLabels.single().span.startColumn)
		LLVMEmitter().emit(parse(source.dropLast(1)))
	}

	@Test
	fun `semicolons cannot replace operands commas or header syntax`() {
		for (source in listOf(
			"fn test() { def x = ; }", "fn<int> test() { return 1 + ; }",
			"fn test() = consume(1; 2)", "fn test() = consume(1;)",
			"fn test() { def x = [1; 2]; }", "fn test() { def x = (1;); }",
			"fn test(int a; int b) {}", "fn test(); {}",
			"fn test() { if (true;) {} }", "fn test() { if (true); {} }",
			"fn<int, int> test() { return 1,; }", "fn test() { def x = [1]; x[0;] = 2; }",
			"fn test() { def; x = 1; }", "fn test() { if (true) {} else; {} }",
			"fn test() { if (true) {}; else {} }", "fn test() { if (true) {}\n;\nelse {} }",
			"fn test() { if (true) {} else if (false) {}; else {} }",
			"fn<int> test() = if (true) { 1; }; else { 2; }",
		)) {
			assertFailsWith<SyntaxException>(source) { parse(source) }
		}
	}

	@Test
	fun `missing separators still fail at the first error`() {
		val failure = assertFailsWith<SyntaxException> { parse("fn test() { print(1) print(2); missing + ; }") }
		assertEquals(DiagnosticCode.EXPECTED_NEWLINE, failure.diagnosticCode)
		assertContains(failure.message.orEmpty(), "newline or ';'")
		assertEquals(22, failure.sourceLabels.single().span.startColumn)
		assertFailsWith<SyntaxException> { parse("fn first() {} fn second() {};") }
		assertFailsWith<SyntaxException> { parse("; print(1);") }
	}

	@Test
	fun `semicolons in literals and comments are preserved`() {
		val module = LLVMEmitter().emit(parse("""
			fn<string> text() = ";"; // ;
			fn<char> character() { /* ; */ return ';'; }
		"""))
		assertContains(module, "ret i32 59")
	}

	@Test
	fun `terminated program executes and returns the requested value`(@TempDir directory: Path) {
		val executable = LLVMEmitter().compile(parse("""
			fn stop() { return; }
			fn<int> value() { return 10; }
			fn<int> main() { stop(); def x = value(); x++; return x; }
		"""), directory.resolve("semicolons"))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS))
			assertEquals(11, process.exitValue(), process.inputStream.readBytes().toString(Charsets.UTF_8))
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
