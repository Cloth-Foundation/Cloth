package cloth.builtin.attachments

import cloth.builtin.*
import cloth.data.CFloat
import cloth.data.CInteger
import cloth.data.ValueType
import cloth.target.TargetLayout
import cloth.token.Operators
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** Typed, reusable definitions only: native handles belong to each emitted call. */
internal abstract class PrimitiveAttachment(val receiver: ValueType) {

	private val definitions = linkedMapOf<String, Attachment>()
	val all: Collection<Attachment> = Collections.unmodifiableCollection(definitions.values)
	operator fun get(name: String): Attachment? = definitions[name]

	/** Explicit opt-in: operands are read-only and buffered results are independent.
	 * Use register's unknown default for mutating, retaining or unproven bodies.
	 */
	protected fun valueMethod(name: String, parameters: List<BuiltinParameter>, returns: ValueType, body: BuiltinBody) = register(name, parameters, returns, BuiltinStorageContract.READ_ONLY_INDEPENDENT_RESULT, body)

	/** Declaring an entry registers it. The hidden receiver uses the ordinary built-in ABI. */
	protected fun register(name: String, parameters: List<BuiltinParameter>, returns: ValueType, storage: BuiltinStorageContract = BuiltinStorageContract.UNKNOWN, body: BuiltinBody): Attachment {
		require(name !in definitions) { "Duplicate attachment '${receiver.spelling}${Operators.DOT}$name'" }
		val function = object : BuiltinFunction(BuiltinProperties(name)) {
			override val overloads = listOf(BuiltinOverload(listOf(BuiltinParameter.Exact(receiver)) + parameters, returns, body, storage))
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
	private val floats = ValueType.FLOAT_WIDTHS.associate { CFloat(it).let { type -> type to FloatAttachments(type) } }
	val BOOL = BoolAttachments()
	val CHAR = CharAttachments()
	private val integers = ConcurrentHashMap<Pair<CInteger, Int>, IntegerAttachments>()
	private fun integers(type: CInteger, sizeBits: Int) = integers.computeIfAbsent(type to sizeBits) { IntegerAttachments(type, sizeBits) }

	// Name/result catalog for pre-typing presence and non-continuing receivers.
	// Integer families have identical names and nullability; one representative
	// avoids eagerly materializing 131,072 fixed receiver types for these passes.
	private val definitions = strings.mapValues { (sizeBits, provider) -> Collections.unmodifiableList(provider.all.toList() + integers(ValueType.INT, sizeBits).all + floats.values.flatMap { it.all } + BOOL.all + CHAR.all) }
	fun all(target: TargetLayout): List<Attachment> = definitions.getValue(target.sizeBits)
	fun string(target: TargetLayout): StringAttachments = strings.getValue(target.sizeBits)
	fun integer(type: CInteger, target: TargetLayout): IntegerAttachments = integers(type, target.sizeBits)
	fun floating(type: CFloat): FloatAttachments = floats.getValue(type)
	operator fun get(receiver: ValueType, name: String, target: TargetLayout): Attachment? = when (receiver) {
		ValueType.STRING -> string(target)[name]
		ValueType.BOOL   -> BOOL[name]
		ValueType.CHAR   -> CHAR[name]
		is CInteger      -> integer(receiver, target)[name]
		is CFloat        -> floating(receiver)[name]
		else             -> null
	}
}
