package cloth.semantic

import cloth.data.CArray
import cloth.data.ValueType
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.Expression
import cloth.parser.expression.NameExpression
import cloth.parser.statement.ForStepper
import cloth.target.TargetLayout
import cloth.token.Operators
import cloth.token.Token

/** Per-compilation facts; syntax and canonical ASTs retain no target-specific state. */
data class ForLoopPlan(val target: TargetLayout, val bindingType: ValueType, val arraySourceType: CArray?, val stepperExpression: Expression?)

/** This semantic expression reuses ordinary assignment/arithmetic checks while the
 * syntax's original operator remains available to the later overflow emitter.
 */
internal fun forStepperExpression(stepper: ForStepper?): Expression? {
	if (stepper?.value == null) return null
	if (stepper.operator.lexeme == Operators.ASSIGN.toString()) return stepper.value
	return BinaryExpression(NameExpression(stepper.target), Token(stepper.operator.type, stepper.operator.span, stepper.operator.lexeme.dropLast(1)), stepper.value)
}
