package cloth.emitter.types

import cloth.diagnostics.InternalErrors
import cloth.emitter.storage.EmitBindings
import cloth.parser.expression.FunctionBlockExpression
import cloth.parser.expression.IfExpression
import cloth.parser.statement.*
import cloth.semantic.ReceptionKind
import cloth.semantic.ReceptionPlan
import cloth.semantic.ResultShape
import cloth.semantic.symbols.BindingSymbol
import cloth.semantic.typing.FunctionSemanticModel
import org.bytedeco.llvm.LLVM.LLVMValueRef

internal class EmitStatement(private val expressionEmitter: EmitExpression, private val bindings: EmitBindings, functions: FunctionSemanticModel) {

	private val transfer = expressionEmitter.valueTransfer
	private val semantics = functions.types
	private val declarations = functions.declarations

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

			is BindingGroupDeclaration    -> emitReception(semantics.receptionOf(statement), declarations.symbolsOf(statement), defining = true)

			is BindingDeclaration         -> {
				// The declaration carries code, not an eagerly computed storage value.
				if (statement.initializer is FunctionBlockExpression) return null
				val value = expressionEmitter.emit(statement.initializer)
				if (expressionEmitter.terminated()) return null
				val snapshot = transfer.transfer(expressionEmitter.adaptValue(checkNotNull(value), semantics.typeOf(statement.initializer), semantics.typeOf(statement)), semantics.typeOf(statement))
				bindings.declare(declarations.symbolOf(statement), semantics.typeOf(statement), snapshot)
				value
			}

			is AssignmentStatement        -> {
				val target = declarations.targetOf(statement)
				bindings.checkDestination(target)
				val value = expressionEmitter.emit(statement.value)
				if (expressionEmitter.terminated()) return null
				bindings.assign(target, transfer.prepareBindingAssignment(bindings, target, expressionEmitter.adaptValue(checkNotNull(value), semantics.typeOf(statement.value), semantics.typeOf(statement)), semantics.typeOf(statement)))
				value
			}

			is MultiAssignmentStatement   -> emitReception(semantics.receptionOf(statement), declarations.targetsOf(statement), defining = false)

			is UpdateStatement            -> {
				val value = expressionEmitter.emitUpdate(statement, semantics.typeOf(statement))
				val stored = semantics.updatePayloads[statement]?.let { expressionEmitter.adaptValue(value, it.payload, it) } ?: value
				bindings.assign(declarations.targetOf(statement), stored)
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

	private fun emitReception(plan: ReceptionPlan, targets: List<BindingSymbol?>, defining: Boolean): LLVMValueRef? {
		if (targets.all { it == null }) return expressionEmitter.emitDiscarded(plan)
		val result = expressionEmitter.emit(plan.producer)
		if (expressionEmitter.terminated()) return null
		val value = checkNotNull(result)

		fun prepare(index: Int, source: LLVMValueRef): LLVMValueRef? {
			val target = targets[index]
			if (target == null && !defining) return null
			val slot = plan.slots[index]
			val adapted = expressionEmitter.adaptValue(source, plan.results.values[slot.sourceIndex], slot.type)
			if (target == null) {
				transfer.validateDiscarded(adapted, slot.type)
				return null
			}
			return if (defining) transfer.transfer(adapted, slot.type)
			else transfer.prepareBindingAssignment(bindings, target, adapted, slot.type)
		}

		fun publish(index: Int, target: BindingSymbol, prepared: LLVMValueRef) {
			if (defining) bindings.declare(target, plan.slots[index].type, prepared)
			else bindings.assign(target, prepared)
		}

		if (plan.kind == ReceptionKind.POSITIONAL) {
			val slots = transfer.takeResults(value, plan.results)
			// Prepare every received value before any destination can replace shared storage.
			val prepared = plan.slots.mapIndexed { index, slot -> prepare(index, slots[slot.sourceIndex]) }
			targets.forEachIndexed { index, target ->
				if (target != null) publish(index, target, checkNotNull(prepared[index]))
			}
			return null
		}

		check(plan.kind == ReceptionKind.BROADCAST)
		targets.forEachIndexed { index, target ->
			if (target != null) publish(index, target, checkNotNull(prepare(index, value)))
		}
		return value
	}
}
