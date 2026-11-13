package cloth.semantic.typing

import cloth.data.CArray
import cloth.data.CStruct
import cloth.data.StructField
import cloth.data.ValueType
import cloth.semantic.declarations.FileBindings

internal data class CollectionForeachPlan(val call: ResolvedCall.Source, val snapshot: SnapshotLayout)

/** The source library owns this representation. Only its registered nominal type
 * can cross the foreach protocol, including during generic declaration checking.
 */
internal data class SnapshotLayout(val slots: StructField, val element: StructField) {

	val arrayType get() = slots.type as CArray

	companion object {

		fun of(result: ValueType, files: FileBindings): SnapshotLayout? {
			val snapshot = result as? CStruct ?: return null
			val file = files.context(snapshot.name)
			if ("cloth/collections/Snapshot.co" !in file.standardLibraryPaths || file.localTypes["Snapshot"]?.declaration !== snapshot.declaration) return null
			val argument = snapshot.typeArguments.singleOrNull() ?: return null
			val slots = snapshot.fields.singleOrNull()?.takeIf { it.name.lexeme == "slots" && it.declaration.privateKeyword != null } ?: return null
			val array = (slots.type as? CArray)?.takeIf { it.size == null } ?: return null
			val element = (array.element as? CStruct)?.fields?.singleOrNull()?.takeIf { it.type == argument } ?: return null
			return SnapshotLayout(slots, element)
		}
	}
}
