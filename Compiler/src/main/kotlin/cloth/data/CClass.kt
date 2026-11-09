package cloth.data

import cloth.parser.declaration.ClassDeclaration
import cloth.parser.declaration.TypeDeclaration
import cloth.parser.type.TypeReference
import cloth.target.TargetLayout
import cloth.token.Keywords
import cloth.token.Token
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*

sealed class CMemberType(keyword: Keywords) : ValueType(keyword) {

	abstract val declaration: TypeDeclaration
	val name get() = declaration.name
	abstract val fields: List<InstanceField>
	open val typeArguments: List<ValueType> get() = emptyList()
	override val spelling get() = if (typeArguments.isEmpty()) name.lexeme else genericTypeDisplay(this)
	override val metadataSpelling get() = if (typeArguments.isEmpty()) name.lexeme else genericTypeMetadata(this)
	internal abstract fun field(name: String): InstanceField?
}

sealed interface InstanceField {

	val name: Token
	val annotation: TypeReference
	val index: Int
	val type: ValueType
}

/** Class identity is established before resolving fields, including recursive class fields. */
class CClass internal constructor(override val declaration: ClassDeclaration, val genericDefinition: CClass? = null, override val typeArguments: List<ValueType> = emptyList()) : CMemberType(Keywords.CLASS) {

	internal var base: CClass? = null
	internal fun ancestry(): Sequence<CClass> = generateSequence(this) { it.base }
	override fun accepts(actual: ValueType) = super.accepts(actual) || actual is CClass && this in actual.ancestry()

	// The implicit root has no fields. A source base occupies the first slot.
	internal val fieldOffset get() = if (base?.base != null) 1 else 0
	override lateinit var fields: List<ClassField>
		private set
	private lateinit var fieldsByName: Map<String, ClassField>
	internal val bufferedFields get() = fields.filter { it.type.buffered }
	private val shapedFields get() = fields.filter { it.nullableArray != null }
	internal override fun field(name: String): ClassField? = fieldsByName[name] ?: base?.field(name)

	internal fun defineFields(fields: List<ClassField>) {
		this.fields = fields
		fieldsByName = fields.associateBy { it.name.lexeme }
	}

	/** A private slot and generation carrier, independent of the native pointer width. */
	override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = LLVMInt64TypeInContext(context)
	override fun providers(target: TargetLayout): Map<CompilerDataProviders, ProviderResult<*>> = super.providers(target) + (CompilerDataProviders.STR to ProviderResult.ClassText(this))

	internal fun arenaIndex(field: ClassField) = fieldOffset + fields.size + bufferedFields.indexOf(field)
	internal fun shapeIndex(field: ClassField) = fieldOffset + fields.size + bufferedFields.size + shapedFields.indexOf(field)

	internal fun instanceStorage(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef {
		val buffered = bufferedFields
		val shaped = shapedFields
		val offset = fieldOffset
		val count = maxOf(1, offset + fields.size + buffered.size + shaped.size)
		return PointerPointer<LLVMTypeRef>(count.toLong()).use { members ->
			if (offset != 0) members.put(0, checkNotNull(base).instanceStorage(context, target))
			else if (fields.isEmpty()) members.put(0, LLVMInt8TypeInContext(context))
			fields.forEach { field -> members.put((offset + field.index).toLong(), field.type.llvmStorage(context, target)) }
			buffered.indices.forEach { members.put((offset + fields.size + it).toLong(), LLVMPointerTypeInContext(context, 0)) }
			shaped.forEachIndexed { index, field -> members.put((offset + fields.size + buffered.size + index).toLong(), field.type.llvmStorage(context, target)) }
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

/** Access privilege belongs to the source declaration, including its closed instances. */
internal fun CClass.sameDeclaration(other: CClass?) = declaration === other?.declaration
