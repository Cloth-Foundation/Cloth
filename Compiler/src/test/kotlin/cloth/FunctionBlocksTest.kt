package cloth

import cloth.diagnostics.*
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.FunctionBlockExpression
import cloth.parser.statement.BindingDeclaration
import cloth.semantic.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FunctionBlocksTest {
	private fun parse(source: String) = Parser(Lexer(SourceFile("blocks.co", source.trimIndent())).scanTokens()).parse()
	private fun analyze(source: String) = FunctionAnalyzer().analyze(parse(source))
	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic {
		val diagnostic = assertFailsWith<ClothDiagnostic> { analyze(source) }
		assertEquals(code, diagnostic.diagnosticCode)
		assertLocated(diagnostic)
		return diagnostic
	}
	private fun native(directory: Path, name: String, source: String) {
		val executable = LLVMEmitter().compile(parse(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), name)
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(0, process.exitValue(), output)
			assertEquals("", output)
		} finally { if (process.isAlive) process.destroyForcibly() }
	}

	@Test fun `block syntax preserves the annotation body source and binding form`() {
		for (assignment in listOf("=", ":=")) {
			val unit = parse("fn test() {\n def<int<64>> x $assignment {\n return 10000000000\n }\n}")
			val binding = ((unit.declarations.single() as FunctionDeclaration).body as BlockBody).statements.single() as BindingDeclaration
			val block = assertIs<FunctionBlockExpression>(binding.initializer)
			assertSame(binding.type, block.declaration.returnType)
			assertEquals("int<64>", block.declaration.returnType?.spelling)
			assertEquals(emptyList(), block.declaration.parameters)
			assertEquals(2, block.span.startLine)
			assertEquals(4, block.span.endLine)
			assertEquals(assignment == "=", binding.mutable)
			analyze("fn test() { def<int<64>> x $assignment { return 10000000000 } }")
		}
	}

	@Test fun `return type is mandatory even for unused blocks and void bodies`() {
		for (body in listOf("return 1", "return", "", "unknown()")) for (assignment in listOf("=", ":=")) {
			failure("fn test() { def x $assignment { $body } }", DiagnosticCode.BLOCK_RETURN_TYPE_REQUIRED)
		}
	}

	@Test fun `block returns use their own strict contract and never imply an enclosing return`() {
		failure("fn test() { def<int> x = { return true } }", DiagnosticCode.RETURN_TYPE)
		failure("fn<int> test() { def<int> x = { return 1 } }", DiagnosticCode.MISSING_RETURN)
		failure("fn test() { def<int> x = { return } }", DiagnosticCode.RETURN_VALUE_REQUIRED)
		failure("fn test() { def<int> x = { 1 } }", DiagnosticCode.MISSING_RETURN)
		failure("fn test(bool flag) { def<int> x = { if (flag) { return 1 } } }", DiagnosticCode.MISSING_RETURN)
		failure("fn test() {\n def n = 1\n def<int<64>> x = { return n }\n}", DiagnosticCode.RETURN_TYPE)
		failure("fn test() { def<float<64>> x = { return 1.5 } }", DiagnosticCode.RETURN_TYPE)
		failure("fn test() { def<unsigned int<8>> x = { return 256 } }", DiagnosticCode.INTEGER_RANGE)
		analyze("fn<string> test() {\n def<int> x = { return 1 }\n return \"value\"\n}")
		analyze("fn<int<64>> test() {\n def<int<64>> x = { return 10000000000 }\n return x\n}")
	}

	@Test fun `block return diagnostics identify the block contract and fail at the first error`() {
		val mismatch = failure("fn test() {\n def<int> x = {\n return true\n return \"wrong\"\n }\n}", DiagnosticCode.RETURN_TYPE)
		assertEquals(listOf(3, 2), mismatch.sourceLabels.map { it.span.startLine })
		assertContains(mismatch.summary, "x")
		assertFalse(mismatch.summary.contains("string"))
		val missing = failure("fn test() {\n def<int> x = {\n 1\n }\n}", DiagnosticCode.MISSING_RETURN)
		assertEquals(listOf(4, 2), missing.sourceLabels.map { it.span.startLine })
		val reports = StringBuilder()
		DiagnosticReporter(reports).report(missing)
		assertContains(reports.toString(), "C3033")
		assertContains(reports.toString(), "blocks.co")
	}

	@Test fun `local scope captures visible bindings without leaking declarations or self recursion`() {
		analyze("fn test(int n) {\n def<int> x = {\n def n = n + 1\n return n\n }\n}")
		failure("fn test() {\n def<int> x = {\n def hidden = 1\n return hidden\n }\n hidden\n}", DiagnosticCode.UNKNOWN_NAME)
		failure("fn test() { def<int> x = { return x } }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn test() { def<int> x = { return later }\n def later = 1\n}", DiagnosticCode.UNKNOWN_NAME)
		failure("fn test(final int n) { def<int> x = {\n n += 1\n return n\n} }", DiagnosticCode.IMMUTABLE_BINDING)
		failure("fn test() { def<int> x = {\n def n = 1\n def n = 2\n return n\n} }", DiagnosticCode.DUPLICATE_BINDING)
	}

	@Test fun `block names supply typed results to overloads and cannot be called or assigned`() {
		analyze("fn take(int value) {}\nfn take(int<64> value) {}\nfn test() {\n def<int> x = { return 1 }\n take(x)\n}")
		failure("fn take(int<64> value) {}\nfn test() {\n def<int> x = { return 1 }\n take(x)\n}", DiagnosticCode.NO_MATCHING_CALL)
		for (call in listOf("x()", "x(1)")) failure("fn test() {\n def<int> x = { return 1 }\n $call\n}", DiagnosticCode.NOT_CALLABLE)
		for (write in listOf("x = 2", "x += 1", "x++", "x--")) failure("fn test() {\n def<int> x = { return 1 }\n $write\n}", DiagnosticCode.BLOCK_ASSIGNMENT)
	}

	@Test fun `void blocks use ordinary void return rules`() {
		analyze("fn test() {\n def<void> x = {}\n x\n}")
		analyze("fn cleanup() {}\nfn test() {\n def<void> x = { return cleanup() }\n x\n}")
		failure("fn test() { def<void> x = { return 1 } }", DiagnosticCode.VALUE_IN_VOID)
		failure("fn test() {\n def<void> x = {}\n def saved = x\n}", DiagnosticCode.VOID_VALUE)
		failure("fn take(int x) {}\nfn test() {\n def<void> x = {}\n take(x)\n}", DiagnosticCode.NO_MATCHING_CALL)
	}

	@Test fun `declaration has no return flow and saved results are ordinary values`() {
		val model = analyze("fn<int> test() {\n def<int> x = { return 1 }\n def saved := x\n return saved\n}")
		val body = model.declarations.functions.single().declaration.body as BlockBody
		val block = body.statements[0] as BindingDeclaration
		assertEquals(ControlFlow.CONTINUE, model.controlFlow.of(block))
		assertEquals(ControlFlow.RETURN, model.controlFlow.of((block.initializer as FunctionBlockExpression).declaration))
		assertEquals(ValueType.INT, model.typeOf(body.statements[1]))
		assertEquals(ControlFlow.RETURN, model.controlFlow.of(body))
	}

	@Test fun `every read executes once and ordinary bindings save a result`(@TempDir directory: Path) {
		native(directory, "reads", """
			fn<int> main() {
			    def n = 0
			    def<int> x = {
			        n += 1
			        def local = 10
			        return n + local
			    }
			    def<int> unused = { return [1][9] }
			    if (n != 0) { return 1 }
			    def saved := x
			    if (saved != 11 || n != 1) { return 2 }
			    def sum = x + x
			    if (sum != 25 || n != 3 || saved != 11) { return 3 }
			    n = 20
			    if (x != 31 || n != 21) { return 4 }
			    return 0
			}
		""")
	}

	@Test fun `arguments arrays casts comparisons interpolation and indices evaluate blocks in source order`(@TempDir directory: Path) {
		native(directory, "contexts", """
			fn<int> combine(int a, int b) = a * 10 + b
			fn<int> choose(int value) = value
			fn<int> choose(int<64> value) = 99
			fn<int> main() {
			    def n = 0
			    def<int> x = {
			        n += 1
			        return n
			    }
			    if (combine(x, x) != 12 || n != 2) { return 1 }
			    def a = [x, x]
			    if (a[0] != 3 || a[1] != 4 || n != 4) { return 2 }
			    if (choose(x) != 5 || n != 5) { return 3 }
			    if ((x -> int<64>) != 6 || n != 6) { return 4 }
			    if (x >= x || n != 8) { return 5 }
			    if (#"{x}:{x}" != "9:10" || n != 10) { return 6 }
			    if (x is int) {} else { return 7 }
			    n = -1
			    if ([42][x] != 42 || n != 0) { return 8 }
			    return 0
			}
		""")
	}

	@Test fun `short circuit reads skip blocks and evaluate needed reads once`(@TempDir directory: Path) {
		native(directory, "short_circuit", """
			fn<int> main() {
			    def n = 0
			    def<bool> x = {
			        n += 1
			        return true
			    }
			    false && x
			    true || x
			    def flag = false
			    flag && x
			    flag = true
			    flag || x
			    if (n != 0) { return 1 }
			    true && x
			    false || x
			    flag && x
			    if (x && x && n == 5) { return 0 }
			    return 2
			}
		""")
	}

	@Test fun `nested blocks use independent returns fresh locals and lexical captures`(@TempDir directory: Path) {
		native(directory, "nested", """
			fn<int> test(bool flag) {
			    def n = 5
			    def<int> outer := {
			        def n = n + 1
			        def<int> inner = {
			            n += 1
			            if (flag) { return n }
			            return if (flag) { return 90 } else { n + 10 }
			        }
			        def first = inner
			        def second = inner
			        return first + second
			    }
			    def first = outer
			    def second = outer
			    return first + second + n
			}
			fn<int> main() {
			    if (test(true) != 35 || test(false) != 75) { return 1 }
			    return 0
			}
		""")
	}

	@Test fun `returns inside block arguments conditions and initializers exit only the block`(@TempDir directory: Path) {
		native(directory, "early_returns", """
			fn<int> take(int a, int b) = [1][99]
			fn<int> test(bool flag) {
			    def<int> a = {
			        take(if (flag) { return 1 } else { return 2 }, [1][99])
			    }
			    def<int> b = {
			        if (if (flag) { return 3 } else { return 4 }) { return 99 }
			    }
			    def<int> c = {
			        def value = [if (flag) { return 5 } else { return 6 }, [1][99]]
			    }
			    return a + b + c
			}
			fn<int> main() = test(true) + test(false) - 21
		""")
	}

	@Test fun `block captures remain independent across recursive calls`(@TempDir directory: Path) {
		native(directory, "recursion", """
			fn<int> sum(int n) {
			    def<int> step = {
			        if (n == 0) { return 0 }
			        return n + sum(n - 1)
			    }
			    return step
			}
			fn<int> main() = sum(20) - 210
		""")
	}

	@Test fun `all primitive return representations work with block result merges`(@TempDir directory: Path) {
		for ((index, pair) in listOf("int<64>" to "10000000000", "unsigned int<8>" to "255", "bool" to "true", "char" to "'😀'", "float<16>" to "(1.5 -> float<16>)", "float<64>" to "(1.5 -> float<64>)").withIndex()) {
			val (type, value) = pair
			native(directory, "primitive_$index", """
				fn<$type> test(bool flag) {
				    def<$type> x = {
				        if (flag) { return $value }
				        return $value
				    }
				    return x
				}
				fn<int> main() {
				    if (test(true) == $value && test(false) == $value) { return 0 }
				    return 1
				}
			""")
		}
	}

	@Test fun `string and nested array results survive reads function returns and recursion`(@TempDir directory: Path) {
		native(directory, "buffers", """
			fn<string> text(int n) {
			    def<string> x = {
			        if (n == 0) { return "Cloth" + "!" }
			        return text(n - 1) + "!"
			    }
			    return x
			}
			fn<string[][]> make(int n) {
			    def<string[][]> x = {
			        def<string> label = { return #"value {n}" }
			        def values = [[label, text(n)], [label, text(0)]]
			        return values
			    }
			    return x
			}
			fn<int> main() {
			    def saved = make(3)
			    def other = make(0)
			    if (saved[0][0] != "value 3" || saved[0][1] != "Cloth!!!!" || saved[1][0] != "value 3") { return 1 }
			    if (other[0][0] != "value 0" || other[0][1] != "Cloth!") { return 2 }
			    def flag = true
			    def<int[]> sizes = { if (flag) { return [1] } else { return [2, 3] } }
			    def first = sizes
			    flag = false
			    def second = sizes
			    return first[0] + second[1] - 4
			}
		""")
	}

	@Test fun `void block reads run effects and returns stop only the block`(@TempDir directory: Path) {
		native(directory, "void", """
			fn<int> main() {
			    def n = 0
			    def<void> x = {
			        n += 1
			        if (n == 1) { return }
			        n += 10
			    }
			    x
			    if (n != 1) { return 1 }
			    x
			    return n - 12
			}
		""")
	}

	@Test fun `compiler reports block errors before producing output`(@TempDir directory: Path) {
		val source = directory.resolve("invalid.co")
		Files.writeString(source, "fn main() { def x = { return 1 } }")
		val target = directory.resolve("output/program")
		val reports = StringBuilder()
		assertEquals(1, runCompiler(arrayOf(source.toString(), target.toString()), DiagnosticReporter(reports)))
		assertContains(reports.toString(), "C3041")
		assertFalse(Files.exists(target.parent))
	}

	@Test fun `function block example compiles through the normal CLI`(@TempDir directory: Path) {
		val reports = StringBuilder()
		val target = directory.resolve("blocks_example")
		assertEquals(0, runCompiler(arrayOf("examples/FunctionBlocks.co", target.toString()), DiagnosticReporter(reports)), reports.toString())
		val executable = cloth.util.ExecutableFormat.current.executablePath(target)
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS))
			assertEquals(0, process.exitValue(), process.inputStream.readBytes().toString(Charsets.UTF_8))
		} finally { if (process.isAlive) process.destroyForcibly() }
	}
}
