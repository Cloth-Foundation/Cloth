package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.parser.exception.SyntaxException
import cloth.settings.LinkOptions
import cloth.settings.OutputKind
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CompilationRequestTest {

	@Test
	fun `requests snapshot collections and expose no mutable collection aliases`(@TempDir directory: Path) {
		val modules = mutableListOf(SourceModule("Frontend", directory))
		val libraryPaths = mutableListOf(directory)
		val libraries = mutableListOf("first")
		val request = CompilationRequest(directory.resolve("source.co"), directory.resolve("program"), modules, linkOptions = LinkOptions(libraryPaths = libraryPaths, libraries = libraries))
		modules.clear()
		libraryPaths.clear()
		libraries[0] = "changed"
		assertEquals(listOf(SourceModule("Frontend", directory)), request.modules)
		assertEquals(listOf(directory), request.linkOptions.libraryPaths)
		assertEquals(listOf("first"), request.linkOptions.libraries)
		assertFailsWith<UnsupportedOperationException> { (request.modules as MutableList).clear() }
		assertFailsWith<UnsupportedOperationException> { (request.linkOptions.libraryPaths as MutableList).clear() }
		assertFailsWith<UnsupportedOperationException> { (request.linkOptions.libraries as MutableList).clear() }
	}

	@Test
	fun `relative request paths fail before source reads or output creation`(@TempDir directory: Path) {
		val source = directory.resolve("missing.co")
		val output = directory.resolve("not-created/program.o")
		val cases = listOf(
			CompilationRequest(Path.of("source.co"), output) to "sourceFile",
			CompilationRequest(source, Path.of("program.o")) to "outputFile",
			CompilationRequest(source, output, listOf(SourceModule("Frontend", Path.of("Frontend")))) to "Frontend",
		)
		for ((request, field) in cases) invalid(request, field)
		assertFalse(Files.exists(output.parent))
	}

	@Test
	fun `nested link paths must be explicit absolute inputs`(@TempDir directory: Path) {
		val relative = Path.of("relative-tools")
		val options = listOf(
			LinkOptions(linker = relative) to "linkOptions.linker",
			LinkOptions(libraryPaths = listOf(relative)) to "linkOptions.libraryPaths",
			LinkOptions(sysroot = relative) to "linkOptions.sysroot",
			LinkOptions(msvcTools = relative) to "linkOptions.msvcTools",
			LinkOptions(windowsSDK = relative) to "linkOptions.windowsSDK",
		)
		for ((links, field) in options) invalid(CompilationRequest(directory.resolve("missing.co"), directory.resolve("program"), linkOptions = links), field)
	}

	@Test
	fun `request entry selection agrees with output kind`(@TempDir directory: Path) {
		val source = directory.resolve("missing.co")
		val output = directory.resolve("program")
		invalid(CompilationRequest(source, output, entryFunction = "start"), "entryFunction")
		invalid(CompilationRequest(source, output, entryFunction = null), "entryFunction")
		invalid(CompilationRequest(source, output, outputKind = OutputKind.OBJECT, entryFunction = "main"), "entryFunction")
	}

	@Test
	fun `module ids and physical roots are validated for every caller`(@TempDir directory: Path) {
		val first = Files.createDirectory(directory.resolve("first"))
		val second = Files.createDirectory(directory.resolve("second"))
		val file = Files.writeString(directory.resolve("not-a-directory"), "text")
		val roots = listOf(
			listOf(SourceModule(" ", first)) to "nonempty id",
			listOf(SourceModule("Frontend", first), SourceModule("Frontend", second)) to "Duplicate source module id",
			listOf(SourceModule("Frontend", first), SourceModule("Backend", first.resolve("."))) to "duplicates module 'Frontend'",
			listOf(SourceModule("Frontend", file)) to "not a directory",
			listOf(SourceModule("Frontend", directory.resolve("missing-root"))) to "Unable to access source root",
		)
		for ((modules, reason) in roots) invalid(CompilationRequest(directory.resolve("missing.co"), directory.resolve("output"), modules), reason)
		assertFalse(Files.exists(directory.resolve("output")))
	}

	@Test
	fun `valid module context does not discover manifests or compile unreferenced source`(@TempDir directory: Path) {
		Files.writeString(directory.resolve("build.toml"), "this is not TOML @")
		val root = Files.createDirectory(directory.resolve("Frontend"))
		Files.writeString(root.resolve("module.toml"), "not TOML @")
		Files.writeString(root.resolve("unused.co"), "@")
		val source = Files.writeString(root.resolve("library.co"), "pub fn<int> answer() = 42")
		val output = directory.resolve("objects/library.o")
		val result = ClothCompiler.compile(CompilationRequest(source, output, listOf(SourceModule("Frontend", root)), outputKind = OutputKind.OBJECT))
		assertEquals(output, result.artifact)
		assertTrue(Files.size(result.artifact) > 0)
	}

	@Test
	fun `facade propagates original syntax diagnostic silently before target selection`(@TempDir directory: Path) {
		val contents = "fn main("
		val source = Files.writeString(directory.resolve("broken.co"), contents)
		val output = directory.resolve("not-created/program")
		val stdout = ByteArrayOutputStream()
		val stderr = ByteArrayOutputStream()
		val previousOut = System.out
		val previousErr = System.err
		val failure = try {
			System.setOut(PrintStream(stdout, true, Charsets.UTF_8))
			System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
			assertFailsWith<SyntaxException> { ClothCompiler.compile(CompilationRequest(source, output, targetTriple = "not-a-target")) }
		} finally {
			System.setOut(previousOut)
			System.setErr(previousErr)
		}
		assertEquals(DiagnosticCode.EXPECTED_TOKEN, failure.diagnosticCode)
		val snapshot = assertNotNull(failure.sourceLabels.first().span.source)
		assertEquals(source.toString(), snapshot.name)
		Files.writeString(source, "fn main() {}")
		assertEquals(contents, snapshot.text)
		assertEquals("", stdout.toString(Charsets.UTF_8))
		assertEquals("", stderr.toString(Charsets.UTF_8))
		assertFalse(Files.exists(output.parent))
	}

	@Test
	fun `target failure still precedes semantic checking`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("source.co"), "fn main() { unknownName }")
		val output = directory.resolve("not-created/program")
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(CompilationRequest(source, output, targetTriple = "not-a-target")) }
		assertEquals(DiagnosticCode.UNSUPPORTED_TARGET, failure.diagnosticCode)
		assertFalse(Files.exists(output.parent))
	}

	@Test
	fun `semantic failure is not replaced by linker or output work`(@TempDir directory: Path) {
		val contents = "fn main() { unknownName }"
		val source = Files.writeString(directory.resolve("source.co"), contents)
		val output = Files.writeString(directory.resolve("previous-output"), "old artifact")
		val request = CompilationRequest(source, output, linkOptions = LinkOptions(linker = directory.resolve("missing-linker")))
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request) }
		assertEquals(DiagnosticCode.UNKNOWN_NAME, failure.diagnosticCode)
		assertEquals(contents, failure.sourceLabels.single().span.source?.text)
		assertEquals("old artifact", Files.readString(output))
	}

	@Test
	fun `source and output IO failures cross the facade as structured diagnostics`(@TempDir directory: Path) {
		val missing = assertFailsWith<ClothDiagnostic> {
			ClothCompiler.compile(CompilationRequest(directory.resolve("missing.co"), directory.resolve("program")))
		}
		assertEquals(DiagnosticCode.SOURCE_READ, missing.diagnosticCode)
		assertIs<IOException>(missing.cause)
		val source = Files.writeString(directory.resolve("library.co"), "pub fn<int> answer() = 42")
		val blocked = Files.writeString(directory.resolve("blocked"), "keep")
		val failure = assertFailsWith<ClothDiagnostic> {
			ClothCompiler.compile(CompilationRequest(source, blocked.resolve("library.o"), outputKind = OutputKind.OBJECT))
		}
		assertEquals(DiagnosticCode.OUTPUT_IO, failure.diagnosticCode)
		assertIs<IOException>(failure.cause)
		assertEquals("keep", Files.readString(blocked))
	}

	@Test
	fun `output aliases cannot overwrite a source through the public API`(@TempDir directory: Path) {
		val contents = "fn helper() {}"
		val source = Files.writeString(directory.resolve("source.co"), contents)
		val alias = Files.createLink(directory.resolve("alias.o"), source)
		for (output in listOf(source, alias)) {
			val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(CompilationRequest(source, output, outputKind = OutputKind.OBJECT)) }
			assertEquals(DiagnosticCode.OUTPUT_OVERWRITES_SOURCE, failure.diagnosticCode)
			assertEquals(contents, Files.readString(source))
		}
	}

	@Test
	fun `native facade returns actual target filename and executable behavior`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("source with spaces λ.co"), "fn<int> main() { println(\"API λ 🚀\"); return 7 }")
		val output = directory.resolve("output with spaces/program")
		val result = ClothCompiler.compile(CompilationRequest(source, output))
		assertEquals(ExecutableFormat.current.executablePath(output), result.artifact)
		assertTrue(result.artifact.isAbsolute)
		assertEquals("API λ 🚀" + System.lineSeparator() + processCompletion(7), runExecutable(result.artifact, 7))
	}

	@Test
	fun `object requests honor all nine targets without main or linker discovery`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("library.co"), "pub fn<int> answer() = 42")
		val targets = listOf(
			Triple("i686-pc-windows-msvc", 0, listOf(0x4c, 0x01)),
			Triple("x86_64-pc-windows-msvc", 0, listOf(0x64, 0x86)),
			Triple("aarch64-pc-windows-msvc", 0, listOf(0x64, 0xaa)),
			Triple("x86_64-unknown-linux-gnu", 18, listOf(0x3e, 0x00)),
			Triple("aarch64-unknown-linux-gnu", 18, listOf(0xb7, 0x00)),
			Triple("x86_64-unknown-linux-musl", 18, listOf(0x3e, 0x00)),
			Triple("aarch64-unknown-linux-musl", 18, listOf(0xb7, 0x00)),
			Triple("x86_64-apple-darwin", 4, listOf(0x07, 0x00, 0x00, 0x01)),
			Triple("arm64-apple-darwin", 4, listOf(0x0c, 0x00, 0x00, 0x01)),
		)
		for ((index, target) in targets.withIndex()) {
			val (triple, offset, architecture) = target
			val output = directory.resolve("objects/$index.o")
			val result = ClothCompiler.compile(CompilationRequest(source, output, outputKind = OutputKind.OBJECT, targetTriple = triple, linkOptions = LinkOptions(linker = directory.resolve("missing-linker"))))
			assertEquals(output, result.artifact)
			val bytes = Files.readAllBytes(result.artifact)
			assertEquals(architecture, bytes.drop(offset).take(architecture.size).map { it.toInt() and 255 }, triple)
		}
	}

	private fun invalid(request: CompilationRequest, reason: String) {
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request) }
		assertEquals(DiagnosticCode.INVALID_COMPILATION_REQUEST, failure.diagnosticCode)
		assertContains(failure.summary, reason)
	}
}
