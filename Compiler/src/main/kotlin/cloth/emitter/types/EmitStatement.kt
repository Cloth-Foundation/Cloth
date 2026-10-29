package cloth.emitter.types

import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.Statement
import org.bytedeco.llvm.LLVM.LLVMValueRef

internal class EmitStatement(private val expressionEmitter: EmitExpression) {

	fun emit(statement: Statement): LLVMValueRef {
		return when (statement) {
			is ExpressionStatement -> expressionEmitter.emit(statement.expression)
			else -> error("Unsupported statement: $statement")
		}
	}
}
