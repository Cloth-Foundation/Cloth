@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package shuttle.nativecli

import kotlinx.cinterop.*
import platform.posix.readlink

internal actual fun executablePath(): String {
	val bytes = ByteArray(32768)
	val length = bytes.usePinned { readlink("/proc/self/exe", it.addressOf(0), bytes.size.toULong()) }
	if (length <= 0 || length >= bytes.size) throw HostFailure("Unable to locate Shuttle")
	return bytes.decodeToString(endIndex = length.toInt(), throwOnInvalidSequence = true)
}
