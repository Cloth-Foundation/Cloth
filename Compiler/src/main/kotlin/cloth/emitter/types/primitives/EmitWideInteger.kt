package cloth.emitter.types.primitives

import cloth.data.CFloat
import cloth.data.CInteger
import cloth.diagnostics.BackendErrors
import cloth.emitter.NativeCABI
import cloth.emitter.storage.EmitMemory
import cloth.emitter.types.EmitRuntimeError
import cloth.emitter.types.kernel.IntegerFloatKernelIR
import cloth.emitter.types.kernel.IntegerKernelIR
import cloth.emitter.types.kernel.IntegerMemberKernelIR
import cloth.emitter.types.kernel.IntegerTextKernelIR
import cloth.token.Operators
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import java.math.BigInteger

/** Integer operation layer. A buffer is a stable value snapshot, never a borrowed mutable binding.
 * Storage retains native-sized limbs; kernels process little-endian i32 half-limbs to avoid
 * target-specific i128 arithmetic/conversion libcalls. No per-operation heap ownership or GC.
 */
internal class EmitWideInteger(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val abi: NativeCABI) {

	private val memory = EmitMemory(context, builder, abi)
	private val word = LLVMInt32TypeInContext(context)
	private val module get() = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
	private fun integer(value: Int) = LLVMConstInt(word, value.toLong(), 0)
	private fun boolean(value: Boolean) = LLVMConstInt(LLVMInt1TypeInContext(context), if (value) 1 else 0, 0)
	private fun words(bits: Int): Int {
		require(bits in 1..131072)
		return ((bits + abi.pointerBits - 1) / abi.pointerBits) * (abi.pointerBits / 32)
	}

	fun allocate(bits: Int): LLVMValueRef = memory.stack(word, memory.count(words(bits).toLong()), "integer_value", "Unable to allocate integer value storage").also { LLVMSetAlignment(it, 8) }

	/** Constants have canonical zero padding and immutable, module-owned storage. */
	fun constant(value: BigInteger, bits: Int): LLVMValueRef {
		val count = words(bits)
		val normalized = value.mod(BigInteger.ONE.shiftLeft(bits))
		val array = LLVMArrayType2(word, count.toLong())
		val initializer = PointerPointer<LLVMValueRef>(count.toLong()).use { values ->
			repeat(count) { index -> values.put(index.toLong(), LLVMConstInt(word, normalized.shiftRight(index * 32).toLong() and 0xffffffffL, 0)) }
			LLVMConstArray2(word, values, count.toLong())
		}
		return LLVMAddGlobal(module, array, "cloth.integer.literal").also {
			LLVMSetInitializer(it, initializer); LLVMSetGlobalConstant(it, 1)
			LLVMSetLinkage(it, LLVMPrivateLinkage); LLVMSetAlignment(it, 8)
		}
	}

	private fun canonicalPadding(value: LLVMValueRef, bits: Int): LLVMValueRef {
		val used = (bits + 31) / 32
		if (words(bits) > used) PointerPointer<LLVMValueRef>(1L).use { indices ->
			indices.put(0, integer(used))
			LLVMBuildStore(builder, integer(0), LLVMBuildInBoundsGEP2(builder, word, value, indices, 1, "integer_padding"))
		}
		return value
	}

	fun call(name: String, vararg arguments: LLVMValueRef): LLVMValueRef {
		install()
		val function = LLVMGetNamedFunction(module, "cloth.integer.$name")
		check(function != null && !function.isNull) { "Missing integer helper: $name" }
		return PointerPointer<LLVMValueRef>(arguments.size.toLong()).use { values ->
			arguments.forEachIndexed { index, value -> values.put(index.toLong(), value) }
			val signature = LLVMGlobalGetValueType(function)
			LLVMBuildCall2(builder, signature, function, values, arguments.size, if (LLVMGetTypeKind(LLVMGetReturnType(signature)) == LLVMVoidTypeKind) "" else "integer_$name")
		}
	}

