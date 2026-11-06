package cloth.semantic

import cloth.data.CClass
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
class FunctionSemanticModel internal constructor(
	val declarations: DeclarationModel,
	internal val signatures: Map<CallableSymbol, FunctionType>,
	val controlFlow: ControlFlowModel = ControlFlowModel(),
	private val operatorWidth: Int? = null,
) {

	val target get() = declarations.target
	val types = SemanticModel(target, declarations.structs + declarations.classes.mapValues { it.value.owner } + declarations.enums + (declarations.objectRoot.spelling to declarations.objectRoot), operatorWidth)
	internal var specializeContext: ((MethodSymbol, Int) -> MethodSymbol)? = null
	internal val specializedBodies = linkedMapOf<MethodSymbol, FunctionSemanticModel>()
	internal val contextSelectorTypes = mutableMapOf<MethodSymbol, ValueType>()
	internal val callableBodies get() = declarations.concreteCallables + specializedBodies.keys
	internal val bindings = IdentityHashMap<BindingSymbol, ValueType>()
	internal val calls = IdentityHashMap<CallExpression, ResolvedCall>()
	internal val sourceMemberCalls = IdentityHashMap<MemberCallExpression, ResolvedCall.Source>()
	internal val constructorCalls = IdentityHashMap<CallSite, ResolvedCall.Source>()
	internal val classDeletions = IdentityHashMap<ClassDeletionExpression, ClassDeletionPlan>()
	internal val lostReceiverProofs = IdentityHashMap<MemberCallExpression, List<Expression>>()
	internal val presentNames = Collections.newSetFromMap(IdentityHashMap<NameExpression, Boolean>())
	internal val narrowedNames = IdentityHashMap<NameExpression, CClass>()
	internal val presentFields = Collections.newSetFromMap(IdentityHashMap<FieldAccessExpression, Boolean>())
	internal val presentUpdates = Collections.newSetFromMap(IdentityHashMap<UpdateStatement, Boolean>())
	internal var inferOperatorType: ((MethodSymbol) -> FunctionType)? = null
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
	internal fun typeOf(callable: CallableSymbol): FunctionType {
		val type = signatures.getValue(callable)
		return if (type.results == ResultShape.Empty && callable is MethodSymbol && callable.declaration.operator != null) {
			checkNotNull(inferOperatorType)(callable)
		} else type
	}

	fun selectedFunction(call: CallExpression): FunctionSymbol = (selectedCall(call) as ResolvedCall.Source).callable as FunctionSymbol
	internal fun selectedCall(call: CallExpression): ResolvedCall = calls.getValue(call)

	internal fun fork(width: Int? = operatorWidth) = FunctionSemanticModel(declarations, signatures, controlFlow, width).also { it.commit(this) }
	internal fun commit(other: FunctionSemanticModel) {
		if (target != other.target) throw InternalErrors.invariant("Cannot commit semantic facts from a different compilation target")
		inferOperatorType = other.inferOperatorType
		specializeContext = other.specializeContext
		contextSelectorTypes.clear(); contextSelectorTypes.putAll(other.contextSelectorTypes)
		bindings.clear(); bindings.putAll(other.bindings)
		calls.clear(); calls.putAll(other.calls)
		sourceMemberCalls.clear(); sourceMemberCalls.putAll(other.sourceMemberCalls)
		constructorCalls.clear(); constructorCalls.putAll(other.constructorCalls)
		classDeletions.clear(); classDeletions.putAll(other.classDeletions)
		lostReceiverProofs.clear(); lostReceiverProofs.putAll(other.lostReceiverProofs)
		presentNames.clear(); presentNames.addAll(other.presentNames)
		narrowedNames.clear(); narrowedNames.putAll(other.narrowedNames)
		presentFields.clear(); presentFields.addAll(other.presentFields)
		types.structLiterals.clear(); types.structLiterals.putAll(other.types.structLiterals)
		types.fields.clear(); types.fields.putAll(other.types.fields)
		types.fieldWrites.clear(); types.fieldWrites.putAll(other.types.fieldWrites)
		types.instanceWrites.clear(); types.instanceWrites.putAll(other.types.instanceWrites)
		types.receiverElementWrites.clear(); types.receiverElementWrites.putAll(other.types.receiverElementWrites)
		presentUpdates.clear(); presentUpdates.addAll(other.presentUpdates)
		types.expressions.clear(); types.expressions.putAll(other.types.expressions)
		types.expressionResults.clear(); types.expressionResults.putAll(other.types.expressionResults)
		types.statementResults.clear(); types.statementResults.putAll(other.types.statementResults)
		types.resultMetadata.clear(); types.resultMetadata.putAll(other.types.resultMetadata)
		types.statements.clear(); types.statements.putAll(other.types.statements)
		types.receptions.clear(); types.receptions.putAll(other.types.receptions)
		types.typeChecks.clear(); types.typeChecks.putAll(other.types.typeChecks)
		types.valueChecks.clear(); types.valueChecks.putAll(other.types.valueChecks)
		types.providers.clear(); types.providers.putAll(other.types.providers)
		types.memberCalls.clear(); types.memberCalls.putAll(other.types.memberCalls)
		types.operatorCalls.clear(); types.operatorCalls.putAll(other.types.operatorCalls)
		types.projectedOperands.clear(); types.projectedOperands.putAll(other.types.projectedOperands)
		types.operatorContexts.clear(); types.operatorContexts.putAll(other.types.operatorContexts)
		types.classComparisons.clear(); types.classComparisons.putAll(other.types.classComparisons)
		types.valueComparisons.clear(); types.valueComparisons.putAll(other.types.valueComparisons)
		types.metadata.clear(); types.metadata.putAll(other.types.metadata)
		types.payloadReads.clear(); types.payloadReads.putAll(other.types.payloadReads)
		types.nullableComparisons.clear(); types.nullableComparisons.putAll(other.types.nullableComparisons)
		types.updatePayloads.clear(); types.updatePayloads.putAll(other.types.updatePayloads)
		types.loops.clear(); types.loops.putAll(other.types.loops)
		types.loopJumps.clear(); types.loopJumps.putAll(other.types.loopJumps)
	}
}
