package cloth.emitter.types

import cloth.data.CUSize
import cloth.emitter.NativeCABI
import cloth.parser.statement.ForSource
import cloth.parser.statement.ForStatement
import cloth.semantic.SemanticModel
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

/** Traverse a captured independent value, receiving a fresh independent element each trip. */
internal class EmitForEach(
	private val context: LLVMContextRef,
	private val builder: LLVMBuilderRef,
	private val bindings: EmitBindings,
	private val semantics: SemanticModel,
	private val abi: NativeCABI,
	private val expressions: EmitExpression,
) {

	fun emit(statement: ForStatement, key: Any) {
		val source = (statement.source as ForSource.Elements).expression
		val raw = expressions.emit(source)
		if (expressions.terminated()) return
		val plan = semantics.loopOf(statement)
		val arrayType = checkNotNull(plan.arraySourceType)
		val snapshot = expressions.transferArrayValue(checkNotNull(raw), arrayType)
		val arrays = EmitArray(context, builder, abi)
		val size = CUSize(abi.layout.sizeBits)
		val length = LLVMBuildExtractValue(builder, snapshot, 1, "foreach_count")
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val entry = LLVMGetInsertBlock(builder)
		val test = LLVMAppendBasicBlockInContext(context, function, "foreach_test")
		val body = LLVMAppendBasicBlockInContext(context, function, "foreach_body")
		val exit = LLVMAppendBasicBlockInContext(context, function, "foreach_end")
		LLVMBuildBr(builder, test)
		LLVMPositionBuilderAtEnd(builder, test)
		val index = LLVMBuildPhi(builder, LLVMTypeOf(length), "foreach_index")
		fun incoming(value: LLVMValueRef, block: LLVMBasicBlockRef) {
			PointerPointer<LLVMValueRef>(1L).use { values ->
				PointerPointer<LLVMBasicBlockRef>(1L).use { blocks ->
					values.put(0, value); blocks.put(0, block)
					LLVMAddIncoming(index, values, blocks, 1)
				}
			}
		}
		incoming(arrays.count(0), entry)
		LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, index, length, "foreach_has_element"), body, exit)
		LLVMPositionBuilderAtEnd(builder, body)
		expressions.emitIteration(statement) {
			bindings.withScope {
				val element = arrays.index(snapshot, arrayType.element, index, size)
				val received = expressions.snapshotValue(expressions.adaptValue(element, arrayType.element, plan.bindingType), plan.bindingType)
				bindings.declare(key, statement.binding.name.lexeme, plan.bindingType, received)
				bindings.withScope { expressions.emitStatements(statement.body.statements) }
			}
		}
		if (!expressions.terminated()) {
			// index < length: the last increment can reach usize::max, never overflow it.
			val next = LLVMBuildAdd(builder, index, arrays.count(1), "foreach_next")
			incoming(next, LLVMGetInsertBlock(builder))
			LLVMBuildBr(builder, test)
		}
		LLVMPositionBuilderAtEnd(builder, exit)
	}
}
