package cloth.emitter.types

import cloth.diagnostics.InternalErrors
import cloth.parser.expression.IfExpression
import cloth.parser.expression.FunctionBlockExpression
import cloth.parser.statement.AssignmentStatement
import cloth.parser.statement.ElementAssignmentStatement
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.Statement
import cloth.parser.statement.UpdateStatement
import cloth.parser.statement.ReturnStatement
import cloth.semantic.FunctionSemanticModel
import cloth.semantic.SemanticModel
import cloth.semantic.ValueType
import org.bytedeco.llvm.LLVM.LLVMValueRef

internal class EmitStatement(private val expressionEmitter: EmitExpression, private val bindings: EmitBindings, private val semantics: SemanticModel, private val functions: FunctionSemanticModel? = null) {

	fun emit(statement: Statement): LLVMValueRef? {
		if (expressionEmitter.terminated()) return null
		return when (statement) {
			is BindingDeclaration  -> {
				// The declaration carries code, not an eagerly computed storage value.
				if (statement.initializer is FunctionBlockExpression) return null
				val value = expressionEmitter.emit(statement.initializer)
				if (expressionEmitter.terminated()) return null
				val snapshot = expressionEmitter.copyArrayValue(checkNotNull(value), semantics.typeOf(statement))
				bindings.declare(functions?.declarations?.symbolOf(statement) ?: statement.name.lexeme, statement.name.lexeme, semantics.typeOf(statement), snapshot)
				value
			}

			is AssignmentStatement -> {
				val value = expressionEmitter.emit(statement.value)
				if (expressionEmitter.terminated()) return null
				val target = functions?.declarations?.targetOf(statement) ?: statement.name.lexeme
				bindings.assign(target, expressionEmitter.copyBindingAssignment(target, checkNotNull(value), semantics.typeOf(statement)))
				value
			}

			is UpdateStatement -> {
				val value = expressionEmitter.emitUpdate(statement, semantics.typeOf(statement))
				bindings.assign(functions?.declarations?.targetOf(statement) ?: statement.name.lexeme, value)
				value
			}
			is ElementAssignmentStatement -> expressionEmitter.emitElementAssignment(statement)

			is ExpressionStatement -> if (statement.expression is IfExpression && semantics.typeOf(statement) == ValueType.VOID) {
				expressionEmitter.emitConditional(statement.expression, requireValue = false)
			} else expressionEmitter.emit(statement.expression)
			is ReturnStatement -> { expressionEmitter.emitReturn(statement); null }

			else                   -> throw InternalErrors.invariant("Unsupported statement: $statement")
		}
	}
}
