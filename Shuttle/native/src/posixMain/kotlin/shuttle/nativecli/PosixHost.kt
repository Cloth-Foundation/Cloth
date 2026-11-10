@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package shuttle.nativecli

import kotlinx.cinterop.*
import platform.posix.*

internal actual val pathSeparator = '/'

internal actual fun platformArguments(arguments: Array<String>): List<String> = arguments.toList()

internal actual fun launcherOptions(value: String): List<String> = buildList {
	val word = StringBuilder()
	var quote: Char? = null
	var escape = false
	var started = false
	for (character in value) {
		when {
			escape -> {
				word.append(character)
				escape = false
			}
			character == '\\' && quote != '\'' -> escape = true
			quote != null -> if (character == quote) quote = null else word.append(character)
			character == '"' || character == '\'' -> quote = character
			character.isWhitespace() -> {
				if (started) add(word.toString())
				word.clear()
				started = false
				continue
			}
			else -> word.append(character)
		}
		started = true
	}
	if (quote != null || escape) throw HostFailure("Unterminated quote or escape in Java options")
	if (started) add(word.toString())
}

internal actual fun environment(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotEmpty() }

internal actual fun javaExecutable(home: String): String {
	val bundled = "$home/runtime/bin/java"
	if (access(bundled, X_OK) == 0) return bundled
	environment("JAVA_HOME")?.let { return it.trim('"') + "/bin/java" }
	return environment("PATH").orEmpty().split(':').asSequence()
		.map { it.ifEmpty { "." } + "/java" }.firstOrNull { access(it, X_OK) == 0 }
		?: throw HostFailure("Set JAVA_HOME or put Java 25 on PATH")
}

internal actual fun <T> withExchangeFile(action: (String) -> T): T = memScoped {
	val directory = environment("TMPDIR") ?: "/tmp"
	val template = "$directory/shuttle-XXXXXX".cstr.ptr
	val descriptor = mkstemp(template)
	if (descriptor < 0) throw HostFailure("Unable to create the compiler exchange file")
	close(descriptor)
	val file = template.toKString()
	try {
		action(file)
	} finally {
		unlink(file)
	}
}

internal actual fun readUtf8(path: String): String = withFile(path, "rb") { file ->
	if (fseek(file, 0, SEEK_END) != 0) throw HostFailure("Unable to seek '$path'")
	val length = ftell(file)
	if (length !in 0..Int.MAX_VALUE.toLong() || fseek(file, 0, SEEK_SET) != 0) throw HostFailure("Unable to read '$path'")
	val bytes = ByteArray(length.toInt())
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

private inline fun <T> withFile(path: String, mode: String, action: (CPointer<FILE>) -> T): T {
	val file = fopen(path, mode) ?: throw HostFailure("Unable to open '$path'")
	try {
		return action(file)
	} finally {
		fclose(file)
	}
}

internal actual fun runProcess(executable: String, arguments: List<String>): Int = memScoped {
	if (access(executable, X_OK) != 0) throw HostFailure("Unable to start '$executable'")
	val program = executable.cstr.ptr
	val argv = allocArray<CPointerVar<ByteVar>>(arguments.size + 2)
	argv[0] = program
	for ((index, argument) in arguments.withIndex()) argv[index + 1] = argument.cstr.ptr
	argv[arguments.size + 1] = null
	val pid = fork()
	// The child calls only C process functions until exec replaces it.
	if (pid == 0) {
		execv(program, argv)
		_exit(127)
	}
	if (pid < 0) throw HostFailure("Unable to start '$executable'")
	val status = alloc<IntVar>()
	while (waitpid(pid, status.ptr, 0) < 0) {
		if (errno != EINTR) throw HostFailure("Unable to wait for '$executable'")
	}
	val signal = status.value and 127
	if (signal == 0) (status.value shr 8) and 255 else 128 + signal
}

internal actual fun reportError(message: String) {
	fputs(message + "\n", stderr)
}
