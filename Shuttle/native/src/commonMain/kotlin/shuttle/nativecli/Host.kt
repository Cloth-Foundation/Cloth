package shuttle.nativecli

internal class HostFailure(message: String) : Exception(message)

internal expect val pathSeparator: Char
internal expect fun platformArguments(arguments: Array<String>): List<String>
internal expect fun launcherOptions(value: String): List<String>
internal expect fun executablePath(): String
internal expect fun environment(name: String): String?
internal expect fun javaExecutable(home: String): String
internal expect fun <T> withExchangeFile(action: (String) -> T): T
internal expect fun readUtf8(path: String): String
internal expect fun writeUtf8(path: String, text: String)
internal expect fun runProcess(executable: String, arguments: List<String>): Int
internal expect fun reportError(message: String)
