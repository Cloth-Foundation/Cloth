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

/** An unknown exact value type during declaration checking; never a native type. */
class CTypeParameter internal constructor(val owner: Declaration, val parameter: TypeParameterDeclaration, val index: Int) : ValueType(Keywords.TYPEINFO) {
	override val spelling get() = parameter.name.lexeme
	// An inferred binding cannot assume that an arbitrary actual argument excludes null.
	override val containsNullability get() = true
	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = throw GenericErrors.compilation(parameter.span)
}

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
