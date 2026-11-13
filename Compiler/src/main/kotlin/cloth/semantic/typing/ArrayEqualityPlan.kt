package cloth.semantic.typing

import cloth.data.*
import cloth.semantic.declarations.DeclarationModel
import cloth.token.Operators

internal data class ArrayEqualityPlan(val left: CArray, val right: CArray, val elementOverride: ResolvedOperator?) {

	fun mayCallUserCode(declarations: DeclarationModel): Boolean = elementOverride != null || declarations.arrayEqualityMayCall(left)
}

internal fun ValueType?.arrayPayload(): CArray? = ((this as? CNullable)?.payload ?: this) as? CArray

/** Only array extents are ignored. The nested nullable positions and leaf identity still match. */
internal fun ValueType.withoutArrayExtents(): ValueType = when (this) {
	is CArray    -> CArray(element.withoutArrayExtents())
	is CNullable -> CNullable(payload.withoutArrayExtents())
	else         -> this
}

internal fun CArray.equalityLeaf(): ValueType = element.arrayPayload()?.equalityLeaf() ?: (element as? CNullable)?.payload ?: element

internal fun DeclarationModel.arrayEqualityMayCall(type: CArray): Boolean {
	val leaf = type.equalityLeaf()
	val equality = Operators.EQUAL.toString()
	val view = leaf.classBackedType()
	if (view != null) return classDefinitions.keys.any { view.accepts(it) && equalityOverride(it, Operators.EQUAL) != null }
	return leaf is CMemberType && (operators(leaf, equality, right = false, arity = 1).isNotEmpty() || operators(leaf, equality, right = true, arity = 1).isNotEmpty())
}
