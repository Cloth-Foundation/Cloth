package cloth.emitter

import cloth.lexer.Lexer
import cloth.parser.Parser
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NumericEmissionTest {

	private fun parse(source: String) = Parser(Lexer(source).scanTokens()).parse()

	@Test
	fun `IR prints the correct numeric type and returns an integer success status`() {
		val emitter = LLVMEmitter()
		val integerIR = emitter.emit(parse("5 / 2"), forcePrint = true)
		assertTrue(integerIR.contains("%d\\0A"), integerIR)
		assertTrue(integerIR.contains("i32 2)"), integerIR)
		assertTrue(integerIR.contains("ret i32 0"), integerIR)

		val floatIR = emitter.emit(parse("5 / 2.0"), forcePrint = true)
		assertTrue(floatIR.contains("%.17g\\0A"), floatIR)
		assertTrue(floatIR.contains("double 2.500000e+00)"), floatIR)
		assertTrue(floatIR.contains("ret i32 0"), floatIR)

		val silentIR = emitter.emit(parse("2.5"))
		assertTrue(silentIR.contains("ret i32 0"), silentIR)
		assertTrue(!silentIR.contains("@printf"), silentIR)
	}

	@Test
	fun `native arithmetic preserves integer behavior and promotes mixed operands`(@TempDir directory: Path) {
		val cases = listOf(
			"2 + 3" to "5",
			"2 - 3" to "-1",
			"2 * 3" to "6",
			"5 / 2" to "2",
			"-5 / 2" to "-2",
			"-5 % 2" to "-1",
			"-(-3)" to "3",
			"1.25 + 2.5" to "3.75",
			"1.25 - 2.5" to "-1.25",
			"1.25 * 2.5" to "3.125",
			"5.0 / 2.0" to "2.5",
			"-5.5 % 2.0" to "-1.5",
			"+(-2.5)" to "-2.5",
			"-(-2.5)" to "2.5",
			"-0.0" to "-0",
			"2 + 0.5" to "2.5",
			"0.5 + 2" to "2.5",
			"2 - 0.5" to "1.5",
			"0.5 - 2" to "-1.5",
			"2 * 0.5" to "1",
			"0.5 * 2" to "1",
			"5 / 2.0" to "2.5",
			"5.0 / 2" to "2.5",
			"5 % 2.5" to "0",
			"5.5 % 2" to "1.5",
			"-3 + 0.5" to "-2.5",
			"5 / 2 + 0.5" to "2.5",
			"1 + -2.5 * (3 + 4.0)" to "-16.5",
			"2147483647 + 0.5" to "2147483647.5",
			"1.5\n2 + 3" to "5",
			"2 + 3\n1.5" to "1.5",
			"\n" to "0"
		)
		val emitter = LLVMEmitter()
		for ((index, case) in cases.withIndex()) {
			val (source, expected) = case
			val executable = emitter.compile(parse(source), directory.resolve("arithmetic-$index"))
			val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
			try {
				assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Timed out: $source")
				val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
				assertEquals(0, process.exitValue(), "$source: $output")
				assertEquals(expected, output, source)
			} finally {
				if (process.isAlive) process.destroyForcibly()
			}
		}
	}
}
