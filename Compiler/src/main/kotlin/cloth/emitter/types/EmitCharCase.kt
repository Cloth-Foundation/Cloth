package cloth.emitter.types

import cloth.data.UnicodeCaseMapping
import cloth.data.UnicodeCharacterCaseMappings
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Exact scalar-to-scalar Unicode mapping; missing keys return the receiver. */
internal class EmitCharCase(private val context: LLVMContextRef, private val builder: LLVMBuilderRef) {

	fun emit(scalar: LLVMValueRef, upper: Boolean): LLVMValueRef {
		val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
		val name = "cloth.character.simple_${if (upper) "uppercase" else "lowercase"}"
		val existing = LLVMGetNamedFunction(module, name)
		val function = if (existing != null && !existing.isNull) existing else install(module, name, UnicodeCharacterCaseMappings.mappings(upper))
		return PointerPointer<LLVMValueRef>(1L).use { arguments ->
			arguments.put(0, scalar)
			LLVMBuildCall2(builder, LLVMGlobalGetValueType(function), function, arguments, 1, "char_case")
		}
	}

	private fun install(module: LLVMModuleRef, name: String, mappings: List<UnicodeCaseMapping>): LLVMValueRef {
		check(mappings.isNotEmpty())
		val word = LLVMInt32TypeInContext(context)
		fun int(value: Int) = LLVMConstInt(word, value.toLong(), 0)
		val table = LLVMAddGlobal(module, LLVMArrayType2(word, mappings.size * 2L), "$name.mappings")
		PointerPointer<LLVMValueRef>(mappings.size * 2L).use { entries ->
			mappings.forEachIndexed { index, mapping -> entries.put(index * 2L, int(mapping.source)); entries.put(index * 2L + 1, int(mapping.target)) }
			LLVMSetInitializer(table, LLVMConstArray2(word, entries, mappings.size * 2L))
		}
		LLVMSetGlobalConstant(table, 1); LLVMSetLinkage(table, LLVMPrivateLinkage); LLVMSetAlignment(table, 4)
		val signature = PointerPointer<LLVMTypeRef>(1L).use { parameters -> parameters.put(0, word); LLVMFunctionType(word, parameters, 1, 0) }
		val function = LLVMAddFunction(module, name, signature)
		LLVMSetLinkage(function, LLVMInternalLinkage)
		val body = LLVMCreateBuilderInContext(context)
		try {
			fun block(label: String) = LLVMAppendBasicBlockInContext(context, function, label)
			val entry = block("entry")
			val search = block("search")
			val inspect = block("inspect")
			val above = block("above")
			val found = block("found")
			val missing = block("missing")
			LLVMPositionBuilderAtEnd(body, entry)
			LLVMBuildBr(body, search)
			LLVMPositionBuilderAtEnd(body, search)
			val low = LLVMBuildPhi(body, word, "low")
			val high = LLVMBuildPhi(body, word, "high")
			LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntULT, low, high, "has_mapping"), inspect, missing)
			LLVMPositionBuilderAtEnd(body, inspect)
			val middle = LLVMBuildAdd(body, low, LLVMBuildLShr(body, LLVMBuildSub(body, high, low, "remaining"), int(1), "half"), "middle")
			val index = LLVMBuildMul(body, middle, int(2), "mapping_index")
			fun load(offset: LLVMValueRef) = PointerPointer<LLVMValueRef>(1L).use { indices ->
				indices.put(0, offset)
				LLVMBuildLoad2(body, word, LLVMBuildInBoundsGEP2(body, word, table, indices, 1, "mapping_address"), "mapping_value")
			}

			val source = load(index)
			val target = load(LLVMBuildAdd(body, index, int(1), "target_index"))
			val scalar = LLVMGetParam(function, 0)
			LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntULT, scalar, source, "below_mapping"), search, above)
			LLVMPositionBuilderAtEnd(body, above)
			val next = LLVMBuildAdd(body, middle, int(1), "next_mapping")
			LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntUGT, scalar, source, "above_mapping"), search, found)
			fun incoming(phi: LLVMValueRef, values: List<LLVMValueRef>) {
				PointerPointer<LLVMValueRef>(3L).use { inputs ->
					PointerPointer<LLVMBasicBlockRef>(3L).use { blocks ->
						values.forEachIndexed { i, value -> inputs.put(i.toLong(), value) }
						blocks.put(0, entry); blocks.put(1, inspect); blocks.put(2, above)
						LLVMAddIncoming(phi, inputs, blocks, 3)
					}
				}
			}
			incoming(low, listOf(int(0), low, next))
			incoming(high, listOf(int(mappings.size), middle, high))
			LLVMPositionBuilderAtEnd(body, found)
			LLVMBuildRet(body, target)
			LLVMPositionBuilderAtEnd(body, missing)
			LLVMBuildRet(body, scalar)
		} finally {
			LLVMDisposeBuilder(body)
		}
		return function
	}
}
