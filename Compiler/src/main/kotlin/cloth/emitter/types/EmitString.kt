package cloth.emitter.types

import cloth.data.CFloat
import cloth.data.ValueType
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** UTF-8 bytes plus a byte length, including embedded NULs. Literals have constant storage;
 * local buffers stay valid throughout the creating invocation. Function returns copy
 * their bytes into caller-owned storage before that invocation exits.
 */
internal class EmitString(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI) {

	val metadata by lazy { EmitTypeInfo(context, builder, abi, this) }

	private val types = LLVMTypes(context)
	private val lengthType = LLVMInt64TypeInContext(context)
	private val boolType = types.value(ValueType.BOOL)

	private fun descriptor(data: LLVMValueRef, length: LLVMValueRef): LLVMValueRef {
		val value = LLVMBuildInsertValue(builder, LLVMGetUndef(types.value(ValueType.STRING)), data, 0, "text_data")
		return LLVMBuildInsertValue(builder, value, length, 1, "text_value")
	}

	/** Shared by interpolation and str; ordinary '+' keeps its strict operand types. */
	fun interpolate(value: LLVMValueRef, type: ValueType, casts: EmitCast, location: String): LLVMValueRef {
		if (type == ValueType.STRING) return value
		if (type == ValueType.TYPEINFO) return metadata.text(value)
		if (type == ValueType.BOOL) {
			val data = LLVMBuildSelect(builder, value, LLVMBuildGlobalStringPtr(builder, "true", "true_text"), LLVMBuildGlobalStringPtr(builder, "false", "false_text"), "bool_text")
			val length = LLVMBuildSelect(builder, value, LLVMConstInt(lengthType, 4, 0), LLVMConstInt(lengthType, 5, 0), "bool_length")
			return descriptor(data, length)
		}
		if (type == ValueType.CHAR) return character(value)

		// Decimal int64/uint64 and %g with at most 17 significant digits fit in 64 bytes,
		// including sign, decimal point, exponent and NUL. Check snprintf's result anyway.
		val capacity = LLVMConstInt(types.value(abi.sizeType), 64, 0)
		val data = LLVMBuildArrayAlloca(builder, LLVMInt8TypeInContext(context), capacity, "formatted_bytes")
		val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
		val nativeInt = types.value(abi.intType)
		val functionType = PointerPointer<LLVMTypeRef>(3L).use { parameters ->
			parameters.put(0, LLVMPointerTypeInContext(context, 0))
			parameters.put(1, types.value(abi.sizeType))
			parameters.put(2, LLVMPointerTypeInContext(context, 0))
			LLVMFunctionType(nativeInt, parameters, 3, 1)
		}
		val existing = LLVMGetNamedFunction(module, "snprintf")
		val function = if (existing == null || existing.isNull) LLVMAddFunction(module, "snprintf", functionType) else existing
		val argumentType = abi.printArgument(type)
		val argument = when {
			type.floating                 -> casts.toFloating(value, type, argumentType)
			type.bits < argumentType.bits -> if (type.unsigned) LLVMBuildZExt(builder, value, types.value(argumentType), "format_unsigned") else LLVMBuildSExt(builder, value, types.value(argumentType), "format_signed")
			else                          -> value
		}
		val precision = if (type is CFloat) ".${EmitHalf.arithmeticType(type).roundTripDigits}" else ""
		val format = LLVMBuildGlobalStringPtr(builder, "%$precision${abi.printSpecifier(type)}", "interpolation_format")
		val length = PointerPointer<LLVMValueRef>(4L).use { arguments ->
			arguments.put(0, data)
			arguments.put(1, capacity)
			arguments.put(2, format)
			arguments.put(3, argument)
			LLVMBuildCall2(builder, functionType, function, arguments, 4, "formatted_length")
		}
		val parent = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val valid = LLVMAppendBasicBlockInContext(context, parent, "format_valid")
		val invalid = LLVMAppendBasicBlockInContext(context, parent, "format_invalid")
		// Unsigned comparison rejects negative encoding failures as well as truncation.
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, length, LLVMConstInt(nativeInt, 64, 0), "format_fits"), valid, invalid)
		LLVMPositionBuilderAtEnd(builder, invalid)
		EmitRuntimeError(context, builder, abi).emit("String interpolation formatting failed at $location")
		LLVMPositionBuilderAtEnd(builder, valid)
		return descriptor(data, LLVMBuildZExt(builder, length, lengthType, "format_length"))
	}

	/** Encode one scalar; unused bytes are outside the descriptor's length. */
	private fun character(value: LLVMValueRef): LLVMValueRef {
		val scalarType = types.value(ValueType.CHAR)
		fun constant(n: Int) = LLVMConstInt(scalarType, n.toLong(), 0)
		fun fits(limit: Int) = LLVMBuildICmp(builder, LLVMIntULT, value, constant(limit), "utf8_fits")
		fun shift(bits: Int) = LLVMBuildLShr(builder, value, constant(bits), "utf8_shift")
		fun continuation(bits: Int) = LLVMBuildOr(builder, LLVMBuildAnd(builder, shift(bits), constant(63), "utf8_payload"), constant(128), "utf8_continuation")
		val one = fits(0x80)
		val two = fits(0x800)
		val three = fits(0x10000)
		fun select(test: LLVMValueRef, yes: LLVMValueRef, no: LLVMValueRef) = LLVMBuildSelect(builder, test, yes, no, "utf8_select")
		val length = select(one, constant(1), select(two, constant(2), select(three, constant(3), constant(4))))
		val lead = select(one, value, select(two, LLVMBuildOr(builder, shift(6), constant(0xc0), "utf8_lead2"), select(three, LLVMBuildOr(builder, shift(12), constant(0xe0), "utf8_lead3"), LLVMBuildOr(builder, shift(18), constant(0xf0), "utf8_lead4"))))
		val bytes = listOf(lead, select(two, continuation(0), select(three, continuation(6), continuation(12))), select(three, continuation(0), continuation(6)), continuation(0))
		val byteType = LLVMInt8TypeInContext(context)
		val data = LLVMBuildArrayAlloca(builder, byteType, constant(4), "char_bytes")
		for ((index, byte) in bytes.withIndex()) {
			val address = PointerPointer<LLVMValueRef>(1L).use { indices ->
				indices.put(0, constant(index))
				LLVMBuildInBoundsGEP2(builder, byteType, data, indices, 1, "char_byte_address")
			}
			LLVMBuildStore(builder, LLVMBuildTrunc(builder, byte, byteType, "char_byte"), address)
		}
		return descriptor(data, LLVMBuildZExt(builder, length, lengthType, "char_length"))
	}

	/** All Cloth strings are well-formed UTF-8. Count leading bytes, including NUL,
	 * without allocating or exposing the descriptor's byte length as a scalar count.
	 * Count never exceeds byte length, so an i64 count cannot overflow here.
	 */
	fun scalarCount(value: LLVMValueRef): LLVMValueRef {
		val data = LLVMBuildExtractValue(builder, value, 0, "scalar_bytes")
		val length = LLVMBuildExtractValue(builder, value, 1, "scalar_byte_length")
		val start = LLVMGetInsertBlock(builder)
		val function = LLVMGetBasicBlockParent(start)
		val check = LLVMAppendBasicBlockInContext(context, function, "scalar_count_check")
		val body = LLVMAppendBasicBlockInContext(context, function, "scalar_count_byte")
		val done = LLVMAppendBasicBlockInContext(context, function, "scalar_count_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val index = LLVMBuildPhi(builder, lengthType, "scalar_byte_index")
		val count = LLVMBuildPhi(builder, lengthType, "scalar_count")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, index, length, "scalar_has_byte"), body, done)
		LLVMPositionBuilderAtEnd(builder, body)
		val byteType = LLVMInt8TypeInContext(context)
		val address = PointerPointer<LLVMValueRef>(1L).use { indices ->
			indices.put(0, index)
			LLVMBuildInBoundsGEP2(builder, byteType, data, indices, 1, "scalar_byte_address")
		}
		val octet = LLVMBuildLoad2(builder, byteType, address, "scalar_byte")
		val prefix = LLVMBuildAnd(builder, octet, LLVMConstInt(byteType, 0xc0, 0), "scalar_prefix")
		val leading = LLVMBuildICmp(builder, LLVMIntNE, prefix, LLVMConstInt(byteType, 0x80, 0), "scalar_leading")
		val nextCount = LLVMBuildAdd(builder, count, LLVMBuildZExt(builder, leading, lengthType, "scalar_increment"), "next_scalar_count")
		val nextIndex = LLVMBuildAdd(builder, index, LLVMConstInt(lengthType, 1, 0), "next_scalar_byte")
		LLVMBuildBr(builder, check)
		PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
			blocks.put(0, start); blocks.put(1, body)
			PointerPointer<LLVMValueRef>(2L).use { values ->
				values.put(0, LLVMConstInt(lengthType, 0, 0)); values.put(1, nextIndex)
				LLVMAddIncoming(index, values, blocks, 2)
				values.put(1, nextCount)
				LLVMAddIncoming(count, values, blocks, 2)
			}
		}
		LLVMPositionBuilderAtEnd(builder, done)
		return count
	}

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
		val allocationLength = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, length, LLVMConstInt(lengthType, 0, 0), "concat_empty"), LLVMConstInt(lengthType, 1, 0), length, "concat_storage_length")
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
		write(module, value)
		write(module, literal("\n"))
	}

	fun write(module: LLVMModuleRef, value: LLVMValueRef) = EmitOutput(context, builder, abi).write(module, value)
}
