package cloth

import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.statement.BindingGroupDeclaration
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MultiBindingsTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("multi-bindings.co", source.trimIndent())).scanTokens()).parse()

	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic {
		val diagnostic = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(parse(source)) }
		assertEquals(code, diagnostic.diagnosticCode)
		assertLocated(diagnostic)
		return diagnostic
	}

	private fun native(directory: Path, source: String, expectedOutput: String = processCompletion()) {
		val executable = LLVMEmitter().compile(parse(source), directory.resolve("multi-bindings"))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS))
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(0, process.exitValue(), output)
			assertEquals(expectedOutput, output)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	@Test
	fun `all names infer int and retain ordinary mutability`() {
		val unit = parse("fn test() { def x, y, z = 10 }")
		val group = assertIs<BindingGroupDeclaration>(((unit.declarations.single() as FunctionDeclaration).body as BlockBody).statements.single())
		assertEquals(listOf("x", "y", "z"), group.targets.map { it.token.lexeme })
		val model = FunctionAnalyzer(nativeTestLayout).analyze(unit)
		val declarations = model.declarations.symbolsOf(group).filterNotNull()
		assertEquals(listOf("x", "y", "z"), declarations.map { it.name.lexeme })
		for (declaration in declarations) {
			assertNull(declaration.declaredType)
			assertTrue(declaration.mutable)
			assertEquals(ValueType.INT, model.typeOf(declaration))
		}
		FunctionAnalyzer(nativeTestLayout).analyze(parse("fn test() {\n def x, y, z = 10\n x += 1\n y = 20\n z++\n}"))
	}

	@Test
	fun `other initializer types propagate to every name`() {
		for (initializer in listOf("1.5", "true", "'a'", "\"hello\"", "[1, 2]", "int<64>::type")) {
			val unit = parse("fn test() { def x, y, z = $initializer }")
			val group = assertIs<BindingGroupDeclaration>(((unit.declarations.single() as FunctionDeclaration).body as BlockBody).statements.single())
			val model = FunctionAnalyzer(nativeTestLayout).analyze(unit)
			val declarations = model.declarations.symbolsOf(group).filterNotNull()
			val first = model.typeOf(declarations.first())
			for (declaration in declarations) assertEquals(first, model.typeOf(declaration))
		}
	}

	@Test
	fun `annotations and immutable assignment apply to every name`() {
		for (assignment in listOf("=", ":=")) {
			val unit = parse("fn test() { def<unsigned int<8>> x, y, z $assignment 255 }")
			val group = assertIs<BindingGroupDeclaration>(((unit.declarations.single() as FunctionDeclaration).body as BlockBody).statements.single())
			val model = FunctionAnalyzer(nativeTestLayout).analyze(unit)
			val declarations = model.declarations.symbolsOf(group).filterNotNull()
			for (declaration in declarations) {
				assertEquals(assignment == "=", declaration.mutable)
				assertEquals("unsigned int<8>", model.typeOf(declaration).spelling)
			}
		}
		for (name in listOf("x", "y", "z")) {
			failure("fn test() {\n def x, y, z := 10\n $name = 20\n}", DiagnosticCode.IMMUTABLE_BINDING)
			failure("fn test() {\n def x, y, z = 10\n $name = true\n}", DiagnosticCode.TYPE_MISMATCH)
		}
		failure("fn test() { def<unsigned int<8>> x, y = 256 }", DiagnosticCode.INTEGER_RANGE)
		failure("fn test() { def<int<64>> x, y = true }", DiagnosticCode.TYPE_MISMATCH)
	}

	@Test
	fun `duplicate names report the first duplicate with both name locations`() {
		val diagnostic = failure("fn test() { def x, y, x, y = 10 }", DiagnosticCode.DUPLICATE_BINDING)
		assertContains(diagnostic.summary, "'x'")
		assertEquals(2, diagnostic.sourceLabels.size)
		assertEquals(setOf(17, 23), diagnostic.sourceLabels.map { it.span.startColumn }.toSet())
		failure("fn test(int y) { def x, y, z = 10 }", DiagnosticCode.DUPLICATE_BINDING)
		failure("fn test() {\n def y = 1\n def x, y, z = 10\n}", DiagnosticCode.DUPLICATE_BINDING)
	}

	@Test
	fun `malformed lists fail at the first syntax error`() {
		for (declaration in listOf("def , x = 10", "def x, = 10", "def x,,y = 10", "def x, y", "def x, y =", "def x, y = 1 def z = 2")) {
			assertFailsWith<ClothDiagnostic>(declaration) { parse("fn test() { $declaration }") }
		}
	}

	@Test
	fun `native scalar declarations create independent values in nested scopes`(@TempDir directory: Path) {
		native(directory, """
			fn<int> main() {
				def x, y, z = 10
				x = 20
				y += 1
				if (x != 20 || y != 11 || z != 10) { return 1 }
				if (true) {
					def<bool> x, y := true
					if (!x || !y) { return 2 }
				}
				def<string> a, b = "hello"
				a += "!"
				if (a != "hello!" || b != "hello") { return 3 }
				return 0
			}
		""")
	}

	@Test
	fun `native array declarations preserve recursive value copies`(@TempDir directory: Path) {
		native(directory, """
			fn<int> main() {
				def source := [[1, 2], [3, 4]]
				def a, b, c := source
				a[0][0] = 99
				b[1][1] = 88
				if (source[0][0] != 1 || b[0][0] != 1 || c[0][0] != 1) { return 1 }
				if (source[1][1] != 4 || a[1][1] != 4 || c[1][1] != 4) { return 2 }
				return 0
			}
		""")
	}

	@Test
	fun `native function call initializer executes once for the whole list`(@TempDir directory: Path) {
		native(directory, """
			fn<int> make() {
				print("called\n")
				return 10
			}
			fn<int> main() {
				def make, y, z = make()
				if (make != 10 || y != 10 || z != 10) { return 1 }
				return 0
			}
		""", "called" + System.lineSeparator() + processCompletion())
	}

	@Test
	fun `native deferred result initializes every name once with independent array storage`(@TempDir directory: Path) {
		native(directory, """
			fn<int> main() {
				def count = 0
				def<int[]> next = {
					count += 1
					return [count, 2]
				}
				def<int[]> x, y, z = next
				if (count != 1 || x[0] != 1 || y[0] != 1 || z[0] != 1) { return 1 }
				x[0] = 99
				if (y[0] != 1 || z[0] != 1) { return 2 }
				def source = 10
				if (true) {
					def source, copied := source + 1
					if (source != 11 || copied != 11) { return 3 }
				}
				return 0
			}
		""")
	}

	@Test
	fun `native multiple function blocks retain deferred execution and their own scopes`(@TempDir directory: Path) {
		native(directory, """
			fn<int> main() {
				def count = 0
				def<int> a, b = {
					def x, y = count
					count += 1
					return x + y
				}
				if (count != 0) { return 1 }
				def first = a
				def second = b
				if (count != 2 || first != 0 || second != 2) { return 2 }
				return 0
			}
		""")
	}
}
