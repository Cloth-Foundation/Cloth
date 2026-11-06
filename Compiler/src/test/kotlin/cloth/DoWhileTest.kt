package cloth

import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoWhileTest {

	private fun parser(source: String) = Parser(Lexer(SourceFile("do-while.co", source.trimIndent())).scanTokens())
	private fun execute(path: Path, status: Int = 0): String {
		val process = ProcessBuilder(path.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Do-while executable timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private fun run(directory: Path, source: String, status: Int = 0) = execute(LLVMEmitter().compile(parser(source).parse(), directory), status)

	@Test
	fun `first entry precedes a false test and continues reach every trailing test`(@TempDir directory: Path) {
		assertEquals("FBCBCBC" + processCompletion(), run(directory, """
			fn<int> main() {
			    do print("F") while (false)
			    def bodies = 0
			    def checks = 0
			    def<bool> again = { print("C"); checks++; return checks < 3 }
			    do {
			        print("B")
			        bodies++
			        if (bodies == 2) continue
			    } while (again)
			    return bodies + checks - 6
			}
		"""))
	}

	@Test
	fun `break and body return skip effectful failing trailing tests`(@TempDir directory: Path) {
		assertEquals("B" + processCompletion(), run(directory, """
			fn<int> zero() { print("bad"); return 0 }
			fn<int> value() { do return 7 while (1 / zero() > 0) }
			fn<int> main() {
			    do { print("B"); break } while (1 / zero() > 0)
			    return value() - 7
			}
		"""))
	}

	@Test
	fun `condition and body returns exit their actual functions and deferred blocks`(@TempDir directory: Path) {
		assertEquals(processCompletion(), run(directory, """
			fn<int> first() { do continue while (if (true) { return 3 }) }
			fn<int> second() { do return 7 while (false) }
			fn<int> main() {
			    def<int> block = { do return 2 while (false) }
			    return first() + second() + block - 12
			}
		"""))
	}

	@Test
	fun `nonfallthrough bodies and unused tests emit verified terminated blocks`() {
		val ir = LLVMEmitter().emit(parser("""
			fn<int> spin() { do continue while (true) }
			fn<int> blocked() { do while (true) {} while (if (true) { return 7 }) }
			fn<int> value() { do return 1 while (false) }
			fn<int> choose(bool flag) {
			    def result = if (flag) { do return 2 while (false) } else { 3 }
			    return result
			}
		""").parse())
		assertContains(ir, "do_body")
		assertContains(ir, "unreachable")
	}

	@Test
	fun `nested do while and foreach bodies keep nearest jumps and updates`(@TempDir directory: Path) {
		assertEquals(processCompletion(), run(directory, """
			fn<int> main() {
			    def n = 0
			    def sum = 0
			    do {
			        n++
			        for (def value <- [1, 2, 3]) {
			            do {
			                if (value == 2) break
			                sum += value
			                break
			            } while (true)
			            if (value == 3) continue
			        }
			        if (n == 2) break
			        continue
			    } while (n < 3)
			    return sum - 8
			}
		"""))
	}

	@Test
	fun `outward condition continues use enclosing range and do destinations`(@TempDir directory: Path) {
		assertEquals(processCompletion(), run(directory, """
			fn<int> main() {
			    def visits = 0
			    def completed = 0
			    for (def i <- 0..3) {
			        do visits++ while (if (i == 1) { continue } else { false })
			        completed++
			    }
			    def n = 0
			    def checks = 0
			    def<bool> again = { checks++; return n < 3 }
			    do {
			        n++
			        do {} while (if (n < 3) { continue } else { false })
			        completed++
			    } while (again)
			    return visits + completed + checks - 9
			}
		"""))
	}

	@Test
	fun `body-established nullable and metadata facts match executed values`(@TempDir directory: Path) {
		assertEquals(processCompletion(), run(directory, """
			fn<int> main() {
			    def<int?> n = null
			    do n = (n ?: 0) + 1 while (n < 3)
			    def info = string::type
			    do info = int::type while (info::width < 0)
			    return n - 3 + (info::width -> int) - 32
			}
		"""))
	}

	@Test
	fun `buffered returns and body publication survive later loop regions`(@TempDir directory: Path) {
		assertEquals(processCompletion(), run(directory, """
			fn<string[]> words() { do return [#"value {2}", "é中😀"] while (false) }
			fn<int> main() {
			    def saved = ["", ""]
			    do { saved = words(); break } while (true)
			    do { def local = words() } while (false)
			    if (saved[0] != "value 2" || saved[1] != "é中😀") return 1
			    return 0
			}
		"""))
	}

	@Test
	fun `condition failure follows the first body and body failure skips the condition`(@TempDir directory: Path) {
		val condition = run(directory.resolve("condition"), "fn<int> zero() { print(\"C\"); return 0 }\nfn main() { do print(\"B\") while (1 / zero() > 0); print(\"bad\") }", 1)
		val body = run(directory.resolve("body"), "fn<int> zero() { print(\"C\"); return 0 }\nfn<bool> again() { print(\"bad\"); return false }\nfn main() { do { print(\"B\"); println(1 / zero()) } while (again()) }", 1)
		for (output in listOf(condition, body)) {
			assertTrue(output.startsWith("BC"), output)
			assertContains(output, "do-while.co:")
			assertFalse(output.contains("bad"))
			assertFalse(output.contains("PEC:"))
		}
	}

	@Test
	fun `do loops preserve post-loop values and remain silent without explicit output`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("value"), "def i = 0; do i++ while (i < 3); println(i)", "3\n")
		assertMainOutput(directory.resolve("silent"), "do {} while (false)")
	}

	@Test
	fun `one shared execution fixture runs on Windows x64 and x86`(@TempDir directory: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val ast = parser("fn<int> main() { def n = 0; do { n++; if (n < 3) continue; break } while (n < 10); println(n); return n - 3 }").parse()
		for ((index, abi) in listOf(NativeCABI.native(), NativeCABI.forTarget("i686-pc-windows-msvc")).withIndex()) {
			assertEquals("3\n" + processCompletion().replace("\r\n", "\n"), execute(LLVMEmitter(abi).compile(ast, directory.resolve("fixture_$index"))).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `maintained example executes on Windows x64 and x86`(@TempDir directory: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val ast = parser(Files.readString(Path.of("examples", "DoWhile.co"))).parse()
		val expected = "first=1\nrepeated=3, tests=3\nsingle=2\nvisited=5, sum=8, tests=4\n" + processCompletion().replace("\r\n", "\n")
		for ((index, triple) in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc").withIndex()) {
			val executable = LLVMEmitter(NativeCABI.forTarget(triple)).compile(ast, directory.resolve("example_$index"))
			assertEquals(expected, execute(executable).replace("\r\n", "\n"), triple)
		}
	}

	@Test
	fun `one shared loop AST verifies and emits objects across supported profiles`(@TempDir directory: Path) {
		val ast = parser("fn<int> count(int n, int[][] values) { def sum = 0; do { n--; if (n == 2) continue; for (def row <- values) { if (row[0] == 0) break; sum += row[0] }; if (sum > 100) break } while (n > 0); return sum }").parse()
		val example = parser(Files.readString(Path.of("examples", "DoWhile.co"))).parse()
		for ((index, triple) in listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin").withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emit(ast), "do_test")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("do_while_$index.o"))) > 0)
			assertContains(emitter.emit(example), "do_test")
			assertTrue(Files.size(emitter.compileObject(example, directory.resolve("do_while_example_$index.o"))) > 0)
		}
	}
}
