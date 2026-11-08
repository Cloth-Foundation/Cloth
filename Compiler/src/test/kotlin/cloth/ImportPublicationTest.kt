package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
import cloth.settings.LinkOptions
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportPublicationTest {

	private val example = Path.of("examples/Shuttle").toAbsolutePath()
	private val bootstrap = Path.of("Bootstrap").toAbsolutePath()
	private fun request(project: Path, output: Path, target: String? = null, kind: OutputKind = OutputKind.EXECUTABLE): CompilationRequest {
		val primary = if (project == example) "app/cli/Main.co" else "Frontend/Main.co"
		val modules = if (project == example) listOf(SourceModule("App", project.resolve("app")), SourceModule("Support", project.resolve("support"))) else listOf("Frontend", "Compiler", "Backend").map { SourceModule(it, project.resolve(it)) }
		return CompilationRequest(project.resolve(primary), output, modules, kind, targetTriple = target, linkOptions = if (kind == OutputKind.OBJECT) LinkOptions(linker = output.resolveSibling("missing-linker")) else LinkOptions())
	}

	@Test
	fun `published and Bootstrap graphs retain their nested source paths`(@TempDir directory: Path) {
		for (project in listOf(example, bootstrap)) {
			val graph = SourceGraphLoader.load(request(project, directory.resolve("not-created/out.o"), kind = OutputKind.OBJECT))
			val relative = graph.sources.map { project.relativize(it.path).toString().replace('\\', '/') }.toSet()
			val expected = if (project == example) setOf("app/cli/Main.co", "app/token/Token.co", "support/report/Report.co", "support/math/Even.co", "app/math/Odd.co") else setOf("Frontend/Main.co", "Frontend/lexer/Lexer.co", "Frontend/lexer/LexerError.co", "Frontend/token/Token.co", "Frontend/token/TokenList.co", "Frontend/token/Keywords.co", "Frontend/token/Operators.co", "Frontend/source/SourceFile.co")
			assertEquals(expected, relative)
			if (project == example) assertTrue(graph.edges.any { it.backEdge })
		}
		assertFalse(Files.exists(directory.resolve("not-created")))
	}

	@Test
	fun `maintained projects run through public requests on available native profiles`(@TempDir directory: Path) {
		for (project in listOf(example, bootstrap)) for (abi in structRunners()) {
			val output = directory.resolve("${project.fileName}_${abi.layout.sizeBits}")
			val artifact = ClothCompiler.compile(request(project, output, abi.targetTriple)).artifact
			val actual = execute(artifact)
			val completion = processCompletion().replace("\r\n", "\n")
			if (project == example) assertEquals("token Name: Cloth\nnested folders\n" + completion, actual)
			else {
				assertTrue(actual.endsWith(completion), actual)
				assertEquals(1, Regex("PEC: 0").findAll(actual).count(), actual)
			}
		}
	}

	@Test
	fun `maintained projects verify executable IR and startup objects for all nine profiles`(@TempDir directory: Path) {
		for ((name, project) in listOf("example" to example, "bootstrap" to bootstrap)) for ((index, triple) in structTargetTriples.withIndex()) {
			val abi = NativeCABI.forTarget(triple)
			val graph = SourceGraphLoader.load(request(project, directory.resolve("unused"), triple))
			val program = ProgramAnalyzer(abi.layout).analyze(FileBindings.graph(graph))
			val emitter = LLVMEmitter(abi)
			val ir = emitter.emitExecutable(program)
			assertContains(ir, "target triple = \"$triple\"")
			assertEquals(1, ir.lineSequence().count { it.startsWith("define i32 @${if (abi.windows) "wmain" else "main"}(") })
			val objectFile = directory.resolve("${name}_$index.o")
			emitExecutableObject(emitter, ir, objectFile)
			assertTrue(Files.size(objectFile) > 0)
			if (triple == "x86_64-unknown-linux-gnu") System.getProperty("cloth.test.importPublicationArtifacts")?.let {
				val artifacts = Files.createDirectories(Path.of(it))
				Files.writeString(artifacts.resolve("$name-linux-x64.ll"), ir)
				Files.copy(objectFile, artifacts.resolve("$name-linux-x64.o"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
			}
		}
	}

	private fun execute(executable: Path): String {
		val process = ProcessBuilder(executable.toString(), "nested folders").redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(30, TimeUnit.SECONDS))
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(0, process.exitValue(), output)
			return output.replace("\r\n", "\n")
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	/** Parse the verified executable IR to emit startup objects without discovering a foreign linker. */
	private fun emitExecutableObject(emitter: LLVMEmitter, ir: String, output: Path) {
		val context = LLVMContextCreate()
		val module = LLVMModuleRef()
		val failure = BytePointer(null as Pointer?)
		try {
			val buffer = LLVMCreateMemoryBufferWithMemoryRangeCopy(ir, ir.toByteArray(Charsets.UTF_8).size.toLong(), "imports")
			assertEquals(0, LLVMParseIRInContext(context, buffer, module, failure), if (failure.isNull) "Invalid IR" else failure.string)
			emitter.verify(module)
			emitter.emitObject(module, output)
		} finally {
			if (!module.isNull) LLVMDisposeModule(module)
			if (!failure.isNull) LLVMDisposeMessage(failure)
			LLVMContextDispose(context)
		}
	}
}
