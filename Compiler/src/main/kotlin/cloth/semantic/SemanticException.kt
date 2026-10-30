package cloth.semantic

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceLabel

class SemanticException(code: DiagnosticCode, message: String, labels: List<SourceLabel>, help: String? = null) : ClothDiagnostic(code, message, labels, help)
