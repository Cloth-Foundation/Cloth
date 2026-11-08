package cloth

import cloth.api.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.settings.LinkOptions
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import cloth.toolchain.StandardLibrary
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.Path
import kotlin.test.*

/** Command selection and request attachment without loading a native backend. */
class StandardLibraryTest {
	@TempDir lateinit var temporary: Path

	private fun source(root: Path, name: String, text: String = "fn main() {}\n"): Path {
		val file = root.resolve(name)
		Files.createDirectories(file.parent)
		return Files.writeString(file, text)
	}
	private fun fixture(layout: String = "development"): Pair<Path, StandardLibrary> {
		val home = Files.createDirectory(temporary.resolve("home"))
		val library = home.resolve(if (layout == "installed") "stdlib" else "Standard-Library")
		source(library, "module.toml", "not parsed by shared selection\n")
		source(library, "cloth/Library.co", "@ invalid but unreachable source\n")
		if (layout == "installed") descriptor(home)
		return home to StandardLibrary.fromContext(home.toString(), layout)
	}
	private fun descriptor(home: Path, text: String = "format=1\ncompiler.version=test\nstdlib.version=dev\nstdlib.revision=local\n"): Path = source(home, "toolchain.properties", text)
	private fun request(modules: List<SourceModule> = emptyList(), output: Path = temporary.resolve("out.o"), kind: OutputKind = OutputKind.OBJECT) =
		CompilationRequest(temporary.resolve("Main.co"), output, modules, kind)
	private fun failure(code: DiagnosticCode, action: () -> Unit): ClothDiagnostic {
		val result = assertFailsWith<ClothDiagnostic>(block = action)
		assertEquals(code, result.diagnosticCode)
		return result
	}

	@Test fun `development selection needs only markers and attaches a canonical explicit role`() {
		val (home, library) = fixture()
		val attached = library.attachTo(request())
		assertEquals(listOf(SourceModule("StandardLibrary", home.resolve("Standard-Library").toRealPath(), SourceModuleRole.STANDARD_LIBRARY)), attached.modules)
		assertFalse(Files.exists(home.resolve("toolchain.properties")))
		assertFalse(Files.exists(temporary.resolve("out.o")))
	}

	@Test fun `installed descriptor is required validated and decoded as strict UTF8`() {
		val (home) = fixture("installed")
		for (text in listOf("format=2\n", "format=1\n", "format=1\ncompiler.version=c\nstdlib.version= \nstdlib.revision=r\n", "format=1\ncompiler.version=c\nstdlib.version=v\n")) {
			descriptor(home, text)
			failure(DiagnosticCode.INVALID_STANDARD_LIBRARY) { StandardLibrary.fromContext(home.toString(), "installed") }
		}
		Files.write(home.resolve("toolchain.properties"), byteArrayOf(0xc3.toByte(), 0x28))
		assertNotNull(failure(DiagnosticCode.INVALID_STANDARD_LIBRARY) { StandardLibrary.fromContext(home.toString(), "installed") }.cause)
		Files.delete(home.resolve("toolchain.properties"))
		failure(DiagnosticCode.INVALID_STANDARD_LIBRARY) { StandardLibrary.fromContext(home.toString(), "installed") }
	}

	@Test fun `selection rejects missing relative and unknown contexts without cwd fallback`() {
		for ((home, layout) in listOf(null to null, "" to "installed", "." to "development", temporary.toString() to "unknown", temporary.resolve("missing").toString() to "installed")) {
			failure(DiagnosticCode.INVALID_STANDARD_LIBRARY) { StandardLibrary.fromContext(home, layout) }
		}
	}

	@Test fun `marker filename case is exact on every host`() {
		val (home, library) = fixture()
		Files.move(library.sourceRoot.resolve("module.toml"), library.sourceRoot.resolve("wrong.toml"))
		Files.move(library.sourceRoot.resolve("wrong.toml"), library.sourceRoot.resolve("Module.toml"))
		assertContains(failure(DiagnosticCode.INVALID_STANDARD_LIBRARY) { StandardLibrary.fromContext(home.toString(), "development") }.guidance.orEmpty(), "exact case")
	}

	@Test fun `matching configured library is promoted once and request options are retained`() {
		val (_, library) = fixture()
		val options = LinkOptions(linker = temporary.resolve("linker"), libraries = listOf("one", "two"))
		val original = CompilationRequest(temporary.resolve("Main.co"), temporary.resolve("out.o"),
			listOf(SourceModule("Existing", library.sourceRoot.resolve("."))), OutputKind.OBJECT,
			targetTriple = "not-selected-yet", linkOptions = options)
		val attached = library.attachTo(original)
		assertEquals("Existing", attached.modules.single().id)
		assertEquals(SourceModuleRole.STANDARD_LIBRARY, attached.modules.single().role)
		assertEquals(SourceModuleRole.REGULAR, original.modules.single().role)
		assertEquals("not-selected-yet", attached.targetTriple)
		assertEquals(options, attached.linkOptions)
		assertNull(attached.entryFunction)
		assertEquals(attached.modules, validateCompilationRequest(attached))
	}

