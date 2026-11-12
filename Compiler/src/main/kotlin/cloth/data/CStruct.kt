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

/** Nominal descriptor: one per ordinary declaration or closed generic argument tuple. */
class CStruct internal constructor(
	override val declaration: StructDeclaration,
	fields: List<StructField>? = null,
	val genericDefinition: CStruct? = null,
	override val typeArguments: List<ValueType> = emptyList(),
	enclosingName: String? = genericDefinition?.enclosingName,
) : CMemberType(Keywords.STRUCT, enclosingName) {

	override val indirect get() = true
	override lateinit var fields: List<StructField>
		private set

	// Field discovery/cycle checking finishes before storage queries. Cache each
	// descriptor's answer so shared closed field DAGs are not traversed exponentially.
	private var bufferedValue: Boolean? = null
	override val buffered get() = bufferedValue ?: fields.any { it.type.buffered }.also { bufferedValue = it }
	private lateinit var fieldsByName: Map<String, StructField>

	init {
		if (fields != null) defineFields(fields)
	}

	internal fun defineFields(fields: List<StructField>) {
		this.fields = fields
		bufferedValue = null
		fieldsByName = fields.associateBy { it.name.lexeme }
	}

	internal override fun field(name: String): StructField? = fieldsByName[name]

	/** Inline, pointer-free witnesses outlive a nullable array field's presence. */
	internal val nullableArrayFields get() = fields.filter { (it.type as? CNullable)?.payload is CArray }
	internal fun shapeIndex(field: StructField): Int = fields.size + nullableArrayFields.indexOf(field).also { require(it >= 0) }

	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = LLVMPointerTypeInContext(context, 0)

	override fun llvmStorage(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = storage(context, target, IdentityHashMap())

	private fun storage(context: LLVMContextRef, target: TargetLayout, layouts: MutableMap<CStruct, LLVMTypeRef>): LLVMTypeRef = layouts.getOrPut(this) {
		// Even an empty value needs a positive stride when stored in an array.
		val witnesses = nullableArrayFields
		val count = maxOf(1, fields.size + witnesses.size)
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
			witnesses.forEach { field -> members.put(shapeIndex(field).toLong(), field.type.llvmStorage(context, target)) }
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
