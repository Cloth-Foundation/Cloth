package cloth.semantic

import cloth.builtin.BoundBuiltinCall
import cloth.data.ValueType
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.Expression
import cloth.semantic.symbols.CallableSymbol
import cloth.token.Operators

internal sealed interface ResolvedCall {

	val type: FunctionType

	data class Source(
		val callable: CallableSymbol,
		override val type: FunctionType,
		val binding: ArgumentBinding,
		val receiver: MethodReceiver? = null,
	) : ResolvedCall

	data class Builtin(val call: BoundBuiltinCall) : ResolvedCall {

		override val type = FunctionType(call.arguments.map { it.parameterType }, call.overload.returns)
	}
}

internal data class ResolvedOperator(val call: ResolvedCall.Source, val receiverIndex: Int)
internal data class ResolvedOperatorContext(val application: BinaryExpression, val projections: List<ResolvedOperator?>)
internal data class ProjectedOperand(val type: ValueType, val original: Expression?)

internal data class ValueComparisonPlan(val operation: Operators, val overload: ResolvedOperator?, val negate: Boolean)
