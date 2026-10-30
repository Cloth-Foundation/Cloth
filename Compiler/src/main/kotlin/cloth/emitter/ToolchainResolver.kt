package cloth.emitter

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.ToolchainErrors
import cloth.settings.ApplicationSubsystem
import cloth.settings.LinkOptions
import cloth.settings.LinkerDriver
import cloth.settings.RuntimeLinkage
import cloth.util.ExecutableFormat
import org.bytedeco.javacpp.Loader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class ToolchainProbeException(val status: Int, val diagnostics: String, command: List<String>) : ClothDiagnostic(DiagnosticCode.TOOLCHAIN_QUERY, "Toolchain query failed ($status): ${command.joinToString(" ")}", guidance = diagnostics)

/** Resolves installed tools and runtime files for an already selected native ABI. */
internal class ToolchainResolver(private val environment: Map<String, String> = System.getenv(), private val probe: (List<String>) -> String = ::runProbe, private val bundledLLD: () -> Path = { Path.of(Loader.load(org.bytedeco.llvm.program.clang::class.java, "lld")) }, private val hostFormat: ExecutableFormat = ExecutableFormat.current) {

	fun resolve(abi: NativeCABI, options: LinkOptions): LinkerConfiguration {
		options.libraries.forEach { if (!it.matches(Regex("[A-Za-z0-9_+][A-Za-z0-9_+.-]*"))) throw ToolchainErrors.libraryName(it) }
		return when (abi.profile) {
			NativeCABI.Profile.WINDOWS_LLP64                              -> windows(abi, options)
			NativeCABI.Profile.LINUX_LP64, NativeCABI.Profile.DARWIN_LP64 -> unix(abi, options)
		}
	}

	private fun windows(abi: NativeCABI, options: LinkOptions): LinkerConfiguration {
		if (options.sysroot != null) throw ToolchainErrors.options("Use --windows-sdk and --msvc-tools for the Windows toolchain, not --sysroot")
		val driver = options.driver ?: LinkerDriver.LLD
		if (driver == LinkerDriver.CC) throw ToolchainErrors.options("Target '${abi.targetTriple}' requires an MSVC-compatible linker (lld or msvc)")
		val arch = when (abi.architecture) {
			NativeCABI.Architecture.X86_64  -> "x64"
			NativeCABI.Architecture.AARCH64 -> "arm64"
		}
		val tools = windowsTools(options, arch)
		val sdk = windowsSDK(options, arch)
		val paths = (options.libraryPaths.map(::directory) + listOf(tools.resolve("lib/$arch"), sdk.resolve("ucrt/$arch"), sdk.resolve("um/$arch"))).distinct()
		val dynamic = options.runtime == RuntimeLinkage.DYNAMIC
		val runtimeNames = if (dynamic) listOf("msvcrt", "vcruntime", "ucrt") else listOf("libcmt", "libvcruntime", "libucrt")
		val runtime = (runtimeNames + listOf("oldnames", "legacy_stdio_definitions", "kernel32")).map { name ->
			findLibrary(paths, listOf("$name.lib"), abi)
		}
		val libraries = options.libraries.map { name -> findLibrary(paths, listOf(if (name.endsWith(".lib")) name else "$name.lib"), abi).toString() }
		val executable = options.linker?.let { program(it.toString()) } ?: when (driver) {
			LinkerDriver.LLD  -> regularFile(bundledLLD())
			LinkerDriver.MSVC -> {
				val hostArch = if (System.getProperty("os.arch").lowercase(Locale.ROOT) in setOf("aarch64", "arm64")) "arm64" else "x64"
				val installed = tools.resolve("bin/Host$hostArch/$arch/link.exe")
				if (Files.isRegularFile(installed)) regularFile(installed) else program("link")
			}
		}
		val banner = try {
			probe(listOf(executable.toString()) + if (driver == LinkerDriver.LLD) listOf("-flavor", "link", "--version") else listOf("/?"))
		} catch (failure: ToolchainProbeException) {
			// MSVC LINK prints valid help but returns its no-input/help status.
			if (driver == LinkerDriver.MSVC && failure.status == 1100 && failure.diagnostics.contains("Microsoft") && failure.diagnostics.contains("Linker")) failure.diagnostics else throw failure
		}
		if (!banner.contains("LLD", ignoreCase = true) && !(banner.contains("Microsoft", ignoreCase = true) && banner.contains("Linker", ignoreCase = true))) throw ToolchainErrors.driver("'$executable' is not an MSVC-compatible linker for '${abi.targetTriple}'")
		val flags = buildList {
			if (driver == LinkerDriver.LLD) addAll(listOf("-flavor", "link"))
			addAll(listOf("/nologo", "/machine:$arch", "/subsystem:${options.subsystem.name.lowercase(Locale.ROOT)}"))
			// Cloth supplies main even when the executable does not allocate a console.
			if (options.subsystem == ApplicationSubsystem.WINDOWS) add("/entry:mainCRTStartup")
		}
		return LinkerConfiguration(abi.targetTriple, executable, driver, flags, runtime, paths, libraries, mapOf("LIB" to paths.joinToString(";")))
	}

