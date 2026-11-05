package cloth.semantic

import cloth.data.CArray
import cloth.data.StructField
import cloth.data.ValueType
import cloth.parser.expression.*
import cloth.semantic.symbols.BindingSymbol
import cloth.semantic.symbols.LocalSymbol
import cloth.semantic.symbols.ReceiverFieldSymbol

internal fun Expression.placeRoot(): NameExpression? = when (this) {
	is NameExpression        -> this
	is FieldAccessExpression -> receiver.placeRoot()
	is IndexExpression       -> array.placeRoot()
	else                     -> null
}

internal fun DeclarationModel.storedRoot(expression: Expression): BindingSymbol? {
	val root = expression.placeRoot()?.let { names[it] as? BindingSymbol }
	return when (root) {
		is LocalSymbol         -> root.takeUnless { it.declaration.initializer is FunctionBlockExpression }
		is ReceiverFieldSymbol -> root.receiver
		else                   -> root
	}
}

internal sealed interface PlaceStep { data class Field(val field: StructField) : PlaceStep
	data class Index(val index: Expression, val array: CArray) : PlaceStep
}

/** A resolved stored path. Each index expression remains a single evaluation. */
internal data class StoredPlace(val root: BindingSymbol, val steps: List<PlaceStep>)

internal data class FieldWritePlan(val place: StoredPlace, val type: ValueType)

internal sealed interface MethodReceiver { data class Stored(val place: StoredPlace) : MethodReceiver
	data class Temporary(val expression: Expression) : MethodReceiver
}
