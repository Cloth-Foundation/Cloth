package cloth.builtin

import java.util.*

/** The complete registration list. Declaring an entry also registers its definition. */
internal object BuiltinFunctions {

	private val functions = mutableListOf<BuiltinDefinition<*>>()
	private val byName = linkedMapOf<String, BuiltinDefinition<*>>()

	val PRINT = register("print", ::PrintFunction)
	val PRINTLN = register("println", ::PrintlnFunction)

	val all: List<BuiltinDefinition<*>> = Collections.unmodifiableList(functions)

	operator fun get(name: String): BuiltinDefinition<*>? = byName[name]

	private fun <T : BuiltinFunction> register(name: String, factory: (BuiltinProperties) -> T): BuiltinDefinition<T> {
		require(name !in byName) { "Duplicate built-in '$name'" }
		val function = factory(BuiltinProperties(name))
		require(function.overloads.isNotEmpty()) { "Built-in '$name' has no overloads" }
		val signatures = function.overloads.map { it.parameters }
		require(signatures.distinct().size == signatures.size) { "Duplicate overload of built-in '$name'" }
		val definition = BuiltinDefinition(name, function)
		functions.add(definition)
		byName[name] = definition
		return definition
	}
}