	private fun windowsTools(options: LinkOptions, arch: String): Path {
		val explicit = options.msvcTools ?: env("VCToolsInstallDir")?.let(Path::of)
		if (explicit != null) {
			val root = directory(explicit)
			if (!Files.isDirectory(root.resolve("lib/$arch"))) throw ToolchainErrors.runtime(arch)
			return root
		}
		val installer = env("ProgramFiles(x86)")?.let { Path.of(it, "Microsoft Visual Studio", "Installer", "vswhere.exe") }
		val vswhere = if (installer != null && Files.isRegularFile(installer)) installer else program("vswhere")
		val installations = probe(listOf(vswhere.toString(), "-products", "*", "-sort", "-property", "installationPath", "-utf8"))
		for (installation in installations.lineSequence().map(String::trim).filter(String::isNotEmpty)) {
			val roots = versions(Path.of(installation, "VC", "Tools", "MSVC"))
			roots.firstOrNull { Files.isRegularFile(it.resolve("lib/$arch/libcmt.lib")) }?.let { return it }
		}
		throw ToolchainErrors.runtime(arch)
	}

	/** Returns the SDK's versioned Lib directory, including UCRT and Win32 import libraries. */
	private fun windowsSDK(options: LinkOptions, arch: String): Path {
		val root = options.windowsSDK ?: (env("WindowsSdkDir") ?: env("UniversalCRTSdkDir"))?.let(Path::of) ?: env("ProgramFiles(x86)")?.let { Path.of(it, "Windows Kits", "10") } ?: throw ToolchainErrors.sdk()
		val libraryRoot = directory(root).resolve("Lib")
		val version = options.windowsSDKVersion ?: env("WindowsSDKVersion") ?: env("UCRTVersion")
		val candidates = if (version != null) listOf(libraryRoot.resolve(version.trimEnd('/', '\\'))) else versions(libraryRoot)
		return candidates.firstOrNull { Files.isDirectory(it.resolve("ucrt/$arch")) && Files.isDirectory(it.resolve("um/$arch")) } ?: throw ToolchainErrors.sdk(arch, version)
	}

	private fun unix(abi: NativeCABI, options: LinkOptions): LinkerConfiguration {
		if (options.driver != null && options.driver != LinkerDriver.CC) throw ToolchainErrors.options("Target '${abi.targetTriple}' requires a C linker driver (cc)")
		if (options.subsystem != ApplicationSubsystem.CONSOLE) throw ToolchainErrors.options("The windows subsystem is only supported for Windows targets")
		if (options.msvcTools != null || options.windowsSDK != null || options.windowsSDKVersion != null) throw ToolchainErrors.options("MSVC/Windows SDK options require a Windows target")
		val darwin = abi.profile == NativeCABI.Profile.DARWIN_LP64
		if (darwin && options.runtime == RuntimeLinkage.STATIC) throw ToolchainErrors.options("Static system C runtime linking is not supported on macOS")
		val executable = program(options.linker?.toString() ?: env("CC") ?: "cc")
		val banner = probe(listOf(executable.toString(), "--version"))
		val clang = banner.contains("clang", ignoreCase = true)
		if (!clang && !banner.contains("gcc", ignoreCase = true) && !banner.contains("Free Software Foundation", ignoreCase = true)) throw ToolchainErrors.driver("Unsupported C linker driver '$executable'; expected GCC or Clang")
		val reportedTarget = probe(listOf(executable.toString(), "-dumpmachine")).trim()
		val driverABI = try {
			NativeCABI.forTarget(reportedTarget)
		} catch (failure: ClothDiagnostic) {
			if (failure.diagnosticCode != DiagnosticCode.UNSUPPORTED_TARGET) throw failure
			throw ToolchainErrors.driver("C linker driver '$executable' reports unsupported target '$reportedTarget'; selected '${abi.targetTriple}'", failure)
		}
		if (driverABI.architecture != abi.architecture || driverABI.profile != abi.profile || (!darwin && driverABI.environment != abi.environment)) throw ToolchainErrors.driver("C linker driver '$executable' targets '${driverABI.targetTriple}', but LLVM selected '${abi.targetTriple}'; choose a matching --linker")
		val sysroot = options.sysroot?.let(::directory) ?: if (darwin) {
			env("SDKROOT")?.let { directory(Path.of(it)) } ?: directory(Path.of(probe(listOf(program("xcrun").toString(), "--sdk", "macosx", "--show-sdk-path")).trim()))
		} else null
		val targetFlags = buildList {
			if (clang) add("--target=${abi.targetTriple}")
			if (sysroot != null) {
				if (darwin) addAll(listOf("-isysroot", sysroot.toString())) else add("--sysroot=$sysroot")
			}
			if (darwin) addAll(listOf("-arch", if (abi.architecture == NativeCABI.Architecture.X86_64) "x86_64" else "arm64"))
		}
		val flags = targetFlags + when (options.runtime) {
			RuntimeLinkage.STATIC             -> listOf("-static")
			RuntimeLinkage.DYNAMIC if !darwin -> listOf("-Wl,-Bdynamic")
			else                              -> emptyList()
		}
		val query = listOf(executable.toString()) + targetFlags
		val search = probe(query + "-print-search-dirs").lineSequence().firstOrNull { it.startsWith("libraries:") }
		val automaticPaths = search?.substringAfter(':')?.trim()?.removePrefix("=")?.split(File.pathSeparatorChar).orEmpty().filter(String::isNotBlank).map(Path::of).filter(Files::isDirectory)
		val paths = (options.libraryPaths.map(::directory) + listOfNotNull(sysroot?.resolve("usr/lib")?.takeIf(Files::isDirectory)) + automaticPaths).map { it.toAbsolutePath().normalize() }.distinct()
		val runtime = if (darwin) listOf(findLibrary(paths, listOf("libSystem.tbd", "libSystem.dylib"), abi)) else {
			val names = if (options.runtime == RuntimeLinkage.STATIC) listOf("libm.a") else listOf("libm.so", "libm.a")
			val math = names.firstNotNullOfOrNull { name ->
				paths.firstOrNull { Files.isRegularFile(it.resolve(name)) }?.resolve(name) ?: probe(query + "-print-file-name=$name").trim().takeIf { it != name && it.isNotEmpty() }?.let(Path::of)?.takeIf(Files::isRegularFile)
			} ?: throw ToolchainErrors.mathRuntime(abi.targetTriple)
			listOf(math.toAbsolutePath().normalize())
		}
		return LinkerConfiguration(abi.targetTriple, executable, LinkerDriver.CC, flags, runtime, paths, options.libraries.toList())
	}

