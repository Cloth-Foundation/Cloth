package cloth.semantic.symbols

import cloth.builtin.BuiltinDefinition
import cloth.data.ValueType
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.declaration.ParameterDeclaration
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.BindingGroupDeclaration
import cloth.parser.type.TypeReference
import cloth.token.Keywords
import cloth.token.Token
import java.math.BigInteger

/** A structural declaration key, not a resolved or validated language type. */
data class SignatureType(val name: String, val unsigned: Boolean, val width: BigInteger?, val arrayRank: Int, val arraySizes: List<BigInteger?> = List(arrayRank) { null }, val nullable: Boolean = false, val arrayNullability: List<Boolean> = List(arrayRank) { false }) {

	val spelling: String
		get() = (if (unsigned) "unsigned " else "") + name + (width?.takeUnless { it == defaultWidth && name in numericNames }?.let { "<$it>" } ?: "") + (if (nullable) "?" else "") + arraySizes.mapIndexed { index, size -> "[${size ?: ""}]" + if (arrayNullability.getOrNull(index) == true) "?" else "" }.joinToString("")

	companion object {

		private val numericNames = setOf(Keywords.INTEGER.toString(), Keywords.FLOAT.toString())
		private val defaultWidth = BigInteger.valueOf(ValueType.DEFAULT_BITS.toLong())
		fun of(type: TypeReference) = SignatureType(type.name.lexeme, type.unsigned, type.width?.lexeme?.toBigInteger() ?: if (type.name.lexeme in numericNames) defaultWidth else null, type.arraySuffixes.size, type.arraySuffixes.indices.map { type.arraySizes.getOrNull(it)?.lexeme?.toBigInteger() }, type.nullable != null, type.arraySuffixes.indices.map { type.arrayNullability.getOrNull(it) != null })
	}
}

/** Return types, parameter names, visibility, and final do not distinguish overloads. */
data class FunctionSignature(val name: String, val parameterTypes: List<SignatureType>) {

	val spelling get() = "$name(${parameterTypes.joinToString { it.spelling }})"
}

/** Symbols use object identity; two declarations with identical syntax remain distinct. */
sealed interface Symbol {

	val name: Token
}

class FunctionSymbol(val declaration: FunctionDeclaration) : Symbol {

	override val name get() = declaration.name
	val signature = FunctionSignature(name.lexeme, declaration.parameters.map { SignatureType.of(it.type) })
	val parameters: List<ParameterSymbol> = declaration.parameters.map { ParameterSymbol(it) }
}

sealed interface CallableGroupSymbol : Symbol

class FunctionGroupSymbol(val overloads: List<FunctionSymbol>) : CallableGroupSymbol {

	override val name get() = overloads.first().name
}

/** Name is the actual use token, not a fabricated built-in declaration location. */
class BuiltinGroupSymbol internal constructor(internal val definition: BuiltinDefinition<*>, override val name: Token) : CallableGroupSymbol

sealed interface BindingSymbol : Symbol {

	val mutable: Boolean
	val declaredType: TypeReference?
}

class ParameterSymbol(val declaration: ParameterDeclaration) : BindingSymbol {

	override val name get() = declaration.name
	override val mutable get() = declaration.mutable
	override val declaredType get() = declaration.type
}

class LocalSymbol(val declaration: BindingDeclaration) : BindingSymbol {

	override val name get() = declaration.name
	override val mutable get() = declaration.mutable
	override val declaredType get() = declaration.type
}

class GroupLocalSymbol(val declaration: BindingGroupDeclaration, val position: Int) : BindingSymbol {

	override val name get() = declaration.targets[position].token
	override val mutable get() = declaration.mutable
	override val declaredType get() = if (declaration.types.size == 1) declaration.types.single() else declaration.types.getOrNull(position)
}
