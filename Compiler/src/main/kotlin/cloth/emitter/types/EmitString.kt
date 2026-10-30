package cloth.emitter.types

import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.semantic.ValueType
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** UTF-8 bytes plus a byte length, including embedded NULs. Literals have constant storage;
 * concatenation buffers stay valid throughout the current executable function.
 */
internal class EmitString(private val context: LLVMContextRef, private val builder: LLVMBuilderRef,
	private val abi: NativeCABI) {
	private val types = LLVMTypes(context)
	private val lengthType = LLVMInt64TypeInContext(context)
	private val boolType = types.value(ValueType.BOOL)

	fun literal(text: String): LLVMValueRef {
		val bytes = text.toByteArray(Charsets.UTF_8)
		val constant = BytePointer(*bytes).use { buffer ->
			LLVMConstStringInContext2(context, buffer, bytes.size.toLong(), 0)
		}
		val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
		val data = LLVMAddGlobal(module, LLVMTypeOf(constant), "string_data")
		LLVMSetInitializer(data, constant)
		LLVMSetGlobalConstant(data, 1)
		LLVMSetLinkage(data, LLVMPrivateLinkage)
		PointerPointer<LLVMValueRef>(2L).use { fields ->
			fields.put(0, data)
			fields.put(1, LLVMConstInt(LLVMInt64TypeInContext(context), bytes.size.toLong(), 0))
			return LLVMConstStructInContext(context, fields, 2, 0)
		}
	}

	fun concatenate(left: LLVMValueRef, right: LLVMValueRef, location: String): LLVMValueRef {
		val leftLength = LLVMBuildExtractValue(builder, left, 1, "concat_left_length")
		val rightLength = LLVMBuildExtractValue(builder, right, 1, "concat_right_length")
		val length = LLVMBuildAdd(builder, leftLength, rightLength, "concat_length")
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val valid = LLVMAppendBasicBlockInContext(context, function, "concat_valid")
		val invalid = LLVMAppendBasicBlockInContext(context, function, "concat_invalid")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntUGE, length, leftLength, "concat_length_fits"), valid, invalid)
		LLVMPositionBuilderAtEnd(builder, invalid)
		EmitRuntimeError(context, builder, abi).emit("String concatenation is too large at $location")
		LLVMPositionBuilderAtEnd(builder, valid)

		// A valid address even for empty strings; no terminator is needed with a stored length.
		val allocationLength = LLVMBuildSelect(builder,
			LLVMBuildICmp(builder, LLVMIntEQ, length, LLVMConstInt(lengthType, 0, 0), "concat_empty"),
			LLVMConstInt(lengthType, 1, 0), length, "concat_storage_length")
		val bytes = LLVMBuildArrayAlloca(builder, LLVMInt8TypeInContext(context), allocationLength, "concat_bytes")
		LLVMBuildMemCpy(builder, bytes, 1, LLVMBuildExtractValue(builder, left, 0, "concat_left_bytes"), 1, leftLength)
		val tail = PointerPointer<LLVMValueRef>(1L).use { indices ->
			indices.put(0, leftLength)
			LLVMBuildInBoundsGEP2(builder, LLVMInt8TypeInContext(context), bytes, indices, 1, "concat_tail")
		}
		LLVMBuildMemCpy(builder, tail, 1, LLVMBuildExtractValue(builder, right, 0, "concat_right_bytes"), 1, rightLength)
		val descriptor = LLVMBuildInsertValue(builder, LLVMGetUndef(types.value(ValueType.STRING)), bytes, 0, "concat_data")
		return LLVMBuildInsertValue(builder, descriptor, length, 1, "concat_value")
	}

	/** Compare bytes only when lengths match, never reading past the shorter operand. */
	fun equal(left: LLVMValueRef, right: LLVMValueRef): LLVMValueRef {
		val leftLength = LLVMBuildExtractValue(builder, left, 1, "string_left_length")
		val rightLength = LLVMBuildExtractValue(builder, right, 1, "string_right_length")
		val entry = LLVMGetInsertBlock(builder)
		val function = LLVMGetBasicBlockParent(entry)
		val compare = LLVMAppendBasicBlockInContext(context, function, "string_compare_bytes")
		val end = LLVMAppendBasicBlockInContext(context, function, "string_compare_end")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntEQ, leftLength, rightLength, "string_same_length"), compare, end)
		LLVMPositionBuilderAtEnd(builder, compare)
		val module = LLVMGetGlobalParent(function)
		val intType = types.value(abi.intType)
		val memcmpType = PointerPointer<LLVMTypeRef>(3L).use { parameters ->
			parameters.put(0, LLVMPointerTypeInContext(context, 0))
			parameters.put(1, LLVMPointerTypeInContext(context, 0))
			parameters.put(2, types.value(abi.sizeType))
			LLVMFunctionType(intType, parameters, 3, 0)
		}
		val existing = LLVMGetNamedFunction(module, "memcmp")
		val memcmp = if (existing == null || existing.isNull) LLVMAddFunction(module, "memcmp", memcmpType) else existing
		val compared = PointerPointer<LLVMValueRef>(3L).use { arguments ->
			arguments.put(0, LLVMBuildExtractValue(builder, left, 0, "string_left_bytes"))
			arguments.put(1, LLVMBuildExtractValue(builder, right, 0, "string_right_bytes"))
			arguments.put(2, leftLength)
			LLVMBuildCall2(builder, memcmpType, memcmp, arguments, 3, "string_compare")
		}
		val same = LLVMBuildICmp(builder, LLVMIntEQ, compared, LLVMConstInt(intType, 0, 0), "string_same_bytes")
		LLVMBuildBr(builder, end)
		LLVMPositionBuilderAtEnd(builder, end)
		val result = LLVMBuildPhi(builder, boolType, "string_equal")
		PointerPointer<LLVMValueRef>(2L).use { values ->
			PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
				values.put(0, LLVMConstInt(boolType, 0, 0))
				values.put(1, same)
				blocks.put(0, entry)
				blocks.put(1, compare)
				LLVMAddIncoming(result, values, blocks, 2)
			}
		}
		return result
	}

	/** Length-based output avoids C string truncation and interpretation of '%' characters. */
	fun print(module: LLVMModuleRef, value: LLVMValueRef) {
		val intType = LLVMTypes(context).value(abi.intType)
		val indexType = LLVMInt64TypeInContext(context)
		val byteType = LLVMInt8TypeInContext(context)
		val putcharType = PointerPointer<LLVMTypeRef>(1L).use { parameters ->
			parameters.put(0, intType)
			LLVMFunctionType(intType, parameters, 1, 0)
		}
		val existing = LLVMGetNamedFunction(module, "putchar")
		val putchar = if (existing == null || existing.isNull) LLVMAddFunction(module, "putchar", putcharType) else existing
		fun write(byte: LLVMValueRef) {
			PointerPointer<LLVMValueRef>(1L).use { arguments ->
				arguments.put(0, byte)
				LLVMBuildCall2(builder, putcharType, putchar, arguments, 1, "")
			}
		}
		val data = LLVMBuildExtractValue(builder, value, 0, "string_bytes")
		val length = LLVMBuildExtractValue(builder, value, 1, "string_length")
		val entry = LLVMGetInsertBlock(builder)
		val function = LLVMGetBasicBlockParent(entry)
		val condition = LLVMAppendBasicBlockInContext(context, function, "string_print_check")
		val body = LLVMAppendBasicBlockInContext(context, function, "string_print_byte")
		val end = LLVMAppendBasicBlockInContext(context, function, "string_print_end")
		LLVMBuildBr(builder, condition)
		LLVMPositionBuilderAtEnd(builder, condition)
		val index = LLVMBuildPhi(builder, indexType, "string_index")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, index, length, "string_has_byte"), body, end)
		LLVMPositionBuilderAtEnd(builder, body)
		val address = PointerPointer<LLVMValueRef>(1L).use { indices ->
			indices.put(0, index)
			LLVMBuildInBoundsGEP2(builder, byteType, data, indices, 1, "string_byte_address")
		}
		write(LLVMBuildZExt(builder, LLVMBuildLoad2(builder, byteType, address, "string_byte"), intType, "string_output_byte"))
		val next = LLVMBuildAdd(builder, index, LLVMConstInt(indexType, 1, 0), "string_next")
		LLVMBuildBr(builder, condition)
		PointerPointer<LLVMValueRef>(2L).use { values ->
			PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
				values.put(0, LLVMConstInt(indexType, 0, 0))
				values.put(1, next)
				blocks.put(0, entry)
				blocks.put(1, body)
				LLVMAddIncoming(index, values, blocks, 2)
			}
		}
		LLVMPositionBuilderAtEnd(builder, end)
		write(LLVMConstInt(intType, '\n'.code.toLong(), 0))
	}
}
