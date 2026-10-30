package cloth.emitter.types

import cloth.diagnostics.InternalErrors
import cloth.parser.expression.IfExpression
import cloth.parser.statement.AssignmentStatement
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.Statement
import cloth.parser.statement.UpdateStatement
import cloth.semantic.SemanticModel
import cloth.semantic.ValueType
import org.bytedeco.llvm.LLVM.LLVMValueRef

internal class EmitStatement(private val expressionEmitter: EmitExpression, private val bindings: EmitBindings, private val semantics: SemanticModel) {

	fun emit(statement: Statement): LLVMValueRef? {
		return when (statement) {
			is BindingDeclaration  -> {
				val value = expressionEmitter.emit(statement.initializer)
				bindings.declare(statement.name.lexeme, semantics.typeOf(statement), value)
				value
			}

			is AssignmentStatement -> {
				val value = expressionEmitter.emit(statement.value)
				bindings.assign(statement.name.lexeme, value)
				value
			}

			is UpdateStatement -> {
				val value = expressionEmitter.emitUpdate(statement, semantics.typeOf(statement))
				bindings.assign(statement.name.lexeme, value)
				value
			}

			is ExpressionStatement -> if (statement.expression is IfExpression && semantics.typeOf(statement) == ValueType.VOID) {
				expressionEmitter.emitConditional(statement.expression, requireValue = false)
			} else expressionEmitter.emit(statement.expression)

			else                   -> throw InternalErrors.invariant("Unsupported statement: $statement")
		}
	}
}
