package cloth.emitter.types

import cloth.parser.statement.*
import org.bytedeco.llvm.LLVM.LLVMValueRef

internal class EmitStatement(private val expressionEmitter: EmitExpression, private val bindings: EmitBindings) {

	fun emit(statement: Statement): LLVMValueRef {
		return when (statement) {
			is BindingDeclaration -> {
				val value = expressionEmitter.emit(statement.initializer)
				bindings.declare(statement.name.lexeme, value)
				value
			}
			is AssignmentStatement -> {
				val value = expressionEmitter.emit(statement.value)
				bindings.assign(statement.name.lexeme, value)
				value
			}
			is ExpressionStatement -> expressionEmitter.emit(statement.expression)
			else -> error("Unsupported statement: $statement")
		}
	}
}
