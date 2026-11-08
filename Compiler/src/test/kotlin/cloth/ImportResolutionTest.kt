package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.ast.ImportSelection
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.settings.LinkOptions
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import cloth.source.SourceId
import cloth.token.TokenSpan
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.FileSystemException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real source trees and compiler requests, with no TOML or native backend required. */
class ImportResolutionTest {

	private fun source(root: Path, relative: String, text: String = "pub fn work() {}\n"): Path {
		val path = root.resolve(relative)
		Files.createDirectories(path.parent)
		return Files.writeString(path, text)
	}

	private fun request(entry: Path, roots: List<SourceModule> = emptyList(), output: Path = entry.parent.resolve("not-created/output.o"), kind: OutputKind = OutputKind.OBJECT) = CompilationRequest(entry, output, roots, outputKind = kind, targetTriple = "not-a-target", linkOptions = LinkOptions(linker = entry.parent.resolve("missing-linker")))

	private fun textAt(span: TokenSpan): String {
		val text = assertNotNull(span.source).text
		val lines = text.split('\n')
		fun offset(line: Int, column: Int) = lines.take(line - 1).sumOf { it.length + 1 } + column - 1
		return text.substring(offset(span.startLine, span.startColumn), offset(span.endLine, span.endColumn))
	}

	private fun directoryAlias(alias: Path, target: Path) {
		try {
			Files.createSymbolicLink(alias, target)
		} catch (failure: FileSystemException) {
			if (!System.getProperty("os.name").startsWith("Windows")) assumeTrue(false, "Host cannot create symbolic links: ${failure.reason}")
			// NTFS junctions exercise directory aliasing without the symbolic-link privilege.
			val process = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", alias.toString(), target.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
			try {
				assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Directory junction creation timed out")
				assertEquals(0, process.exitValue(), "Unable to create the directory alias fixture")
			} finally {
				if (process.isAlive) process.destroyForcibly()
			}
		} catch (_: UnsupportedOperationException) {
			assumeTrue(false, "Filesystem does not support symbolic links")
		}
	}

	@Test
	fun `a primary file needs no roots and discovery ignores manifests and unreachable files`(@TempDir directory: Path) {
		if (java.lang.Boolean.getBoolean("cloth.test.nativeFreeImports")) {
			assertNull(javaClass.classLoader.getResource("org/bytedeco/llvm/global/LLVM.class"))
			assertNull(javaClass.classLoader.getResource("org/bytedeco/javacpp/Pointer.class"))
		}
		val entry = source(directory, "Main.co", "fn main() { unknownName }\n")
		source(directory, "Unused.co", "@")
		source(directory, "build.toml", "not TOML @")
		source(directory, "module.toml", "not TOML @")
		for (roots in listOf(emptyList(), listOf(SourceModule("Frontend", directory)))) {
			val graph = SourceGraphLoader.load(request(entry, roots))
			assertEquals(1, graph.sources.size)
			assertSame(graph.primary, graph.source(SourceId(0)))
			assertEquals(entry.toRealPath(), graph.primary.path)
			assertTrue(graph.edges.isEmpty())
			assertFalse(Files.exists(directory.resolve("not-created")))
		}
	}

