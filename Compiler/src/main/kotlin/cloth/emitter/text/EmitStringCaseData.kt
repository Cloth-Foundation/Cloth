package cloth.emitter.text

import cloth.data.UnicodeStringCaseMappings
import cloth.data.UnicodeStringCaseProperty
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

/** Module-local immutable tables. Lookup never allocates or consults a host locale. */
internal class EmitStringCaseData(private val context: LLVMContextRef, private val builder: LLVMBuilderRef) {

	private val word = LLVMInt32TypeInContext(context)
	private val bool = LLVMInt1TypeInContext(context)
	private val mappingType = PointerPointer<LLVMTypeRef>(4L).use { fields ->
		for (index in 0L..3L) fields.put(index, word)
		LLVMStructTypeInContext(context, fields, 4, 0)
	}

	private fun int(value: Int) = LLVMConstInt(word, value.toLong(), 0)
	fun mapping(scalar: LLVMValueRef, upper: Boolean): LLVMValueRef = lookup(scalar, if (upper) "uppercase" else "lowercase", true) {
		UnicodeStringCaseMappings.mappings(upper).map { listOf(it.source, it.targets.size) + it.targets + List(3 - it.targets.size) { 0 } }
	}

	fun property(scalar: LLVMValueRef, property: UnicodeStringCaseProperty): LLVMValueRef = lookup(scalar, if (property == UnicodeStringCaseProperty.CASED) "cased" else "case_ignorable", false) {
		UnicodeStringCaseMappings.ranges(property).map { listOf(it.first, it.last) }
	}

	private fun lookup(scalar: LLVMValueRef, suffix: String, mapping: Boolean, rows: () -> List<List<Int>>): LLVMValueRef {
		val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
		val name = "cloth.string.unicode.$suffix"
		val existing = LLVMGetNamedFunction(module, name)
		val function = if (existing != null && !existing.isNull) existing else install(module, name, rows(), mapping)
		return PointerPointer<LLVMValueRef>(1L).use { arguments ->
			arguments.put(0, scalar)
			LLVMBuildCall2(builder, LLVMGlobalGetValueType(function), function, arguments, 1, "string_case_$suffix")
		}
	}

	private fun install(module: LLVMModuleRef, name: String, rows: List<List<Int>>, mapping: Boolean): LLVMValueRef {
		val stride = if (mapping) 5 else 2
		val table = LLVMAddGlobal(module, LLVMArrayType2(word, rows.size.toLong() * stride), "$name.table")
		PointerPointer<LLVMValueRef>(rows.size.toLong() * stride).use { entries ->
			rows.forEachIndexed { row, values -> values.forEachIndexed { column, value -> entries.put(row.toLong() * stride + column, int(value)) } }
			LLVMSetInitializer(table, LLVMConstArray2(word, entries, rows.size.toLong() * stride))
		}
		LLVMSetGlobalConstant(table, 1); LLVMSetLinkage(table, LLVMPrivateLinkage); LLVMSetAlignment(table, 4)
		val signature = PointerPointer<LLVMTypeRef>(1L).use { parameters -> parameters.put(0, word); LLVMFunctionType(if (mapping) mappingType else bool, parameters, 1, 0) }
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
			LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntULT, low, high, "has_entry"), inspect, missing)
			LLVMPositionBuilderAtEnd(body, inspect)
			val middle = LLVMBuildAdd(body, low, LLVMBuildLShr(body, LLVMBuildSub(body, high, low, "remaining"), int(1), "half"), "middle")
			val index = LLVMBuildMul(body, middle, int(stride), "table_index")
			fun load(column: Int) = PointerPointer<LLVMValueRef>(1L).use { indices ->
				indices.put(0, LLVMBuildAdd(body, index, int(column), "column"))
				LLVMBuildLoad2(body, word, LLVMBuildInBoundsGEP2(body, word, table, indices, 1, "table_address"), "table_value")
			}

			val first = load(0)
			val last = if (mapping) first else load(1)
			val scalar = LLVMGetParam(function, 0)
			LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntULT, scalar, first, "below_entry"), search, above)
			LLVMPositionBuilderAtEnd(body, above)
			val next = LLVMBuildAdd(body, middle, int(1), "next_entry")
			LLVMBuildCondBr(body, LLVMBuildICmp(body, LLVMIntUGT, scalar, last, "above_entry"), search, found)
			fun incoming(phi: LLVMValueRef, values: List<LLVMValueRef>) {
				PointerPointer<LLVMValueRef>(3L).use { inputs ->
					PointerPointer<LLVMBasicBlockRef>(3L).use { blocks ->
						values.forEachIndexed { index, value -> inputs.put(index.toLong(), value) }
						blocks.put(0, entry); blocks.put(1, inspect); blocks.put(2, above)
						LLVMAddIncoming(phi, inputs, blocks, 3)
					}
				}
			}
			incoming(low, listOf(int(0), low, next))
			incoming(high, listOf(int(rows.size), middle, high))
			fun record(fields: List<LLVMValueRef>): LLVMValueRef = fields.foldIndexed(LLVMGetUndef(mappingType)) { index, value, field -> LLVMBuildInsertValue(body, value, field, index, "case_mapping") }
			LLVMPositionBuilderAtEnd(body, found)
			LLVMBuildRet(body, if (mapping) record((1..4).map(::load)) else LLVMConstInt(bool, 1, 0))
			LLVMPositionBuilderAtEnd(body, missing)
			LLVMBuildRet(body, if (mapping) record(listOf(int(1), scalar, int(0), int(0))) else LLVMConstInt(bool, 0, 0))
		} finally {
			LLVMDisposeBuilder(body)
		}
		return function
	}
}
