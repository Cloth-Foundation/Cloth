package cloth.ast

import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenSpan

/** A source-file path, without a module prefix or the .co extension. */
data class ImportPath(val components: List<Token>, val dots: List<Token>) {

	val span: TokenSpan get() = components.first().span.through(components.last().span)
	val spelling: String get() = components.joinToString(Operators.DOT.toString()) { it.lexeme }
}

sealed interface ImportSelection {

	val span: TokenSpan

	data class Names(val names: List<Token>, val commas: List<Token>) : ImportSelection {

		override val span: TokenSpan get() = names.first().span.through(names.last().span)
	}

	data class Wildcard(val star: Token) : ImportSelection {

		override val span: TokenSpan get() = star.span
	}
}

/** Imports are file headers, separate from ordinary declarations and their scopes. */
data class ImportDeclaration(val keyword: Token, val path: ImportPath, val colon: Token, val selection: ImportSelection, val terminator: Token? = null) {

	/** The optional semicolon is retained separately, as for other declaration spans. */
	val span: TokenSpan get() = keyword.span.through(selection.span)
}
