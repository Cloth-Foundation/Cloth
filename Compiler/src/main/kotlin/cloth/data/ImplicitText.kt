package cloth.data

import cloth.target.TargetLayout

/** Bind compiler-inserted text conversion without exposing nullable str as a source provider. */
internal fun ValueType.implicitText(target: TargetLayout): ProviderResult<*>? {
	if (this == ValueType.Null) return ProviderResult.NullText
	val receiver = (this as? CNullable)?.payload ?: this
	val recipe = receiver.provider(CompilerDataProviders.STR.toString(), target) ?: return null
	if (recipe.type != CString || ProviderReceiverForm.VALUE !in recipe.receiverForms) return null
	return if (this is CNullable) ProviderResult.NullableText(this, recipe) else recipe
}
