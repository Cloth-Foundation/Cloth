package cloth.semantic

import cloth.data.ValueType
import cloth.diagnostics.InternalErrors
import cloth.token.Keywords
import cloth.token.Operators

/** An expression/return contract, not a storable Cloth tuple type. */
sealed interface ResultShape {

	val values: List<ValueType>

	data object Empty : ResultShape {

		override val values = emptyList<ValueType>()
	}

	data class Single(val type: ValueType) : ResultShape { init {
		require(type != ValueType.VOID && type != ValueType.NEVER)
	}

		override val values = listOf(type)
	}

	data class Multiple(override val values: List<ValueType>) : ResultShape { init {
		require(values.size > 1 && values.none { it == ValueType.VOID || it == ValueType.NEVER })
	}
	}

	data object Never : ResultShape {

		override val values = emptyList<ValueType>()
	}

	val spelling: String
		get() = when (this) {
			Empty       -> Keywords.VOID.toString()
			Never       -> "<no continuation>"
			is Single   -> type.spelling
			is Multiple -> values.joinToString(prefix = Operators.PAREN_OPEN.toString(), postfix = Operators.PAREN_CLOSE.toString()) { it.spelling }
		}

	/** Existing scalar/backend APIs must never silently select the first result. */
	fun scalarOrNull(): ValueType? = when (this) {
		Empty       -> ValueType.VOID
		Never       -> ValueType.NEVER
		is Single   -> type
		is Multiple -> null
	}

	companion object {

		fun of(type: ValueType): ResultShape = when (type) {
			ValueType.VOID  -> Empty
			ValueType.NEVER -> Never
			else            -> Single(type)
		}

		fun of(values: List<ValueType>): ResultShape = when (values.size) {
			0    -> Empty
			1    -> of(values.single())
			else -> Multiple(values.toList())
		}
	}
}

internal fun ResultShape.scalar() = scalarOrNull() ?: throw InternalErrors.invariant("Multiple results require an ordered-result consumer")
