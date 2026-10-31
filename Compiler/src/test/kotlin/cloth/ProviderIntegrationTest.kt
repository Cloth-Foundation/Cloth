package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProviderIntegrationTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("provider-integration.co", source.trimIndent())).scanTokens()).parse()
	private fun invalid(source: String, provider: String) {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(unit(source)) }
		assertEquals(DiagnosticCode.INVALID_METADATA_ACCESS, error.diagnosticCode)
		assertLocated(error)
		assertContains(error.summary, "::$provider")
	}

	@Test
	fun `returning branches cannot erase definite metadata on continuing paths`() {
		invalid("""
			fn test(bool leave) {
			    def info = string::type
			    if (leave) { info = int::type
			        return }
			    info::width
			}
		""", "width")
		invalid("""
			fn test(bool leave) {
			    def info = if (leave) { return } else { int::type }
			    info::element
			}
		""", "element")
		invalid("""
			fn test(bool first, bool second) {
			    def info = int::type
			    if (first) { return } else if (second) { info = string::type } else { info = string::type }
			    info::width
			}
		""", "width")
	}

	@Test
	fun `constant conditions preserve the metadata of executable branches`() {
		invalid("""
			fn test() {
			    def info = int::type
			    if (false) { info = string::type }
			    info::element
			}
		""", "element")
		invalid("""
			fn test(bool fallback) {
			    def info = int::type
			    if (false) { info = int[2]::type } else if (!false) { info = string::type } else if (fallback) { info = int[3]::type }
			    info::extent
			}
		""", "extent")
		invalid("fn main() { (if (true) { int::type } else { int[2]::type })::element }", "element")
	}

	@Test
	fun `short circuit facts ignore skipped and terminating operands`() {
		for (operator in listOf("false &&", "true ||", "true &&", "false ||")) {
			val executes = operator in listOf("true &&", "false ||")
			val initial = if (executes) "int[2]" else "string"
			val assigned = if (executes) "string" else "int"
			invalid("""
				fn test() {
				    def info = $initial::type
				    $operator (if (true) { info = $assigned::type
				        true } else { false })
				    info::width
				}
			""", "width")
		}
		for (operator in listOf("&&", "||")) invalid("""
			fn test(bool stop) {
			    def info = string::type
			    stop $operator (if (true) { info = int::type
			        return } else { return })
			    info::width
			}
		""", "width")
	}

	@Test
	fun `valid metadata follows continuing paths and preserves skipped effects`(@TempDir directory: Path) {
		native(directory, "continuing", """
			fn<int> branch(bool stop) {
			    def info = int<16>::type
			    if (stop) { info = string::type
			        return 0 }
			    if (info::width != 16) { return 1 }
			    def chosen = if (stop) { return 0 } else { int[3][]::type }
			    if (chosen::element != int[]::type || chosen::extent != 3) { return 2 }
			    return 0
			}
			fn<int> logical(bool stop) {
			    def info = int<8>::type
			    def<bool> change = { info = string::type
			        return true }
			    false && change
			    true || change
			    if (info::width != 8) { return 1 }
			    stop && (if (true) { info = string::type
			        return 0 } else { return 0 })
			    if (info::width != 8) { return 2 }
			    return 0
			}
			fn<int> main() {
			    if (branch(true) != 0 || branch(false) != 0 || logical(true) != 0 || logical(false) != 0) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `dynamic joins still check invalid metadata at runtime`(@TempDir directory: Path) {
		native(directory, "dynamic", """
			fn<unsigned int<64>> inspect(bool numeric) {
			    def info = if (numeric) { int::type } else { string::type }
			    return info::width
			}
			fn main() { inspect(false) }
		""", "Type metadata ::width requires")
	}

	@Test
	fun `provider chains retain value copies static types and shared Unicode formatting`(@TempDir directory: Path) {
		native(directory, "chains", """
			fn<typeinfo> identity(typeinfo info) = info
			fn<int> main() {
			    def info = unsigned int<16>[3][]::type
			    def independent = info::element
			    info = float<64>::type
			    if (independent != unsigned int<16>[]::type || independent::element::width != 16) { return 1 }
			    if (identity(info)::str::size != #"{info}"::size || info::width::str != "64") { return 2 }
			    if ("é😀\0"::str::size::type != unsigned int<64>::type || '😀'::str::size != 1) { return 3 }
			    def text = #"{independent} {'😀'}"
			    if (text != "unsigned int<16>[] 😀" || text::size != 20) { return 4 }
			    return 0
			}
		""")
	}

	private fun native(directory: Path, name: String, source: String, expectedError: String? = null) {
		val executable = LLVMEmitter().compile(unit(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "$name timed out")
			val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
			if (expectedError == null) {
				assertEquals(0, process.exitValue(), "$name: $output"); assertEquals("", output)
			} else {
				assertNotEquals(0, process.exitValue(), name); assertContains(output, expectedError)
			}
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
