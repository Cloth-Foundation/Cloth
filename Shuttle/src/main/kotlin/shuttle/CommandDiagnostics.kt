package shuttle

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticReporter
import shuttle.diagnostics.ShuttleDiagnostic
import shuttle.diagnostics.ShuttleErrors
import java.io.IOException

internal inline fun withShuttleDiagnostics(reporter: DiagnosticReporter, debug: Boolean = false, action: () -> Int): Int =
	try {
		action()
	} catch (failure: ShuttleDiagnostic) {
		reporter.report(failure, debug)
		if (failure.code().startsWith("S9")) 2 else 1
	} catch (failure: ClothDiagnostic) {
		reporter.report(failure, debug)
		if (failure.code().startsWith("C9")) 2 else 1
	} catch (failure: IOException) {
		reporter.report(ShuttleErrors.io(failure), debug)
		1
	} catch (failure: Exception) {
		reporter.report(ShuttleErrors.internal(failure), debug)
		2
	} catch (failure: LinkageError) {
		reporter.report(ShuttleErrors.internal(failure), debug)
		2
	}

