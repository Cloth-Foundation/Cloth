package cloth.emitter.types

import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Write a Unicode scalar as UTF-8 bytes without depending on the C locale or wchar_t. */
internal class EmitCharacter(private val context: LLVMContextRef, private val builder: LLVMBuilderRef,
	private val abi: NativeCABI) {
	fun print(module: LLVMModuleRef, value: LLVMValueRef) {
		val intType = LLVMTypes(context).value(abi.intType)
		val putcharType = PointerPointer<LLVMTypeRef>(1L).use { parameters ->
			parameters.put(0, intType)
			LLVMFunctionType(intType, parameters, 1, 0)
		}
		val putchar = LLVMAddFunction(module, "putchar", putcharType)
		fun constant(number: Int) = LLVMConstInt(intType, number.toLong(), 0)
		fun write(byte: LLVMValueRef) {
			PointerPointer<LLVMValueRef>(1L).use { arguments ->
				arguments.put(0, byte)
				LLVMBuildCall2(builder, putcharType, putchar, arguments, 1, "")
			}
		}
		fun shifted(bits: Int) = if (bits == 0) value else LLVMBuildLShr(builder, value, constant(bits), "char_shift")
		fun continuation(bits: Int) = LLVMBuildOr(builder,
			LLVMBuildAnd(builder, shifted(bits), constant(0x3f), "char_payload"), constant(0x80), "char_byte")
		fun writeBytes(length: Int) {
			if (length == 1) {
				write(value)
				return
			}
			val prefix = when (length) { 2 -> 0xc0; 3 -> 0xe0; else -> 0xf0 }
			write(LLVMBuildOr(builder, shifted(6 * (length - 1)), constant(prefix), "char_lead"))
			for (index in length - 2 downTo 0) write(continuation(6 * index))
		}

		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val end = LLVMAppendBasicBlockInContext(context, function, "char_print_end")
		for (length in 1..4) {
			if (length < 4) {
				val output = LLVMAppendBasicBlockInContext(context, function, "char_utf8_$length")
				val next = LLVMAppendBasicBlockInContext(context, function, "char_utf8_next")
				val limit = when (length) { 1 -> 0x80; 2 -> 0x800; else -> 0x10000 }
				LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, value, constant(limit), "char_fits"), output, next)
				LLVMPositionBuilderAtEnd(builder, output)
				writeBytes(length)
				LLVMBuildBr(builder, end)
				LLVMPositionBuilderAtEnd(builder, next)
			} else {
				writeBytes(length)
				LLVMBuildBr(builder, end)
			}
		}
		LLVMPositionBuilderAtEnd(builder, end)
		write(constant('\n'.code))
	}
}
