@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package shuttle.nativecli

import kotlinx.cinterop.*
import platform.posix.FILE
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix._wfopen
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.memset
import platform.posix.stderr
import platform.windows.*

private const val wideCapacity = 32768

internal actual val pathSeparator = '\\'

internal actual fun platformArguments(arguments: Array<String>): List<String> = splitArguments(GetCommandLineW())

internal actual fun launcherOptions(value: String): List<String> = memScoped { splitArguments(("java " + value).wcstr.ptr) }

private fun splitArguments(command: CPointer<UShortVar>?): List<String> = memScoped {
	val count = alloc<IntVar>()
	val arguments = CommandLineToArgvW(command, count.ptr) ?: windowsFailure("Unable to read arguments")
	try {
		(1 until count.value).map { arguments[it]!!.toKString() }
	} finally {
		LocalFree(arguments)
	}
}

internal actual fun executablePath(): String = widePath("Unable to locate Shuttle") { buffer ->
	GetModuleFileNameW(null, buffer, wideCapacity.toUInt())
}

private fun absolutePath(path: String): String = widePath("Unable to resolve '$path'") { buffer ->
	GetFullPathNameW(path.wcstr.ptr, wideCapacity.toUInt(), buffer, null)
}

private fun isFile(path: String): Boolean = memScoped {
	val attributes = GetFileAttributesW(path.wcstr.ptr)
	attributes != UInt.MAX_VALUE && attributes and FILE_ATTRIBUTE_DIRECTORY.toUInt() == 0u
}

internal actual fun javaExecutable(home: String): String {
	val bundled = "$home\\runtime\\bin\\java.exe"
	if (isFile(bundled)) return bundled
	environment("JAVA_HOME")?.let { return absolutePath(it.trim('"') + "\\bin\\java.exe") }
	val path = environment("PATH") ?: throw HostFailure("Set JAVA_HOME or put Java 25 on PATH")
	return widePath("Unable to locate java.exe on PATH") { buffer ->
		SearchPathW(path.wcstr.ptr, "java.exe".wcstr.ptr, null, wideCapacity.toUInt(), buffer, null)
	}
}

internal actual fun environment(name: String): String? = memScoped {
	val buffer = allocArray<UShortVar>(wideCapacity)
	val length = GetEnvironmentVariableW(name.wcstr.ptr, buffer, wideCapacity.toUInt())
	if (length >= wideCapacity.toUInt()) throw HostFailure("Environment variable '$name' is too long")
	if (length == 0u) null else buffer.toKString()
}

private inline fun widePath(operation: String, read: MemScope.(CPointer<UShortVar>) -> UInt): String = memScoped {
	val buffer = allocArray<UShortVar>(wideCapacity)
	val length = read(buffer)
	if (length == 0u || length >= wideCapacity.toUInt()) windowsFailure(operation)
	buffer.toKString()
}

internal actual fun <T> withExchangeFile(action: (String) -> T): T = memScoped {
	val directory = allocArray<UShortVar>(MAX_PATH)
	val length = GetTempPathW(MAX_PATH.toUInt(), directory)
	if (length == 0u || length >= MAX_PATH.toUInt()) windowsFailure("Unable to locate a temporary directory")
	val file = allocArray<UShortVar>(MAX_PATH)
	if (GetTempFileNameW(directory, "sht".wcstr.ptr, 0u, file) == 0u) windowsFailure("Unable to create the compiler exchange file")
	try {
		action(file.toKString())
	} finally {
		DeleteFileW(file)
	}
}

internal actual fun readUtf8(path: String): String = withFile(path, "rb") { file ->
	if (fseek(file, 0, SEEK_END) != 0) throw HostFailure("Unable to seek '$path'")
	val length = ftell(file)
	if (length < 0 || fseek(file, 0, SEEK_SET) != 0) throw HostFailure("Unable to read '$path'")
	val bytes = ByteArray(length)
	if (bytes.isNotEmpty()) bytes.usePinned {
		if (fread(it.addressOf(0), 1u, bytes.size.toULong(), file) != bytes.size.toULong()) throw HostFailure("Unable to read '$path'")
	}
	bytes.decodeToString(throwOnInvalidSequence = true)
}

internal actual fun writeUtf8(path: String, text: String) = withFile(path, "wb") { file ->
	val bytes = text.encodeToByteArray()
	if (bytes.isNotEmpty()) bytes.usePinned {
		if (fwrite(it.addressOf(0), 1u, bytes.size.toULong(), file) != bytes.size.toULong() || fflush(file) != 0) {
			throw HostFailure("Unable to write '$path'")
		}
	}
}

private inline fun <T> withFile(path: String, mode: String, action: (CPointer<FILE>) -> T): T = memScoped {
	val file = _wfopen(path.wcstr.ptr, mode.wcstr.ptr) ?: throw HostFailure("Unable to open '$path'")
	try {
		action(file)
	} finally {
		fclose(file)
	}
}

internal actual fun runProcess(executable: String, arguments: List<String>): Int = memScoped {
	val startup = alloc<STARTUPINFOW>()
	memset(startup.ptr, 0, sizeOf<STARTUPINFOW>().toULong())
	startup.cb = sizeOf<STARTUPINFOW>().toUInt()
	startup.dwFlags = STARTF_USESTDHANDLES.toUInt()
	startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE)
	startup.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE)
	startup.hStdError = GetStdHandle(STD_ERROR_HANDLE)
	val process = alloc<PROCESS_INFORMATION>()
	val command = (listOf(executable) + arguments).joinToString(" ", transform = ::quoteArgument)
	if (CreateProcessW(executable.wcstr.ptr, command.wcstr.ptr, null, null, 1, 0u, null, null, startup.ptr, process.ptr) == 0) {
		windowsFailure("Unable to start '$executable'")
	}
	CloseHandle(process.hThread)
	try {
		if (WaitForSingleObject(process.hProcess, INFINITE) != WAIT_OBJECT_0) windowsFailure("Unable to wait for '$executable'")
		val status = alloc<DWORDVar>()
		if (GetExitCodeProcess(process.hProcess, status.ptr) == 0) windowsFailure("Unable to read the status of '$executable'")
		status.value.toInt()
	} finally {
		CloseHandle(process.hProcess)
	}
}

/** Windows CRT quoting for empty arguments, quotes and trailing backslashes. */
private fun quoteArgument(value: String): String = buildString {
	append('"')
	var slashes = 0
	for (character in value) {
		if (character == '\\') {
			slashes++
		} else {
			repeat(if (character == '"') slashes * 2 + 1 else slashes) { append('\\') }
			append(character)
			slashes = 0
		}
	}
	repeat(slashes * 2) { append('\\') }
	append('"')
}

internal actual fun reportError(message: String) {
	fputs(message + "\n", stderr)
}

private fun windowsFailure(operation: String): Nothing = throw HostFailure(operation + " (Windows error " + GetLastError() + ")")
