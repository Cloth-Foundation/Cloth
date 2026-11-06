package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import com.opencastsoftware.yvette.handlers.graphical.GraphicalReportHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperatorPublicationTest {

	private val examples = mapOf(
		"ObjectOperators" to """
			Static type: Object
			Account ID: 7
			Account 7
			Equal: true, same instance: false
			Alias: true, value check: true
			[Account 7, null, Account 7]
			Scores equal: true
			damage 1, same case as HIT: false
			Priorities equal: true
			Closed account 7
			Closed account 7
		""".trimIndent(),
		"OperandContexts" to """
			Scores: 22, 17
			Points: 16, 15
			Health: 102.5
			Player: Rin
		""".trimIndent(),
	)

	@Test
	fun `published operator examples run with exact output on native targets`(@TempDir directory: Path) {
		for ((name, output) in examples) {
			val source = Files.readString(Path.of("examples", "$name.co"))
			val expected = output + "\n" + processCompletion().replace("\r\n", "\n")
			for (abi in structRunners()) {
				assertEquals(expected, runStructProgram(abi, source, directory.resolve("${name}_${abi.architecture}")))
			}
		}
	}

	@Test
	fun `published operator examples verify and emit objects for all target profiles`(@TempDir directory: Path) {
		for (name in examples.keys) {
			val unit = structUnit(Files.readString(Path.of("examples", "$name.co")))
			for ((index, triple) in structTargetTriples.withIndex()) {
				val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
				assertContains(emitter.emitExecutable(unit), "target triple = \"$triple\"")
				assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("${name}_$index.o"))) > 0)
			}
		}
	}

	@Test
	fun `ambiguous full overload stops the CLI before a context or later error`(@TempDir directory: Path) {
		val source = directory.resolve("ambiguous.co")
		Files.writeString(source, """
			typedef<enum> Left {
			    A;
			    operator +(Right other) = 1
			    operator<Right> +() = 0
			}
			typedef<enum> Right {
			    A;
			    operator +(Left other, self) = 2
			}
			fn main() {
			    println(Left.A + Right.A)
			    println("later error" - 1)
			}
		""".trimIndent())
		val destination = directory.resolve("output/program")
		val output = StringBuilder()
		val handler = GraphicalReportHandler.builder().withColours(false).withUnicode(false).withTerminalLinks(false).withCauseChain(false).buildFor(output)
		val status = runCompiler(arrayOf(source.toString(), destination.toString(), "--linker", "missing-linker"), DiagnosticReporter(output, handler))
		assertEquals(1, status)
		assertEquals(listOf(DiagnosticCode.OPERATOR_MATCH.id), Regex("(?m)^C\\d{4}\\s*$").findAll(output).map { it.value.trim() }.toList())
		assertContains(output, "is ambiguous")
		assertFalse(Files.exists(destination.parent))
	}
}
