package cloth.builtin

import cloth.data.ValueType

internal open class PrintFunction(properties: BuiltinProperties, newline: Boolean = false) : BuiltinFunction(properties) {

	override val overloads: List<BuiltinOverload> = buildList {
		val body = BuiltinBody { context, arguments ->
			arguments.singleOrNull()?.let(context::writeStdout)
			if (newline) context.writeNewline()
			null
		}
		add(BuiltinOverload(listOf(BuiltinParameter.Text), ValueType.VOID, body))
		if (newline) add(BuiltinOverload(emptyList(), ValueType.VOID, body))
	}
}

internal class PrintlnFunction(properties: BuiltinProperties) : PrintFunction(properties, newline = true)
