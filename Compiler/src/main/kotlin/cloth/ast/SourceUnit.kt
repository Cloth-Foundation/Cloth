package cloth.ast

import cloth.diagnostics.SourceFile
import cloth.parser.declaration.Declaration
import cloth.token.TokenSpan

/** File header and declarations, with the original snapshot and EOF location. */
data class SourceUnit(val declarations: List<Declaration>, val end: TokenSpan? = null, val imports: List<ImportDeclaration> = emptyList()) {

	val source: SourceFile? get() = end?.source
}
