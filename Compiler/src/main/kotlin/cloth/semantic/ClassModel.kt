package cloth.semantic

import cloth.data.CMemberType
import cloth.parser.expression.Expression
import cloth.semantic.symbols.ReceiverSymbol
import cloth.semantic.typing.ResolvedOperator
import cloth.token.Operators

/** A selected object identity, independent of any binding that happens to hold it. */
internal sealed interface ObjectReceiver {

	data class Explicit(val expression: Expression) : ObjectReceiver
	data class Self(val symbol: ReceiverSymbol) : ObjectReceiver
}

/** These projections start after resolving and validating the object identity. */
internal data class InstancePlace(val receiver: ObjectReceiver, val steps: List<PlaceStep>)

internal data class ClassDeletionPlan(val owner: CMemberType, val nullable: Boolean)

internal data class InterfaceTypeTest(val source: CMemberType, val target: CMemberType, val nullableSource: Boolean)

internal data class ClassComparisonPlan(
	val operation: Operators,
	val identityOverride: ResolvedOperator?,
	val checkedIdentity: Boolean = false,
) {

	val checksLiveness get() = operation != Operators.IDENTITY || identityOverride != null || checkedIdentity
}
