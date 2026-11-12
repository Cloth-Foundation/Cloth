package cloth.emitter.runtime

import cloth.data.CClass
import cloth.data.CEnum
import cloth.data.CNullable
import cloth.emitter.NativeCABI
import cloth.emitter.callables.EmitFunctionFrame
import cloth.emitter.callables.EmitResults
import cloth.emitter.storage.*
import cloth.semantic.FileIoError
import cloth.semantic.LibraryIntrinsic
import cloth.semantic.ResultShape
import cloth.util.ExecutableFormat
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** The private stream slot follows the ordinary instance fields in the final File class. */
internal class EmitFileIO(
	private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI,
	owner: CClass, private val classes: EmitClassRuntime, private val strings: EmitStringArena, private val results: EmitResults,
) {

	private val pointer = LLVMPointerTypeInContext(context, 0)
	private val integer = abi.intType.llvmValue(context, abi.layout)
	private val size = abi.sizeType.llvmValue(context, abi.layout)
	private val byte = LLVMInt8TypeInContext(context)
	private val wide = LLVMInt16TypeInContext(context)
	private val absentStream = LLVMConstNull(pointer)
	val layout: LLVMTypeRef = PointerPointer<LLVMTypeRef>(2L).use { members ->
		members.put(0, owner.instanceStorage(context, abi.layout))
		members.put(1, pointer)
		LLVMStructTypeInContext(context, members, 2, 0)
	}

	private data class Routine(val function: LLVMValueRef, val signature: LLVMTypeRef, val convention: Int)

	private val memchr by lazy { external("memchr", pointer, pointer, integer, size) }
	private val fopen by lazy { external(if (abi.windows) "_wfopen" else "fopen", pointer, pointer, pointer) }
	private val fread by lazy { external("fread", size, pointer, size, size, pointer) }
	private val ferror by lazy { external("ferror", integer, pointer) }
	private val feof by lazy { external("feof", integer, pointer) }
	private val fclose by lazy { external("fclose", integer, pointer) }
	private val errno by lazy {
		val name = when (abi.executableFormat) {
			ExecutableFormat.PE     -> "_errno"
			ExecutableFormat.ELF    -> "__errno_location"
			ExecutableFormat.MACH_O -> "__error"
		}
		external(name, pointer)
	}
	private val utf16 by lazy { external("MultiByteToWideChar", integer, integer, integer, pointer, integer, pointer, integer, convention = abi.windowsAPICallConvention) }
	private val wideMode by lazy {
		LLVMAddGlobal(module, LLVMArrayType2(wide, 3L), "cloth.rt.file.mode.utf16").also { global ->
			PointerPointer<LLVMValueRef>(3L).use { characters ->
				characters.put(0, LLVMConstInt(wide, 'r'.code.toLong(), 0))
				characters.put(1, LLVMConstInt(wide, 'b'.code.toLong(), 0))
				characters.put(2, LLVMConstNull(wide))
				LLVMSetInitializer(global, LLVMConstArray2(wide, characters, 3L))
			}
			LLVMSetGlobalConstant(global, 1)
			LLVMSetLinkage(global, LLVMPrivateLinkage)
		}
	}

	fun open(builder: LLVMBuilderRef, receiver: ObjectAccess, path: LLVMValueRef, result: CNullable, frame: EmitFunctionFrame) {
		val memory = EmitMemory(context, builder, abi)
		val slot = slot(builder, receiver)
		val stream = LLVMBuildLoad2(builder, pointer, slot, "file_stream")
		fun failure(error: FileIoError) = errorValue(builder, error, result)
		returnIf(builder, frame, LLVMBuildIsNotNull(builder, stream, "file_is_open"), "already_open") { failure(FileIoError.ALREADY_OPEN) }

		val data = LLVMBuildExtractValue(builder, path, 0, "path_data")
		val length = LLVMBuildExtractValue(builder, path, 1, "path_length")
		returnIf(builder, frame, isZero(builder, length), "empty_path") { failure(FileIoError.INVALID_PATH) }
		val nul = call(builder, memchr, "path_nul", data, int(0), length)
		returnIf(builder, frame, LLVMBuildIsNotNull(builder, nul, "embedded_nul"), "invalid_path") { failure(FileIoError.INVALID_PATH) }

		val nativePath = if (abi.windows) {
			returnIf(builder, frame, LLVMBuildICmp(builder, LLVMIntUGT, length, memory.count(Int.MAX_VALUE.toLong()), "path_too_long"), "path_length_out_of_range") { failure(FileIoError.INVALID_PATH) }
			val bytes = memory.resize(length, integer)
			val units = call(builder, utf16, "path_utf16_length", int(65001), int(8), data, bytes, absentStream, int(0))
			returnIf(builder, frame, isZero(builder, units), "path_conversion_failed") { failure(FileIoError.INVALID_PATH) }
			val count = memory.resize(units)
			val capacity = memory.add(count, memory.count(1), "File path exceeds native allocation size")
			val buffer = strings.allocate(builder, frame.stringReturnArena(), memory.elementBytes(capacity, wide, "File path exceeds native allocation size"), 2)
			val written = call(builder, utf16, "path_utf16_written", int(65001), int(8), data, bytes, buffer, units)
			returnIf(builder, frame, LLVMBuildICmp(builder, LLVMIntNE, units, written, "path_conversion_incomplete"), "path_conversion_failed") { failure(FileIoError.INVALID_PATH) }
			terminate(builder, buffer, count, wide)
			buffer
		} else {
			val capacity = memory.add(length, memory.count(1), "File path exceeds native allocation size")
			val buffer = strings.allocate(builder, frame.stringReturnArena(), capacity)
			LLVMBuildMemCpy(builder, buffer, 1, data, 1, length)
			terminate(builder, buffer, length, byte)
			buffer
		}
		val mode = if (abi.windows) wideMode else LLVMBuildGlobalStringPtr(builder, "rb", "cloth.rt.file.mode")
		val opened = call(builder, fopen, "opened_file", nativePath, mode)
		returnIf(builder, frame, LLVMBuildIsNull(builder, opened, "open_failed"), "open_failed") {
			val code = LLVMBuildLoad2(builder, integer, call(builder, errno, "errno_address"), "open_errno")
			// All supported CRTs use these values for ENOENT, EACCES and EPERM.
			val missing = LLVMBuildICmp(builder, LLVMIntEQ, code, int(2), "file_missing")
			val denied = LLVMBuildOr(builder, LLVMBuildICmp(builder, LLVMIntEQ, code, int(13), "file_access_denied"), LLVMBuildICmp(builder, LLVMIntEQ, code, int(1), "file_not_permitted"), "permission_denied")
			val other = LLVMBuildSelect(builder, denied, failure(FileIoError.PERMISSION_DENIED), failure(FileIoError.OPEN_FAILED), "open_failure")
			LLVMBuildSelect(builder, missing, failure(FileIoError.NOT_FOUND), other, "file_open_error")
		}
		LLVMBuildStore(builder, opened, slot)
		frame.emitReturn(EmitNullable(context, builder, abi.layout).absent(result))
	}

	fun read(builder: LLVMBuilderRef, receiver: ObjectAccess, requested: LLVMValueRef, result: ResultShape, frame: EmitFunctionFrame) {
		val bytesType = result.values[0] as CNullable
		val errorType = result.values[1] as CNullable
		val nullable = EmitNullable(context, builder, abi.layout)
		val noBytes = nullable.absent(bytesType)
		val noError = nullable.absent(errorType)
		fun pack(bytes: LLVMValueRef, error: LLVMValueRef) = results.pack(builder, listOf(bytes, error), result)
		fun failed(error: FileIoError) = pack(noBytes, errorValue(builder, error, errorType))
		val arrays = EmitArray(context, builder, abi)
		fun chunk(data: LLVMValueRef, count: LLVMValueRef) = pack(nullable.wrap(arrays.descriptor(data, count, LibraryIntrinsic.bytes), bytesType), noError)

		val stream = LLVMBuildLoad2(builder, pointer, slot(builder, receiver), "file_stream")
		returnIf(builder, frame, LLVMBuildIsNull(builder, stream, "file_unopened"), "unopened_file") { failed(FileIoError.NOT_OPEN) }
		returnIf(builder, frame, isZero(builder, requested), "zero_read") { chunk(absentStream, LLVMConstNull(size)) }
		returnIf(builder, frame, nonzero(builder, call(builder, ferror, "previous_read_error", stream)), "prior_read_failure") { failed(FileIoError.READ_FAILED) }
		returnIf(builder, frame, nonzero(builder, call(builder, feof, "previous_eof", stream)), "already_at_eof") { pack(noBytes, noError) }

		val buffer = strings.allocate(builder, frame.stringReturnArena(), requested)
		val count = call(builder, fread, "bytes_read", buffer, LLVMConstInt(size, 1, 0), requested, stream)
		returnIf(builder, frame, nonzero(builder, call(builder, ferror, "read_error", stream)), "read_failed") { failed(FileIoError.READ_FAILED) }
		returnIf(builder, frame, nonzero(builder, count), "read_chunk") { chunk(buffer, count) }
		val eof = nonzero(builder, call(builder, feof, "reached_eof", stream))
		frame.emitReturn(LLVMBuildSelect(builder, eof, pack(noBytes, noError), failed(FileIoError.READ_FAILED), "read_result"))
	}

	fun release(builder: LLVMBuilderRef, receiver: ObjectAccess) {
		val slot = slot(builder, receiver)
		val stream = LLVMBuildLoad2(builder, pointer, slot, "released_stream")
		EmitNullable(context, builder, abi.layout).choose(LLVMBuildIsNotNull(builder, stream, "stream_to_close"), null, {
			LLVMBuildStore(builder, absentStream, slot)
			call(builder, fclose, "close_status", stream)
			null
		}, { null })
	}

	private fun slot(builder: LLVMBuilderRef, receiver: ObjectAccess): LLVMValueRef = LLVMBuildStructGEP2(builder, layout, classes.instance(builder, receiver), 1, "file_stream_slot")

	private fun errorValue(builder: LLVMBuilderRef, error: FileIoError, type: CNullable): LLVMValueRef {
		val enum = type.payload as CEnum
		val ordinal = enum.cases.getValue(error.spelling).ordinal
		val value = LLVMConstInt(enum.llvmValue(context, abi.layout), ordinal.toLong(), 0)
		return EmitNullable(context, builder, abi.layout).wrap(value, type)
	}

	private fun returnIf(builder: LLVMBuilderRef, frame: EmitFunctionFrame, condition: LLVMValueRef, label: String, value: () -> LLVMValueRef) {
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val branch = LLVMAppendBasicBlockInContext(context, function, label)
		val next = LLVMAppendBasicBlockInContext(context, function, "file_continue")
		LLVMBuildCondBr(builder, condition, branch, next)
		LLVMPositionBuilderAtEnd(builder, branch)
		frame.emitReturn(value())
		LLVMPositionBuilderAtEnd(builder, next)
	}

	private fun terminate(builder: LLVMBuilderRef, buffer: LLVMValueRef, length: LLVMValueRef, element: LLVMTypeRef) {
		PointerPointer<LLVMValueRef>(1L).use { indices ->
			indices.put(0, length)
			val end = LLVMBuildInBoundsGEP2(builder, element, buffer, indices, 1, "path_end")
			LLVMBuildStore(builder, LLVMConstNull(element), end)
		}
	}

	private fun int(value: Long) = LLVMConstInt(integer, value, 0)
	private fun isZero(builder: LLVMBuilderRef, value: LLVMValueRef) = LLVMBuildIsNull(builder, value, "zero")
	private fun nonzero(builder: LLVMBuilderRef, value: LLVMValueRef) = LLVMBuildIsNotNull(builder, value, "nonzero")

	private fun external(name: String, result: LLVMTypeRef, vararg parameters: LLVMTypeRef, convention: Int = LLVMCCallConv): Routine {
		val signature = PointerPointer<LLVMTypeRef>(parameters.size.toLong()).use { types ->
			parameters.forEachIndexed { index, type -> types.put(index.toLong(), type) }
			LLVMFunctionType(result, types, parameters.size, 0)
		}
		val existing = LLVMGetNamedFunction(module, name)
		val function = if (existing == null || existing.isNull) LLVMAddFunction(module, name, signature) else existing
		LLVMSetFunctionCallConv(function, convention)
		return Routine(function, signature, convention)
	}

	private fun call(builder: LLVMBuilderRef, callee: Routine, name: String, vararg arguments: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(arguments.size.toLong()).use { values ->
		arguments.forEachIndexed { index, argument -> values.put(index.toLong(), argument) }
		LLVMBuildCall2(builder, callee.signature, callee.function, values, arguments.size, name).also { LLVMSetInstructionCallConv(it, callee.convention) }
	}
}
