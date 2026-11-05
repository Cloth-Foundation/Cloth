package cloth.emitter.types

import cloth.diagnostics.InternalErrors
import cloth.parser.expression.FunctionBlockExpression
import cloth.parser.expression.IfExpression
import cloth.parser.statement.*
import cloth.semantic.FunctionSemanticModel
import cloth.semantic.ReceptionKind
import cloth.semantic.ResultShape
import cloth.semantic.SemanticModel
import org.bytedeco.llvm.LLVM.LLVMValueRef

internal class EmitStatement(private val expressionEmitter: EmitExpression, private val bindings: EmitBindings, private val semantics: SemanticModel, private val functions: FunctionSemanticModel? = null) {

	fun emit(statement: Statement, discardResult: Boolean = false): LLVMValueRef? {
		if (expressionEmitter.terminated()) return null
		return when (statement) {
			is DoWhileStatement           -> {
				expressionEmitter.emitDoWhile(statement); null
			}

			is WhileStatement             -> {
				expressionEmitter.emitWhile(statement); null
			}

			is LoopJumpStatement          -> {
				expressionEmitter.emitLoopJump(statement); null
			}

			is ForStatement               -> {
				expressionEmitter.emitFor(statement); null
			}

			is BindingGroupDeclaration    -> {
				val plan = semantics.receptionOf(statement)
				if (plan.slots.none { it.target is ReceivingTarget.Named }) return expressionEmitter.emitDiscarded(plan)
				val value = expressionEmitter.emit(plan.producer)
				if (expressionEmitter.terminated()) return null
				val symbols = functions?.declarations?.symbolsOf(statement)
				val prepared = if (plan.kind == ReceptionKind.POSITIONAL) {
					val slots = expressionEmitter.takeResults(checkNotNull(value), plan.results)
					plan.slots.map { slot ->
						if (slot.target is ReceivingTarget.Named) expressionEmitter.transferArrayValue(expressionEmitter.adaptValue(slots[slot.sourceIndex], plan.results.values[slot.sourceIndex], slot.type), slot.type)
						else {
							expressionEmitter.validateDiscardedResult(expressionEmitter.adaptValue(slots[slot.sourceIndex], plan.results.values[slot.sourceIndex], slot.type), slot.type); null
						}
					}
				} else null
				var stored = false
				for ((index, slot) in plan.slots.withIndex()) if (slot.target is ReceivingTarget.Named) {
					val snapshot = if (prepared != null) checkNotNull(prepared[index]) else expressionEmitter.transferArrayValue(expressionEmitter.adaptValue(checkNotNull(value), plan.results.values.single(), slot.type), slot.type)
					bindings.declare(symbols?.get(index) ?: slot.target.token.lexeme, slot.target.token.lexeme, slot.type, snapshot)
					stored = true
				}
				if (stored && plan.kind == ReceptionKind.BROADCAST) value else null
			}

			is BindingDeclaration         -> {
				// The declaration carries code, not an eagerly computed storage value.
				if (statement.initializer is FunctionBlockExpression) return null
				val value = expressionEmitter.emit(statement.initializer)
				if (expressionEmitter.terminated()) return null
				val snapshot = expressionEmitter.transferArrayValue(expressionEmitter.adaptValue(checkNotNull(value), semantics.typeOf(statement.initializer), semantics.typeOf(statement)), semantics.typeOf(statement))
				bindings.declare(functions?.declarations?.symbolOf(statement) ?: statement.name.lexeme, statement.name.lexeme, semantics.typeOf(statement), snapshot)
				value
			}

			is AssignmentStatement        -> {
				val target = functions?.declarations?.targetOf(statement) ?: statement.name.lexeme
				bindings.checkDestination(target)
				val value = expressionEmitter.emit(statement.value)
				if (expressionEmitter.terminated()) return null
				bindings.assign(target, expressionEmitter.transferBindingAssignment(target, expressionEmitter.adaptValue(checkNotNull(value), semantics.typeOf(statement.value), semantics.typeOf(statement)), semantics.typeOf(statement)))
				value
			}

			is MultiAssignmentStatement   -> {
				val plan = semantics.receptionOf(statement)
				if (plan.slots.none { it.target is ReceivingTarget.Named }) return expressionEmitter.emitDiscarded(plan)
				val value = expressionEmitter.emit(plan.producer)
				if (expressionEmitter.terminated()) return null
				val targets = functions?.declarations?.targetsOf(statement)
				val prepared = if (plan.kind == ReceptionKind.POSITIONAL) {
					val slots = expressionEmitter.takeResults(checkNotNull(value), plan.results)
					plan.slots.mapIndexed { index, slot ->
						if (slot.target is ReceivingTarget.Discard) null else expressionEmitter.transferBindingAssignment(targets?.get(index) ?: slot.target.token.lexeme, expressionEmitter.adaptValue(slots[slot.sourceIndex], plan.results.values[slot.sourceIndex], slot.type), slot.type)
					}
				} else null
				var stored = false
				for ((index, slot) in plan.slots.withIndex()) if (slot.target is ReceivingTarget.Named) {
					val target = targets?.get(index) ?: slot.target.token.lexeme
					bindings.assign(target, if (prepared != null) checkNotNull(prepared[index]) else expressionEmitter.transferBindingAssignment(target, expressionEmitter.adaptValue(checkNotNull(value), plan.results.values.single(), slot.type), slot.type))
					stored = true
				}
				if (stored && plan.kind == ReceptionKind.BROADCAST) value else null
			}

			is UpdateStatement            -> {
				val value = expressionEmitter.emitUpdate(statement, semantics.typeOf(statement))
				val stored = semantics.updatePayloads[statement]?.let { expressionEmitter.adaptValue(value, it.payload, it) } ?: value
				bindings.assign(functions?.declarations?.targetOf(statement) ?: statement.name.lexeme, stored)
				value
			}

			is ElementAssignmentStatement -> expressionEmitter.emitElementAssignment(statement)
			is FieldWriteStatement        -> expressionEmitter.emitFieldWrite(statement)

			is ExpressionStatement        -> if (discardResult) expressionEmitter.emitDiscarded(statement.expression) else if (statement.expression is IfExpression && semantics.resultOf(statement) == ResultShape.Empty) {
				expressionEmitter.emitConditional(statement.expression, requireValue = false)
			} else expressionEmitter.emit(statement.expression)

			is ReturnStatement            -> {
				expressionEmitter.emitReturn(statement); null
			}

			else                          -> throw InternalErrors.invariant("Unsupported statement: $statement")
		}
	}
}
