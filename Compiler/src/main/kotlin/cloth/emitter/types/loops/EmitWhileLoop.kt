package cloth.emitter.types.loops

import cloth.emitter.storage.EmitBindings
import cloth.emitter.types.EmitExpression
import cloth.parser.statement.WhileStatement
import cloth.semantic.flow.ControlFlowModel
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.global.LLVM.*

/** Repeated bool testing with shared body jumps and proven region cleanup. */
internal class EmitWhileLoop(
	private val context: LLVMContextRef,
	private val builder: LLVMBuilderRef,
	private val bindings: EmitBindings,
	private val expressions: EmitExpression,
	private val flow: ControlFlowModel,
) {

	fun emit(statement: WhileStatement) {
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val test = LLVMAppendBasicBlockInContext(context, function, "while_test")
		LLVMBuildBr(builder, test)
		LLVMPositionBuilderAtEnd(builder, test)
		val condition = expressions.emitLoopCondition(statement.condition)
		if (expressions.terminated()) return
		val body = LLVMAppendBasicBlockInContext(context, function, "while_body")
		val exit = LLVMAppendBasicBlockInContext(context, function, "while_end")
		LLVMBuildCondBr(builder, checkNotNull(condition), body, exit)
		LLVMPositionBuilderAtEnd(builder, body)
		expressions.emitLoopBody(statement.loopTarget, exit, test) {
			expressions.emitIteration(statement.body.statements) {
				bindings.withScope { expressions.emitStatements(statement.body.statements) }
			}
		}
		if (!expressions.terminated()) LLVMBuildBr(builder, test)
		LLVMPositionBuilderAtEnd(builder, exit)
		if (!flow.of(statement).canFallThrough) LLVMBuildUnreachable(builder)
	}
}
