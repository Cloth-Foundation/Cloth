package cloth.semantic

import cloth.data.ValueType
import cloth.parser.expression.Expression
import cloth.parser.statement.ReceivingTarget

enum class ReceptionKind { BROADCAST,
	POSITIONAL,
	NONCONTINUING
}

/** Discards retain type checking and a source position, but own no symbol/storage. */
data class ReceptionSlot(val target: ReceivingTarget, val type: ValueType, val sourceIndex: Int)

/** One producer evaluation followed by the recorded bindings/writes in source order.
 * Positional assignments must snapshot every result before the first target store.
 */
data class ReceptionPlan(val producer: Expression, val results: ResultShape, val kind: ReceptionKind, val slots: List<ReceptionSlot>) {

	val snapshotsBeforeWrites get() = kind == ReceptionKind.POSITIONAL
	val statementResult: ResultShape
		get() = when (kind) {
			ReceptionKind.BROADCAST     -> slots.firstOrNull { it.target is ReceivingTarget.Named }?.let { ResultShape.of(it.type) } ?: ResultShape.Empty
			ReceptionKind.POSITIONAL    -> ResultShape.Empty
			ReceptionKind.NONCONTINUING -> ResultShape.Never
		}
}

/** Context for one broadcast value or independently typed positional values.
 * Missing contexts stay absent; discards never manufacture a wildcard value type.
 */
internal data class ReceptionContext(val broadcast: ValueType?, val positional: List<ValueType?>)