	private fun findLibrary(paths: List<Path>, names: List<String>, abi: NativeCABI): Path = names.firstNotNullOfOrNull { name -> paths.firstOrNull { Files.isRegularFile(it.resolve(name)) }?.resolve(name) } ?: throw ToolchainErrors.library(names, abi.targetTriple)

	private fun env(name: String): String? = (if (hostFormat == ExecutableFormat.PE) environment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value else environment[name])?.takeIf(String::isNotBlank)
	private fun regularFile(path: Path): Path = path.toAbsolutePath().normalize().also { if (!Files.isRegularFile(it)) throw ToolchainErrors.executable(it.toString()) }
	private fun directory(path: Path): Path = path.toAbsolutePath().normalize().also { if (!Files.isDirectory(it)) throw ToolchainErrors.directory(it) }

	private fun program(name: String): Path {
		val path = Path.of(name)
		if (path.isAbsolute || path.parent != null) return regularFile(path)
		val names = if (hostFormat == ExecutableFormat.PE && !name.endsWith(".exe", ignoreCase = true)) listOf(name, "$name.exe") else listOf(name)
		for (root in env("PATH").orEmpty().split(File.pathSeparatorChar).filter(String::isNotBlank)) {
			for (candidate in names) Path.of(root, candidate).takeIf(Files::isRegularFile)?.let { return regularFile(it) }
		}
		throw ToolchainErrors.executable(name)
	}

	private fun versions(root: Path): List<Path> {
		if (!Files.isDirectory(root)) return emptyList()
		return Files.list(root).use { entries -> entries.filter(Files::isDirectory).toList() }.sortedWith { left, right ->
			val a = left.fileName.toString().split('.').map { it.toIntOrNull() ?: 0 }
			val b = right.fileName.toString().split('.').map { it.toIntOrNull() ?: 0 }
			(0 until maxOf(a.size, b.size)).firstNotNullOfOrNull { index ->
				b.getOrElse(index) { 0 }.compareTo(a.getOrElse(index) { 0 }).takeIf { it != 0 }
			} ?: 0
		}
	}

	companion object {

		private val defaults = ConcurrentHashMap<String, LinkerConfiguration>()
		fun native(abi: NativeCABI, options: LinkOptions): LinkerConfiguration = if (options == LinkOptions()) defaults.computeIfAbsent(abi.targetTriple) { ToolchainResolver().resolve(abi, options) }
		else ToolchainResolver().resolve(abi, options)

		private fun runProbe(command: List<String>): String {
			val process = try {
				ProcessBuilder(command).redirectErrorStream(true).start()
			} catch (failure: Exception) {
				throw ToolchainErrors.query(command, failure)
			}
			val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
			try {
				if (!process.waitFor(15, TimeUnit.SECONDS)) throw ToolchainErrors.timeout(command)
				val text = output.get(5, TimeUnit.SECONDS)
				if (process.exitValue() != 0) throw ToolchainProbeException(process.exitValue(), text, command)
				return text
			} catch (failure: InterruptedException) {
				Thread.currentThread().interrupt()
				throw ToolchainErrors.interrupted(failure)
			} catch (failure: java.util.concurrent.TimeoutException) {
				throw ToolchainErrors.timeout(command)
			} catch (failure: java.util.concurrent.ExecutionException) {
				throw ToolchainErrors.query(command, failure.cause ?: failure)
			} finally {
				if (process.isAlive) process.destroyForcibly()
				output.cancel(true)
			}
		}
	}
}
