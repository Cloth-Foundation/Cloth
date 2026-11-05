package cloth.emitter.types.primitives

import cloth.data.ProviderReceiver
import cloth.data.ProviderResult
import cloth.data.ValueType
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.types.EmitMemory
import cloth.emitter.types.EmitNullable
import cloth.emitter.types.EmitStoredValue
import cloth.emitter.types.ProviderEmissionContext
import cloth.token.Keywords
import cloth.token.Operators
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Two linear traversals of an already evaluated value: measure, then fill one
 * result buffer. Leaf conversion scratch is reclaimed per element; nested arrays
 * write directly into the same buffer rather than concatenating growing prefixes.
 */
internal class EmitArrayText(
	private val context: LLVMContextRef, private val builder: LLVMBuilderRef,
	private val abi: NativeCABI, private val strings: EmitString,
	private val providers: ProviderEmissionContext, private val location: String,
) {

	private val target = strings.target
	private val types = LLVMTypes(context, target)
	private val memory = EmitMemory(context, builder, abi, target)
	private val arrays = EmitArray(context, builder, abi, target)
	private val byte = LLVMInt8TypeInContext(context)
	private val pointer = LLVMPointerTypeInContext(context, 0)
	private val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
	private val overflow = "Array text is too large at $location"

	fun emit(value: LLVMValueRef, recipe: ProviderResult.ArrayText): LLVMValueRef {
		val length = walk(value, recipe, memory.count(0), null)
		val data = memory.stack(byte, length, "array_text_bytes", overflow)
		walk(value, recipe, memory.count(0), data)
		val result = LLVMBuildInsertValue(builder, LLVMGetUndef(types.value(ValueType.STRING)), data, 0, "array_text_data")
		return LLVMBuildInsertValue(builder, result, length, 1, "array_text")
	}

	private fun append(text: LLVMValueRef, offset: LLVMValueRef, destination: LLVMValueRef?): LLVMValueRef {
		val length = LLVMBuildExtractValue(builder, text, 1, "array_text_piece_length")
		if (destination == null) return memory.add(offset, length, overflow)
		val address = PointerPointer<LLVMValueRef>(1L).use { indices ->
			indices.put(0, offset)
			LLVMBuildInBoundsGEP2(builder, byte, destination, indices, 1, "array_text_tail")
		}
		LLVMBuildMemCpy(builder, address, 1, LLVMBuildExtractValue(builder, text, 0, "array_text_piece"), 1, length)
		// The measure pass already checked every prefix of this same immutable traversal.
		return LLVMBuildAdd(builder, offset, length, "array_text_offset")
	}

	private fun walk(value: LLVMValueRef, recipe: ProviderResult.ArrayText, offset: LLVMValueRef, destination: LLVMValueRef?): LLVMValueRef {
		val initial = append(strings.literal(Operators.BRACKET_OPEN.toString()), offset, destination)
		val length = LLVMBuildExtractValue(builder, value, 1, "array_text_count")
		val data = LLVMBuildExtractValue(builder, value, 0, "array_text_elements")
		val start = LLVMGetInsertBlock(builder)
		val function = LLVMGetBasicBlockParent(start)
		val check = LLVMAppendBasicBlockInContext(context, function, "array_text_check")
		val body = LLVMAppendBasicBlockInContext(context, function, "array_text_element")
		val done = LLVMAppendBasicBlockInContext(context, function, "array_text_done")
		LLVMBuildBr(builder, check)
		LLVMPositionBuilderAtEnd(builder, check)
		val index = LLVMBuildPhi(builder, memory.size, "array_text_index")
		val position = LLVMBuildPhi(builder, memory.size, "array_text_position")
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, index, length, "array_text_has_element"), body, done)
		LLVMPositionBuilderAtEnd(builder, body)
		val separator = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, index, memory.count(0), "array_text_first"), strings.literal(""), strings.literal("${Operators.COMMA} "), "array_text_separator")
		val separated = append(separator, position, destination)
		val elementType = recipe.receiverType.element
		// A wide element read itself allocates a snapshot, so save before decoding.
		val checkpoint = stackSave()
		val element = EmitStoredValue(context, builder, target).load(arrays.address(data, elementType, index), elementType)
		val elementText = recipe.elementText
		val nextPosition = if (elementText is ProviderResult.ArrayText) walk(element, elementText, separated, destination)
		else if (elementText is ProviderResult.NullableText && elementText.payloadText is ProviderResult.ArrayText) {
			val nullable = EmitNullable(context, builder, target)
			checkNotNull(nullable.choose(nullable.present(element), memory.size, {
				walk(nullable.payload(element), elementText.payloadText, separated, destination)
			}, { append(strings.literal(Keywords.NULL.toString()), separated, destination) }))
		} else {
			// Formatting may allocate numeric or UTF-8 scratch. Consume it before
			// restoring the stack; only the integer offset survives the checkpoint.
			val text = recipe.elementText.emit(providers, ProviderReceiver.Value(elementType, element))
			append(text, separated, destination)
		}
		stackRestore(checkpoint)
		val nextIndex = LLVMBuildAdd(builder, index, memory.count(1), "array_text_next")
		val end = LLVMGetInsertBlock(builder)
		LLVMBuildBr(builder, check)
		PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
			blocks.put(0, start); blocks.put(1, end)
			PointerPointer<LLVMValueRef>(2L).use { values ->
				values.put(0, memory.count(0)); values.put(1, nextIndex)
				LLVMAddIncoming(index, values, blocks, 2)
				values.put(0, initial); values.put(1, nextPosition)
				LLVMAddIncoming(position, values, blocks, 2)
			}
		}
		LLVMPositionBuilderAtEnd(builder, done)
		return append(strings.literal(Operators.BRACKET_CLOSE.toString()), position, destination)
	}

	private fun intrinsic(name: String, type: LLVMTypeRef): LLVMValueRef {
		val existing = LLVMGetNamedFunction(module, name)
		return if (existing == null || existing.isNull) LLVMAddFunction(module, name, type) else existing
	}

	private fun stackSave(): LLVMValueRef {
		val type = LLVMFunctionType(pointer, null as PointerPointer<*>?, 0, 0)
		return LLVMBuildCall2(builder, type, intrinsic("llvm.stacksave.p0", type), null as PointerPointer<*>?, 0, "array_text_scratch")
	}

	private fun stackRestore(checkpoint: LLVMValueRef) {
		PointerPointer<LLVMTypeRef>(1L).use { parameters ->
			parameters.put(0, pointer)
			val type = LLVMFunctionType(LLVMVoidTypeInContext(context), parameters, 1, 0)
			PointerPointer<LLVMValueRef>(1L).use { arguments ->
				arguments.put(0, checkpoint)
				LLVMBuildCall2(builder, type, intrinsic("llvm.stackrestore.p0", type), arguments, 1, "")
			}
		}
	}
}
