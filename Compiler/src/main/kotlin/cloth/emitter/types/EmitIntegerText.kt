package cloth.emitter.types

import cloth.data.CInteger
import cloth.data.ValueType
import cloth.emitter.NativeCABI
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Shared fixed-integer decimal formatter, with caller-owned byte scratch. */
internal class EmitIntegerText(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI) {

	fun emit(value: LLVMValueRef, type: CInteger, location: String): LLVMValueRef {
		val memory = EmitMemory(context, builder, abi)
		val capacity = capacity(type.bits)
		val data = memory.stack(LLVMInt8TypeInContext(context), memory.count(capacity.toLong()), "integer_text_bytes", "Unable to allocate integer text storage at $location")
		val integers = EmitWideInteger(context, builder, abi)
		val input = if (type.bits <= 64) integers.scalar(value, type) else value
		fun int(n: Int) = LLVMConstInt(LLVMInt32TypeInContext(context), n.toLong(), 0)
		val length = integers.call("decimal", data, input, int(type.bits), LLVMConstInt(LLVMInt1TypeInContext(context), if (type.unsigned) 0 else 1, 0), int(capacity))
		memory.guard(LLVMBuildICmp(builder, LLVMIntULT, length, int(capacity), "integer_text_fits"), "Integer text formatting failed at $location")
		val result = LLVMBuildInsertValue(builder, LLVMGetUndef(ValueType.STRING.llvmValue(context, abi.layout)), data, 0, "integer_text_data")
		return LLVMBuildInsertValue(builder, result, memory.resize(length), 1, "integer_text")
	}

	companion object {

		/** log10(2) < 30103/100000. Ceiling bounds the unsigned digits;
		 * reserve one sign byte and one NUL, using exact integer arithmetic. */
		fun capacity(bits: Int): Int {
			require(bits in ValueType.FIXED_INTEGER_WIDTHS)
			return ((bits.toLong() * 30103 + 99999) / 100000 + 2).toInt()
		}
	}
}
