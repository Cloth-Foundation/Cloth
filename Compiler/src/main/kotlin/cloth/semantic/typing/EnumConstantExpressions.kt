package cloth.semantic.typing

import cloth.parser.expression.CastExpression
import cloth.parser.expression.Expression
import cloth.semantic.flow.eagerOperands

/** EnumModel admits the forms first; reuse ordinary eager operand order for their values. */
internal class EnumConstantExpressions(model: SemanticModel) {

	private val numbers = NumericConstants(model)
	fun firstInvalidCast(expression: Expression): CastExpression? {
		for (child in expression.eagerOperands()) firstInvalidCast(child)?.let { return it }
		return (expression as? CastExpression)?.takeIf(numbers::isInvalidCast)
	}
}
