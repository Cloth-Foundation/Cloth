package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.IfExpression
import cloth.parser.statement.*
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticException
import cloth.token.TokenSpan
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class UnbracedConditionalsTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("unbraced.co", source.trimIndent())).scanTokens()).parse()
	private fun body(source: String) = assertIs<BlockBody>(assertIs<FunctionDeclaration>(parse(source).declarations.single()).body).statements
	private fun chain(statement: Statement) = assertIs<IfExpression>(assertIs<ExpressionStatement>(statement).expression)
	private fun text(span: TokenSpan): String {
		val lines = assertNotNull(span.source).text.split('\n')
		fun offset(line: Int, column: Int) = lines.take(line - 1).sumOf { it.length + 1 } + column - 1
		return assertNotNull(span.source).text.substring(offset(span.startLine, span.startColumn), offset(span.endLine, span.endColumn))
	}

	@Test
	fun `single return chains parse and preserve strict return types`() {
		val source = """
			fn<bool> test(int x) {
			    if (x == 1) return true
			    else if (x == 2) return 2
			    else return false
			}
		"""
		val conditional = chain(body(source).single())
		assertEquals(2, conditional.branches.size)
		conditional.branches.forEach { assertIs<ReturnStatement>(it.statements.single()) }
		assertIs<ReturnStatement>(conditional.otherwise!!.single())
		val error = assertFailsWith<SemanticException> { FunctionAnalyzer().analyze(parse(source)) }
		assertEquals(DiagnosticCode.RETURN_TYPE, error.diagnosticCode)
		assertEquals(3, error.sourceLabels.first().span.startLine)
		FunctionAnalyzer().analyze(parse(source.replace("return 2", "return true")))
	}

	@Test
	fun `braced and unbraced expressions mix across comments and newlines`() {
		val statements = body("""
			fn<int> test(int x) {
			    def result = if (x == 1) 10
			    // Keep the next alternative attached.
			    else if (x == 2) { 20 }
			    else
			        30
			    return result
			}
		""")
		assertEquals(2, statements.size)
		val conditional = assertIs<IfExpression>(assertIs<BindingDeclaration>(statements.first()).initializer)
		assertEquals(2, conditional.branches.size)
		assertEquals("30", text(assertNotNull(conditional.otherwiseSpan)))
		assertIs<ReturnStatement>(statements.last())
	}

	@Test
	fun `else belongs to the nearest conditional through unbraced loop bodies`() {
		for (prefix in listOf("", "while (false) ", "for (def i <- 0..1) ")) {
			val outer = chain(body("fn test(bool a, bool b) { if (a) ${prefix}if (b) print(1) else print(2) }").single())
			assertNull(outer.otherwise)
			val inner = when (val statement = outer.branches.single().statements.single()) {
				is WhileStatement -> chain(statement.body.statements.single())
				is ForStatement   -> chain(statement.body.statements.single())
				else              -> chain(statement)
			}
			assertNotNull(inner.otherwise)
		}
		val outer = chain(body("fn test(bool a, bool b) { if (a) if (b) print(1) else print(2) else print(3) }").single())
		assertNotNull(outer.otherwise)
		assertNotNull(chain(outer.branches.single().statements.single()).otherwise)
	}

	@Test
	fun `one source statement includes definitions assignments updates and loop jumps`() {
		for ((source, kind) in listOf(
			"def local = 1" to BindingDeclaration::class,
			"def a, b = pair()" to BindingGroupDeclaration::class,
			"x = 1" to AssignmentStatement::class,
			"x++" to UpdateStatement::class,
			"values[0] = 1" to ElementAssignmentStatement::class,
			"break" to LoopJumpStatement::class,
			"continue" to LoopJumpStatement::class,
		)) {
			val conditional = chain(body("fn test() { if (true) $source }").single())
			assertEquals(kind, conditional.branches.single().statements.single()::class)
		}
		// One broadcast definition can expand into independent deferred bindings.
		val split = chain(body("fn test() { if (true) def<int> a, b = { return 1 } }").single())
		assertEquals(2, split.branches.single().statements.size)
	}

	@Test
	fun `bare return before else and result lists use ordinary return handling`() {
		val unit = parse("fn test(bool flag) { if (flag) return else return }")
		FunctionAnalyzer().analyze(unit)
		val conditional = chain(assertIs<BlockBody>(assertIs<FunctionDeclaration>(unit.declarations.single()).body).statements.single())
		assertNull(assertIs<ReturnStatement>(conditional.branches.single().statements.single()).value)
		assertNull(assertIs<ReturnStatement>(conditional.otherwise!!.single()).value)
		LLVMEmitter().emit(parse("fn<int, int> pair(bool flag) { if (flag) return 1, 2 else return 3, 4 }"))
	}

	@Test
	fun `unbraced branches preserve scopes skipped checking and value requirements`() {
		for (source in listOf(
			"fn test() { if (false) missing() }",
			"fn test() { if (true) def local = 1\nprint(local) }",
			"fn test() { if (true) def local = 1 else print(local) }",
			"fn<int> test(bool flag) = if (flag) 1",
			"fn<int> test(bool flag) = if (flag) 1 else false",
		)) assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parse(source)) }
		FunctionAnalyzer().analyze(parse("fn test(bool flag) { if (flag) def local = 1 else def local = false\ndef local = 2 }"))
	}

	@Test
	fun `missing bodies and separators fail at the first located error`() {
		for (source in listOf(
			"if (true)", "if (true);", "if (true) else print(1)",
			"if (true) print(1) else", "if (true) print(1) else;",
			"if (true) print(1); else print(2)", "if (true) {} else print(1); else print(2)",
			"if (true) print(1) print(2)",
		)) {
			val error = assertFailsWith<SyntaxException>(source) { body("fn test() { $source }\nfn later() { missing + ; }") }
			assertLocated(error)
			assertEquals(1, error.sourceLabels.single().span.startLine)
		}
	}

	@Test
	fun `CRLF spans exclude terminators and the next statement stays outside the branch`() {
		val statements = body("fn test() {\r\n\tif (true) print(1) else print(2);\r\n\tprint(3)\r\n}")
		assertEquals(2, statements.size)
		val conditional = chain(statements.first())
		assertEquals("true) print(1)", text(conditional.branches.single().span))
		assertEquals("print(2)", text(assertNotNull(conditional.otherwiseSpan)))
		assertEquals("if (true) print(1) else print(2)", text(conditional.span))
	}

	@Test
	fun `native branches execute returns values mutations and nearest loop jumps`(@TempDir directory: Path) {
		val executable = LLVMEmitter().compile(parse("""
			fn<int> choose(int x) {
			    if (x == 1) return 10
			    else if (x == 2) { return 20 }
			    else return 30
			}
			fn<int> value(bool flag) = if (flag) 1 else 2
			fn stop(bool flag) { if (flag) return else return }
			fn<int> main() {
			    stop(true)
			    stop(false)
			    def result = 0
			    for (def i <- 0..4) {
			        if (i == 0) continue
			        if (i == 3) break
			        if (i == 1) result += choose(i) else result += choose(i)
			    }
			    if (false) result = 100 else result += value(true) + value(false)
			    if (true) if (false) result = 100 else result += 9
			    if (choose(0) != 30) return 1
			    return result
			}
		"""), directory.resolve("unbraced"))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS))
			assertEquals(42, process.exitValue(), process.inputStream.readBytes().toString(Charsets.UTF_8))
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
