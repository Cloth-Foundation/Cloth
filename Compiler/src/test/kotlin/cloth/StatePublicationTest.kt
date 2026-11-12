package cloth

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatePublicationTest {

	@Test
	fun `published state examples verify executable IR and startup objects on all profiles`(@TempDir directory: Path) {
		val examples = Path.of("examples").toAbsolutePath()
		val project = examples.resolve("States")
		val app = project.resolve("app")
		val modules = listOf(SourceModule("App", app), SourceModule("Library", project.resolve("library")))
		val graphs = mapOf(
			"nested" to SourceGraphLoader.load(CompilationRequest(examples.resolve("States.co"), directory.resolve("nested"))),
			"attached" to SourceGraphLoader.load(CompilationRequest(app.resolve("Main.co"), directory.resolve("attached"), modules)),
		)
		for (triple in structTargetTriples) {
			val abi = NativeCABI.forTarget(triple)
			val emitter = LLVMEmitter(abi)
			for ((name, graph) in graphs) {
				val program = ProgramAnalyzer(abi.layout).analyze(FileBindings.graph(graph))
				val ir = emitter.emitExecutable(program)
				assertContains(ir, "target triple = \"$triple\"")
				assertContains(ir, "@cloth.state.dispatch.")
				assertContains(ir, "@cloth.state.init.")
				val entry = if (abi.windows) "wmain" else "main"
				assertEquals(1, ir.lineSequence().count { it.startsWith("define i32 @$entry(") })
				val output = directory.resolve("$name-$triple.o")
				emitExecutableObject(emitter, ir, output)
				assertTrue(Files.size(output) > 0, "$name on $triple")
				System.getProperty("cloth.test.statePublicationArtifacts")?.let {
					val evidence = Files.createDirectories(Path.of(it))
					Files.writeString(evidence.resolve("$name-$triple.ll"), ir)
					Files.copy(output, evidence.resolve(output.fileName), REPLACE_EXISTING)
				}
			}
		}
	}
}