	@Test
	fun `transitive discovery is depth first and preserves file scopes and written edge order`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "using Left: *\nusing Right: right\nfn main() {}")
		source(directory, "Left.co", "using Shared: shared\nfn privateHelper() {}")
		source(directory, "Right.co", "using Shared: *\nfn privateHelper() {}")
		source(directory, "Shared.co", "pub fn shared() {}")
		val graph = SourceGraphLoader.load(request(entry, listOf(SourceModule("Frontend", directory))))
		assertEquals(listOf("Main.co", "Left.co", "Shared.co", "Right.co"), graph.sources.map { it.path.fileName.toString() })
		assertEquals(listOf("Left", "Shared", "Right", "Shared"), graph.edges.map { it.declaration.path.spelling })
		assertEquals(listOf("Left", "Right"), graph.importsOf(graph.primary.id).map { it.declaration.path.spelling })
		assertFalse(graph.edges.any { it.backEdge })
		assertSame(graph.source(graph.edges[1].to), graph.source(graph.edges[3].to))
		assertEquals(1, graph.sources[1].unit.declarations.size)
		assertEquals(1, graph.sources[3].unit.declarations.size)
		assertNotSame(graph.sources[1].unit.declarations.single(), graph.sources[3].unit.declarations.single())
	}

	@Test
	fun `module free paths cross roots with spaces and Unicode without module manifests`(@TempDir directory: Path) {
		val frontend = directory.resolve("Frontend with spaces λ")
		val library = directory.resolve("Library 🚀")
		val entry = source(frontend, "Main.co", "using token.Token: Token, make\nfn main() {}")
		val token = source(library, "token/Token.co", "pub fn make() {}")
		val graph = SourceGraphLoader.load(request(entry, listOf(SourceModule("Frontend", frontend), SourceModule("Library", library))))
		val edge = graph.edges.single()
		assertEquals("Library", edge.moduleId)
		assertEquals(token, edge.selectedPath)
		assertEquals(token.toRealPath(), graph.source(edge.to).path)
		assertEquals(listOf("Token", "make"), assertIs<ImportSelection.Names>(edge.declaration.selection).names.map { it.lexeme })
	}

	@Test
	fun `absent root context never guesses the source directory`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "using Token: *\nfn main() {}")
		source(directory, "Token.co")
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request(entry)) }
		assertEquals(DiagnosticCode.MISSING_IMPORT_CONTEXT, failure.diagnosticCode)
		assertEquals("Token", textAt(failure.sourceLabels.single().span))
		assertFalse(Files.exists(directory.resolve("not-created")))
	}

	@Test
	fun `missing files identify the original import path and snapshot`(@TempDir directory: Path) {
		val text = "// Header\r\nusing token.Missing: Missing\r\nfn main() {}"
		val entry = source(directory, "Main.co", text)
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request(entry, listOf(SourceModule("Frontend", directory)))) }
		assertEquals(DiagnosticCode.IMPORTED_SOURCE_NOT_FOUND, failure.diagnosticCode)
		val span = failure.sourceLabels.single().span
		assertEquals(2, span.startLine)
		assertEquals("token.Missing", textAt(span))
		Files.writeString(entry, "fn main() {}")
		assertEquals(text, span.source?.text)
		val rendered = StringBuilder()
		DiagnosticReporter(rendered).report(failure)
		assertContains(rendered.toString(), "C3098")
		assertContains(rendered.toString(), "token.Missing")
	}

	@Test
	fun `relative directories filenames and co extensions require exact case on every host`(@TempDir directory: Path) {
		source(directory, "Token/Name.CO")
		val entry = directory.resolve("Main.co")
		for (path in listOf("token.Name", "Token.name", "Token.Name")) {
			source(directory, "Main.co", "using $path: *\nfn main() {}")
			val failure = assertFailsWith<ClothDiagnostic> { SourceGraphLoader.load(request(entry, listOf(SourceModule("M", directory)))) }
			assertEquals(DiagnosticCode.IMPORTED_SOURCE_NOT_FOUND, failure.diagnosticCode, path)
		}
		source(directory, "Correct/Name.co")
		source(directory, "Main.co", "using Correct.Name: *\nfn main() {}")
		assertEquals(2, SourceGraphLoader.load(request(entry, listOf(SourceModule("M", directory)))).sources.size)
	}

	@Test
	fun `directories cannot act as source files and files cannot act as path directories`(@TempDir directory: Path) {
		Files.createDirectories(directory.resolve("Folder.co"))
		source(directory, "blocked", "keep")
		for (path in listOf("Folder", "blocked.Child")) {
			val entry = source(directory, "Main.co", "using $path: *\nfn main() {}")
			assertEquals(DiagnosticCode.IMPORTED_SOURCE_NOT_FOUND, assertFailsWith<ClothDiagnostic> { SourceGraphLoader.load(request(entry, listOf(SourceModule("M", directory)))) }.diagnosticCode)
		}
	}

	@Test
	fun `ambiguity precedes symbol selection parsing candidate files and current module preference`(@TempDir directory: Path) {
		val first = directory.resolve("first")
		val second = directory.resolve("second")
		val entry = source(first, "Main.co", "using token.Token: OnlyHere\nfn main() {}")
		val firstMatch = source(first, "token/Token.co", "pub fn OnlyHere() {}")
		val secondMatch = source(second, "token/Token.co", "@")
		val roots = listOf(SourceModule("Zulu", first), SourceModule("Alpha", second))
		val failures = listOf(roots, roots.reversed()).map { modules -> assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request(entry, modules)) } }
		assertTrue(failures.all { it.diagnosticCode == DiagnosticCode.AMBIGUOUS_IMPORT_SOURCE })
		assertEquals(failures[0].guidance, failures[1].guidance)
		assertEquals("Alpha: $secondMatch\nZulu: $firstMatch", failures[0].guidance)
		assertEquals("token.Token", textAt(failures[0].sourceLabels.single().span))
	}

	@Test
	fun `two roots matching one already loaded physical file remain ambiguous`(@TempDir directory: Path) {
		val first = directory.resolve("first")
		val second = Files.createDirectory(directory.resolve("second"))
		val entry = source(first, "Main.co", "using Main: *\nfn main() {}")
		Files.createLink(second.resolve("Main.co"), entry)
		val failure = assertFailsWith<ClothDiagnostic> { SourceGraphLoader.load(request(entry, listOf(SourceModule("A", first), SourceModule("B", second)))) }
		assertEquals(DiagnosticCode.AMBIGUOUS_IMPORT_SOURCE, failure.diagnosticCode)
		assertContains(failure.guidance.orEmpty(), "A:")
		assertContains(failure.guidance.orEmpty(), "B:")
	}

	@Test
	fun `repeated selective and wildcard imports share one parsed source identity`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "using Token: first\nusing Token: *\nusing Token: first\nfn main() {}")
		source(directory, "Token.co")
		val graph = SourceGraphLoader.load(request(entry, listOf(SourceModule("M", directory))))
		assertEquals(2, graph.sources.size)
		assertEquals(3, graph.edges.size)
		assertEquals(1, graph.edges.map { it.to }.distinct().size)
		assertTrue(graph.edges.all { !it.backEdge })
		assertTrue(graph.edges.all { graph.source(it.to).source === graph.sources[1].source })
	}

	@Test
	fun `self imports retain a back edge rather than reloading or rejecting the file`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "using Main: main\nfn main() {}")
		val graph = SourceGraphLoader.load(request(entry, listOf(SourceModule("M", directory))))
		assertEquals(1, graph.sources.size)
		val edge = graph.edges.single()
		assertEquals(graph.primary.id, edge.to)
		assertEquals(edge.from, edge.to)
		assertTrue(edge.backEdge)
	}

	@Test
	fun `two file cycles retain selective and wildcard edges in written order`(@TempDir directory: Path) {
		val entry = source(directory, "Alpha.co", "using Beta: beta\nfn alpha() {}")
		source(directory, "Beta.co", "using Alpha: *\nfn beta() {}")
		val graph = SourceGraphLoader.load(request(entry, listOf(SourceModule("M", directory))))
		assertEquals(listOf("Alpha.co", "Beta.co"), graph.sources.map { it.path.fileName.toString() })
		assertEquals(listOf(false, true), graph.edges.map { it.backEdge })
		assertEquals(graph.primary.id, graph.edges.last().to)
	}

	@Test
	fun `longer cycles can cross configured modules without source topological ordering`(@TempDir directory: Path) {
		val frontend = directory.resolve("Frontend")
		val backend = directory.resolve("Backend")
		val entry = source(frontend, "Main.co", "using Beta: beta\nfn main() {}")
		source(backend, "Beta.co", "using Gamma: *\nfn beta() {}")
		source(frontend, "Gamma.co", "using Main: main\nfn gamma() {}")
		val graph = SourceGraphLoader.load(request(entry, listOf(SourceModule("Frontend", frontend), SourceModule("Backend", backend))))
		assertEquals(listOf("Main.co", "Beta.co", "Gamma.co"), graph.sources.map { it.path.fileName.toString() })
		assertEquals(listOf("Backend", "Frontend", "Frontend"), graph.edges.map { it.moduleId })
		assertEquals(listOf(false, false, true), graph.edges.map { it.backEdge })
		assertSame(graph.primary, graph.source(graph.edges.last().to))
	}

	@Test
	fun `deep import cycles use an explicit traversal stack`(@TempDir directory: Path) {
		val count = 768
		for (index in 0 until count) source(directory, "Node$index.co", "using Node${(index + 1) % count}: *")
		val graph = SourceGraphLoader.load(request(directory.resolve("Node0.co"), listOf(SourceModule("M", directory))))
		assertEquals(count, graph.sources.size)
		assertEquals(count, graph.edges.size)
		assertEquals((0 until count).toList(), graph.sources.map { it.id.index })
		assertEquals(1, graph.edges.count { it.backEdge })
		assertEquals(graph.primary.id, graph.edges.last().to)
	}

	@Test
	fun `hard link aliases preserve the first snapshot and distinct written edges`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "using Original: *\nusing Alias: work\nfn main() {}")
		val original = source(directory, "Original.co")
		val alias = Files.createLink(directory.resolve("Alias.co"), original)
		val graph = SourceGraphLoader.load(request(entry, listOf(SourceModule("M", directory))))
		assertEquals(2, graph.sources.size)
		assertEquals(listOf(original, alias), graph.edges.map { it.selectedPath })
		assertEquals(graph.edges.first().to, graph.edges.last().to)
		assertEquals(original.toString(), graph.sources[1].source.name)
		assertSame(graph.source(graph.edges.first().to).unit, graph.source(graph.edges.last().to).unit)
	}

	@Test
	fun `a hard link back to the primary file is a single source cycle`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "using Alias: *\nfn main() {}")
		Files.createLink(directory.resolve("Alias.co"), entry)
		val graph = SourceGraphLoader.load(request(entry, listOf(SourceModule("M", directory))))
		assertEquals(1, graph.sources.size)
		assertTrue(graph.edges.single().backEdge)
		assertEquals(graph.primary.id, graph.edges.single().to)
	}

	@Test
	fun `directory aliases reuse sources and duplicate physical roots are rejected`(@TempDir directory: Path) {
		val actual = directory.resolve("actual")
		val entry = source(directory, "Main.co", "using actual.Token: *\nusing alias.Token: *\nfn main() {}")
		source(actual, "Token.co")
		val alias = directory.resolve("alias")
		directoryAlias(alias, actual)
		val graph = SourceGraphLoader.load(request(entry, listOf(SourceModule("M", directory))))
		assertEquals(2, graph.sources.size)
		assertEquals(graph.edges.first().to, graph.edges.last().to)
		val failure = assertFailsWith<ClothDiagnostic> { SourceGraphLoader.load(request(entry, listOf(SourceModule("A", actual), SourceModule("B", alias)))) }
		assertEquals(DiagnosticCode.INVALID_COMPILATION_REQUEST, failure.diagnosticCode)
	}

	@Test
	fun `a root without file keys does not compare source identities across different filesystems`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "using Token: *\nusing Token: *\nfn main() {}")
		FileSystems.newFileSystem(directory.resolve("fixture.zip"), mapOf("create" to "true")).use { filesystem ->
			val root = filesystem.getPath("/")
			val token = source(root, "Token.co")
			assertNull(Files.readAttributes(token, BasicFileAttributes::class.java).fileKey())
			val graph = SourceGraphLoader.load(request(entry, listOf(SourceModule("External", root))))
			assertEquals(2, graph.sources.size)
			assertEquals(graph.edges.first().to, graph.edges.last().to)
			assertEquals("pub fn work() {}\n", graph.sources[1].source.text)
			val existing = source(directory, "old-artifact", "preserve")
			graph.checkOutput(existing)
			assertEquals("preserve", Files.readString(existing))
		}
	}

	@Test
	fun `discovery retains immutable collections and original text across later file edits`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "using Token: *\nfn main() {}")
		val token = source(directory, "Token.co", "// Original\n")
		val inputs = request(entry, listOf(SourceModule("M", directory)))
		val graph = SourceGraphLoader.load(inputs)
		assertFailsWith<UnsupportedOperationException> { (graph.sources as MutableList).clear() }
		assertFailsWith<UnsupportedOperationException> { (graph.edges as MutableList).clear() }
		assertFailsWith<UnsupportedOperationException> { (graph.importsOf(graph.primary.id) as MutableList).clear() }
		Files.writeString(token, "@")
		assertEquals("// Original\n", graph.sources[1].source.text)
		val failure = assertFailsWith<ClothDiagnostic> { SourceGraphLoader.load(inputs) }
		assertEquals(DiagnosticCode.UNEXPECTED_CHARACTER, failure.diagnosticCode)
		assertEquals(token.toString(), failure.sourceLabels.single().span.source?.name)
	}

	@Test
	fun `subfolders stay hierarchical and imports resolve from the module root`(@TempDir directory: Path) {
		val entry = source(directory, "cli/deep/Main.co", "using token.Token: Token\nfn main() {}")
		val token = source(directory, "token/Token.co", "using helpers.TokenSupport: work\npub typedef<struct> Token {}")
		val helper = source(directory, "helpers/TokenSupport.co")
		val decoy = source(directory, "cli/deep/token/Token.co")
		val roots = listOf(SourceModule("Frontend", directory))
		val graph = SourceGraphLoader.load(request(entry, roots))
		assertEquals(listOf(entry, token, helper), graph.sources.map { it.path })
		assertTrue(graph.edges.all { it.moduleId == "Frontend" })
		assertFalse(graph.sources.any { it.path == decoy })
		Files.writeString(entry, "using Token: Token\nfn main() {}")
		val failure = assertFailsWith<ClothDiagnostic> { SourceGraphLoader.load(request(entry, roots)) }
		assertEquals(DiagnosticCode.IMPORTED_SOURCE_NOT_FOUND, failure.diagnosticCode)
	}

	@Test
	fun `output cannot overwrite any transitive source or physical alias`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "using Branch: *\nfn main() {}")
		source(directory, "Branch.co", "using Leaf: *\nfn branch() {}")
		val leaf = source(directory, "Leaf.co")
		val alias = Files.createLink(directory.resolve("alias.o"), leaf)
		val roots = listOf(SourceModule("M", directory))
		for (output in listOf(entry, leaf, alias, directory.resolve("unused/../Leaf.co"))) {
			val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request(entry, roots, output)) }
			assertEquals(DiagnosticCode.OUTPUT_OVERWRITES_SOURCE, failure.diagnosticCode)
		}
		assertEquals("pub fn work() {}\n", Files.readString(leaf))
		assertFalse(Files.exists(directory.resolve("unused")))
	}

	@Test
	fun `pre binding output checks protect a possible executable suffix without selecting a target`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "using Token: *\nfn main() {}")
		val token = source(directory, "Token.co")
		Files.createLink(directory.resolve("program.exe"), token)
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request(entry, listOf(SourceModule("M", directory)), directory.resolve("program"), OutputKind.EXECUTABLE)) }
		assertEquals(DiagnosticCode.OUTPUT_OVERWRITES_SOURCE, failure.diagnosticCode)
		val graph = SourceGraphLoader.load(request(entry, listOf(SourceModule("M", directory))))
		graph.checkOutput(directory.resolve("not-created/artifact.o"))
		assertFalse(Files.exists(directory.resolve("not-created")))
	}

	@Test
	fun `depth first source failures precede later imports and preserve imported syntax locations`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "using Branch: MissingSymbol\nusing Missing: *\nfn main() {}")
		source(directory, "Branch.co", "using Broken: *\nfn branch() {}")
		val broken = source(directory, "Broken.co", "// Original\r\n@")
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request(entry, listOf(SourceModule("M", directory)))) }
		assertEquals(DiagnosticCode.UNEXPECTED_CHARACTER, failure.diagnosticCode)
		val span = failure.sourceLabels.single().span
		assertEquals(broken.toString(), span.source?.name)
		assertEquals(2, span.startLine)
		Files.writeString(broken, "")
		assertEquals("// Original\r\n@", span.source?.text)
	}

	@Test
	fun `primary syntax is parsed before any of its import paths are resolved`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "using Missing: *\nfn main(")
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request(entry, listOf(SourceModule("M", directory)))) }
		assertEquals(DiagnosticCode.EXPECTED_TOKEN, failure.diagnosticCode)
	}

	@Test
	fun `malformed UTF8 is a source read diagnostic with the importing snapshot and cause`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "using Bad: *\nfn main() {}")
		Files.write(directory.resolve("Bad.co"), byteArrayOf(0xc3.toByte(), 0x28))
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request(entry, listOf(SourceModule("M", directory)))) }
		assertEquals(DiagnosticCode.SOURCE_READ, failure.diagnosticCode)
		assertIs<CharacterCodingException>(failure.cause)
		assertEquals("Bad", textAt(failure.sourceLabels.single().span))
		assertEquals(entry.toString(), failure.sourceLabels.single().span.source?.name)
	}

	@Test
	fun `duplicate roots and ids are rejected before reading or parsing a primary file`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "@")
		for (roots in listOf(listOf(SourceModule("A", directory), SourceModule("B", directory.resolve("."))), listOf(SourceModule("A", directory), SourceModule("A", directory)))) {
			assertEquals(DiagnosticCode.INVALID_COMPILATION_REQUEST, assertFailsWith<ClothDiagnostic> { SourceGraphLoader.load(request(entry, roots)) }.diagnosticCode)
		}
	}

	@Test
	fun `successful file selection is independent of selector order and root order`(@TempDir directory: Path) {
		val first = directory.resolve("first")
		val second = directory.resolve("second")
		val entry = source(first, "Main.co", "using Shared: First, Second\nfn main() {}")
		source(second, "Shared.co")
		val roots = listOf(SourceModule("Z", first), SourceModule("A", second))
		val firstGraph = SourceGraphLoader.load(request(entry, roots))
		Files.writeString(entry, "using Shared: Second, First\nfn main() {}")
		val secondGraph = SourceGraphLoader.load(request(entry, roots.reversed()))
		assertEquals(firstGraph.sources.map { it.path }, secondGraph.sources.map { it.path })
		assertEquals(firstGraph.edges.map { it.to }, secondGraph.edges.map { it.to })
		assertEquals("A", secondGraph.edges.single().moduleId)
	}

	@Test
	fun `binding follows completed discovery and CLI resolution failures are reported once`(@TempDir directory: Path) {
		val entry = source(directory, "Main.co", "using Token: DoesNotExist\nfn main() { unknownName }")
		source(directory, "Token.co", "fn privateHelper() {}")
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request(entry, listOf(SourceModule("M", directory)))) }
		assertEquals(DiagnosticCode.IMPORT_NAME_NOT_FOUND, failure.diagnosticCode)
		assertEquals("DoesNotExist", textAt(failure.sourceLabels.single().span))
		val stale = source(directory, "old-artifact", "preserve")
		val bytes = ByteArrayOutputStream()
		PrintStream(bytes, true, Charsets.UTF_8).use { stream ->
			assertEquals(1, runCompiler(arrayOf("run", entry.toString(), stale.toString()), DiagnosticReporter(stream)))
		}
		assertEquals(1, Regex("C3098").findAll(bytes.toString(Charsets.UTF_8)).count())
		assertEquals("preserve", Files.readString(stale))
	}
}
