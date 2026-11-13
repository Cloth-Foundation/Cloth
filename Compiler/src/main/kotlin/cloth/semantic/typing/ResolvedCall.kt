package cloth.semantic.typing

import cloth.builtin.BoundBuiltinCall
import cloth.data.CClass
import cloth.data.CInterface
import cloth.data.ValueType
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.Expression
import cloth.semantic.MethodReceiver
import cloth.semantic.ObjectReceiver
import cloth.semantic.generics.ClosedStateContractKey
import cloth.semantic.symbols.*
import cloth.token.Operators

internal sealed interface ResolvedCall {

	val type: FunctionType

	sealed interface Bound : ResolvedCall {

		val binding: ArgumentBinding
	}

	data class Source(
		val callable: CallableSymbol,
		override val type: FunctionType,
		override val binding: ArgumentBinding,
		val receiver: MethodReceiver? = null,
	) : Bound

	/** The static contract supplies labels and omissions. Runtime selects the body. */
	data class Interface(
		val owner: CInterface,
		val contract: InterfaceMethodSymbol,
		override val type: FunctionType,
		override val binding: ArgumentBinding,
		val receiver: ObjectReceiver,
	) : Bound

	data class Builtin(val call: BoundBuiltinCall) : ResolvedCall {

		override val type = FunctionType(call.arguments.map { it.parameterType }, call.overload.returns)
	}
}

/** The implementation is selected after argument evaluation, using the active state at dispatch. */
internal data class ResolvedStateCall(
	val owner: CClass,
	val contract: CallableContractSymbol,
	val type: FunctionType,
	val binding: ArgumentBinding,
	val specialization: ClosedStateContractKey? = null,
)

internal data class ResolvedGuaranteedConstruction(
	val contract: ConstructorGuaranteeSymbol,
	// A declaration-site check can select the guarantee before the concrete type is known.
	val constructor: ConstructorSymbol?,
	val type: FunctionType,
	val binding: ArgumentBinding,
)

internal data class ResolvedOperator(val call: ResolvedCall.Source, val receiverIndex: Int, val indexValidator: MethodSymbol? = null)
internal data class ResolvedIndexWrite(val setter: ResolvedCall.Source, val validator: MethodSymbol)
internal data class BoundValueCheck(val application: BinaryExpression, val operator: ResolvedOperator)
internal data class ResolvedOperatorContext(val application: BinaryExpression, val projections: List<ResolvedOperator?>)
internal data class ProjectedOperand(val type: ValueType, val original: Expression?)

internal data class ValueComparisonPlan(val operation: Operators, val overload: ResolvedOperator?, val negate: Boolean)
