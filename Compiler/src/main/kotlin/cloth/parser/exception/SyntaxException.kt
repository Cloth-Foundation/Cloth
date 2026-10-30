package cloth.parser.exception

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceLabel
import cloth.token.TokenSpan

class SyntaxException(code: DiagnosticCode, message: String, span: TokenSpan, help: String? = null) : ClothDiagnostic(code, message, listOf(SourceLabel(span, "here")), help)
