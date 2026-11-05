package cloth.semantic.symbols

import cloth.builtin.BuiltinDefinition
import cloth.data.CStruct
import cloth.data.StructField
import cloth.data.ValueType
import cloth.parser.declaration.CallableDeclaration
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.declaration.MethodDeclaration
import cloth.parser.declaration.ParameterDeclaration
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.BindingGroupDeclaration
import cloth.parser.statement.ForStatement
import cloth.parser.type.TypeReference
import cloth.token.*
import java.math.BigInteger

/** A structural declaration key, not a resolved or validated language type. */
data class SignatureType(val name: String, val unsigned: Boolean, val width: BigInteger?, val arrayRank: Int, val arraySizes: List<BigInteger?> = List(arrayRank) { null }, val nullable: Boolean = false, val arrayNullability: List<Boolean> = List(arrayRank) { false }) {

	val spelling: String
		get() = (if (unsigned) "${Keywords.UNSIGNED} " else "") + name + (width?.takeUnless { it == defaultWidth && name in numericNames }?.let { "${Operators.LESS}$it${Operators.GREATER}" } ?: "") + (if (nullable) Operators.QUESTION.toString() else "") + arraySizes.mapIndexed { index, size -> "${Operators.BRACKET_OPEN}${size ?: ""}${Operators.BRACKET_CLOSE}" + if (arrayNullability.getOrNull(index) == true) Operators.QUESTION.toString() else "" }.joinToString("")

	companion object {

		private val numericNames = setOf(Keywords.INTEGER.toString(), Keywords.FLOAT.toString())
		private val defaultWidth = BigInteger.valueOf(ValueType.DEFAULT_BITS.toLong())
		fun of(type: TypeReference) = SignatureType(type.name.lexeme, type.unsigned, type.width?.integerLiteralValue() ?: if (type.name.lexeme in numericNames) defaultWidth else null, type.arraySuffixes.size, type.arraySuffixes.indices.map { type.arraySizes.getOrNull(it)?.integerLiteralValue() }, type.nullable != null, type.arraySuffixes.indices.map { type.arrayNullability.getOrNull(it) != null })
	}
}

/** Return types, parameter names, visibility, and final do not distinguish overloads. */
data class FunctionSignature(val name: String, val parameterTypes: List<SignatureType>) {

	val spelling get() = "$name${Operators.PAREN_OPEN}${parameterTypes.joinToString("${Operators.COMMA} ") { it.spelling }}${Operators.PAREN_CLOSE}"

	companion object {

		internal fun of(declaration: CallableDeclaration) = FunctionSignature(declaration.name.lexeme, declaration.parameters.map { SignatureType.of(it.type) })
	}
}

/** Symbols use object identity; two declarations with identical syntax remain distinct. */
sealed interface Symbol {

	val name: Token
}

sealed interface CallableSymbol : Symbol {

	val declaration: CallableDeclaration
	val signature: FunctionSignature
	val parameters: List<ParameterSymbol>
}

class FunctionSymbol(override val declaration: FunctionDeclaration) : CallableSymbol {

	override val name get() = declaration.name
	override val signature = FunctionSignature.of(declaration)
	override val parameters: List<ParameterSymbol> = declaration.parameters.map { ParameterSymbol(it) }
}

class MethodSymbol(override val declaration: MethodDeclaration, val owner: CStruct) : CallableSymbol {

	override val name get() = declaration.name
	override val signature = FunctionSignature.of(declaration)
	override val parameters: List<ParameterSymbol> = declaration.parameters.map(::ParameterSymbol)
	val receiver = ReceiverSymbol(owner, declaration.keyword)
}

sealed interface CallableGroupSymbol : Symbol

class FunctionGroupSymbol(val overloads: List<FunctionSymbol>) : CallableGroupSymbol {

	override val name get() = overloads.first().name
}

class MethodGroupSymbol(val overloads: List<MethodSymbol>) : CallableGroupSymbol {

	override val name get() = overloads.first().name
}

/** Name is the actual use token, not a fabricated built-in declaration location. */
class BuiltinGroupSymbol internal constructor(internal val definition: BuiltinDefinition<*>, override val name: Token) : CallableGroupSymbol

sealed interface BindingSymbol : Symbol {

	val mutable: Boolean
	val declaredType: TypeReference?
}

class ReceiverSymbol(val owner: CStruct, val keyword: Token) : BindingSymbol {

	override val name = Token(TokenType.Keyword, keyword.span, Keywords.SELF.toString())
	override val mutable get() = false
	override val declaredType get() = null
	private val fields = mutableMapOf<String, ReceiverFieldSymbol>()
	internal fun field(name: String): ReceiverFieldSymbol? = owner.field(name)?.let { field ->
		fields.getOrPut(name) { ReceiverFieldSymbol(this, field) }
	}
}

/** A field selected through the current receiver, including implicit name lookup. */
class ReceiverFieldSymbol(val receiver: ReceiverSymbol, val field: StructField) : BindingSymbol {

	override val name get() = field.declaration.name
	override val mutable get() = true
	override val declaredType get() = field.declaration.type
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

class LoopBindingSymbol(val declaration: ForStatement) : BindingSymbol {

	override val name get() = declaration.binding.name
	override val mutable get() = true
	override val declaredType get() = declaration.binding.type
}
