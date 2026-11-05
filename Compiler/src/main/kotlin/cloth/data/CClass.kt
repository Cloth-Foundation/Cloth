package cloth.data

import cloth.parser.declaration.ClassDeclaration
import cloth.parser.type.TypeReference
import cloth.target.TargetLayout
import cloth.token.Keywords
import cloth.token.Token
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*

sealed class CMemberType(keyword: Keywords) : ValueType(keyword) {

	abstract val name: Token
	abstract val fields: List<InstanceField>
	override val spelling get() = name.lexeme
	internal abstract fun field(name: String): InstanceField?
}

sealed interface InstanceField {

	val name: Token
	val annotation: TypeReference
	val index: Int
	val type: ValueType
}

/** Class identity is established before resolving fields, including recursive class fields. */
class CClass internal constructor(val declaration: ClassDeclaration) : CMemberType(Keywords.CLASS) {

	override val name get() = declaration.name
	override lateinit var fields: List<ClassField>
		private set
	private lateinit var fieldsByName: Map<String, ClassField>
	internal val bufferedFields get() = fields.filter { it.type.buffered }
	private val shapedFields get() = fields.filter { it.nullableArray != null }
	internal override fun field(name: String): ClassField? = fieldsByName[name]

	internal fun defineFields(fields: List<ClassField>) {
		this.fields = fields
		fieldsByName = fields.associateBy { it.name.lexeme }
	}

	/** A private slot and generation carrier, independent of the native pointer width. */
	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = LLVMInt64TypeInContext(context)

	internal fun arenaIndex(field: ClassField) = fields.size + bufferedFields.indexOf(field)
	internal fun shapeIndex(field: ClassField) = fields.size + bufferedFields.size + shapedFields.indexOf(field)

	internal fun instanceStorage(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef {
		val buffered = bufferedFields
		val shaped = shapedFields
		val count = maxOf(1, fields.size + buffered.size + shaped.size)
		return PointerPointer<LLVMTypeRef>(count.toLong()).use { members ->
			if (fields.isEmpty()) members.put(0, LLVMInt8TypeInContext(context))
			else fields.forEach { field -> members.put(field.index.toLong(), field.type.llvmStorage(context, target)) }
			buffered.indices.forEach { members.put((fields.size + it).toLong(), LLVMPointerTypeInContext(context, 0)) }
			shaped.forEachIndexed { index, field -> members.put((fields.size + buffered.size + index).toLong(), field.type.llvmStorage(context, target)) }
			LLVMStructTypeInContext(context, members, count, 0)
		}
	}
}

class ClassField internal constructor(
	val owner: CClass,
	override val name: Token,
	override val annotation: TypeReference,
	override val index: Int,
	override val type: ValueType,
	val visibility: Token?,
) : InstanceField {

	internal val nullableArray get() = (type as? CNullable)?.payload as? CArray
}
