package cloth.semantic.symbols

import cloth.diagnostics.SemanticErrors
import cloth.token.Token

/** One namespace; lookup always prefers the nearest lexical declaration. */
class LexicalScope(val parent: LexicalScope? = null) {

	private val declarations = linkedMapOf<String, Symbol>()
	val symbols: List<Symbol> get() = declarations.values.toList()

	fun lookup(name: String): Symbol? = declarations[name] ?: parent?.lookup(name)

	internal fun checkAvailable(name: Token) {
		declarations[name.lexeme]?.let { throw SemanticErrors.duplicate(name, it.name) }
	}

	internal fun declare(symbol: Symbol) {
		checkAvailable(symbol.name)
		declarations[symbol.name.lexeme] = symbol
	}
}
