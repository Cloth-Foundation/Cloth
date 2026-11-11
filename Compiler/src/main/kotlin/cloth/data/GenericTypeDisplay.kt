package cloth.data

import cloth.token.Operators

/** Human-facing type text must not expand a shared argument DAG without a bound.
 * Canonical metadata and semantic identity use their separate representations.
 */
internal fun genericTypeDisplay(root: CMemberType, limit: Int = 4096, metadata: Boolean = false): String {
	class Arguments(val values: Iterator<ValueType>, var first: Boolean = true)
	class Suffixes(val arrays: List<CArray>, val nullable: List<Boolean>, var index: Int = 0)
	val pending = ArrayDeque<Any>().also { it.add(root) }
	val text = StringBuilder()
	var truncated = false
	fun ellipsis() {
		if (text.length == limit) text.setLength(limit - 1)
		if (text.lastOrNull()?.isHighSurrogate() == true) text.setLength(text.length - 1)
		text.append('…')
		truncated = true
		pending.clear()
	}
	fun append(value: String) {
		if (truncated) return
		if (text.length + value.length <= limit) text.append(value)
		else {
			text.append(value.take((limit - text.length - 1).coerceAtLeast(0)))
			ellipsis()
		}
	}
	while (pending.isNotEmpty() && !truncated) {
		if (text.length == limit) { ellipsis(); break }
		when (val item = pending.removeLast()) {
			is String -> append(item)
			is Arguments -> if (item.values.hasNext()) {
				if (!item.first) append("${Operators.COMMA} ")
				item.first = false
				pending.add(item)
				pending.add(item.values.next())
			}
			is Suffixes -> if (item.index < item.arrays.size) {
				val index = item.index++
				append("${Operators.BRACKET_OPEN}${item.arrays[index].size ?: ""}${Operators.BRACKET_CLOSE}" + if (item.nullable[item.nullable.lastIndex - index]) Operators.QUESTION.toString() else "")
				if (text.length < limit) pending.add(item)
			}
			is ValueType -> when {
				item is CArray || item is CNullable && item.payload is CArray -> {
					var base = item
					val arrays = mutableListOf<CArray>()
					val nullable = mutableListOf<Boolean>()
					while (base is CArray || base is CNullable && base.payload is CArray) {
						nullable.add(base is CNullable)
						val array = ((base as? CNullable)?.payload ?: base) as CArray
						arrays.add(array)
						base = array.element
					}
					pending.add(Suffixes(arrays, nullable))
					pending.add(base)
				}
				item is CNullable -> { pending.add(Operators.QUESTION.toString()); pending.add(item.payload) }
				item is CMemberType -> {
					append(item.qualifiedName)
					if (item.typeArguments.isNotEmpty() && text.length < limit) {
						append(Operators.LESS.toString())
						pending.add(Operators.GREATER.toString())
						pending.add(Arguments(item.typeArguments.iterator()))
					}
				}
				else -> append(if (metadata) item.metadataSpelling else item.spelling)
			}
		}
	}
	return text.toString()
}
