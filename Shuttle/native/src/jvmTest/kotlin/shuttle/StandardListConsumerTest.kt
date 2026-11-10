package shuttle

import cloth.api.ClothCompiler
import cloth.api.SourceModuleRole
import cloth.settings.OutputKind
import cloth.settings.StandardLibrarySelection
import cloth.toolchain.StandardLibrary
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shuttle.project.ProjectLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Shuttle consumes generic collections entirely through Compiler's public request API. */
class StandardListConsumerTest {
	private val example = Path.of("examples/Collections").toAbsolutePath()
	private val library = Path.of("Standard-Library").toAbsolutePath()
	private fun project(root: Path): Path {
		for (relative in listOf("build.toml", "app/module.toml", "app/Main.co")) {
			val target = root.resolve(relative)
			Files.createDirectories(target.parent)
			Files.copy(example.resolve(relative), target)
		}
		return root
	}

	@Test fun `maintained Collections project attaches the real library through the plain request boundary`(@TempDir root: Path) {
		val loaded = ProjectLoader.load(project(root.resolve("project")))
		val request = StandardLibrary.select(StandardLibrarySelection.Explicit(library), root)!!.attachTo(loaded.toCompilationRequest(OutputKind.OBJECT, root.resolve("collections.o"), "aarch64-unknown-linux-gnu"))
		assertEquals(2, request.modules.size)
		assertEquals(library.toRealPath(), request.modules.single { it.role == SourceModuleRole.STANDARD_LIBRARY }.sourceRoot)
		assertTrue(Files.size(ClothCompiler.compile(request).artifact) > 0)
	}

	@Test fun `real Shuttle and standalone commands execute actual List with default and explicit selection`(@TempDir root: Path) {
		val project = project(root.resolve("project with spaces"))
		val targets = if (System.getProperty("os.name").startsWith("Windows")) listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc") else listOf<String?>(null)
		for (target in targets) for (explicit in listOf(false, true)) {
			val options = (target?.let { listOf("--target", it) } ?: emptyList()) + if (explicit) listOf("--stdlib", library.toString()) else emptyList()
			for ((entry, arguments) in listOf(
				null to (listOf("run", "--project", project.toString()) + options),
				"cloth.EntryKt" to (listOf("run", project.resolve("app/Main.co").toString(), root.resolve("standalone_${target ?: "native"}_$explicit").toString()) + options),
			)) {
				val result = shuttleProcess(root, arguments, mainClass = entry)
				assertEquals(0, result.status, result.output)
				assertContains(result.output.replace("\r\n", "\n"), "1\n[2, 9]\n2\n[1, 9]\n[2, 3]\n[1]\n[2, 3]\n")
				assertContains(result.output, "PEC: 0")
			}
		}
	}

	@Test fun `disabled commands report the missing List import without producing an artifact`(@TempDir root: Path) {
		val project = project(root.resolve("project"))
		for ((entry, arguments) in listOf(
			null to listOf("build", "--project", project.toString(), "--no-stdlib", "--emit", "object"),
			"cloth.EntryKt" to listOf(project.resolve("app/Main.co").toString(), root.resolve("absent.o").toString(), "--no-stdlib", "--emit", "object"),
		)) {
			val result = shuttleProcess(root, arguments, mainClass = entry)
			assertEquals(1, result.status, result.output)
			assertContains(result.output, "cloth.collections.List")
			assertEquals(1, Regex("C\\d{4}").findAll(result.output).count())
			assertFalse(result.output.contains("PEC:"))
		}
		assertFalse(Files.exists(root.resolve("absent.o")))
		assertFalse(Files.exists(project.resolve("build/Collections.o")))
	}
}
