package cloth.data

import cloth.diagnostics.InterfaceErrors
import cloth.parser.declaration.InterfaceDeclaration
import cloth.target.TargetLayout
import cloth.token.Keywords
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef

/** The original declaration identity used by file scopes and generic name binding. */
class CInterface internal constructor(override val declaration: InterfaceDeclaration) : CMemberType(Keywords.INTERFACE) {
	override val fields: List<InstanceField> get() = emptyList()
	override fun field(name: String): InstanceField? = null
	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef =
		throw InterfaceErrors.compilation(declaration.kind.span)
}
