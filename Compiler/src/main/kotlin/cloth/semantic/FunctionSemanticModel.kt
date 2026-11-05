package cloth.semantic

import cloth.data.ValueType
import cloth.diagnostics.InternalErrors
import cloth.parser.expression.*
import cloth.parser.statement.Statement
import cloth.parser.statement.UpdateStatement
import cloth.semantic.symbols.*
import java.util.*

data class FunctionType(val parameters: List<ValueType>, val results: ResultShape) { constructor(parameters: List<ValueType>, returns: ValueType) : this(parameters, ResultShape.of(returns))

	val returns get() = results.scalar()
}

/** Committed call choices and expression types; rejected candidate facts never escape. */
class FunctionSemanticModel internal constructor(val declarations: DeclarationModel, internal val signatures: Map<CallableSymbol, FunctionType>, val controlFlow: ControlFlowModel = ControlFlowModel()) {

	val target get() = declarations.target
	val types = SemanticModel(target, declarations.structs)
	internal val bindings = IdentityHashMap<BindingSymbol, ValueType>()
	internal val calls = IdentityHashMap<CallExpression, ResolvedCall>()
	internal val sourceMemberCalls = IdentityHashMap<MemberCallExpression, ResolvedCall.Source>()
	internal val lostReceiverProofs = IdentityHashMap<MemberCallExpression, List<Expression>>()
	internal val presentNames = Collections.newSetFromMap(IdentityHashMap<NameExpression, Boolean>())
	internal val presentFields = Collections.newSetFromMap(IdentityHashMap<FieldAccessExpression, Boolean>())
	internal val presentUpdates = Collections.newSetFromMap(IdentityHashMap<UpdateStatement, Boolean>())
	fun typeOf(expression: Expression) = types.typeOf(expression)
	fun resultOf(expression: Expression) = types.resultOf(expression)
	fun resultOf(statement: Statement) = types.resultOf(statement)
	fun typeOf(statement: Statement) = types.typeOf(statement)
	fun typeOf(binding: BindingSymbol): ValueType = when (binding) {
		is ReceiverSymbol      -> binding.owner
		is ReceiverFieldSymbol -> binding.field.type
		else                   -> bindings.getValue(binding)
	}

	fun typeOf(function: FunctionSymbol): FunctionType = signatures.getValue(function)
	internal fun typeOf(callable: CallableSymbol): FunctionType = signatures.getValue(callable)
	fun selectedFunction(call: CallExpression): FunctionSymbol = (selectedCall(call) as ResolvedCall.Source).callable as FunctionSymbol
	internal fun selectedCall(call: CallExpression): ResolvedCall = calls.getValue(call)

	internal fun fork() = FunctionSemanticModel(declarations, signatures, controlFlow).also { it.commit(this) }
	internal fun commit(other: FunctionSemanticModel) {
		if (target != other.target) throw InternalErrors.invariant("Cannot commit semantic facts from a different compilation target")
		bindings.clear(); bindings.putAll(other.bindings)
		calls.clear(); calls.putAll(other.calls)
		sourceMemberCalls.clear(); sourceMemberCalls.putAll(other.sourceMemberCalls)
		lostReceiverProofs.clear(); lostReceiverProofs.putAll(other.lostReceiverProofs)
		presentNames.clear(); presentNames.addAll(other.presentNames)
		presentFields.clear(); presentFields.addAll(other.presentFields)
		types.structLiterals.clear(); types.structLiterals.putAll(other.types.structLiterals)
		types.fields.clear(); types.fields.putAll(other.types.fields)
		types.fieldWrites.clear(); types.fieldWrites.putAll(other.types.fieldWrites)
		presentUpdates.clear(); presentUpdates.addAll(other.presentUpdates)
		types.expressions.clear(); types.expressions.putAll(other.types.expressions)
		types.expressionResults.clear(); types.expressionResults.putAll(other.types.expressionResults)
		types.statementResults.clear(); types.statementResults.putAll(other.types.statementResults)
		types.resultMetadata.clear(); types.resultMetadata.putAll(other.types.resultMetadata)
		types.statements.clear(); types.statements.putAll(other.types.statements)
		types.receptions.clear(); types.receptions.putAll(other.types.receptions)
		types.typeChecks.clear(); types.typeChecks.putAll(other.types.typeChecks)
		types.providers.clear(); types.providers.putAll(other.types.providers)
		types.memberCalls.clear(); types.memberCalls.putAll(other.types.memberCalls)
		types.metadata.clear(); types.metadata.putAll(other.types.metadata)
		types.payloadReads.clear(); types.payloadReads.putAll(other.types.payloadReads)
		types.nullableComparisons.clear(); types.nullableComparisons.putAll(other.types.nullableComparisons)
		types.updatePayloads.clear(); types.updatePayloads.putAll(other.types.updatePayloads)
		types.loops.clear(); types.loops.putAll(other.types.loops)
		types.loopJumps.clear(); types.loopJumps.putAll(other.types.loopJumps)
	}
}
