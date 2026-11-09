package cloth.data

import cloth.diagnostics.InterfaceErrors
import cloth.parser.declaration.InterfaceDeclaration
import cloth.target.TargetLayout
import cloth.token.Keywords
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef

/** A nominal interface declaration or an interned application of it. */
class CInterface internal constructor(
	override val declaration: InterfaceDeclaration,
	val genericDefinition: CInterface? = null,
	override val typeArguments: List<ValueType> = emptyList(),
) : CMemberType(Keywords.INTERFACE) {
	internal val definition get() = genericDefinition ?: this
	override val fields: List<InstanceField> get() = emptyList()
	override fun field(name: String): InstanceField? = null
	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef =
		throw InterfaceErrors.compilation(declaration.kind.span)
}
