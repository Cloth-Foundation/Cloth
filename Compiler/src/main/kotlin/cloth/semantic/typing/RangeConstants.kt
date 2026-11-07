package cloth.semantic.typing

import cloth.data.CFloat
import cloth.data.CInteger
import cloth.parser.expression.Expression
import java.math.BigInteger

/** Range and attachment checks require ordinary, non-nullable numeric values. */
internal class RangeConstants(private val model: SemanticModel) {

	private val numbers = NumericConstants(model)
	fun integer(expression: Expression): BigInteger? = if (model.typeOf(expression) is CInteger) numbers.integer(expression) else null
	fun floating(expression: Expression): Double? = if (model.typeOf(expression) is CFloat) numbers.floating(expression) else null
}
