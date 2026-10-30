package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode

import cloth.emitter.*
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.settings.*
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ToolchainResolverTest {
	private fun file(path: Path): Path {
		Files.createDirectories(path.parent)
		if (!Files.exists(path)) Files.write(path, byteArrayOf())
		return path.toAbsolutePath().normalize()
	}
	private fun directory(path: Path): Path = Files.createDirectories(path).toAbsolutePath().normalize()
	private val windows = NativeCABI.forTarget("x86_64-pc-windows-msvc")
	private val linux = NativeCABI.forTarget("x86_64-unknown-linux-gnu")
	private val mac = NativeCABI.forTarget("arm64-apple-darwin24.0.0")

	private fun windowsLibraries(tools: Path, sdkLib: Path, arch: String) {
		for (name in listOf("libcmt", "libvcruntime", "msvcrt", "vcruntime", "oldnames", "legacy_stdio_definitions")) file(tools.resolve("lib/$arch/$name.lib"))
		for (name in listOf("libucrt", "ucrt")) file(sdkLib.resolve("ucrt/$arch/$name.lib"))
		file(sdkLib.resolve("um/$arch/kernel32.lib"))
	}

	@Test
	fun `Windows resolves explicit static CRT paths and preserves spaced command arguments`(@TempDir root: Path) {
		val tools = root.resolve("MSVC tools/14.44.0")
		val sdk = root.resolve("Windows SDK")
		windowsLibraries(tools, sdk.resolve("Lib/10.0.26100.0"), "x64")
		val executable = file(root.resolve("bin with spaces/lld.exe"))
		val resolver = ToolchainResolver(emptyMap(), { "LLD 22.1.8" }, { executable })
		val config = resolver.resolve(windows, LinkOptions(msvcTools = tools, windowsSDK = sdk))
		assertEquals(executable, config.executable)
		assertContains(config.flags, "/machine:x64")
		assertContains(config.flags, "/subsystem:console")
		assertFalse(config.flags.any { it.startsWith("/entry:") })
		assertEquals(setOf("libcmt.lib", "libvcruntime.lib", "libucrt.lib", "oldnames.lib", "legacy_stdio_definitions.lib", "kernel32.lib"),
			config.runtimeLibraries.map { it.fileName.toString() }.toSet())
		assertTrue(config.runtimeLibraries.all { it.isAbsolute && Files.isRegularFile(it) })
		assertEquals(config.libraryPaths.joinToString(";"), config.environment["LIB"])
		val objectFile = root.resolve("object with spaces/input.o")
		val output = root.resolve("output with spaces/program.exe")
		val command = config.command(objectFile, output)
		assertContains(command, objectFile.toString())
		assertContains(command, "/out:$output")
		assertFalse(command.any { it.startsWith('"') })
	}

	@Test
	fun `Windows ARM64 dynamic runtime and GUI use matching libraries and CRT main startup`(@TempDir root: Path) {
		val tools = root.resolve("tools")
		val sdk = root.resolve("sdk")
		windowsLibraries(tools, sdk.resolve("Lib/10.0.26100.0"), "arm64")
		val extra = directory(root.resolve("custom libs"))
		val custom = file(extra.resolve("custom.lib"))
		val executable = file(root.resolve("lld-link.exe"))
		val config = ToolchainResolver(emptyMap(), { "LLD 22.1.8" }).resolve(
			NativeCABI.forTarget("aarch64-pc-windows-msvc"), LinkOptions(
				linker = executable, driver = LinkerDriver.MSVC, runtime = RuntimeLinkage.DYNAMIC,
				subsystem = ApplicationSubsystem.WINDOWS, msvcTools = tools, windowsSDK = sdk,
				libraryPaths = listOf(extra), libraries = listOf("custom")
			))
		assertContains(config.flags, "/machine:arm64")
		assertContains(config.flags, "/subsystem:windows")
		assertContains(config.flags, "/entry:mainCRTStartup")
		assertFalse(config.flags.contains("-flavor"))
		assertEquals(extra, config.libraryPaths.first())
		assertEquals(listOf(custom.toString()), config.libraries)
		assertTrue(config.runtimeLibraries.any { it.fileName.toString() == "msvcrt.lib" })
		assertFalse(config.runtimeLibraries.any { it.fileName.toString() == "libcmt.lib" })
		assertTrue(config.runtimeLibraries.all { it.parent.fileName.toString() == "arm64" })
	}

	@Test
	fun `Windows discovers installed versions numerically and honors explicit SDK versions`(@TempDir root: Path) {
		val programFiles = root.resolve("Program Files x86")
		val vswhere = file(programFiles.resolve("Microsoft Visual Studio/Installer/vswhere.exe"))
		val installation = root.resolve("Visual Studio")
		val oldTools = installation.resolve("VC/Tools/MSVC/14.9.0")
		val newTools = installation.resolve("VC/Tools/MSVC/14.10.0")
		val sdk = programFiles.resolve("Windows Kits/10")
		windowsLibraries(oldTools, sdk.resolve("Lib/10.0.9.0"), "x64")
		windowsLibraries(newTools, sdk.resolve("Lib/10.0.10.0"), "x64")
		val executable = file(root.resolve("lld.exe"))
		val resolver = ToolchainResolver(mapOf("ProgramFiles(x86)" to programFiles.toString()), { command ->
			if (command.first() == vswhere.toString()) installation.toString() else "LLD 22.1.8"
		}, { executable })
		val config = resolver.resolve(windows, LinkOptions())
		assertTrue(config.libraryPaths.contains(newTools.resolve("lib/x64").toAbsolutePath()))
		assertTrue(config.libraryPaths.contains(sdk.resolve("Lib/10.0.10.0/ucrt/x64").toAbsolutePath()))
		val pinned = resolver.resolve(windows, LinkOptions(windowsSDKVersion = "10.0.9.0"))
		assertTrue(pinned.libraryPaths.contains(sdk.resolve("Lib/10.0.9.0/ucrt/x64").toAbsolutePath()))
	}

	private fun unixResolver(executable: Path, library: Path, target: String, clang: Boolean = true,
		queries: MutableList<List<String>> = mutableListOf(), environment: Map<String, String> = emptyMap()): ToolchainResolver =
		ToolchainResolver(environment, { command ->
			queries.add(command)
			when (command.last()) {
				"--version" -> if (clang) "clang version 22.1.8" else "gcc (GCC) Free Software Foundation"
				"-dumpmachine" -> target
				"-print-search-dirs" -> "libraries: =$library"
				"--show-sdk-path" -> library.parent.parent.toString()
				else -> command.last().substringAfter("-print-file-name=")
			}
		}, { executable })

	@Test
	fun `Linux Clang resolves target flags sysroot math runtime and ordered user libraries`(@TempDir root: Path) {
		val executable = file(root.resolve("toolchain with spaces/cc"))
		val sysroot = directory(root.resolve("target sysroot"))
		val library = directory(sysroot.resolve("usr/lib"))
		val math = file(library.resolve("libm.so"))
		file(library.resolve("libm.a"))
		val extra = directory(root.resolve("extra libs"))
		val queries = mutableListOf<List<String>>()
		val resolver = unixResolver(executable, library, linux.targetTriple, queries = queries)
		val options = LinkOptions(linker = executable, sysroot = sysroot, libraryPaths = listOf(extra), libraries = listOf("custom"))
		val config = resolver.resolve(linux, options)
		assertContains(config.flags, "--target=${linux.targetTriple}")
		assertContains(config.flags, "--sysroot=$sysroot")
		assertEquals(extra, config.libraryPaths.first())
		assertEquals(listOf(math), config.runtimeLibraries)
		val objectFile = root.resolve("program.o")
		val command = config.command(objectFile, root.resolve("program"))
		assertTrue(command.indexOf(objectFile.toString()) < command.indexOf("-lcustom"))
		assertTrue(command.indexOf("-lcustom") < command.indexOf(math.toString()))
		val static = resolver.resolve(linux, options.copy(runtime = RuntimeLinkage.STATIC))
		assertContains(static.flags, "-static")
		assertEquals(listOf(library.resolve("libm.a")), static.runtimeLibraries)
		assertFalse(queries.any { it.last() == "-print-search-dirs" && it.contains("-static") })
	}

	@Test
	fun `fixed-target GCC is checked and never receives Clang target options`(@TempDir root: Path) {
		val executable = file(root.resolve("gcc"))
		val library = directory(root.resolve("lib"))
		file(library.resolve("libm.so"))
		val config = unixResolver(executable, library, "x86_64-linux-gnu", clang = false)
			.resolve(linux, LinkOptions(linker = executable, runtime = RuntimeLinkage.DYNAMIC))
		assertFalse(config.flags.any { it.startsWith("--target=") })
		assertContains(config.flags, "-Wl,-Bdynamic")
		for (target in listOf("aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "x86_64-apple-darwin", "riscv64-unknown-linux-gnu")) {
			val failure = assertFailsWith<ClothDiagnostic>(target) {
				unixResolver(executable, library, target).resolve(linux, LinkOptions(linker = executable))
			}
			assertContains(failure.message.orEmpty(), linux.targetTriple)
			assertEquals(DiagnosticCode.INCOMPATIBLE_DRIVER, failure.diagnosticCode)
		}
	}

	@Test
	fun `macOS resolves its SDK System runtime architecture and deployment triple`(@TempDir root: Path) {
		val executable = file(root.resolve("cc"))
		val sdk = directory(root.resolve("MacOSX.sdk"))
		val library = directory(sdk.resolve("usr/lib"))
		val system = file(library.resolve("libSystem.tbd"))
		val resolver = unixResolver(executable, library, "aarch64-apple-darwin23.0.0", environment = mapOf("SDKROOT" to sdk.toString()))
		val config = resolver.resolve(mac, LinkOptions(linker = executable))
		assertContains(config.flags, "--target=${mac.targetTriple}")
		assertContains(config.flags, "-isysroot")
		assertContains(config.flags, sdk.toString())
		assertEquals("arm64", config.flags[config.flags.indexOf("-arch") + 1])
		assertEquals(listOf(system), config.runtimeLibraries)
		val bin = directory(root.resolve("bin"))
		file(bin.resolve("xcrun${ExecutableFormat.current.extension}"))
		val discovered = unixResolver(executable, library, "aarch64-apple-darwin23.0.0", environment = mapOf("PATH" to bin.toString()))
			.resolve(mac, LinkOptions(linker = executable))
		assertEquals(config.runtimeLibraries, discovered.runtimeLibraries)
	}

	@Test
	fun `incompatible build choices and missing target files fail during resolution`(@TempDir root: Path) {
		val resolver = ToolchainResolver(emptyMap(), { error("Unexpected toolchain query: $it") })
		for ((abi, options) in listOf(
			windows to LinkOptions(driver = LinkerDriver.CC), windows to LinkOptions(sysroot = root),
			linux to LinkOptions(driver = LinkerDriver.LLD), linux to LinkOptions(subsystem = ApplicationSubsystem.WINDOWS),
			linux to LinkOptions(msvcTools = root), mac to LinkOptions(runtime = RuntimeLinkage.STATIC),
			linux to LinkOptions(libraries = listOf("bad name"))
		)) {
			val failure = assertFailsWith<ClothDiagnostic> { resolver.resolve(abi, options) }
			assertEquals(if (options.libraries.isEmpty()) DiagnosticCode.TOOLCHAIN_OPTIONS else DiagnosticCode.INVALID_LIBRARY_NAME,
				failure.diagnosticCode)
		}
		val tools = root.resolve("tools")
		val sdk = root.resolve("sdk")
		windowsLibraries(tools, sdk.resolve("Lib/10.0.26100.0"), "x64")
		Files.delete(sdk.resolve("Lib/10.0.26100.0/um/x64/kernel32.lib"))
		val missing = assertFailsWith<ClothDiagnostic> {
			resolver.resolve(windows, LinkOptions(msvcTools = tools, windowsSDK = sdk))
		}
		assertContains(missing.message.orEmpty(), "kernel32.lib")
		assertContains(missing.message.orEmpty(), windows.targetTriple)
		assertEquals(DiagnosticCode.MISSING_LIBRARY, missing.diagnosticCode)
		assertFailsWith<ClothDiagnostic> {
			resolver.resolve(windows, LinkOptions(msvcTools = tools, windowsSDK = sdk, libraryPaths = listOf(root.resolve("missing"))))
		}
	}

	@Test
	fun `invalid toolchain choices fail before creating compiler output directories`(@TempDir root: Path) {
		val incompatible = if (NativeCABI.native().profile == NativeCABI.Profile.WINDOWS_LLP64) LinkerDriver.CC else LinkerDriver.MSVC
		val output = root.resolve("should not exist/program")
		val ast = Parser(Lexer("1").scanTokens()).parseSnippet()
		assertFailsWith<ClothDiagnostic> { LLVMEmitter(LinkOptions(driver = incompatible)).compileSnippet(ast, output) }
		assertFalse(Files.exists(output.parent))
	}

	@Test
	fun `native explicit runtime and linker choices produce runnable programs`(@TempDir root: Path) {
		if (NativeCABI.native().profile != NativeCABI.Profile.WINDOWS_LLP64) return
		val ast = Parser(Lexer("def fraction = 7.0\nfraction / 2").scanTokens()).parseSnippet()
		for ((index, options) in listOf(
			LinkOptions(runtime = RuntimeLinkage.DYNAMIC),
			LinkOptions(subsystem = ApplicationSubsystem.WINDOWS),
			LinkOptions(driver = LinkerDriver.MSVC)
		).withIndex()) {
			val executable = LLVMEmitter(options).compileSnippet(ast, root.resolve("native_$index"))
			val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
			try {
				assertTrue(process.waitFor(10, TimeUnit.SECONDS))
				val output = process.inputStream.bufferedReader().use { it.readText().trim() }
				assertEquals(0, process.exitValue(), output)
				assertEquals("3.5", output)
			} finally { if (process.isAlive) process.destroyForcibly() }
		}
	}
}
