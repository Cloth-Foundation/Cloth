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
import kotlin.collections.ArrayDeque
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.emptyList
import kotlin.collections.fold
import kotlin.collections.isNotEmpty
import kotlin.collections.plus

internal enum class TypeParameterProjection { ELEMENT,
	PAYLOAD
}

/** An unknown exact value type, including leaves of a known array or nullable shape. */
class CTypeParameter internal constructor(
	val owner: Declaration,
	val parameter: TypeParameterDeclaration,
	val index: Int,
	internal val numberBound: Boolean,
	private val projection: List<TypeParameterProjection> = emptyList(),
) : ValueType(Keywords.TYPEINFO) {

	internal var nominalBounds: List<CMemberType> = emptyList()
	internal var constructorSignatures: List<List<ValueType>> = emptyList()
	override val spelling get() = parameter.name.lexeme
	override val containsNullability get() = projection.isEmpty() && !numberBound && nominalBounds.isEmpty()
	internal fun project(actual: ValueType): ValueType = projection.fold(actual) { type, step ->
		when (step) {
			TypeParameterProjection.ELEMENT -> (type as CArray).element
			TypeParameterProjection.PAYLOAD -> (type as CNullable).payload
		}
	}

	internal override fun providers(target: TargetLayout): Map<CompilerDataProviders, ProviderResult<*>> {
		val providers = super.providers(target)
		val text = when {
			numberBound                -> ProviderResult.ScalarText(this)
			nominalBounds.isNotEmpty() -> ProviderResult.ClassText(this)
			else                       -> return providers
		}
		return providers + (CompilerDataProviders.STR to text)
	}

	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = throw GenericErrors.compilation(parameter.span)
}

internal fun ValueType.parameterPayload(): CTypeParameter? = ((this as? CNullable)?.payload ?: this) as? CTypeParameter

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
			is CArray         -> pending.add(type.element)
			is CNullable      -> pending.add(type.payload)
			is CMemberType    -> pending.addAll(type.typeArguments)
			else              -> Unit
		}
	}
	return false
}
