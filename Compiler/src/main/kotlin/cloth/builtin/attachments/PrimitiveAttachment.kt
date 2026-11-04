package cloth.builtin.attachments

import cloth.builtin.*
import cloth.data.ValueType
import cloth.target.TargetLayout
import cloth.token.Operators
import java.util.*

/** Typed, reusable definitions only: native handles belong to each emitted call. */
internal abstract class PrimitiveAttachment(val receiver: ValueType) {

	private val definitions = linkedMapOf<String, Attachment>()
	val all: Collection<Attachment> = Collections.unmodifiableCollection(definitions.values)
	operator fun get(name: String): Attachment? = definitions[name]

	/** Declaring an entry registers it. The hidden receiver uses the ordinary built-in ABI. */
	protected fun register(name: String, parameters: List<BuiltinParameter>, returns: ValueType, body: BuiltinBody): Attachment {
		require(name !in definitions) { "Duplicate attachment '${receiver.spelling}${Operators.DOT}$name'" }
		val function = object : BuiltinFunction(BuiltinProperties(name)) {
			override val overloads = listOf(BuiltinOverload(listOf(BuiltinParameter.Exact(receiver)) + parameters, returns, body))
		}
		return Attachment(receiver, BuiltinDefinition(name, function)).also { definitions[name] = it }
	}
}

internal data class Attachment(val receiver: ValueType, val definition: BuiltinDefinition<*>) {

	val overload: BuiltinOverload get() = definition.function.overloads.single()
	val parameters: List<BuiltinParameter> get() = overload.parameters.drop(1)
}

/** Value member lookup is separate from lexical function lookup and :: providers. */
internal object PrimitiveAttachments {

	// Target counts are specialized separately; reusable ASTs must never retain host widths.
	private val strings = ValueType.MACHINE_INTEGER_WIDTHS.associateWith { StringAttachments(it) }
	val STRING: StringAttachments get() = strings.getValue(TargetLayout.native().sizeBits)
	val all: List<Attachment> get() = all(TargetLayout.native())
	private val definitions = strings.mapValues { (_, provider) -> Collections.unmodifiableList(provider.all.toList()) }
	fun all(target: TargetLayout): List<Attachment> = definitions.getValue(target.sizeBits)
	operator fun get(receiver: ValueType, name: String, target: TargetLayout = TargetLayout.native()): Attachment? = if (receiver == ValueType.STRING) strings.getValue(target.sizeBits)[name] else null
}
