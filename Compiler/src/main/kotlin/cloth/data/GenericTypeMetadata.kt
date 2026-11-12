package cloth.data

import cloth.diagnostics.GenericErrors
import cloth.token.Operators
import java.util.IdentityHashMap
import kotlin.collections.ArrayDeque

/** Canonical metadata is exact, never the bounded diagnostic spelling. Check the
 * expanded UTF-8 length on the argument DAG before allocating its flattened text.
 */
internal const val GENERIC_METADATA_BYTES = 1_048_576

internal fun genericTypeMetadata(root: CMemberType): String {
	val lengths = IdentityHashMap<ValueType, Long>()
	val pending = ArrayDeque<Pair<ValueType, Boolean>>().also { it.add(root to false) }
	fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size.toLong()
	while (pending.isNotEmpty()) {
		val (type, visited) = pending.removeLast()
		if (lengths.containsKey(type)) continue
		val children = when (type) {
			is CMemberType -> type.typeArguments
			is CArray      -> listOf(type.element)
			is CNullable   -> listOf(type.payload)
			else           -> emptyList()
		}
		if (!visited) {
			pending.add(type to true)
			children.asReversed().forEach { if (!lengths.containsKey(it)) pending.add(it to false) }
			continue
		}
		val length = when (type) {
			is CMemberType -> bytes(type.qualifiedName) + if (children.isEmpty()) 0 else bytes("${Operators.LESS}${Operators.GREATER}") + (children.size - 1L) * bytes("${Operators.COMMA} ") + children.sumOf { lengths.getValue(it) }
			is CArray      -> lengths.getValue(type.element) + bytes("${Operators.BRACKET_OPEN}${type.size ?: ""}${Operators.BRACKET_CLOSE}")
			is CNullable   -> lengths.getValue(type.payload) + bytes(Operators.QUESTION.toString())
			else           -> bytes(type.metadataSpelling)
		}
		// Saturate intermediate sums, so even a huge shared DAG stays cheap to measure.
		lengths[type] = minOf(length, GENERIC_METADATA_BYTES + 1L)
	}
	if (lengths.getValue(root) > GENERIC_METADATA_BYTES) throw GenericErrors.metadata(root.name.span, GENERIC_METADATA_BYTES)
	return genericTypeDisplay(root, GENERIC_METADATA_BYTES + 1, metadata = true)
}
