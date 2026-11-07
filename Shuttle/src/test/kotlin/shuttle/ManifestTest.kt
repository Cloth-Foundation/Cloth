package shuttle

import com.opencastsoftware.yvette.handlers.graphical.GraphicalReportHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shuttle.diagnostics.ShuttleDiagnostic
import shuttle.diagnostics.ShuttleDiagnosticCode
import shuttle.manifest.ManifestLoader
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ManifestTest {

	@Test
	fun `manifest types are strict and dates cannot be coerced into strings`(@TempDir directory: Path) {
		val values = listOf("42", "true", "['Demo']", "{ value = 'Demo' }", "2026-11-07", "12:30:00", "2026-11-07T12:30:00Z")
		for ((index, value) in values.withIndex()) {
			val path = Files.writeString(directory.resolve("$index.toml"), "[project]\nname = $value\nentryPoint = 'Frontend.Main.main'\n[modules]\nFrontend = 'Frontend'\n")
			val failure = assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(path) }
			assertEquals(ShuttleDiagnosticCode.INVALID_MANIFEST_SCHEMA, failure.diagnosticCode, value)
			assertEquals("project.name", failure.keyPath)
			assertNull(failure.syntaxPosition)
		}
	}

	@Test
	fun `required fields and table shapes reject missing and wrong values`(@TempDir directory: Path) {
		val cases = listOf(
			"" to "project",
			"project = 'Demo'" to "project",
			"[project]\nentryPoint = 'Frontend.Main.main'" to "project.name",
			"[project]\nname = 'Demo'" to "project.entryPoint",
			"[project]\nname = 'Demo'\nentryPoint = 1" to "project.entryPoint",
			"[project]\nname = 'Demo'\nentryPoint = 'Frontend.Main.main'" to "modules",
			projectText(modules = "") to "modules",
			projectText(modules = "Frontend = 1") to "modules.Frontend",
			projectText(extra = "[output]\ndirectory = false") to "output.directory",
			projectText(extra = "[dependencies]\n").replace("[dependencies]", "dependencies = []") to "modules.dependencies",
		)
		for ((index, case) in cases.withIndex()) {
			val path = Files.writeString(directory.resolve("$index.toml"), case.first)
			val failure = assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(path) }
			assertEquals(ShuttleDiagnosticCode.INVALID_MANIFEST_SCHEMA, failure.diagnosticCode, case.first)
			assertEquals(case.second, failure.keyPath)
		}
	}

	@Test
	fun `optional metadata remains optional and canonical module fields are supported`(@TempDir directory: Path) {
		val project = ManifestLoader.project(Files.writeString(directory.resolve("build.toml"), projectText()))
		assertNull(project.version)
		assertEquals("build", project.outputDirectory)
		val module = ManifestLoader.module(Files.writeString(directory.resolve("module.toml"), "[module]\nname = 'Frontend'\nversion = '0.0.1'\n[dependencies]\n"))
		assertEquals("Frontend", module.name)
		assertEquals("0.0.1", module.version)
	}

	@Test
	fun `blank values and nonportable artifact filenames are rejected`(@TempDir directory: Path) {
		for ((index, name) in listOf("", " ", ".", "..", "../outside", "folder\\outside", "CON", "com1.txt", "bad:name", "trailing.", "trailing ").withIndex()) {
			val path = Files.writeString(directory.resolve("$index.toml"), projectText(name = name))
			assertEquals("project.name", assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(path) }.keyPath)
		}
		val blankVersion = Files.writeString(directory.resolve("version.toml"), projectText().replace("[modules]", "version = ''\n[modules]"))
		assertEquals("project.version", assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(blankVersion) }.keyPath)
	}

	@Test
	fun `module names and entry components must be identifiers and select main`(@TempDir directory: Path) {
		for ((index, entry) in listOf("Main.main", "Frontend..main", "Frontend.Main.start", "Frontend.1Main.main", "Frontend.fn.main").withIndex()) {
			val path = Files.writeString(directory.resolve("entry$index.toml"), projectText(entry = entry))
			val failure = assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(path) }
			assertEquals(ShuttleDiagnosticCode.INVALID_ENTRY_POINT, failure.diagnosticCode)
			assertEquals("project.entryPoint", failure.keyPath)
		}
		for ((index, id) in listOf("two.parts", "bad-name", "1Bad", "_", "fn").withIndex()) {
			val path = Files.writeString(directory.resolve("module$index.toml"), projectText(modules = "${tomlString(id)} = 'Frontend'"))
			assertEquals(ShuttleDiagnosticCode.INVALID_MANIFEST_SCHEMA, assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(path) }.diagnosticCode)
		}
	}

	@Test
	fun `unknown fields are deterministic and table names are case sensitive`(@TempDir directory: Path) {
		val path = directory.resolve("build.toml")
		Files.writeString(path, projectText().replace("[modules]", "zeta = true\nalpha = true\n[modules]"))
		assertEquals("project.alpha", assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(path) }.keyPath)
		Files.writeString(path, projectText().replace("[project]", "[Project]"))
		assertEquals("project", assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(path) }.keyPath)
		val module = Files.writeString(directory.resolve("module.toml"), "[Module]\nname = 'Frontend'\n")
		assertEquals("module", assertFailsWith<ShuttleDiagnostic> { ManifestLoader.module(module) }.keyPath)
		Files.writeString(path, projectText(extra = "[output]\nzeta = true\nalpha = true\n"))
		assertEquals("output.alpha", assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(path) }.keyPath)
	}

	@Test
	fun `empty dependencies are accepted and nonempty tables fail explicitly`(@TempDir directory: Path) {
		val path = Files.writeString(directory.resolve("build.toml"), projectText(extra = "[dependencies]\n"))
		assertEquals("Demo", ManifestLoader.project(path).name)
		Files.writeString(path, projectText(extra = "[dependencies]\nexample = '1.0'\n"))
		assertEquals(ShuttleDiagnosticCode.UNSUPPORTED_DEPENDENCIES, assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(path) }.diagnosticCode)
		val module = Files.writeString(directory.resolve("module.toml"), "[module]\nname = 'Frontend'\n[dependencies]\nexample = '1.0'\n")
		assertEquals(ShuttleDiagnosticCode.UNSUPPORTED_DEPENDENCIES, assertFailsWith<ShuttleDiagnostic> { ManifestLoader.module(module) }.diagnosticCode)
	}

	@Test
	fun `TOML syntax stops at the first failure and retains exact snapshot and position`(@TempDir directory: Path) {
		val path = directory.resolve("build.toml")
		val text = "[project]\r\nname = @\r\nversion = @\r\n"
		Files.writeString(path, text)
		val failure = assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(path) }
		assertEquals(ShuttleDiagnosticCode.INVALID_TOML, failure.diagnosticCode)
		assertEquals(2, assertNotNull(failure.syntaxPosition).startLine)
		assertSame(failure.source, failure.syntaxPosition.source)
		Files.writeString(path, projectText())
		assertEquals(text, failure.source?.text)
		assertEquals(path, failure.manifestPath)
		assertNotNull(failure.cause)
		assertNull(failure.keyPath)
	}

	@Test
	fun `duplicate TOML definitions and invalid datetime values fail during parsing`(@TempDir directory: Path) {
		val malformed = listOf("name = 'a'\nname = 'b'\n", "[project]\n[project]\n", "project = 'a'\n[project]\n", "name = 2026-13-40\n", "name = '\\'\nother = @\n")
		for ((index, text) in malformed.withIndex()) {
			val path = Files.writeString(directory.resolve("$index.toml"), text)
			assertEquals(ShuttleDiagnosticCode.INVALID_TOML, assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(path) }.diagnosticCode, text)
		}
	}

	@Test
	fun `strict UTF8 rejects malformed bytes without replacing their text`(@TempDir directory: Path) {
		val path = Files.write(directory.resolve("invalid.toml"), byteArrayOf(0xc3.toByte(), 0x28))
		val failure = assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(path) }
		assertEquals(ShuttleDiagnosticCode.INVALID_MANIFEST_ENCODING, failure.diagnosticCode)
		assertIs<CharacterCodingException>(failure.cause)
		assertEquals(path, failure.manifestPath)
		assertNull(failure.source)
	}

	@Test
	fun `Unicode multiline strings and standard arrays preserve TOML semantics`(@TempDir directory: Path) {
		val path = Files.writeString(directory.resolve("build.toml"), projectText(name = "Demo λ 🚀").replace("[modules]", "version = \"\"\"\nfirst\nsecond\"\"\"\n[modules]"))
		val project = ManifestLoader.project(path)
		assertEquals("Demo λ 🚀", project.name)
		assertEquals("first\nsecond", project.version)
		Files.writeString(path, projectText().replace("name = \"Demo\"", "name = ['Demo',]"))
		assertEquals(ShuttleDiagnosticCode.INVALID_MANIFEST_SCHEMA, assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(path) }.diagnosticCode)
	}

	@Test
	fun `schema diagnostics render S codes with honest manifest context`(@TempDir directory: Path) {
		val path = Files.writeString(directory.resolve("build.toml"), "# Context\n[project]\nname = 42\n")
		val failure = assertFailsWith<ShuttleDiagnostic> { ManifestLoader.project(path) }
		assertNull(failure.syntaxPosition)
		assertEquals("project.name", failure.keyPath)
		val output = StringBuilder()
		GraphicalReportHandler.builder().withColours(false).withUnicode(false).withCauseChain(false).buildFor(output).display(failure, output)
		assertContains(output.toString(), "S0005")
		assertContains(output.toString(), "project.name")
		assertContains(output.toString(), "In this manifest")
		assertEquals(ShuttleDiagnosticCode.entries.size, ShuttleDiagnosticCode.entries.map { it.id }.toSet().size)
	}
}
