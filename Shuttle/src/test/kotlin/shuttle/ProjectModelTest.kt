package shuttle

import cloth.api.SourceModule
import cloth.settings.LinkOptions
import cloth.settings.OutputKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shuttle.diagnostics.ShuttleDiagnostic
import shuttle.diagnostics.ShuttleDiagnosticCode
import shuttle.project.ProjectLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ProjectModelTest {

	@Test
	fun `nearest project and explicit selections produce the same rooted request`(@TempDir directory: Path) {
		val fixture = projectFixture(directory.resolve("project with spaces λ"))
		val nested = Files.createDirectories(fixture.root.resolve("nested/deeper"))
		val projects = listOf(ProjectLoader.load(fixture.root), ProjectLoader.load(nested),
			ProjectLoader.load(nested, Path.of("../..")), ProjectLoader.load(directory, fixture.build),
			ProjectLoader.load(directory, fixture.root))
		for (project in projects) {
			val request = project.toCompilationRequest()
			assertEquals(fixture.root, project.root)
			assertEquals(fixture.source.toRealPath(), request.sourceFile)
			assertEquals(fixture.root.resolve("build/Demo"), request.outputFile)
			assertEquals(listOf(SourceModule("Frontend", fixture.module.toRealPath())), request.modules)
			assertEquals("main", request.entryFunction)
			assertFalse(Files.exists(project.outputDirectory))
		}
	}

	@Test
	fun `invalid nearest and explicit manifests do not fall back to another project`(@TempDir directory: Path) {
		val outer = projectFixture(directory)
		val nested = Files.createDirectories(directory.resolve("nested"))
		val invalid = Files.writeString(nested.resolve("build.toml"), "@")
		val first = assertFailsWith<ShuttleDiagnostic> { ProjectLoader.load(nested) }
		assertEquals(ShuttleDiagnosticCode.INVALID_TOML, first.diagnosticCode)
		assertEquals(invalid, first.manifestPath)
		val explicit = assertFailsWith<ShuttleDiagnostic> { ProjectLoader.load(outer.root, directory.resolve("missing/build.toml")) }
		assertEquals(ShuttleDiagnosticCode.INVALID_PROJECT_SELECTION, explicit.diagnosticCode)
	}

	@Test
	fun `missing and invalid project selection fail without creating output`(@TempDir directory: Path) {
		val empty = Files.createDirectory(directory.resolve("empty"))
		assertEquals(ShuttleDiagnosticCode.PROJECT_NOT_FOUND, assertFailsWith<ShuttleDiagnostic> { ProjectLoader.load(empty) }.diagnosticCode)
		val other = Files.writeString(empty.resolve("other.toml"), "")
		assertEquals(ShuttleDiagnosticCode.INVALID_PROJECT_SELECTION, assertFailsWith<ShuttleDiagnostic> { ProjectLoader.load(empty, other) }.diagnosticCode)
		assertEquals(ShuttleDiagnosticCode.MANIFEST_READ, assertFailsWith<ShuttleDiagnostic> { ProjectLoader.load(empty, empty.resolve("build.toml")) }.diagnosticCode)
		assertFalse(Files.exists(empty.resolve("build")))
	}

	@Test
	fun `module metadata is validated in ordinal id order and fails first`(@TempDir directory: Path) {
		Files.createDirectories(directory.resolve("Alpha"))
		val alpha = Files.writeString(directory.resolve("Alpha/module.toml"), "[module]\nversion = '1'\n")
		Files.writeString(directory.resolve("build.toml"), projectText(entry = "Alpha.Main.main", modules = "Zulu = 'missing'\nAlpha = 'Alpha'"))
		val failure = assertFailsWith<ShuttleDiagnostic> { ProjectLoader.load(directory) }
		assertEquals(ShuttleDiagnosticCode.INVALID_MANIFEST_SCHEMA, failure.diagnosticCode)
		assertEquals(alpha, failure.manifestPath)
		assertEquals("module.name", failure.keyPath)
	}

	@Test
	fun `physical duplicate roots and module name mismatches have dedicated errors`(@TempDir directory: Path) {
		val fixture = projectFixture(directory)
		Files.writeString(fixture.moduleManifest, "[module]\nname = 'Alpha'\n")
		Files.writeString(fixture.build, projectText(entry = "Alpha.Main.main", modules = "Zulu = 'Frontend/.'\nAlpha = 'Frontend'"))
		val duplicate = assertFailsWith<ShuttleDiagnostic> { ProjectLoader.load(directory) }
		assertEquals(ShuttleDiagnosticCode.DUPLICATE_MODULE_ROOT, duplicate.diagnosticCode)
		assertContains(duplicate.summary, "Alpha")
		assertContains(duplicate.summary, "Zulu")
		Files.writeString(fixture.build, projectText())
		val mismatch = assertFailsWith<ShuttleDiagnostic> { ProjectLoader.load(directory) }
		assertEquals(ShuttleDiagnosticCode.MODULE_NAME_MISMATCH, mismatch.diagnosticCode)
		assertEquals(fixture.moduleManifest, mismatch.manifestPath)
	}

	@Test
	fun `missing module roots and manifests fail before entry resolution`(@TempDir directory: Path) {
		val fixture = projectFixture(directory)
		Files.writeString(fixture.build, projectText(modules = "Frontend = 'missing'"))
		assertEquals(ShuttleDiagnosticCode.INVALID_MODULE_ROOT, assertFailsWith<ShuttleDiagnostic> { ProjectLoader.load(directory) }.diagnosticCode)
		Files.writeString(fixture.build, projectText())
		Files.delete(fixture.moduleManifest)
		val failure = assertFailsWith<ShuttleDiagnostic> { ProjectLoader.load(directory) }
		assertEquals(ShuttleDiagnosticCode.MANIFEST_READ, failure.diagnosticCode)
		assertEquals(fixture.moduleManifest, failure.manifestPath)
	}

	@Test
	fun `entry can live in nested module folders without additional module manifests`(@TempDir directory: Path) {
		val fixture = projectFixture(directory)
		val nested = Files.createDirectories(fixture.module.resolve("cli/tools"))
		val main = Files.writeString(nested.resolve("Main.co"), "fn main() {}")
		Files.writeString(fixture.build, projectText(entry = "Frontend.cli.tools.Main.main"))
		val project = ProjectLoader.load(nested)
		assertEquals(main.toRealPath(), project.sourceFile)
		assertEquals(fixture.root, project.root)
		assertEquals(listOf(fixture.module.toRealPath()), project.modules.map { it.sourceRoot })
		assertFalse(Files.exists(project.outputDirectory))
	}

	@Test
	fun `entry files must exist with exact component case and declared ownership`(@TempDir directory: Path) {
		val fixture = projectFixture(directory)
		for (entry in listOf("Missing.Main.main", "Frontend.Missing.main", "Frontend.main.main", "Frontend.Main.Child.main")) {
			Files.writeString(fixture.build, projectText(entry = entry))
			val failure = assertFailsWith<ShuttleDiagnostic> { ProjectLoader.load(directory) }
			assertEquals(ShuttleDiagnosticCode.INVALID_ENTRY_POINT, failure.diagnosticCode, entry)
			assertEquals("project.entryPoint", failure.keyPath)
		}
	}

	@Test
	fun `configuration can point outside the project and normalizes output without creating it`(@TempDir directory: Path) {
		val fixture = projectFixture(directory.resolve("project"))
		Files.writeString(fixture.build, projectText(modules = "Frontend = ${tomlString(fixture.module.toString())}", extra = "[output]\ndirectory = '../artifacts/new/../final'"))
		val project = ProjectLoader.load(fixture.root)
		assertEquals(directory.resolve("artifacts/final"), project.outputDirectory)
		assertFalse(Files.exists(directory.resolve("artifacts")))
		val blocked = Files.writeString(fixture.root.resolve("blocked"), "keep")
		Files.writeString(fixture.build, projectText(extra = "[output]\ndirectory = 'blocked/output'"))
		assertEquals(ShuttleDiagnosticCode.INVALID_OUTPUT_SELECTION, assertFailsWith<ShuttleDiagnostic> { ProjectLoader.load(fixture.root) }.diagnosticCode)
		assertEquals("keep", Files.readString(blocked))
	}

	@Test
	fun `request conversion forwards options and object mode does not require a main declaration`(@TempDir directory: Path) {
		val fixture = projectFixture(directory)
		Files.writeString(fixture.source, "this is not Cloth source @")
		val project = ProjectLoader.load(directory)
		val request = project.toCompilationRequest(outputKind = OutputKind.OBJECT, targetTriple = "not-a-target",
			linkOptions = LinkOptions(linker = directory.resolve("missing-linker")))
		assertEquals(directory.resolve("build/Demo.o"), request.outputFile)
		assertNull(request.entryFunction)
		assertEquals("not-a-target", request.targetTriple)
		assertEquals(directory.resolve("missing-linker"), request.linkOptions.linker)
		assertFalse(Files.exists(project.outputDirectory))
		val explicit = project.toCompilationRequest(outputKind = OutputKind.OBJECT, outputFile = directory.resolve("chosen.obj"))
		assertEquals(directory.resolve("chosen.obj"), explicit.outputFile)
		assertEquals(ShuttleDiagnosticCode.INVALID_OUTPUT_SELECTION, assertFailsWith<ShuttleDiagnostic> { project.toCompilationRequest(outputFile = Path.of("relative")) }.diagnosticCode)
	}

	@Test
	fun `resolved collections and source snapshots cannot be changed through aliases`(@TempDir directory: Path) {
		val fixture = projectFixture(directory)
		val project = ProjectLoader.load(directory)
		val oldText = project.manifest.source.text
		assertFailsWith<UnsupportedOperationException> { (project.modules as MutableList).clear() }
		assertFailsWith<UnsupportedOperationException> { (project.manifests as MutableList).clear() }
		Files.writeString(fixture.build, "@")
		assertEquals(oldText, project.manifest.source.text)
		assertEquals(directory.resolve("build/Demo"), project.toCompilationRequest().outputFile)
	}

	@Test
	fun `output protection checks every manifest and possible executable suffix without choosing an ABI`(@TempDir directory: Path) {
		val fixture = projectFixture(directory)
		val project = ProjectLoader.load(directory)
		val alias = Files.createLink(directory.resolve("alias.o"), fixture.moduleManifest)
		for (path in listOf(fixture.build, fixture.moduleManifest, alias)) {
			val failure = assertFailsWith<ShuttleDiagnostic> { project.toCompilationRequest(outputKind = OutputKind.OBJECT, outputFile = path) }
			assertEquals(ShuttleDiagnosticCode.OUTPUT_OVERWRITES_MANIFEST, failure.diagnosticCode)
		}
		Files.createLink(directory.resolve("program.exe"), fixture.build)
		assertEquals(ShuttleDiagnosticCode.OUTPUT_OVERWRITES_MANIFEST, assertFailsWith<ShuttleDiagnostic> { project.toCompilationRequest(outputFile = directory.resolve("program"), targetTriple = "not-a-target") }.diagnosticCode)
		assertEquals("Demo", ProjectLoader.load(directory).name)
	}

	@Test
	fun `the migrated Bootstrap project loads and prepares its explicit compiler request`() {
		val root = Path.of("Bootstrap").toAbsolutePath()
		val project = ProjectLoader.load(root)
		assertEquals("Cloth", project.name)
		assertEquals(listOf("Backend", "Compiler", "Frontend"), project.modules.map { it.name })
		assertEquals(root.resolve("Frontend/Main.co"), project.sourceFile)
		assertEquals(root.resolve("build/Cloth"), project.toCompilationRequest().outputFile)
	}
}
