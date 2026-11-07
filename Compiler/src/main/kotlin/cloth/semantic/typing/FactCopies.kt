package cloth.semantic.typing

import java.util.*

/** Registers each table with its owner so snapshots follow the declared facts. */
internal class FactCopies<Owner> {

	private val copies = mutableListOf<(Owner) -> Unit>()

	fun <K, V> identityMap(read: (Owner) -> Map<K, V>): MutableMap<K, V> = map(IdentityHashMap(), read)

	fun <K, V> orderedMap(read: (Owner) -> Map<K, V>): MutableMap<K, V> = map(linkedMapOf(), read)

	private fun <K, V> map(table: MutableMap<K, V>, read: (Owner) -> Map<K, V>): MutableMap<K, V> {
		copies.add { source ->
			table.clear()
			table.putAll(read(source))
		}
		return table
	}

	fun copyFrom(source: Owner) = copies.forEach { it(source) }
}
