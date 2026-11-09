package cloth.builtin.functions

import cloth.builtin.BuiltinBody
import cloth.builtin.BuiltinFunction
import cloth.builtin.BuiltinOverload
import cloth.builtin.BuiltinParameter
import cloth.builtin.BuiltinProperties
import cloth.data.ValueType

internal class ExitFunction(properties: BuiltinProperties) : BuiltinFunction(properties) {

	override val overloads = listOf(
		BuiltinOverload(listOf(BuiltinParameter.NativeInt), ValueType.NEVER, BuiltinBody { context, arguments ->
			context.processExit.terminate(arguments.single())
			null
		}),
	)
}
