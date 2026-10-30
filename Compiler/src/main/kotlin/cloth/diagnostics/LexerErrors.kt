package cloth.diagnostics

import cloth.token.TokenSpan

object LexerErrors {

	private fun issue(code: DiagnosticCode, message: String, span: TokenSpan, help: String? = null) = ClothDiagnostic(code, message, listOf(SourceLabel(span, "here")), help)
	fun unexpected(character: Char, span: TokenSpan) = issue(DiagnosticCode.UNEXPECTED_CHARACTER, "Unexpected character '$character'", span, "Remove this character or use a supported token.")
	fun comment(span: TokenSpan) = issue(DiagnosticCode.UNTERMINATED_COMMENT, "Unterminated block comment", span, "Close the comment with '*/'.")
	fun separator(span: TokenSpan) = issue(DiagnosticCode.INVALID_SEPARATOR, "Underscores must appear between digits", span)
	fun string(span: TokenSpan, raw: Boolean = false) = issue(if (raw) DiagnosticCode.UNTERMINATED_RAW_STRING else DiagnosticCode.UNTERMINATED_STRING, if (raw) "Unterminated raw string" else "Unterminated string", span, if (raw) "Close the raw string with three double quotes." else "Close the string with a double quote.")
	fun unicode(span: TokenSpan, kind: String) = issue(DiagnosticCode.INVALID_UNICODE, "Invalid Unicode scalar in $kind", span)
	fun multiline(span: TokenSpan) = issue(DiagnosticCode.MULTILINE_STRING, "Strings cannot span multiple lines", span, "Use a triple-quoted raw string for multiline text.")
	fun escape(escaped: Char, span: TokenSpan, kind: String) = issue(DiagnosticCode.INVALID_ESCAPE, "Invalid $kind escape: \\$escaped", span, "Supported escapes: \\n, \\r, \\t, \\0, \\\\, \\', and \\\".")
	fun character(span: TokenSpan) = issue(DiagnosticCode.UNTERMINATED_CHARACTER, "Unterminated character literal", span, "Close the character literal with a single quote.")
	fun emptyCharacter(span: TokenSpan) = issue(DiagnosticCode.EMPTY_CHARACTER, "Empty character literal", span)
	fun characterLength(span: TokenSpan) = issue(DiagnosticCode.INVALID_CHARACTER_LENGTH, "Expected closing quote after one character", span, "A character literal contains exactly one Unicode scalar.")
}
