package cloth.data

import cloth.diagnostics.GenericErrors
import cloth.parser.declaration.Declaration
import cloth.parser.type.TypeParameterDeclaration
import cloth.target.TargetLayout
import cloth.token.Keywords
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import java.util.Collections
import java.util.IdentityHashMap

/** An unknown exact value type during declaration checking. */
class CTypeParameter internal constructor(
	val owner: Declaration,
	val parameter: TypeParameterDeclaration,
	val index: Int,
	internal val numberBound: Boolean,
) : ValueType(Keywords.TYPEINFO) {
	internal var nominalBound: CMemberType? = null
	override val spelling get() = parameter.name.lexeme
	override val containsNullability get() = !numberBound && nominalBound == null
	internal override fun providers(target: TargetLayout): Map<CompilerDataProviders, ProviderResult<*>> {
		val providers = super.providers(target)
		val text = when {
			numberBound -> ProviderResult.ScalarText(this)
			nominalBound != null -> ProviderResult.ClassText(this)
			else -> return providers
		}
		return providers + (CompilerDataProviders.STR to text)
	}
	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = throw GenericErrors.compilation(parameter.span)
}

/** Numeric operations can be checked on a bound parameter before its format is known. */
internal val ValueType.supportsNumericOperations: Boolean get() = numeric || (this is CTypeParameter && numberBound)

internal fun ValueType.hasTypeParameters(): Boolean {
	val seen = Collections.newSetFromMap(IdentityHashMap<ValueType, Boolean>())
	val pending = ArrayDeque<ValueType>().also { it.add(this) }
	while (pending.isNotEmpty()) {
		val type = pending.removeLast()
		if (!seen.add(type)) continue
		when (type) {
			is CTypeParameter -> return true
			is CArray -> pending.add(type.element)
			is CNullable -> pending.add(type.payload)
			is CMemberType -> pending.addAll(type.typeArguments)
			else -> Unit
		}
	}
	return false
}
