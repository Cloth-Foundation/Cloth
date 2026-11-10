package shuttle.nativecli

import platform.Foundation.NSBundle

internal actual fun executablePath(): String =
	NSBundle.mainBundle.executablePath ?: throw HostFailure("Unable to locate Shuttle")
