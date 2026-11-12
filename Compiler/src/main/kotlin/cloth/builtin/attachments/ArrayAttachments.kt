package cloth.builtin.attachments

import cloth.builtin.BuiltinParameter
import cloth.builtin.BuiltinStorageContract
import cloth.data.CArray
import cloth.data.CUSize
import cloth.data.ValueType
import cloth.diagnostics.InternalErrors

internal enum class ArrayMutation(val spelling: String, val changesLength: Boolean = true) {

	APPEND("append"),
	INSERT("insert"),
	REMOVE_AT("removeAt"),
	CLEAR("clear"),
	RESERVE("reserve", changesLength = false);

	companion object {

		fun named(name: String): ArrayMutation? = entries.firstOrNull { it.spelling == name }
	}
}

/** Signatures specialize by concrete element type and compilation target. A
 * mutating receiver is resolved separately from the by-value explicit operands.
 */
internal class ArrayAttachments(type: CArray, sizeBits: Int) : PrimitiveAttachment(type) {

	init {
		val index = BuiltinParameter.Exact(CUSize(sizeBits))
		val element = BuiltinParameter.Exact(type.element)
		for (operation in ArrayMutation.entries) {
			val parameters = when (operation) {
				ArrayMutation.APPEND                           -> listOf(element)
				ArrayMutation.INSERT                           -> listOf(index, element)
				ArrayMutation.REMOVE_AT, ArrayMutation.RESERVE -> listOf(index)
				ArrayMutation.CLEAR                            -> emptyList()
			}
			register(operation.spelling, parameters, if (operation == ArrayMutation.REMOVE_AT) type.element else ValueType.VOID, BuiltinStorageContract.MUTATING_RECEIVER) { _, _ ->
				throw InternalErrors.invariant("Array mutation requires receiver-aware storage lowering")
			}
		}
	}
}
