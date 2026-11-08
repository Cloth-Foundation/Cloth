package shuttle

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.settings.LinkerDriver
import cloth.settings.RuntimeLinkage
import cloth.settings.StandardLibrarySelection
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shuttle.command.ShuttleSettings
import shuttle.command.buildProject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ShuttleBuildTest {
	@Test
	fun `library flags share parsing while run arguments remain literal`() {
		val settings = ShuttleSettings(arrayOf("run", "--stdlib=first", "--project=project", "--stdlib", "last with spaces", "--", "--no-stdlib", "--stdlib=program"))
		assertEquals(StandardLibrarySelection.Explicit(Path.of("last with spaces")), settings.compiler.standardLibrary)
		assertEquals(listOf("--no-stdlib", "--stdlib=program"), settings.arguments)
		assertEquals(StandardLibrarySelection.Disabled, ShuttleSettings(arrayOf("build", "--no-stdlib", "--no-stdlib")).compiler.standardLibrary)
		for (args in listOf(arrayOf("build", "--no-stdlib", "--stdlib=checkout"), arrayOf("build", "--stdlib=checkout", "--no-stdlib"), arrayOf("build", "--no-stdlib=true"))) {
			assertEquals(DiagnosticCode.INVALID_OPTION_VALUE, assertFailsWith<ClothDiagnostic> { ShuttleSettings(args) }.diagnosticCode)
		}
		assertEquals(DiagnosticCode.MISSING_OPTION_VALUE, assertFailsWith<ClothDiagnostic> {
			ShuttleSettings(arrayOf("build", "--stdlib", "--project=project", "later-path"))
		}.diagnosticCode)
	}

	@Test
	fun `build returns the actual executable filename for a multi module project`(@TempDir directory: Path) {
		val project = importedProject(directory.resolve("project with spaces λ"))
		val artifact = buildProject(ShuttleSettings(arrayOf("build", "--project", project.root.toString())), directory)
		assertEquals(ExecutableFormat.current.executablePath(project.root.resolve("build/Demo")), artifact)
		assertTrue(Files.isRegularFile(artifact))
	}

	@Test
	fun `object build forwards foreign target and linker options without discovering a linker or requiring main`(@TempDir directory: Path) {
		val project = importedProject(directory)
		Files.writeString(project.source, "using support.Worker: answer\npub fn<int> library() = answer()")
		val reports = StringBuilder()
		assertEquals(0, runShuttle(arrayOf("build", "--emit=object", "--target=aarch64-unknown-linux-gnu", "--linker", "does-not-exist", "-o", "objects/foreign.o", "-Lrelative/libs", "-lfoo"), DiagnosticReporter(reports), directory), reports.toString())
		val bytes = Files.readAllBytes(directory.resolve("objects/foreign.o"))
		assertEquals(listOf(0x7f, 0x45, 0x4c, 0x46), bytes.take(4).map { it.toInt() and 255 })
		assertEquals(0xb7, bytes[18].toInt() and 255)
		assertTrue(reports.isEmpty())
		assertFalse(Files.exists(directory.resolve("build")))
	}

	@Test
	fun `nearest ancestor project selection works without changing invocation cwd`(@TempDir directory: Path) {
		val project = importedProject(directory)
		val nested = Files.createDirectories(directory.resolve("tools/nested"))
		val reports = StringBuilder()
		assertEquals(0, runShuttle(arrayOf("build", "--emit", "object", "--output=local.o"), DiagnosticReporter(reports), nested), reports.toString())
		assertTrue(Files.isRegularFile(nested.resolve("local.o")))
		assertFalse(Files.exists(project.root.resolve("build")))
	}

	@Test
	fun `project flags wrap the shared compiler option parser and preserve ordered overrides`() {
		val settings = ShuttleSettings(arrayOf("build", "--project=example", "-o=result", "--target", "x86_64-pc-windows-msvc", "--runtime", "static", "--linker-driver", "lld", "-Lone", "--library-path", "two", "-lfirst", "--library=second", "--debug"))
		assertEquals(Path.of("example"), settings.project)
		assertEquals(Path.of("result"), settings.output)
		assertEquals(RuntimeLinkage.STATIC, settings.compiler.linkOptions.runtime)
		assertEquals(LinkerDriver.LLD, settings.compiler.linkOptions.driver)
		assertEquals(listOf(Path.of("one"), Path.of("two")), settings.compiler.linkOptions.libraryPaths)
		assertEquals(listOf("first", "second"), settings.compiler.linkOptions.libraries)
		assertTrue(settings.compiler.debug)
	}

	@Test
	fun `original imported compiler diagnostic is rendered exactly once`(@TempDir directory: Path) {
		val project = importedProject(directory)
		val worker = directory.resolve("Library/support/Worker.co")
		val text = "pub fn<int> answer() = unknownName\nfn later() { anotherUnknown }"
		Files.writeString(worker, text)
		val failure = assertFailsWith<ClothDiagnostic> { buildProject(ShuttleSettings(arrayOf("build")), directory) }
		assertEquals(DiagnosticCode.UNKNOWN_NAME, failure.diagnosticCode)
		assertEquals(text, failure.sourceLabels.single().span.source?.text)
		assertEquals(worker.toString(), failure.sourceLabels.single().span.source?.name)
		val expected = StringBuilder().also { DiagnosticReporter(it).report(failure) }.toString()
		val rendered = StringBuilder()
		assertEquals(1, runShuttle(arrayOf("build"), DiagnosticReporter(rendered), directory))
		assertEquals(expected, rendered.toString())
		assertEquals(1, Regex("C3020").findAll(rendered).count())
		assertFalse(Files.exists(project.root.resolve("build")))
	}

	@Test
	fun `invalid nearest manifest reports one Shuttle failure and creates no output`(@TempDir directory: Path) {
		importedProject(directory)
		val nested = Files.createDirectories(directory.resolve("nested"))
		Files.writeString(nested.resolve("build.toml"), "[project\n")
		val reports = StringBuilder()
		assertEquals(1, runShuttle(arrayOf("build"), DiagnosticReporter(reports), nested))
		assertEquals(1, Regex("S0004").findAll(reports).count())
		assertFalse(Files.exists(directory.resolve("build")))
		assertFalse(Files.exists(nested.resolve("build")))
	}

	@Test
	fun `commands reject bad arguments and object runs before loading a project`(@TempDir directory: Path) {
		for ((args, code) in listOf(
			arrayOf<String>() to "S0014", arrayOf("unknown") to "S0014", arrayOf("build", "--project") to "S0014",
			arrayOf("build", "extra") to "S0014", arrayOf("build", "--", "argument") to "S0014",
			arrayOf("run", "--emit=object") to "S0015", arrayOf("build", "--unknown") to "C0001")) {
			val reports = StringBuilder()
			assertEquals(1, runShuttle(args, DiagnosticReporter(reports), directory), args.toList().toString())
			assertEquals(1, Regex(code).findAll(reports).count(), reports.toString())
		}
		assertTrue(Files.list(directory).use { it.findAny().isEmpty })
	}

	@Test
	fun `artifact aliases cannot overwrite project or module manifests`(@TempDir directory: Path) {
		val project = importedProject(directory)
		val inputs = listOf(project.build, project.moduleManifest, directory.resolve("Library/module.toml"))
		for ((index, input) in inputs.withIndex()) {
			val original = Files.readAllBytes(input)
			val alias = Files.createLink(directory.resolve("alias$index.o"), input)
			val reports = StringBuilder()
			assertEquals(1, runShuttle(arrayOf("build", "--emit", "object", "-o", alias.toString()), DiagnosticReporter(reports), directory))
			assertEquals(1, Regex("S0012").findAll(reports).count())
			assertContentEquals(original, Files.readAllBytes(input))
		}
		val executable = directory.resolve("alias")
		Files.createLink(executable.resolveSibling("alias.exe"), project.build)
		val reports = StringBuilder()
		assertEquals(1, runShuttle(arrayOf("build", "-o", executable.toString()), DiagnosticReporter(reports), directory))
		assertContains(reports.toString(), "S0012")
	}

	@Test
	fun `compiler protects imported sources from Shuttle output overrides`(@TempDir directory: Path) {
		importedProject(directory)
		val source = directory.resolve("Library/support/Worker.co")
		val original = Files.readString(source)
		val reports = StringBuilder()
		assertEquals(1, runShuttle(arrayOf("build", "--emit=object", "-o", source.toString()), DiagnosticReporter(reports), directory))
		assertContains(reports.toString(), "C0014")
		assertEquals(original, Files.readString(source))
	}
}
