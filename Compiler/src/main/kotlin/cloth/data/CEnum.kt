package cloth.data

import cloth.parser.declaration.EnumCaseDeclaration
import cloth.parser.declaration.EnumDeclaration
import cloth.parser.declaration.EnumFieldDeclaration
import cloth.parser.declaration.MethodDeclaration
import cloth.target.TargetLayout
import cloth.token.Keywords
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.global.LLVM.LLVMInt32TypeInContext

/** Every value carries its case ordinal. Payloads are fixed by the declaration. */
class CEnum internal constructor(val declaration: EnumDeclaration) : CMemberType(Keywords.ENUM) {

	override val name get() = declaration.name
	override lateinit var fields: List<EnumField>
		private set
	private lateinit var fieldsByName: Map<String, EnumField>
	internal val cases = declaration.cases.mapIndexed { index, case -> EnumCase(this, case, index) }.associateBy { it.declaration.name.lexeme }
	internal override fun field(name: String) = fieldsByName[name]

	internal fun defineFields(fields: List<EnumField>) {
		this.fields = fields
		fieldsByName = fields.associateBy { it.name.lexeme }
	}

	override fun llvmValue(context: LLVMContextRef, target: TargetLayout) = LLVMInt32TypeInContext(context)
	override fun providers(target: TargetLayout) = super.providers(target) + (CompilerDataProviders.STR to ProviderResult.EnumText(this))
}

internal data class EnumCase(val owner: CEnum, val declaration: EnumCaseDeclaration, val ordinal: Int)

class EnumField internal constructor(
	val owner: CEnum,
	val declaration: EnumFieldDeclaration,
	override val index: Int,
	override val type: ValueType,
	internal val reader: MethodDeclaration,
) : InstanceField {

	override val name get() = declaration.name
	override val annotation get() = declaration.type
}
