package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
import cloth.settings.StandardLibrarySelection
import cloth.source.SourceGraphLoader
import cloth.toolchain.StandardLibrary
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandardLibraryPublicationTest {

	private val example = Path.of("examples/StandardLibrary").toAbsolutePath()
	private fun request(output: Path, abi: NativeCABI): CompilationRequest {
		val request = CompilationRequest(example.resolve("app/Main.co"), output, listOf(SourceModule("App", example.resolve("app"))), targetTriple = abi.targetTriple)
		return StandardLibrary.select(StandardLibrarySelection.Explicit(example.resolve("library")), example)!!.attachTo(request)
	}

	@Test
	fun `maintained example loads just its explicit graph and runs on available native profiles`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val request = request(directory.resolve("example_${abi.layout.sizeBits}"), abi)
			assertEquals(setOf("app/Main.co", "library/cloth/example/Greeting.co", "library/cloth/example/Words.co"), SourceGraphLoader.load(request).sources.map { example.relativize(it.path).toString().replace('\\', '/') }.toSet())
			assertEquals("Hello from bundled Cloth\n" + processCompletion().replace("\r\n", "\n"), runExecutable(ClothCompiler.compile(request).artifact).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `maintained library example verifies executable IR and startup objects on all nine profiles`(@TempDir directory: Path) {
		for ((index, triple) in structTargetTriples.withIndex()) {
			val abi = NativeCABI.forTarget(triple)
			val program = ProgramAnalyzer(abi.layout).analyze(FileBindings.graph(SourceGraphLoader.load(request(directory.resolve("unused"), abi))))
			val emitter = LLVMEmitter(abi)
			val ir = emitter.emitExecutable(program)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "define internal i64 @cloth.objects.allocate(i${abi.layout.sizeBits}")
			val objectFile = directory.resolve("example_$index.o")
			val context = LLVMContextCreate()
			val module = LLVMModuleRef()
			val failure = BytePointer(null as Pointer?)
			try {
				val buffer = LLVMCreateMemoryBufferWithMemoryRangeCopy(ir, ir.toByteArray(Charsets.UTF_8).size.toLong(), "standard-library-example")
				assertEquals(0, LLVMParseIRInContext(context, buffer, module, failure), if (failure.isNull) "Invalid IR" else failure.string)
				emitter.verify(module)
				emitter.emitObject(module, objectFile)
			} finally {
				if (!module.isNull) LLVMDisposeModule(module)
				if (!failure.isNull) LLVMDisposeMessage(failure)
				LLVMContextDispose(context)
			}
			assertTrue(Files.size(objectFile) > 0)
			if (triple == "x86_64-unknown-linux-gnu") System.getProperty("cloth.test.standardLibraryPublicationArtifacts")?.let {
				val evidence = Files.createDirectories(Path.of(it))
				Files.writeString(evidence.resolve("example-linux-x64.ll"), ir)
				Files.copy(objectFile, evidence.resolve("example-linux-x64.o"), StandardCopyOption.REPLACE_EXISTING)
			}
		}
	}
}
