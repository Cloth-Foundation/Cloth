package cloth.emitter.types

import cloth.data.CNullable
import cloth.data.ValueType
import cloth.diagnostics.InternalErrors
import cloth.emitter.LLVMTypes
import cloth.semantic.ResultShape
import cloth.target.TargetLayout
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Internal ordered-result carrier. Multiple results use ABI-encoded fields;
 * scalar expressions retain their ordinary representation and existing ABI.
 */
internal class EmitResults(private val context: LLVMContextRef, private val target: TargetLayout) {

	private val types = LLVMTypes(context, target)
	fun abiType(type: ValueType): LLVMTypeRef = when {
		type == ValueType.HALF                              -> LLVMInt16TypeInContext(context)
		type is CNullable && type.payload == ValueType.HALF -> PointerPointer<LLVMTypeRef>(2L).use { fields ->
			fields.put(0, types.value(ValueType.BOOL)); fields.put(1, LLVMInt16TypeInContext(context))
			LLVMStructTypeInContext(context, fields, 2, 0)
		}

		else                                                -> types.value(type)
	}

	fun abiType(shape: ResultShape): LLVMTypeRef = when (shape) {
		ResultShape.Empty       -> LLVMVoidTypeInContext(context)
		is ResultShape.Single   -> abiType(shape.type)
		is ResultShape.Multiple -> PointerPointer<LLVMTypeRef>(shape.values.size.toLong()).use { fields ->
			shape.values.forEachIndexed { index, type -> fields.put(index.toLong(), abiType(type)) }
			LLVMStructTypeInContext(context, fields, shape.values.size, 0)
		}

		ResultShape.Never       -> throw InternalErrors.invariant("A non-continuing result has no LLVM carrier")
	}

	fun valueType(shape: ResultShape): LLVMTypeRef = if (shape is ResultShape.Single) types.value(shape.type) else abiType(shape)
	fun encode(builder: LLVMBuilderRef, value: LLVMValueRef, type: ValueType): LLVMValueRef = when {
		type == ValueType.HALF                              -> LLVMBuildBitCast(builder, value, abiType(type), "half_argument_bits")
		type is CNullable && type.payload == ValueType.HALF -> EmitNullable(context, builder, target).map(value, abiType(type)) { LLVMBuildBitCast(builder, it, LLVMInt16TypeInContext(context), "nullable_half_bits") }
		else                                                -> value
	}

	fun decode(builder: LLVMBuilderRef, value: LLVMValueRef, type: ValueType): LLVMValueRef = when {
		type == ValueType.HALF                              -> LLVMBuildBitCast(builder, value, types.value(type), "half_result")
		type is CNullable && type.payload == ValueType.HALF -> EmitNullable(context, builder, target).map(value, types.value(type)) { LLVMBuildBitCast(builder, it, types.value(type.payload), "nullable_half_result") }
		else                                                -> value
	}

	fun encode(builder: LLVMBuilderRef, value: LLVMValueRef, shape: ResultShape): LLVMValueRef = if (shape is ResultShape.Single) encode(builder, value, shape.type) else value
	fun decode(builder: LLVMBuilderRef, value: LLVMValueRef, shape: ResultShape): LLVMValueRef = if (shape is ResultShape.Single) decode(builder, value, shape.type) else value
	fun pack(builder: LLVMBuilderRef, values: List<LLVMValueRef>, shape: ResultShape): LLVMValueRef {
		if (shape is ResultShape.Single) return values.single()
		if (shape !is ResultShape.Multiple) throw InternalErrors.invariant("Only produced values can be packed into a result carrier")
		var carrier = LLVMGetUndef(abiType(shape))
		values.forEachIndexed { index, value -> carrier = LLVMBuildInsertValue(builder, carrier, encode(builder, value, shape.values[index]), index, "result_slot") }
		return carrier
	}

	fun unpack(builder: LLVMBuilderRef, value: LLVMValueRef, shape: ResultShape): List<LLVMValueRef> = if (shape is ResultShape.Single) listOf(value) else shape.values.mapIndexed { index, type ->
		decode(builder, LLVMBuildExtractValue(builder, value, index, "result_$index"), type)
	}
}
