package cloth.builtin.functions

import cloth.builtin.*
import cloth.data.CNullable
import cloth.data.ValueType

internal class InputFunction(properties: BuiltinProperties) : BuiltinFunction(properties) {

	override val overloads: List<BuiltinOverload> = buildList {
		val text = CNullable(ValueType.STRING)
		val body = BuiltinBody { context, arguments -> context.readStdin(arguments.singleOrNull()) }
		add(BuiltinOverload(emptyList(), text, body))
		add(BuiltinOverload(listOf(BuiltinParameter.Exact(text)), text, body))
	}
}
