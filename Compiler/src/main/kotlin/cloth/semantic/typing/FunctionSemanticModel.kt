package cloth.semantic.typing

import cloth.data.ValueType
import cloth.parser.expression.*
import cloth.parser.statement.Statement
import cloth.semantic.ClassDeletionPlan
import cloth.semantic.ResultShape
import cloth.semantic.declarations.DeclarationModel
import cloth.semantic.declarations.StateDefinition
import cloth.semantic.declarations.TypeResolver
import cloth.semantic.flow.ControlFlowModel
import cloth.semantic.flow.PresenceFacts
import cloth.semantic.generics.ClosedCallableKey
import cloth.semantic.generics.GenericCallableRequest
import cloth.semantic.generics.TypeArgumentSpans
import cloth.semantic.generics.sourceSpans
import cloth.parser.type.TypeArgumentList
import cloth.semantic.scalar
import cloth.semantic.symbols.*

data class FunctionType(val parameters: List<ValueType>, val results: ResultShape) { constructor(parameters: List<ValueType>, returns: ValueType) : this(parameters, ResultShape.of(returns))

	val returns get() = results.scalar()
}

/** Body facts and callable choices for one analysis or candidate trial. */
class FunctionSemanticModel internal constructor(
	val declarations: DeclarationModel,
	private val contracts: CallableContracts,
	val controlFlow: ControlFlowModel = ControlFlowModel(declarations),
	internal val presence: PresenceFacts = PresenceFacts(),
	private val operatorBodies: OperatorBodies = OperatorBodies(),
	private val operatorWidth: Int? = null,
	private val enclosingBody: FunctionSemanticModel? = null,
	private val publishInference: Boolean = true,
	private val localCallResults: MutableMap<CallSite, ResultShape>? = null,
) {

	val target get() = declarations.target
	val types = SemanticModel(declarations.typeResolver(operatorWidth))
	private val callables = CallableFacts()
	private val facts = FactCopies<FunctionSemanticModel>()
	internal val bindings: MutableMap<BindingSymbol, ValueType> = facts.identityMap { it.bindings }
	internal val calls: MutableMap<CallExpression, ResolvedCall> = facts.identityMap { it.calls }
	private val callableRequests: MutableMap<ClosedCallableKey, GenericCallableRequest> = facts.orderedMap { it.callableRequests }
	private val inferredResults: MutableMap<CallSite, ResultShape> = facts.identityMap { it.inferredResults }
	internal val sourceMemberCalls: MutableMap<MemberCallExpression, ResolvedCall.Source> = facts.identityMap { it.sourceMemberCalls }
	internal val interfaceMemberCalls: MutableMap<MemberCallExpression, ResolvedCall.Interface> = facts.identityMap { it.interfaceMemberCalls }
	internal val constructorCalls: MutableMap<CallSite, ResolvedCall.Source> = facts.identityMap { it.constructorCalls }
	internal val classDeletions: MutableMap<ClassDeletionExpression, ClassDeletionPlan> = facts.identityMap { it.classDeletions }
	internal val stateCalls: MutableMap<StateCallExpression, ResolvedStateCall> = facts.identityMap { it.stateCalls }
	internal val stateTests: MutableMap<TypeCheckExpression, StateDefinition> = facts.identityMap { it.stateTests }
	internal val stateTransitions: MutableMap<Expression, StateDefinition> = facts.identityMap { it.stateTransitions }
	internal val inferredBoundBodies: MutableMap<MethodSymbol, FunctionSemanticModel> = facts.identityMap { it.inferredBoundBodies }

	internal val callableBodies get() = declarations.concreteCallables + callables.publishedMethods()
	internal fun bodyOf(callable: CallableContractSymbol): FunctionSemanticModel = (callable as? MethodSymbol)?.let(callables::bodyOf) ?: this

	internal fun allBodyModels(): Sequence<FunctionSemanticModel> = sequenceOf(this) + callables.publishedMethods().asSequence().map(::bodyOf)
	internal fun boundDeclarationModels(template: FunctionSemanticModel): Sequence<FunctionSemanticModel> {
		val models = generateSequence(template) { it.enclosingBody }
			.flatMap { it.allBodyModels() + it.inferredBoundBodies.values.asSequence() }
		return if (operatorWidth == null) models else models.sortedBy { it.operatorWidth != operatorWidth }
	}

	internal fun canonicalBoundMethod(method: MethodSymbol): MethodSymbol? =
		declarations.genericViews?.canonicalBoundMethod(method.declaration)?.let(declarations::symbolOf)

	fun typeOf(expression: Expression) = types.typeOf(expression)
	fun resultOf(expression: Expression) = types.resultOf(expression)
	fun resultOf(statement: Statement) = types.resultOf(statement)
	fun typeOf(statement: Statement) = types.typeOf(statement)
	fun typeOf(binding: BindingSymbol): ValueType = when (binding) {
		is ReceiverSymbol      -> binding.owner
		is ReceiverFieldSymbol -> binding.field.type
		else                   -> bindings.getValue(binding)
	}

	fun typeOf(function: FunctionSymbol): FunctionType = declaredTypeOf(function)
	internal fun declaredTypeOf(callable: CallableContractSymbol): FunctionType =
		contracts.signatures[callable] ?: declarations.genericViews?.candidateType(callable) ?: contracts.signatures.getValue(callable)
	internal val intrinsics get() = contracts.intrinsics
	internal fun contextSelectorType(method: MethodSymbol): ValueType? = contracts.contextSelectors[method]

	internal fun typeOf(callable: CallableContractSymbol): FunctionType {
		if (callable !is MethodSymbol) return declaredTypeOf(callable)
		callables.signature(callable)?.let { return it }
		val declared = declaredTypeOf(callable)
		if (declared.results != ResultShape.Empty || callable.declaration.operator == null) return declared
		return operatorBodies.infer(this, callable, declared).also { callables.recordInferred(callable, it) }
	}

	fun selectedFunction(call: CallExpression): FunctionSymbol = (selectedCall(call) as ResolvedCall.Source).callable as FunctionSymbol
	internal fun recordCall(call: CallExpression, resolved: ResolvedCall) {
		calls[call] = resolved
		if (resolved is ResolvedCall.Source) requestCallable(call, call.typeArguments, resolved.callable)
		recordInferredResult(call, resolved.type.results)
	}

	internal fun recordMemberCall(call: MemberCallExpression, resolved: ResolvedCall.Source) {
		sourceMemberCalls[call] = resolved
		requestCallable(call, call.typeArguments, resolved.callable)
	}

	internal fun recordInferredResult(call: CallSite, results: ResultShape) {
		if (call in presence.inferredResultsUsed) {
			inferredResults[call] = results
			publishInferredResults()
		}
	}

	internal fun inferredResult(call: CallSite): ResultShape? =
		if (localCallResults != null) localCallResults[call] else declarations.genericViews?.inferredResult(call)

	private fun publishInferredResults() {
		if (!publishInference) return
		if (localCallResults == null) {
			declarations.genericViews?.publishInferredResults(inferredResults)
			return
		}
		var changed = false
		for ((call, result) in inferredResults) {
			if (localCallResults.put(call, result) != result) changed = true
		}
		if (changed) throw CallResultFlowRestart()
	}

	internal fun requestCallable(call: CallSite, supplied: TypeArgumentList?, callable: CallableSymbol) {
		requestCallable(call, supplied?.sourceSpans(), callable)
	}

	internal fun requestCallable(call: CallSite, supplied: TypeArgumentSpans?, callable: CallableSymbol) {
		val request = declarations.genericViews?.callableRequest(call, supplied, callable) ?: return
		callableRequests.putIfAbsent(request.key, request)
	}

	internal fun methodTypeArguments(supplied: TypeArgumentList, report: Boolean): List<ValueType>? {
		if (!report && operatorWidth == null && supplied.arguments.any { it.capturesWidth }) return null
		return supplied.arguments.map(types.typeResolver::resolve)
	}

	internal fun genericCallableRequests(): Sequence<GenericCallableRequest> =
		allBodyModels().flatMap { it.callableRequests.values.asSequence() }

	internal fun selectedCall(call: CallExpression): ResolvedCall = calls.getValue(call)
	internal fun selectedMemberCall(call: MemberCallExpression): ResolvedCall.Source? = sourceMemberCalls[call]
	internal fun selectedBoundMemberCall(call: MemberCallExpression): ResolvedCall.Bound? = selectedMemberCall(call) ?: interfaceMemberCalls[call]
	internal fun selectedConstructor(call: CallSite): ResolvedCall.Source = constructorCalls.getValue(call)
	internal fun deletionOf(expression: ClassDeletionExpression): ClassDeletionPlan = classDeletions.getValue(expression)

	internal fun specialize(template: MethodSymbol, width: Int): MethodSymbol = callables.specialization(template, width) ?: operatorBodies.specialize(this, template, width)

	internal fun reserveSpecialization(template: MethodSymbol, width: Int, signature: FunctionType): MethodSymbol = callables.reserve(template, width, signature)

	internal fun publishSpecialization(method: MethodSymbol, body: FunctionSemanticModel) {
		callables.copyFrom(body.callables)
		callables.complete(method, body)
	}

	internal fun initializeBindings() {
		types.loopJumps.putAll(declarations.loopJumps)
		for (function in declarations.concreteContracts) {
			val parameters = declaredTypeOf(function).parameters
			function.parameters.forEachIndexed { index, parameter -> bindings[parameter] = parameters[index] }
			declarations.states.payloadOf(function.declaration)?.let { bindings[it] = it.type }
		}
	}

	/** Callable bodies have their own expression facts, including when widths share syntax. */
	internal fun forCallableBody(width: Int?, results: MutableMap<CallSite, ResultShape>?): FunctionSemanticModel {
		val body = if (results == null) fresh(width, enclosing = this) else FunctionSemanticModel(
			declarations, contracts, controlFlow, PresenceFacts(), operatorBodies, width, this, localCallResults = results,
		)
		body.initializeBindings()
		body.callables.copyFrom(callables)
		return body
	}

	private fun fresh(width: Int?, enclosing: FunctionSemanticModel? = enclosingBody) =
		FunctionSemanticModel(
			declarations, contracts, controlFlow, presence, operatorBodies, width, enclosing,
			publishInference = false, localCallResults = localCallResults,
		)

	/** Dropping a trial discards all its body and callable facts. */
	internal fun trial() = Trial()

	internal inner class Trial {

		val model = fresh(operatorWidth).also { it.copyFrom(this@FunctionSemanticModel) }
		private var committed = false

		fun commit() {
			check(!committed) { "A semantic trial can only commit once" }
			copyFrom(model)
			committed = true
			publishInferredResults()
		}
	}

	private fun copyFrom(source: FunctionSemanticModel) {
		callables.copyFrom(source.callables)
		facts.copyFrom(source)
		types.copyFrom(source.types)
	}
}
