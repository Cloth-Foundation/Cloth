package cloth.emitter.types

import cloth.data.*
import cloth.emitter.NativeCABI
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Immutable, module-owned descriptors. No native handles escape into ValueType recipes.
 * Equality uses canonical type text so independently emitted descriptors compare equally.
 */
internal class EmitTypeInfo(
	private val context: LLVMContextRef, private val builder: LLVMBuilderRef,
	private val abi: NativeCABI, private val strings: EmitString,
) {

	private val pointer get() = CTypeInfo.llvmValue(context)
	private val count get() = LLVMInt64TypeInContext(context)
	private val bool get() = CBool.llvmValue(context)
	private val descriptor: LLVMTypeRef
		get() = PointerPointer<LLVMTypeRef>(9L).use { fields ->
			fields.put(0, CString.llvmValue(context)); fields.put(1, pointer)
			for (index in 2L..4L) fields.put(index, count)
			for (index in 5L..8L) fields.put(index, bool)
			LLVMStructTypeInContext(context, fields, 9, 0)
		}

	fun constant(type: ValueType): LLVMValueRef {
		val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
		val name = "cloth_type_" + type.metadataSpelling.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
		val existing = LLVMGetNamedGlobal(module, name)
		if (existing != null && !existing.isNull) return existing
		val global = LLVMAddGlobal(module, descriptor, name)
		LLVMSetGlobalConstant(global, 1)
		LLVMSetLinkage(global, LLVMPrivateLinkage)
		val array = type as? CArray
		fun flag(value: Boolean) = LLVMConstInt(bool, if (value) 1 else 0, 0)
		val values = listOf(
			strings.literal(type.metadataSpelling),
			array?.let { constant(it.element) } ?: LLVMConstNull(pointer),
			LLVMConstInt(count, type.arrayDimensions.size.toLong(), 0),
			array?.size?.let { LLVMConstIntOfString(count, it.toString(), 10.toByte()) } ?: LLVMConstInt(count, 0, 0),
			LLVMConstInt(count, if (type.numeric) type.bits.toLong() else 0, 0),
			flag(array != null), flag(array?.size != null), flag(type.integer && type.unsigned), flag(type.numeric),
		)
		PointerPointer<LLVMValueRef>(values.size.toLong()).use { fields ->
			values.forEachIndexed { index, value -> fields.put(index.toLong(), value) }
			LLVMSetInitializer(global, LLVMConstStructInContext(context, fields, values.size, 0))
		}
		return global
	}

	private fun field(value: LLVMValueRef, index: Int, type: LLVMTypeRef): LLVMValueRef {
		val address = LLVMBuildStructGEP2(builder, descriptor, value, index, "typeinfo_field")
		return LLVMBuildLoad2(builder, type, address, "typeinfo_value")
	}

	fun text(value: LLVMValueRef): LLVMValueRef = field(value, 0, CString.llvmValue(context))
	fun equal(left: LLVMValueRef, right: LLVMValueRef): LLVMValueRef = strings.equal(text(left), text(right))

	fun property(value: LLVMValueRef, query: MetadataQuery, location: String): LLVMValueRef {
		query.precondition?.let { condition ->
			val valid = field(value, condition.field, bool)
			val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
			val success = LLVMAppendBasicBlockInContext(context, function, "metadata_valid")
			val failure = LLVMAppendBasicBlockInContext(context, function, "metadata_invalid")
			LLVMBuildCondBr(builder, valid, success, failure)
			LLVMPositionBuilderAtEnd(builder, failure)
			EmitRuntimeError(context, builder, abi).emit("Type metadata ::${query.provider.spelling} requires ${query.requirement} at $location")
			LLVMPositionBuilderAtEnd(builder, success)
		}
		return field(value, query.field, query.type.llvmValue(context))
	}
}
