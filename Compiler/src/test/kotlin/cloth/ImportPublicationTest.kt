package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
import cloth.settings.LinkOptions
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import cloth.toolchain.StandardLibrary
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.llvm.LLVM.LLVMContextRef
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportPublicationTest {

	private val example = Path.of("examples/Shuttle").toAbsolutePath()
	private val bootstrap = Path.of("Bootstrap").toAbsolutePath()
	private fun request(project: Path, output: Path, target: String? = null, kind: OutputKind = OutputKind.EXECUTABLE): CompilationRequest {
		val primary = if (project == example) "app/cli/Main.co" else "Frontend/Main.co"
		val modules = if (project == example) listOf(SourceModule("App", project.resolve("app")), SourceModule("Support", project.resolve("support"))) else listOf("Frontend", "Compiler", "Backend").map { SourceModule(it, project.resolve(it)) }
		val request = CompilationRequest(project.resolve(primary), output, modules, kind, targetTriple = target, linkOptions = if (kind == OutputKind.OBJECT) LinkOptions(linker = output.resolveSibling("missing-linker")) else LinkOptions())
		return if (project == bootstrap) StandardLibrary.default().attachTo(request) else request
	}

	@Test
	fun `published and Bootstrap graphs retain their nested source paths`(@TempDir directory: Path) {
		for (project in listOf(example, bootstrap)) {
			val graph = SourceGraphLoader.load(request(project, directory.resolve("not-created/out.o"), kind = OutputKind.OBJECT))
			val relative = graph.sources.map { project.relativize(it.path).toString().replace('\\', '/') }.toSet()
			val expected = if (project == example) {
				setOf("app/cli/Main.co", "app/token/Token.co", "support/report/Report.co", "support/math/Even.co", "app/math/Odd.co")
			} else {
				setOf(
					"Frontend/Main.co", "Frontend/lexer/Lexer.co", "Frontend/lexer/LexerError.co",
					"Frontend/token/Token.co", "Frontend/token/TokenList.co", "Frontend/token/Keywords.co",
					"Frontend/token/Operators.co", "Frontend/source/SourceFile.co", "../Standard-Library/cloth/collections/List.co",
					"../Standard-Library/cloth/io/File.co", "../Standard-Library/cloth/io/Path.co", "../Standard-Library/cloth/text/Utf8.co",
				)
			}
			assertEquals(expected, relative)
			if (project == example) assertTrue(graph.edges.any { it.backEdge })
		}
		assertFalse(Files.exists(directory.resolve("not-created")))
	}

	@Test
	fun `maintained projects run through public requests on available native profiles`(@TempDir directory: Path) {
		// The first byte of the non-ASCII scalar lands at the end of Bootstrap's first chunk.
		val source = "//" + " ".repeat(65533) + "界\r\ndef nested = 42\r\nprintln(\"folders\")\r\n"
		val input = Files.writeString(directory.resolve("输入 source.co"), source)
		val tokens = Lexer(SourceFile(input.toString(), source)).scanTokens().joinToString("\n", postfix = "\n")
		val empty = Files.createFile(directory.resolve("empty.co"))
		val malformed = Files.write(directory.resolve("malformed.co"), byteArrayOf(0xC3.toByte(), 0x28))
		val invalidSource = Files.writeString(directory.resolve("invalid.co"), "0x")
		val lexerError = assertFailsWith<ClothDiagnostic> { Lexer(SourceFile(invalidSource.toString(), "0x")).scanTokens() }
		for (project in listOf(example, bootstrap)) for (abi in structRunners()) {
			val output = directory.resolve("${project.fileName}_${abi.layout.sizeBits}")
			val artifact = ClothCompiler.compile(request(project, output, abi.targetTriple)).artifact
			val completion = processCompletion().replace("\r\n", "\n")
			if (project == example) assertEquals("token Name: Cloth\nnested folders\n" + completion, execute(artifact, listOf("nested folders")))
			else {
				val actual = execute(artifact, listOf(input.toString()))
				assertContains(actual, tokens)
				assertTrue(actual.endsWith(completion), actual)
				assertFalse(actual.contains("Input your code"), actual)
				assertContains(execute(artifact, listOf("--file", empty.toString())), "\"kind\": \"EOF\"")
				val decoding = execute(artifact, listOf(malformed.toString()), status = 1)
				assertContains(decoding, "Invalid UTF-8 in '$malformed'")
				assertFalse(decoding.contains("\"lexeme\""), decoding)
				val missing = directory.resolve("missing.co")
				assertContains(execute(artifact, listOf(missing.toString()), status = 1), "Cannot open '$missing' (NotFound)")
				val failure = execute(artifact, listOf(invalidSource.toString()), status = 1)
				assertContains(failure, lexerError.code())
				assertContains(failure, lexerError.summary)
				assertContains(execute(artifact, listOf("--text", "def literal = 1")), "\"lexeme\": \"literal\"")
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
		val foreign = request(bootstrap, directory.resolve("bootstrap-object.o"), "aarch64-unknown-linux-gnu", OutputKind.OBJECT)
		assertTrue(Files.size(ClothCompiler.compile(foreign).artifact) > 0)
	}

	private fun execute(executable: Path, arguments: List<String>, status: Int = 0): String {
		val process = ProcessBuilder(listOf(executable.toString()) + arguments).redirectErrorStream(true).start()
		try {
			// Keep stdin open so argument modes cannot rely on EOF to finish.
			assertTrue(process.waitFor(30, TimeUnit.SECONDS))
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			return output.replace("\r\n", "\n")
		} finally {
			if (process.isAlive) process.destroyForcibly()
			process.outputStream.close()
		}
	}


}

/** Parse the verified executable IR to emit startup objects without discovering a foreign linker. */
internal fun emitExecutableObject(emitter: LLVMEmitter, ir: String, output: Path, instrument: (LLVMContextRef, LLVMModuleRef) -> Unit = { _, _ -> }) {
	val context = LLVMContextCreate()
	val module = LLVMModuleRef()
	val failure = BytePointer(null as Pointer?)
	try {
		val buffer = LLVMCreateMemoryBufferWithMemoryRangeCopy(ir, ir.toByteArray(Charsets.UTF_8).size.toLong(), output.fileName.toString())
		assertEquals(0, LLVMParseIRInContext(context, buffer, module, failure), if (failure.isNull) "Invalid IR" else failure.string)
		instrument(context, module)
		emitter.verify(module)
		emitter.emitObject(module, output)
	} finally {
		if (!module.isNull) LLVMDisposeModule(module)
		if (!failure.isNull) LLVMDisposeMessage(failure)
		LLVMContextDispose(context)
	}
}