	@Test fun `physical directory aliases are promoted without a second root`() {
		val (_, library) = fixture()
		val alias = temporary.resolve("alias")
		try { Files.createSymbolicLink(alias, library.sourceRoot) }
		catch (failure: FileSystemException) {
			if (!System.getProperty("os.name").startsWith("Windows")) throw failure
			val process = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", alias.toString(), library.sourceRoot.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
			assertEquals(0, process.waitFor())
		}
		try {
			val attached = library.attachTo(request(listOf(SourceModule("Alias", alias))))
			assertEquals(listOf(SourceModule("Alias", library.sourceRoot, SourceModuleRole.STANDARD_LIBRARY)), attached.modules)
		} finally { Files.delete(alias) }
	}

	@Test fun `id conflicts and preexisting duplicate modules fail instead of being silently repaired`() {
		val (_, library) = fixture()
		val project = Files.createDirectory(temporary.resolve("project"))
		failure(DiagnosticCode.STANDARD_LIBRARY_MODULE_CONFLICT) { library.attachTo(request(listOf(SourceModule("StandardLibrary", project)))) }
		for (modules in listOf(
			listOf(SourceModule("same", project), SourceModule("same", library.sourceRoot)),
			listOf(SourceModule("one", library.sourceRoot), SourceModule("two", library.sourceRoot.resolve("."))),
			listOf(SourceModule("other", project, SourceModuleRole.STANDARD_LIBRARY)),
		)) failure(DiagnosticCode.INVALID_COMPILATION_REQUEST) { library.attachTo(request(modules)) }
		val promoted = library.attachTo(request(listOf(SourceModule("StandardLibrary", project), SourceModule("Selected", library.sourceRoot))))
		assertEquals("Selected", promoted.modules.single { it.role == SourceModuleRole.STANDARD_LIBRARY }.id)
	}

	@Test fun `a request cannot select two standard library roles`() {
		val (_, library) = fixture()
		val other = Files.createDirectory(temporary.resolve("other"))
		failure(DiagnosticCode.INVALID_COMPILATION_REQUEST) {
			validateCompilationRequest(request(listOf(SourceModule("one", library.sourceRoot, SourceModuleRole.STANDARD_LIBRARY), SourceModule("two", other, SourceModuleRole.STANDARD_LIBRARY))))
		}
	}

	@Test fun `selected metadata and marker files are protected including hard links and executable suffixes`() {
		val (home, library) = fixture("installed")
		for (input in listOf(library.sourceRoot.resolve("module.toml"), library.sourceRoot.resolve("cloth/Library.co"), home.resolve("toolchain.properties"))) {
			failure(DiagnosticCode.OUTPUT_OVERWRITES_TOOLCHAIN_INPUT) { library.attachTo(request(output = input)) }
		}
		val alias = Files.createLink(temporary.resolve("metadata-link"), home.resolve("toolchain.properties"))
		failure(DiagnosticCode.OUTPUT_OVERWRITES_TOOLCHAIN_INPUT) { library.attachTo(request(output = alias)) }
		Files.createLink(temporary.resolve("program.exe"), library.sourceRoot.resolve("module.toml"))
		failure(DiagnosticCode.OUTPUT_OVERWRITES_TOOLCHAIN_INPUT) { library.attachTo(request(output = temporary.resolve("program"), kind = OutputKind.EXECUTABLE)) }
	}

	@Test fun `public requests keep ordinary roots and never discover the process installation`() {
		val root = Files.createDirectory(temporary.resolve("regular"))
		val main = source(root, "Main.co", "using cloth.Own : value\nfn main() {}\n")
		source(root, "cloth/Own.co", "pub fn<int> value() = 1\n")
		val previous = System.getProperty("cloth.toolchain.home")
		try {
			System.setProperty("cloth.toolchain.home", temporary.resolve("missing").toString())
			val graph = SourceGraphLoader.load(CompilationRequest(main, temporary.resolve("unused.o"), listOf(SourceModule("StandardLibrary", root)), OutputKind.OBJECT))
			assertEquals(2, graph.sources.size)
		} finally {
			if (previous == null) System.clearProperty("cloth.toolchain.home") else System.setProperty("cloth.toolchain.home", previous)
		}
	}

	@Test fun `missing default library fails once before invalid source and creates no output`() {
		val main = source(temporary, "bad.co", "@")
		val output = temporary.resolve("not-created/out.o")
		val previous = System.getProperty("cloth.toolchain.home")
		val reports = StringBuilder()
		try {
			System.setProperty("cloth.toolchain.home", temporary.resolve("missing").toString())
			assertEquals(1, runCompiler(arrayOf("--emit", "object", main.toString(), output.toString()), DiagnosticReporter(reports)))
		} finally {
			if (previous == null) System.clearProperty("cloth.toolchain.home") else System.setProperty("cloth.toolchain.home", previous)
		}
		assertEquals(1, Regex("C0018").findAll(reports).count())
		assertFalse(Files.exists(output.parent))
	}
}
