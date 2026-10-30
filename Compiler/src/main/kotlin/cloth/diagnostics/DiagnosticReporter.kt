package cloth.diagnostics

import com.opencastsoftware.yvette.handlers.ReportHandler
import com.opencastsoftware.yvette.handlers.graphical.GraphicalReportHandler
import com.opencastsoftware.yvette.handlers.graphical.RgbColours
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter

class DiagnosticReporter(
	private val output: Appendable = System.err,
	private val handler: ReportHandler = GraphicalReportHandler.builder().withRgbColours(RgbColours.PREFERRED).withCauseChain(false).apply {
			// Piped Windows stderr may use a legacy code page even though Yvette assumes Unicode.
			if (output is PrintStream && !output.charset().newEncoder().canEncode("╭─│╰┬×")) withUnicode(false)
		}.buildFor(output),
) {

	fun report(diagnostic: ClothDiagnostic, debug: Boolean = false) {
		handler.display(diagnostic, output)
		if (debug) {
			val trace = StringWriter()
			diagnostic.printStackTrace(PrintWriter(trace))
			output.append(trace.toString())
		}
	}
}
