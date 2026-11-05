package cloth.emitter.types

import cloth.builtin.attachments.IntegerMethod
import cloth.data.CInteger
import cloth.data.ValueType
import cloth.emitter.NativeCABI
import cloth.token.Operators
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import java.math.BigInteger

/** Read-only integer members. Wide operands are independent call snapshots. */
internal class EmitIntegerMember(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI, private val location: String) {

	private val memory = EmitMemory(context, builder, abi)
	private val wide = EmitWideInteger(context, builder, abi)
	private val word = LLVMInt32TypeInContext(context)
	private fun int(n: Int) = LLVMConstInt(word, n.toLong(), 1)
	private fun constant(n: BigInteger, type: CInteger): LLVMValueRef = if (type.indirect) wide.constant(n, type.bits)
	else LLVMConstIntOfString(type.llvmValue(context, abi.layout), n.toString(), 10.toByte())

	private fun compare(a: LLVMValueRef, b: LLVMValueRef, type: CInteger, operation: Operators): LLVMValueRef {
		if (type.indirect) return wide.compare(a, b, type, operation)
		val predicate = when (operation) {
			Operators.EQUAL      -> LLVMIntEQ
			Operators.NOT_EQUAL  -> LLVMIntNE
			Operators.LESS       -> if (type.unsigned) LLVMIntULT else LLVMIntSLT
			Operators.LESS_EQUAL -> if (type.unsigned) LLVMIntULE else LLVMIntSLE
			else                 -> error("Unsupported integer member comparison: $operation")
		}
		return LLVMBuildICmp(builder, predicate, a, b, "integer_member_compare")
	}

	private fun select(test: LLVMValueRef, yes: LLVMValueRef, no: LLVMValueRef) = LLVMBuildSelect(builder, test, yes, no, "integer_member_result")

	fun emit(operation: IntegerMethod, type: CInteger, values: List<LLVMValueRef>): LLVMValueRef {
		val value = values[0]
		return when (operation) {
			IntegerMethod.ABS                                         -> {
				if (type.unsigned) value else {
					memory.guard(compare(value, constant(type.minimum, type), type, Operators.NOT_EQUAL), "Integer abs overflow: minimum value of '${type.spelling}' at $location")
					val negative = compare(value, constant(BigInteger.ZERO, type), type, Operators.LESS)
					val magnitude = if (type.indirect) wide.negate(value, type.bits) else LLVMBuildNeg(builder, value, "integer_magnitude")
					select(negative, magnitude, value)
				}
			}

			IntegerMethod.CLAMP                                       -> {
				val minimum = values[1]
				val maximum = values[2]
				memory.guard(compare(minimum, maximum, type, Operators.LESS_EQUAL), "Integer clamp requires min ${Operators.LESS_EQUAL} max at $location")
				select(compare(value, minimum, type, Operators.LESS), minimum, select(compare(maximum, value, type, Operators.LESS), maximum, value))
			}

			IntegerMethod.SIGNUM                                      -> {
				val zero = constant(BigInteger.ZERO, type)
				val nonzero = select(compare(value, zero, type, Operators.EQUAL), int(0), int(1))
				if (type.unsigned) nonzero else select(compare(value, zero, type, Operators.LESS), int(-1), nonzero)
			}

			IntegerMethod.LEADING_ZEROS, IntegerMethod.TRAILING_ZEROS -> zeroCount(value, type, operation == IntegerMethod.LEADING_ZEROS)
			IntegerMethod.TO_STRING_RADIX                             -> radix(value, type, values[1])
		}
	}

	private fun zeroCount(value: LLVMValueRef, type: CInteger, leading: Boolean): LLVMValueRef {
		val count = if (type.indirect) {
			if (leading) LLVMBuildSub(builder, int(type.bits), wide.call("length", value, int(type.bits)), "integer_leading_zeros")
			else wide.call("trailing", value, int(type.bits))
		} else {
			val carrierBits = if (type.bits <= 32) 32 else 64
			val carrier = LLVMIntTypeInContext(context, carrierBits)
			val bits = memory.resize(value, carrier)
			val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
			val name = "llvm.${if (leading) "ctlz" else "cttz"}.i$carrierBits"
			val signature = PointerPointer<LLVMTypeRef>(2L).use { parameters ->
				parameters.put(0, carrier); parameters.put(1, LLVMInt1TypeInContext(context))
				LLVMFunctionType(carrier, parameters, 2, 0)
			}
			val existing = LLVMGetNamedFunction(module, name)
			val function = if (existing == null || existing.isNull) LLVMAddFunction(module, name, signature) else existing
			val raw = PointerPointer<LLVMValueRef>(2L).use { arguments ->
				arguments.put(0, bits); arguments.put(1, LLVMConstInt(LLVMInt1TypeInContext(context), 0, 0))
				LLVMBuildCall2(builder, signature, function, arguments, 2, "integer_zero_count")
			}
			if (leading) LLVMBuildSub(builder, raw, LLVMConstInt(carrier, (carrierBits - type.bits).toLong(), 0), "integer_leading_zeros")
			else {
				val width = LLVMConstInt(carrier, type.bits.toLong(), 0)
				select(LLVMBuildICmp(builder, LLVMIntULT, raw, width, "integer_trailing_fits"), raw, width)
			}
		}
		return memory.resize(count)
	}

	private fun radix(value: LLVMValueRef, type: CInteger, radix: LLVMValueRef): LLVMValueRef {
		val valid = LLVMBuildAnd(builder, LLVMBuildICmp(builder, LLVMIntSGE, radix, int(2), "radix_minimum"), LLVMBuildICmp(builder, LLVMIntSLE, radix, int(36), "radix_maximum"), "radix_valid")
		memory.guard(valid, "Integer formatting radix must be from 2 through 36 at $location")
		// Base two is the longest representation. One sign and one terminator
		// bound every result, including the full-cap signed minimum.
		val capacity = type.bits + 2
		val output = memory.stack(LLVMInt8TypeInContext(context), memory.count(capacity.toLong()), "integer_radix_bytes", "Unable to allocate integer radix text at $location")
		val input = if (type.indirect) value else wide.scalar(value, type)
		val length = wide.call("radix", output, input, int(type.bits), LLVMConstInt(LLVMInt1TypeInContext(context), if (type.unsigned) 0 else 1, 0), radix, int(capacity))
		memory.guard(LLVMBuildICmp(builder, LLVMIntULT, length, int(capacity), "integer_radix_fits"), "Integer radix formatting failed at $location")
		val result = LLVMBuildInsertValue(builder, LLVMGetUndef(ValueType.STRING.llvmValue(context, abi.layout)), output, 0, "integer_radix_data")
		return LLVMBuildInsertValue(builder, result, memory.resize(length), 1, "integer_radix_text")
	}
}
