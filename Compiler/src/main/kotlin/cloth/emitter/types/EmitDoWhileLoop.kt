package cloth.emitter.types

import cloth.parser.statement.DoWhileStatement
import cloth.semantic.ControlFlowModel
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.global.LLVM.*

/** First body entry precedes the trailing test; jumps share ordinary loop cleanup. */
internal class EmitDoWhileLoop(
	private val context: LLVMContextRef,
	private val builder: LLVMBuilderRef,
	private val bindings: EmitBindings,
	private val expressions: EmitExpression,
	private val flow: ControlFlowModel,
) {

	fun emit(statement: DoWhileStatement) {
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val body = LLVMAppendBasicBlockInContext(context, function, "do_body")
		val test = LLVMAppendBasicBlockInContext(context, function, "do_test")
		val exit = LLVMAppendBasicBlockInContext(context, function, "do_end")
		LLVMBuildBr(builder, body)
		LLVMPositionBuilderAtEnd(builder, body)
		val reachesTest = expressions.emitLoopBody(statement.loopTarget, exit, test) {
			expressions.emitIteration(statement.body.statements) {
				bindings.withScope { expressions.emitStatements(statement.body.statements) }
			}
		}
		if (!expressions.terminated()) LLVMBuildBr(builder, test)
		LLVMPositionBuilderAtEnd(builder, test)
		if (reachesTest) {
			val condition = expressions.emitLoopCondition(statement.condition)
			if (!expressions.terminated()) LLVMBuildCondBr(builder, checkNotNull(condition), body, exit)
		} else {
			LLVMBuildUnreachable(builder)
		}
		// Even a returning or outward-jumping condition leaves these blocks behind.
		LLVMPositionBuilderAtEnd(builder, exit)
		if (!flow.of(statement).canFallThrough) LLVMBuildUnreachable(builder)
	}
}
