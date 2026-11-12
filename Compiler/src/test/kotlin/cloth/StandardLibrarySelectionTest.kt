package cloth

import cloth.api.CompilationRequest
import cloth.api.SourceModuleRole
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.settings.CompilerSettings
import cloth.settings.OutputKind
import cloth.settings.RuntimeLinkage
import cloth.settings.StandardLibrarySelection
import cloth.toolchain.StandardLibrary
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Selection contracts that must work without a native backend or an installation. */
class StandardLibrarySelectionTest {

	@TempDir
	lateinit var temporary: Path

	private fun failure(code: DiagnosticCode, action: () -> Unit): ClothDiagnostic {
		val result = assertFailsWith<ClothDiagnostic>(block = action)
		assertEquals(code, result.diagnosticCode)
		return result
	}

	private fun library(root: Path): Path {
		Files.createDirectories(root.resolve("cloth"))
		Files.writeString(root.resolve("module.toml"), "not parsed by command selection\n")
		Files.writeString(root.resolve("cloth/Library.co"), "@ invalid but unreachable\n")
		return root
	}

	private fun withContext(home: String?, layout: String?, action: () -> Unit) {
		val properties = mapOf("cloth.toolchain.home" to home, "cloth.toolchain.layout" to layout)
		val previous = properties.mapValues { System.getProperty(it.key) }
		try {
			properties.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
			action()
		} finally {
			previous.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
		}
	}

	@Test
	fun `flags select immutable modes and keep C runtime linkage independent`() {
		assertEquals(StandardLibrarySelection.Default, CompilerSettings(emptyArray()).standardLibrary)
		val explicit = CompilerSettings(arrayOf("--stdlib", "first", "--runtime=static", "--stdlib=last with spaces"))
		assertEquals(StandardLibrarySelection.Explicit(Path.of("last with spaces")), explicit.standardLibrary)
		assertEquals(RuntimeLinkage.STATIC, explicit.linkOptions.runtime)
		val disabled = CompilerSettings(arrayOf("--no-stdlib", "--no-stdlib", "--runtime=dynamic"))
		assertEquals(StandardLibrarySelection.Disabled, disabled.standardLibrary)
		assertEquals(RuntimeLinkage.DYNAMIC, disabled.linkOptions.runtime)
		assertEquals(StandardLibrarySelection.Explicit(Path.of("-checkout")), CompilerSettings(arrayOf("--stdlib=-checkout")).standardLibrary)
	}

	@Test
	fun `each override occurrence must contain a nonblank valid path`() {
		for (args in listOf(arrayOf("--stdlib"), arrayOf("--stdlib="), arrayOf("--stdlib", " "), arrayOf("--stdlib", "--no-stdlib"), arrayOf("--stdlib=", "--stdlib=valid"))) {
			failure(DiagnosticCode.MISSING_OPTION_VALUE) { CompilerSettings(args) }
		}
		failure(DiagnosticCode.INVALID_PATH) { CompilerSettings(arrayOf("--stdlib=bad\u0000path", "--stdlib=valid")) }
	}

	@Test
	fun `override and disable conflict in either order and disable accepts no value`() {
		for (args in listOf(arrayOf("--stdlib=checkout", "--no-stdlib"), arrayOf("--no-stdlib", "--stdlib", "checkout"))) {
			assertContains(failure(DiagnosticCode.INVALID_OPTION_VALUE) { CompilerSettings(args) }.summary, "cannot be combined")
		}
		for (argument in listOf("--no-stdlib=", "--no-stdlib=true", "--no-stdlib=false")) {
			failure(DiagnosticCode.INVALID_OPTION_VALUE) { CompilerSettings(arrayOf(argument)) }
		}
	}

	@Test
	fun `standalone positional separator preserves option shaped filenames`() {
		val settings = CompilerSettings(arrayOf("--", "--no-stdlib", "--stdlib=literal"))
		assertEquals(StandardLibrarySelection.Default, settings.standardLibrary)
		assertEquals("--no-stdlib", settings.getSourceFile().path)
		assertEquals("--stdlib=literal", settings.getOutputFile()!!.path)
	}

	@Test
	fun `relative override selects only the final root without default context or release metadata`() {
		val root = library(temporary.resolve("checkout with spaces 移"))
		withContext(null, null) {
			val settings = CompilerSettings(arrayOf("--stdlib=missing", "--stdlib", temporary.relativize(root).toString()))
			val selected = assertNotNull(StandardLibrary.select(settings.standardLibrary, temporary))
			assertEquals(root.toRealPath(), selected.sourceRoot)
			val request = selected.attachTo(CompilationRequest(temporary.resolve("Main.co"), temporary.resolve("out.o"), outputKind = OutputKind.OBJECT))
			assertEquals(SourceModuleRole.STANDARD_LIBRARY, request.modules.single().role)
			assertFalse(Files.exists(root.resolve("toolchain.properties")))
		}
	}

	@Test
	fun `override and disable bypass invalid default properties and structure`() {
		val root = library(temporary.resolve("checkout"))
		Files.writeString(temporary.resolve("toolchain.properties"), "format=invalid\n")
		withContext(temporary.toString(), "installed") {
			assertEquals(root.toRealPath(), StandardLibrary.select(StandardLibrarySelection.Explicit(root), temporary)!!.sourceRoot)
			assertNull(StandardLibrary.select(StandardLibrarySelection.Disabled, temporary))
			failure(DiagnosticCode.INVALID_STANDARD_LIBRARY) { StandardLibrary.select(StandardLibrarySelection.Default, temporary) }
		}
		withContext("bad\u0000home", "invalid") {
			assertNull(StandardLibrary.select(StandardLibrarySelection.Disabled, temporary))
			assertEquals(root.toRealPath(), StandardLibrary.select(StandardLibrarySelection.Explicit(root), temporary)!!.sourceRoot)
		}
	}

	@Test
	fun `invalid explicit selection never falls back to a valid default`() {
		library(temporary.resolve("Standard-Library"))
		val incomplete = Files.createDirectory(temporary.resolve("incomplete"))
		withContext(temporary.toString(), "development") {
			assertNotNull(StandardLibrary.select(StandardLibrarySelection.Default, temporary))
			for (path in listOf(temporary.resolve("missing"), incomplete, temporary.resolve("Standard-Library/cloth"))) {
				assertContains(failure(DiagnosticCode.INVALID_STANDARD_LIBRARY) {
					StandardLibrary.select(StandardLibrarySelection.Explicit(path), temporary)
				}.summary, path.toString())
			}
		}
	}

	@Test
	fun `explicit selection protects its markers but consumes no release descriptor`() {
		val root = library(temporary.resolve("checkout"))
		val selected = StandardLibrary.select(StandardLibrarySelection.Explicit(root), temporary)!!
		for (input in listOf(root.resolve("module.toml"), root.resolve("cloth/Library.co"))) {
			failure(DiagnosticCode.OUTPUT_OVERWRITES_TOOLCHAIN_INPUT) {
				selected.attachTo(CompilationRequest(temporary.resolve("Main.co"), input, outputKind = OutputKind.OBJECT))
			}
		}
		val descriptor = Files.writeString(root.resolve("toolchain.properties"), "invalid and unconsumed")
		selected.attachTo(CompilationRequest(temporary.resolve("Main.co"), descriptor, outputKind = OutputKind.OBJECT))
		assertEquals("invalid and unconsumed", Files.readString(descriptor))
	}
}
