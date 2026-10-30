package cloth.ast

import cloth.parser.declaration.Declaration
import cloth.token.TokenSpan

/** Declaration-based source, with EOF location available for whole-program diagnostics. */
data class SourceUnit(val declarations: List<Declaration>, val end: TokenSpan? = null)
