package cloth.data

import cloth.parser.declaration.InterfaceDeclaration
import cloth.target.TargetLayout
import cloth.token.Keywords
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.LLVMInt64TypeInContext

/** A nominal interface declaration or an interned application of it. */
class CInterface internal constructor(
	override val declaration: InterfaceDeclaration,
	val genericDefinition: CInterface? = null,
	override val typeArguments: List<ValueType> = emptyList(),
	enclosingName: String? = genericDefinition?.enclosingName,
) : CMemberType(Keywords.INTERFACE, enclosingName) {
	internal val definition get() = genericDefinition ?: this
	internal var ancestors: Set<CInterface> = emptySet()
	override fun accepts(actual: ValueType): Boolean = super.accepts(actual) || when (actual) {
		is CClass -> this in actual.implementedInterfaces
		is CInterface -> this in actual.ancestors
		else -> false
	}
	override val fields: List<InstanceField> get() = emptyList()
	override fun field(name: String): InstanceField? = null
	/** Same private slot and generation carrier as its implementing class. */
	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = LLVMInt64TypeInContext(context)
	override fun providers(target: TargetLayout): Map<CompilerDataProviders, ProviderResult<*>> = super.providers(target) + (CompilerDataProviders.STR to ProviderResult.ClassText(this))
}

/** Source types with a class identity, including nullable views of that identity. */
internal fun ValueType.classBackedType(): CMemberType? = when (val payload = (this as? CNullable)?.payload ?: this) {
	is CClass -> payload
	is CInterface -> payload
	else -> null
}

/** A successful nominal type test refines a stable object binding. */
internal fun CMemberType.canNarrowTo(target: CMemberType): Boolean = when (this) {
	is CClass -> target is CInterface || target is CClass && accepts(target)
	is CInterface -> target is CClass || target is CInterface
	else -> false
}
