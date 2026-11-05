package cloth.semantic

import cloth.data.CArray
import cloth.data.StructField
import cloth.data.ValueType
import cloth.parser.expression.Expression
import cloth.parser.expression.FieldAccessExpression
import cloth.parser.expression.IndexExpression
import cloth.parser.expression.NameExpression
import cloth.semantic.symbols.BindingSymbol

internal fun Expression.placeRoot(): NameExpression? = when (this) {
	is NameExpression        -> this
	is FieldAccessExpression -> receiver.placeRoot()
	is IndexExpression       -> array.placeRoot()
	else                     -> null
}

internal sealed interface PlaceStep { data class Field(val field: StructField) : PlaceStep
	data class Index(val index: Expression, val array: CArray) : PlaceStep
}

/** A resolved stored path. Each index expression remains a single evaluation. */
internal data class FieldWritePlan(val root: BindingSymbol, val steps: List<PlaceStep>, val type: ValueType)
