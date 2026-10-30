package cloth.diagnostics

import cloth.token.TokenSpan
import com.opencastsoftware.prettier4j.Doc
import com.opencastsoftware.yvette.*
import java.net.URI

data class SourceLabel(val span: TokenSpan, val text: String)

/** A structured issue; throwing it aborts the current compilation, rendering stays at the boundary. */
open class ClothDiagnostic(
	val diagnosticCode: DiagnosticCode,
	val summary: String,
	val sourceLabels: List<SourceLabel> = emptyList(),
	val guidance: String? = null,
	val level: Severity = Severity.Error,
	val documentation: URI? = null,
	cause: Throwable? = null,
) : Diagnostic(cause) {

	override fun code(): String = diagnosticCode.id
	override fun message(): Doc = Doc.text(summary)
	override fun severity(): Severity = level
	override fun help(): Doc? = guidance?.let(Doc::text)
	override fun url(): URI? = documentation
	override fun sourceCode(): SourceCode? = sourceLabels.firstOrNull()?.span?.source
	override fun labels(): Collection<LabelledRange> = sourceLabels.map { label ->
		val span = label.span
		LabelledRange(label.text, span.source?.range(span) ?: Range(span.startLine - 1, span.startColumn - 1, span.endLine - 1, span.endColumn - 1))
	}
}
