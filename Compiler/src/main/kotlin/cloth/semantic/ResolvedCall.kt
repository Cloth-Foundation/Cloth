package cloth.semantic

import cloth.builtin.BoundBuiltinCall
import cloth.semantic.symbols.FunctionSymbol

internal sealed interface ResolvedCall {

	val type: FunctionType

	data class Source(val function: FunctionSymbol, override val type: FunctionType) : ResolvedCall

	data class Builtin(val call: BoundBuiltinCall) : ResolvedCall {

		override val type = FunctionType(call.arguments.map { it.parameterType }, call.overload.returns)
	}
}
