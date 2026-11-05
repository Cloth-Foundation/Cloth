package cloth.data

import cloth.parser.declaration.StructDeclaration
import cloth.parser.declaration.StructFieldDeclaration
import cloth.target.TargetLayout
import cloth.token.Keywords
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*
import java.util.*

/** One descriptor per declaration per source unit. Equality is nominal. */
class CStruct internal constructor(val declaration: StructDeclaration, override val fields: List<StructField>) : CMemberType(Keywords.STRUCT) {

	override val name get() = declaration.name
	override val indirect get() = true
	override val buffered = fields.any { it.type.buffered }
	private val fieldsByName = fields.associateBy { it.declaration.name.lexeme }
	internal override fun field(name: String): StructField? = fieldsByName[name]

	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = LLVMPointerTypeInContext(context, 0)

	override fun llvmStorage(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = storage(context, target, IdentityHashMap())

	private fun storage(context: LLVMContextRef, target: TargetLayout, layouts: MutableMap<CStruct, LLVMTypeRef>): LLVMTypeRef = layouts.getOrPut(this) {
		// Even an empty value needs a positive stride when stored in an array.
		val count = maxOf(1, fields.size)
		PointerPointer<LLVMTypeRef>(count.toLong()).use { members ->
			if (fields.isEmpty()) members.put(0, LLVMInt8TypeInContext(context))
			else fields.forEach { field ->
				val type = field.type
				val member = when {
					type is CStruct                              -> type.storage(context, target, layouts)
					type is CNullable && type.payload is CStruct -> type.storageCarrier(context, type.payload.storage(context, target, layouts))
					else                                         -> type.llvmStorage(context, target)
				}
				members.put(field.index.toLong(), member)
			}
			LLVMStructTypeInContext(context, members, count, 0)
		}
	}
}

data class StructField(val declaration: StructFieldDeclaration, override val index: Int, override val type: ValueType) : InstanceField {

	override val name get() = declaration.name
	override val annotation get() = declaration.type
}

internal val ValueType.isStructValue: Boolean get() = this is CStruct || this is CNullable && payload is CStruct

internal val ValueType.containsStruct: Boolean
	get() = when (this) {
		is CStruct   -> true
		is CNullable -> payload.containsStruct
		is CArray    -> element.containsStruct
		else         -> false
	}
