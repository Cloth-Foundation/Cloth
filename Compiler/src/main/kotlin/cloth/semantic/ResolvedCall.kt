package cloth.semantic

import cloth.builtin.BoundBuiltinCall
import cloth.semantic.symbols.CallableSymbol

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