	private fun install() {
		val existing = LLVMGetNamedFunction(module, "cloth.integer.addsub")
		if (existing != null && !existing.isNull) return
		val source = IntegerKernelIR.source + "\n" + IntegerFloatKernelIR.source + "\n" + IntegerTextKernelIR.source + "\n" + IntegerMemberKernelIR.source
		val helper = LLVMModuleRef()
		val error = BytePointer(null as Pointer?)
		var consumed = false
		try {
			val buffer = LLVMCreateMemoryBufferWithMemoryRangeCopy(source, source.toByteArray(Charsets.UTF_8).size.toLong(), "integer-kernels")
			if (LLVMParseIRInContext(context, buffer, helper, error) != 0) throw BackendErrors.invalidIR(error.string)
			if (LLVMVerifyModule(helper, LLVMReturnStatusAction, error) != 0) throw BackendErrors.invalidIR(error.string)
			LLVMSetTarget(helper, LLVMGetTarget(module))
			LLVMSetModuleDataLayout(helper, LLVMGetModuleDataLayout(module))
			// The linker may discard unreferenced internal definitions before callers exist.
			// Import the reserved helper names, then immediately restore private linkage.
			val names = mutableListOf<String>()
			var function = LLVMGetFirstFunction(helper)
			while (function != null && !function.isNull) {
				val name = LLVMGetValueName(function).string
				if (name.startsWith("cloth.integer.")) {
					names.add(name); LLVMSetLinkage(function, LLVMExternalLinkage)
				}
				function = LLVMGetNextFunction(function)
			}
			consumed = true // LLVMLinkModules2 owns the source even on failure.
			if (LLVMLinkModules2(module, helper) != 0) throw BackendErrors.invalidIR("Unable to install integer helpers")
			names.forEach { LLVMSetLinkage(LLVMGetNamedFunction(module, it), LLVMInternalLinkage) }
		} finally {
			if (!error.isNull) LLVMDisposeMessage(error)
			if (!consumed && !helper.isNull) LLVMDisposeModule(helper)
		}
	}

	fun copy(value: LLVMValueRef, bits: Int): LLVMValueRef = allocate(bits).also { call("copy", it, value, integer(bits)); canonicalPadding(it, bits) }
	fun negate(value: LLVMValueRef, bits: Int): LLVMValueRef = allocate(bits).also { call("neg", it, value, integer(bits)); canonicalPadding(it, bits) }

	fun binary(left: LLVMValueRef, right: LLVMValueRef, bits: Int, operation: Operators): LLVMValueRef {
		val result = allocate(bits)
		when (operation) {
			Operators.PLUS, Operators.MINUS -> call("addsub", result, left, right, integer(bits), boolean(operation == Operators.MINUS))
			Operators.MULTIPLY              -> call("multiply", result, left, right, integer(bits))
			else                            -> error("Not a wrapping integer operation: $operation")
		}
		return canonicalPadding(result, bits)
	}

	fun comparison(left: LLVMValueRef, right: LLVMValueRef, bits: Int, signed: Boolean): LLVMValueRef = call("compare", left, right, integer(bits), boolean(signed))
	fun compare(left: LLVMValueRef, right: LLVMValueRef, type: CInteger, operation: Operators): LLVMValueRef {
		val predicate = when (operation) {
			Operators.EQUAL   -> LLVMIntEQ; Operators.NOT_EQUAL -> LLVMIntNE
			Operators.LESS    -> LLVMIntSLT; Operators.LESS_EQUAL -> LLVMIntSLE
			Operators.GREATER -> LLVMIntSGT; Operators.GREATER_EQUAL -> LLVMIntSGE
			else              -> error("Not an integer comparison: $operation")
		}
		return LLVMBuildICmp(builder, predicate, comparison(left, right, type.width, !type.unsigned), integer(0), "integer_compare")
	}

	fun castBuffer(value: LLVMValueRef, from: Int, to: Int, signed: Boolean): LLVMValueRef = allocate(to).also {
		words(from)
		call("cast", it, value, integer(from), integer(to), boolean(signed))
		canonicalPadding(it, to)
	}

	fun scalar(value: LLVMValueRef, type: CInteger): LLVMValueRef {
		require(type.bits <= 64)
		val result = allocate(type.bits)
		call("zero", result, integer(words(type.bits) * 32))
		val carrier = if (type.bits <= 32) word else LLVMInt64TypeInContext(context)
		val converted = if (LLVMGetIntTypeWidth(LLVMTypeOf(value)) == LLVMGetIntTypeWidth(carrier)) value else LLVMBuildZExt(builder, value, carrier, "integer_scalar_bits")
		LLVMBuildStore(builder, converted, result)
		return result
	}

	fun loadScalar(value: LLVMValueRef, type: CInteger): LLVMValueRef {
		require(type.bits <= 64)
		val carrier = if (type.bits <= 32) word else LLVMInt64TypeInContext(context)
		val loaded = LLVMBuildLoad2(builder, carrier, value, "integer_scalar")
		// A native i32-limb buffer on x86 need not satisfy i64's ABI alignment.
		LLVMSetAlignment(loaded, 1)
		return if (type.bits == LLVMGetIntTypeWidth(carrier)) loaded else LLVMBuildTrunc(builder, loaded, LLVMIntTypeInContext(context, type.bits), "integer_scalar_narrow")
	}

