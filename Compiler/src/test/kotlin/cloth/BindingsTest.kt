package cloth

import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.BooleanLiteral
import cloth.parser.statement.AssignmentStatement
import cloth.parser.statement.BindingDeclaration
import cloth.semantic.SemanticAnalyzer
import cloth.semantic.SemanticException
import cloth.token.Operators
import cloth.token.TokenType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BindingsTest {

	private fun parse(source: String) = Parser(Lexer(source.trimIndent()).scanTokens()).parse()

	@Test
	fun `declarations preserve explicit types and binding mutability`() {
		val ast = parse("""
			def<int> x = 10
			def<int> original := x
			x = x + 5
		""")
		val mutable = assertIs<BindingDeclaration>(ast.statements[0])
		val immutable = assertIs<BindingDeclaration>(ast.statements[1])
		assertEquals("int", assertNotNull(mutable.type).name.lexeme)
		assertEquals("x", mutable.name.lexeme)
		assertTrue(mutable.mutable)
		assertFalse(immutable.mutable)
		assertIs<AssignmentStatement>(ast.statements[2])
		SemanticAnalyzer().analyze(ast)
	}

	@Test
	fun `inferred declarations omit the annotation and preserve mutability`() {
		val ast = parse("""
			def x = 10
			def y := x + 2
			x = y + 5
		""")
		val mutable = assertIs<BindingDeclaration>(ast.statements[0])
		val immutable = assertIs<BindingDeclaration>(ast.statements[1])
		assertNull(mutable.type)
		assertNull(immutable.type)
		assertTrue(mutable.mutable)
		assertFalse(immutable.mutable)
		SemanticAnalyzer().analyze(ast)
	}

	@Test
	fun `angle parser supports caller defined contents and nesting`() {
		val parser = Parser(Lexer("<outer<inner>>").scanTokens())
		fun parseName(): String {
			val name = parser.expect(TokenType.Identifier, "a name").lexeme
			return if (parser.peek().lexeme == Operators.LESS.character) {
				val nested = parser.delimited.angles { parseName() }
				"$name($nested)"
			} else name
		}
		assertEquals("outer(inner)", parser.delimited.angles { parseName() })
		assertEquals(TokenType.EOF, parser.peek().type)
	}

	@Test
	fun `malformed binding syntax is rejected`() {
		val cases = listOf(
			"def x",
			"def x =",
			"def y :=",
			"def = 10",
			"def<> x = 10",
			"def<int x = 10",
			"def<int>> x = 10",
			"def<int> = 10",
			"def<int> x 10",
			"def<int> x =",
			"def<int> x = (1 + 2",
			"def<int> x = 1 def<int> y = 2",
			"x := 10"
		)
		for (source in cases) {
			assertFailsWith<SyntaxException>(source) { parse(source) }
		}
	}

	@Test
	fun `inferred bindings keep their initializer type and ordinary binding rules`() {
		val cases = listOf(
			"def x = 10\nx = 1.5" to "Cannot assign 'float' to 'int'",
			"def x = 10\ndef y := x + 2\nx = y / 2.0" to "Cannot assign 'float' to 'int'",
			"def x = 10\nx = x + 1\nx = 1.5" to "Cannot assign 'float' to 'int'",
			"def x = 1.5\nx = 10" to "Cannot assign 'int' to 'float'",
			"def x = 1 + 0.5\ndef y = -x\ny = 2" to "Cannot assign 'int' to 'float'",
			"def x := 10\nx = 20" to "Cannot reassign immutable binding 'x'",
			"def x := 1.5\nx = 2.5" to "Cannot reassign immutable binding 'x'",
			"def x = x + 1" to "Unknown binding 'x'",
			"def x := missing + 2" to "Unknown binding 'missing'",
			"def x = 1\ndef<int> x = 2" to "already declared",
			"def<int> x = 1\ndef x := 2" to "already declared",
			"def x = 1.5\ndef<int> y = x" to "Cannot assign 'float' to 'int'"
		)
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) {
				// The public emission path must reject mismatches before generating LLVM.
				LLVMEmitter().emit(parse(source))
			}
			assertContains(failure.message.orEmpty(), diagnostic)
			assertContains(failure.message.orEmpty(), " at ")
		}
	}

	@Test
	fun `invalid binding uses report semantic errors with locations`() {
		val cases = listOf(
			"missing + 1" to "Unknown binding 'missing'",
			"missing = 1" to "Unknown binding 'missing'",
			"def<int> x = x + 1" to "Unknown binding 'x'",
			"def<int> x = later\ndef<int> later = 1" to "Unknown binding 'later'",
			"def<int> x = 1\ndef<int> x = 2" to "already declared",
			"def<int> x := 1\nx = 2" to "Cannot reassign immutable binding 'x'",
			"def<int> x = 1.5" to "Cannot assign 'float' to 'int'",
			"def<int> x = 1\nx = x + 0.5" to "Cannot assign 'float' to 'int'",
			"def<bool> x = 1" to "Cannot assign 'int' to 'bool'",
			"def<Widget> x = 1" to "not supported yet"
		)
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) {
				SemanticAnalyzer().analyze(parse(source))
			}
			assertContains(failure.message.orEmpty(), diagnostic)
			assertContains(failure.message.orEmpty(), " at ")
		}
	}

	@Test
	fun `emitter checks bindings before emitting LLVM`() {
		assertFailsWith<SemanticException> {
			LLVMEmitter().emit(parse("def<int> x := 1\nx = 2"))
		}
	}

	@Test
	fun `analysis state belongs to one program`() {
		val analyzer = SemanticAnalyzer()
		analyzer.analyze(parse("def<int> x = 1"))
		analyzer.analyze(parse("def<int> x := 2"))
		assertFailsWith<SemanticException> { analyzer.analyze(parse("x")) }
	}

	@Test
	fun `native bindings read current values and preserve integer copies`(@TempDir directory: Path) {
		val source = """
			def<int> x = 10
			def<int> original := x
			def<int> y = 3
			x = (x + y) * 2
			y = -x + original % y
			x = x / 2
			original * 100 + x * 10 + y
		"""
		val executable = LLVMEmitter().compile(parse(source), directory.resolve("bindings"))
		assertOutput(executable, "1105")
	}

	@Test
	fun `bindings and existing math examples compile through the CLI`(@TempDir directory: Path) {
		for ((file, expected) in listOf("Bindings.co" to "76.25", "Math.co" to "6689", "Booleans.co" to "true")) {
			val output = directory.resolve(file.removeSuffix(".co"))
			// Absolute paths exercise source selection and the separate output argument.
			main(arrayOf(Path.of(file).toAbsolutePath().toString(), output.toString()))
			val executable = cloth.util.ExecutableFormat.current.executablePath(output)
			assertOutput(executable, expected)
		}
	}

	@Test
	fun `integer bindings can participate in existing floating point arithmetic`(@TempDir directory: Path) {
		val executable = LLVMEmitter().compile(parse("def<int> x = 9\nx / 2.0"), directory.resolve("mixed"))
		assertOutput(executable, "4.5")
	}

	@Test
	fun `inferred integer expressions support reassignment and copy values`(@TempDir directory: Path) {
		val source = """
			def x = 10
			def y := x + 2
			def half = y / 5
			x = -(-x) + half
			half = half * 3
			x * 100 + y * 10 + half
		"""
		val executable = LLVMEmitter().compile(parse(source), directory.resolve("inferred_int"))
		assertOutput(executable, "1326")
	}

	@Test
	fun `inferred floats preserve storage types across reads and reassignment`(@TempDir directory: Path) {
		val source = """
			def scale = 1.5
			def original := scale
			def<int> count := 3
			def mixed := count + scale
			scale = (scale + 0.75)
			original + mixed + scale
		"""
		val executable = LLVMEmitter().compile(parse(source), directory.resolve("inferred_float"))
		assertOutput(executable, "8.25")
	}

	@Test
	fun `explicit floats enforce declared types and immutability`() {
		val cases = listOf(
			"def<float> x = 1" to "Cannot assign 'int' to 'float'",
			"def<float> x := 1" to "Cannot assign 'int' to 'float'",
			"def x = 10\ndef<float> y = x" to "Cannot assign 'int' to 'float'",
			"def<float> x = 1.5\nx = 2" to "Cannot assign 'int' to 'float'",
			"def<float> x = 1.5\nx = 2.0\nx = 2 / 2" to "Cannot assign 'int' to 'float'",
			"def<float> x := 1.5\nx = 2.0" to "Cannot reassign immutable binding 'x'",
			"def<float> x = 1.5\ndef<int> y = x" to "Cannot assign 'float' to 'int'",
			"def<float> x = 1.5\ndef y = 1\ny = x" to "Cannot assign 'float' to 'int'",
			"def<float> x = x + 1.0" to "Unknown binding 'x'",
			"def x = 1.5\ndef<float> x = 2.0" to "already declared"
		)
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) {
				LLVMEmitter().emit(parse(source))
			}
			assertContains(failure.message.orEmpty(), diagnostic)
			assertContains(failure.message.orEmpty(), " at ")
		}
	}

	@Test
	fun `explicit and inferred floats share arithmetic and preserve copied values`(@TempDir directory: Path) {
		val source = """
			def<float> scale = 1.5
			def offset = 0.25
			def<float> snapshot := scale
			def mixed := scale + offset
			def<float> fromInferred := mixed
			scale = mixed * 2
			offset = scale / 2
			snapshot + fromInferred + scale + offset
		"""
		val executable = LLVMEmitter().compile(parse(source), directory.resolve("explicit_float"))
		assertOutput(executable, "8.5")
	}

	@Test
	fun `boolean literals support explicit and inferred bindings`() {
		val ast = parse("""
			def<bool> alive = true
			def stopped := (false)
			alive = stopped
		""")
		val explicit = assertIs<BindingDeclaration>(ast.statements[0])
		val inferred = assertIs<BindingDeclaration>(ast.statements[1])
		assertEquals("bool", assertNotNull(explicit.type).name.lexeme)
		assertEquals(true, assertIs<BooleanLiteral>(explicit.initializer).value)
		assertTrue(explicit.mutable)
		assertNull(inferred.type)
		assertEquals(false, assertIs<BooleanLiteral>(inferred.initializer).value)
		assertFalse(inferred.mutable)
		SemanticAnalyzer().analyze(ast)
	}

	@Test
	fun `booleans reject numeric conversions arithmetic and immutable assignment`() {
		val cases = listOf(
			"def<bool> x = 0" to "Cannot assign 'int' to 'bool'",
			"def<bool> x = 1.0" to "Cannot assign 'float' to 'bool'",
			"def<int> x = true" to "Cannot assign 'bool' to 'int'",
			"def<float> x = false" to "Cannot assign 'bool' to 'float'",
			"def x = true\nx = 1" to "Cannot assign 'int' to 'bool'",
			"def x = false\nx = 1.0" to "Cannot assign 'float' to 'bool'",
			"def x = 1\nx = false" to "Cannot assign 'bool' to 'int'",
			"def x = 1.0\nx = true" to "Cannot assign 'bool' to 'float'",
			"def<bool> x := true\nx = false" to "Cannot reassign immutable binding 'x'",
			"def x := false\nx = true" to "Cannot reassign immutable binding 'x'",
			"def x = true\ndef x = false" to "already declared",
			"def x = x" to "Unknown binding 'x'",
			"def x = later\ndef later = true" to "Unknown binding 'later'"
		) + listOf("+true", "-false").map { it to "requires a numeric operand" } +
			listOf("+", "-", "*", "/", "%").flatMap { operator ->
				listOf("true $operator false", "true $operator 1", "1.0 $operator false", "1 $operator true")
					.map { it to "requires numeric operands" }
			}
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) { LLVMEmitter().emit(parse(source)) }
			assertContains(failure.message.orEmpty(), diagnostic)
			assertContains(failure.message.orEmpty(), " at ")
		}
	}

	@Test
	fun `each boolean binding owns a byte even with interleaved numeric bindings`() {
		for (count in listOf(1, 2, 8, 9, 16, 17)) {
			val source = (0 until count).joinToString("\n") {
				"def flag$it = true\ndef number$it = $it\ndef scale$it = 1.5"
			} + "\nflag0 = flag${count - 1}\nflag0"
			val ir = LLVMEmitter().emit(parse(source))
			assertEquals(count, Regex("alloca i8\\b").findAll(ir).count(), ir)
			assertFalse(ir.contains("alloca i1,"), ir)
			assertContains(ir, "to i1")
			assertContains(ir, "zext i1")
			assertFalse(Regex("\\b(lshr|shl|and|or) i8\\b").containsMatchIn(ir), ir)
		}
	}

	@Test
	fun `boolean updates preserve independent bindings and copied values`(@TempDir directory: Path) {
		val source = """
			def<bool> a = true
			def<int> count = 7
			def b = false
			def<float> scale = 1.5
			def c = true
			def d = false
			def e = true
			def f = false
			def g = true
			def h = false
			def i = true
			def snapshot := a
			a = false
			b = true
			c = b
			d = a
			e = false
			f = true
			g = false
			h = true
			i = false
			h = h
		""".trimIndent()
		val expected = listOf(false, true, true, false, false, true, false, true, false, true)
		for ((index, name) in listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "snapshot").withIndex()) {
			val executable = LLVMEmitter().compile(parse("$source\n$name"), directory.resolve("independent_$name"))
			assertOutput(executable, expected[index].toString())
		}
	}

	@Test
	fun `boolean literals and last assignments print as boolean values`(@TempDir directory: Path) {
		for ((index, source) in listOf("true", "false", "def x = true\nx = false", "def<bool> x = false\nx = true").withIndex()) {
			val executable = LLVMEmitter().compile(parse(source), directory.resolve("bool_$index"))
			assertOutput(executable, (index == 0 || index == 3).toString())
		}
	}

	private fun assertOutput(executable: Path, expected: String) {
		assertTrue(Files.isRegularFile(executable))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Executable timed out")
			val output = process.inputStream.bufferedReader().use { it.readText().trim() }
			assertEquals(0, process.exitValue(), output)
			assertEquals(expected, output)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
