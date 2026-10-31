package cloth

import cloth.diagnostics.ClothDiagnostic

import cloth.settings.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class CompilerSettingsTest {
	@Test
	fun `existing positional CLI and default source remain available`() {
		assertEquals(Path.of("examples", "StringFunctions.co"), CompilerSettings(emptyArray()).getSourceFile().toPath())
		val settings = CompilerSettings(arrayOf("source.co", "program"))
		assertEquals("source.co", settings.getSourceFile().path)
		assertEquals("program", settings.getOutputFile()!!.path)
		assertEquals(LinkOptions(), settings.linkOptions)
	}

	@Test
	fun `CLI exposes typed link options and preserves spaced paths and repeated libraries`() {
		val settings = CompilerSettings(arrayOf(
			"--runtime=dynamic", "source.co", "--linker", "tools with spaces/lld.exe", "--linker-driver", "lld",
			"--subsystem=windows", "program", "-L", "libs with spaces", "-Lmore", "-lcustom", "--library", "other",
			"--msvc-tools", "MSVC tools", "--windows-sdk=Windows SDK", "--windows-sdk-version", "10.0.26100.0"
		))
		assertEquals("source.co", settings.getSourceFile().path)
		assertEquals("program", settings.getOutputFile()!!.path)
		assertEquals(LinkOptions(
			linker = Path.of("tools with spaces/lld.exe"), driver = LinkerDriver.LLD, runtime = RuntimeLinkage.DYNAMIC,
			subsystem = ApplicationSubsystem.WINDOWS, libraryPaths = listOf(Path.of("libs with spaces"), Path.of("more")),
			libraries = listOf("custom", "other"), msvcTools = Path.of("MSVC tools"), windowsSDK = Path.of("Windows SDK"),
			windowsSDKVersion = "10.0.26100.0"
		), settings.linkOptions)
		assertEquals(Path.of("target root"), CompilerSettings(arrayOf("--sysroot=target root")).linkOptions.sysroot)
	}

	@Test
	fun `CLI rejects unknown missing and invalid options and supports the positional delimiter`() {
		for (arguments in listOf(
			arrayOf("--unknown"), arrayOf("--target=x86_64-linux-gnu"), arrayOf("--runtime"),
			arrayOf("--runtime", "--subsystem=console"), arrayOf("--runtime=invalid"), arrayOf("--subsystem=invalid"),
			arrayOf("--linker-driver=invalid"), arrayOf("--library-path="), arrayOf("-L"), arrayOf("-l"),
			arrayOf("one", "two", "three")
		)) assertFailsWith<ClothDiagnostic>(arguments.toList().toString()) { CompilerSettings(arguments) }
		val literal = CompilerSettings(arrayOf("--", "-source.co", "-output"))
		assertEquals("-source.co", literal.getSourceFile().path)
		assertEquals("-output", literal.getOutputFile()!!.path)
	}
}