	fun integerCast(value: LLVMValueRef, source: CInteger, target: CInteger): LLVMValueRef {
		val input = if (source.bits <= 64) scalar(value, source) else value
		val output = castBuffer(input, source.bits, target.bits, !source.unsigned)
		return if (target.bits <= 64) loadScalar(output, target) else output
	}

	private fun rejectStatus(status: LLVMValueRef, code: Int, message: String) {
		val invalid = LLVMBuildICmp(builder, LLVMIntEQ, status, integer(code), "integer_invalid")
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val failure = LLVMAppendBasicBlockInContext(context, function, "integer_error")
		val valid = LLVMAppendBasicBlockInContext(context, function, "integer_valid")
		LLVMBuildCondBr(builder, invalid, failure, valid)
		LLVMPositionBuilderAtEnd(builder, failure)
		EmitRuntimeError(context, builder, abi).emit(message)
		LLVMPositionBuilderAtEnd(builder, valid)
	}

	data class Division(val quotient: LLVMValueRef, val remainder: LLVMValueRef, val status: LLVMValueRef)

	fun division(left: LLVMValueRef, right: LLVMValueRef, bits: Int, signed: Boolean): Division {
		val q = allocate(bits);
		val r = allocate(bits)
		val status = call("divrem", q, r, left, right, integer(bits), boolean(signed))
		canonicalPadding(q, bits); canonicalPadding(r, bits)
		return Division(q, r, status)
	}

	fun divide(left: LLVMValueRef, right: LLVMValueRef, type: CInteger, remainder: Boolean, location: String): LLVMValueRef {
		val result = division(left, right, type.bits, !type.unsigned)
		val operation = if (remainder) "remainder" else "division"
		rejectStatus(result.status, 1, "Integer $operation by zero at $location")
		rejectStatus(result.status, 2, "Signed integer $operation overflow: minimum value with -1 at $location")
		return if (remainder) result.remainder else result.quotient
	}

	fun power(base: LLVMValueRef, exponent: LLVMValueRef, type: CInteger, location: String): LLVMValueRef {
		val result = allocate(type.bits)
		val status = call("power", result, base, exponent, integer(type.bits), boolean(!type.unsigned))
		rejectStatus(status, 3, "Integer exponentiation requires a nonnegative exponent at $location")
		return canonicalPadding(result, type.bits)
	}

	data class Checked(val value: LLVMValueRef, val overflow: LLVMValueRef)

	fun checked(left: LLVMValueRef, right: LLVMValueRef, bits: Int, signed: Boolean, operation: Operators): Checked {
		require(bits in 1..65536)
		val op = when (operation) {
			Operators.PLUS -> 0; Operators.MINUS -> 1; Operators.MULTIPLY -> 2; else -> error(operation)
		}
		val result = allocate(bits)
		val overflow = call("checked", result, left, right, integer(bits), boolean(signed), integer(op))
		return Checked(canonicalPadding(result, bits), overflow)
	}

	fun toFloating(value: LLVMValueRef, source: CInteger, target: CFloat): LLVMValueRef {
		require(target.bits in setOf(32, 64))
		val raw = call("tofp", value, integer(source.bits), boolean(!source.unsigned), integer(target.fractionBits + 1), integer(target.exponentBias))
		val bits = if (target.bits == 64) raw else LLVMBuildTrunc(builder, raw, word, "integer_float_bits")
		return LLVMBuildBitCast(builder, bits, target.llvmValue(context, abi.layout), "integer_float")
	}

	data class FloatCast(val value: LLVMValueRef, val status: LLVMValueRef)

	fun floatingCast(value: LLVMValueRef, source: CFloat, bits: Int, signed: Boolean): FloatCast {
		val unpacked = if (source.bits == 16) EmitHalf(context, builder, abi.layout).toFloat(value) else value
		val double = if (source.bits == 64) unpacked else LLVMBuildFPExt(builder, unpacked, LLVMDoubleTypeInContext(context), "integer_cast_double")
		val raw = LLVMBuildBitCast(builder, double, LLVMInt64TypeInContext(context), "integer_cast_bits")
		val result = allocate(bits)
		val status = call("fromfp", result, raw, integer(bits), boolean(signed))
		return FloatCast(canonicalPadding(result, bits), status)
	}

	fun fromFloating(value: LLVMValueRef, source: CFloat, target: CInteger, location: String): LLVMValueRef {
		val result = floatingCast(value, source, target.bits, !target.unsigned)
		rejectStatus(result.status, 4, "Invalid numeric cast: '${source.spelling}' to '${target.spelling}' at $location")
		return result.value
	}
}
