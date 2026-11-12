package cloth.builtin.functions

import cloth.builtin.*
import cloth.data.ValueType

internal class ExitFunction(properties: BuiltinProperties) : BuiltinFunction(properties) {

	override val overloads: List<BuiltinOverload> = buildList {
		val body = BuiltinBody { context, arguments ->
			val status = arguments.first()
			arguments.getOrNull(1)?.let(context::writeStdout)
			context.processExit.report(status)
			context.processExit.terminate(status)
			null
		}
		add(BuiltinOverload(listOf(BuiltinParameter.NativeInt), ValueType.NEVER, body))
		add(BuiltinOverload(listOf(BuiltinParameter.NativeInt, BuiltinParameter.Exact(ValueType.STRING)), ValueType.NEVER, body))
	}
}
