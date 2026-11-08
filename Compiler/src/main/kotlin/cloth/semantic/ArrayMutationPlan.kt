package cloth.semantic

import cloth.builtin.BuiltinArgument
import cloth.builtin.attachments.ArrayMutation
import cloth.data.CArray
import cloth.data.ValueType

/** The receiver carries its stored owner/path or temporary lifetime separately
 * from explicit value arguments. No private storage address enters source types.
 */
internal data class ArrayMutationPlan(
	val operation: ArrayMutation,
	val type: CArray,
	val receiver: MethodReceiver,
	val arguments: List<BuiltinArgument>,
	val result: ValueType,
)
