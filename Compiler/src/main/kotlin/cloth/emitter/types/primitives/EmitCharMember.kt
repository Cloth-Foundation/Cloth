package cloth.emitter.types.primitives

import cloth.builtin.attachments.CharPredicate
import cloth.data.UnicodeCharacterProperties
import cloth.data.UnicodeCharacterProperty
import cloth.data.UnicodeWhiteSpace
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import java.util.Locale

/** Module-local constant ranges and bounded binary searches, without locale APIs. */
internal class EmitCharMember(private val context: LLVMContextRef, private val builder: LLVMBuilderRef) {

	fun predicate(scalar: LLVMValueRef, operation: CharPredicate): LLVMValueRef = when (operation) {
		CharPredicate.WHITESPACE      -> classify(scalar, "whitespace", UnicodeWhiteSpace.ranges)
		CharPredicate.LETTER          -> property(scalar, UnicodeCharacterProperty.LETTER)
		CharPredicate.DIGIT           -> property(scalar, UnicodeCharacterProperty.DIGIT)
		CharPredicate.LETTER_OR_DIGIT -> LLVMBuildOr(builder, property(scalar, UnicodeCharacterProperty.LETTER), property(scalar, UnicodeCharacterProperty.DIGIT), "char_letter_or_digit")
		CharPredicate.UPPERCASE       -> property(scalar, UnicodeCharacterProperty.UPPERCASE)
		CharPredicate.LOWERCASE       -> property(scalar, UnicodeCharacterProperty.LOWERCASE)
	}

	private fun property(scalar: LLVMValueRef, property: UnicodeCharacterProperty) = classify(scalar, property.name.lowercase(Locale.ROOT), UnicodeCharacterProperties.ranges(property))

	private fun classify(scalar: LLVMValueRef, name: String, ranges: List<IntRange>): LLVMValueRef {
		val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
		val symbol = "cloth.character.$name"
		val existing = LLVMGetNamedFunction(module, symbol)
		val function = if (existing != null && !existing.isNull) existing else install(module, symbol, ranges)
		return PointerPointer<LLVMValueRef>(1L).use { arguments ->
			arguments.put(0, scalar)
			LLVMBuildCall2(builder, LLVMGlobalGetValueType(function), function, arguments, 1, "char_$name")
		}
	}

	private fun install(module: LLVMModuleRef, name: String, ranges: List<IntRange>): LLVMValueRef {
		check(ranges.isNotEmpty())
		val word = LLVMInt32TypeInContext(context)
		val bool = LLVMInt1TypeInContext(context)
		fun int(n: Int) = LLVMConstInt(word, n.toLong(), 0)
		val table = LLVMAddGlobal(module, LLVMArrayType2(word, ranges.size * 2L), "$name.ranges")
		PointerPointer<LLVMValueRef>(ranges.size * 2L).use { entries ->
			ranges.forEachIndexed { index, range -> entries.put(index * 2L, int(range.first)); entries.put(index * 2L + 1, int(range.last)) }
			LLVMSetInitializer(table, LLVMConstArray2(word, entries, ranges.size * 2L))
		}
		LLVMSetGlobalConstant(table, 1)
		LLVMSetLinkage(table, LLVMPrivateLinkage)
		LLVMSetAlignment(table, 4)
		val signature = PointerPointer<LLVMTypeRef>(1L).use { parameters -> parameters.put(0, word); LLVMFunctionType(bool, parameters, 1, 0) }
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
			LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntULT, low, high, "has_range"), inspect, missing)
			LLVMPositionBuilderAtEnd(body, inspect)
			val middle = LLVMBuildAdd(body, low, LLVMBuildLShr(body, LLVMBuildSub(body, high, low, "remaining"), int(1), "half"), "middle")
			val index = LLVMBuildMul(body, middle, int(2), "range_index")
			fun load(offset: LLVMValueRef) = PointerPointer<LLVMValueRef>(1L).use { indices ->
				indices.put(0, offset)
				LLVMBuildLoad2(body, word, LLVMBuildInBoundsGEP2(body, word, table, indices, 1, "range_address"), "range_bound")
			}

			val first = load(index)
			val last = load(LLVMBuildAdd(body, index, int(1), "end_index"))
			val scalar = LLVMGetParam(function, 0)
			LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntULT, scalar, first, "below_range"), search, above)
			LLVMPositionBuilderAtEnd(body, above)
			val next = LLVMBuildAdd(body, middle, int(1), "next_range")
			LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntUGT, scalar, last, "above_range"), search, found)
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
			incoming(high, listOf(int(ranges.size), middle, high))
			LLVMPositionBuilderAtEnd(body, found)
			LLVMBuildRet(body, LLVMConstInt(bool, 1, 0))
			LLVMPositionBuilderAtEnd(body, missing)
			LLVMBuildRet(body, LLVMConstInt(bool, 0, 0))
		} finally {
			LLVMDisposeBuilder(body)
		}
		return function
	}
}
