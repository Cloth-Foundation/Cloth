package cloth.ast

import cloth.parser.statement.Statement

/** Body fragment used by focused parser/backend tests; source files use SourceUnit. */
data class ASTStructure(val statements: List<Statement>)
