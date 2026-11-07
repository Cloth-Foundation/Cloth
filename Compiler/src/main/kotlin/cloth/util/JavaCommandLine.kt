package cloth.util

import java.lang.foreign.*

/** Recover direct Java application arguments before Windows' native code page loses characters.
 * JVM argument-file expansion stays with the JVM; never reinterpret an in-process argument array.
 */
object JavaCommandLine {

	fun arguments(received: Array<String>, mainClass: String): Array<String> {
		if (received.isEmpty() || ExecutableFormat.current != ExecutableFormat.PE) return received
		val command = windowsArguments()
		val entry = command.size - received.size - 1
		val directMain = command.getOrNull(entry) == mainClass
		val jarMain = entry > 0 && command[entry - 1] == "-jar"
		return if (directMain || jarMain) command.drop(entry + 1).toTypedArray() else received
	}

	private fun windowsArguments(): List<String> = Arena.ofConfined().use { arena ->
		val linker = Linker.nativeLinker()
		val kernel = SymbolLookup.libraryLookup("kernel32.dll", arena)
		val shell = SymbolLookup.libraryLookup("shell32.dll", arena)
		val commandLine = linker.downcallHandle(kernel.find("GetCommandLineW").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS))
		val split = linker.downcallHandle(shell.find("CommandLineToArgvW").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
		val free = linker.downcallHandle(kernel.find("LocalFree").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
		val count = arena.allocate(ValueLayout.JAVA_INT)
		val argv = split.invokeWithArguments(commandLine.invokeWithArguments(), count) as MemorySegment
		check(argv.address() != 0L) { "Unable to read Windows command-line arguments" }
		try {
			val size = count.get(ValueLayout.JAVA_INT, 0)
			check(size in 1..32768) { "Invalid Windows command-line argument count" }
			val pointers = argv.reinterpret(size * ValueLayout.ADDRESS.byteSize())
			List(size) { index ->
				// A Windows command line has at most 32767 UTF-16 units plus its terminator.
				val text = pointers.getAtIndex(ValueLayout.ADDRESS, index.toLong()).reinterpret(65536)
				buildString {
					for (offset in 0L until 65536L step 2) {
						val character = text.get(ValueLayout.JAVA_CHAR, offset)
						if (character == '\u0000') break
						append(character)
					}
				}
			}
		} finally {
			free.invokeWithArguments(argv)
		}
	}
}
