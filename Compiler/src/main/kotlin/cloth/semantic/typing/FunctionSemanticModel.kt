package cloth.semantic.typing

import cloth.data.ValueType
import cloth.parser.expression.*
import cloth.parser.statement.Statement
import cloth.semantic.ClassDeletionPlan
import cloth.semantic.ResultShape
import cloth.semantic.declarations.DeclarationModel
import cloth.semantic.declarations.TypeResolver
import cloth.semantic.flow.ControlFlowModel
import cloth.semantic.flow.PresenceFacts
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
) {

	val target get() = declarations.target
	val types = SemanticModel(TypeResolver(target, operatorWidth, contextualNamedType = declarations::namedType))
	private val callables = CallableFacts()
	private val facts = FactCopies<FunctionSemanticModel>()
	internal val bindings: MutableMap<BindingSymbol, ValueType> = facts.identityMap { it.bindings }
	internal val calls: MutableMap<CallExpression, ResolvedCall> = facts.identityMap { it.calls }
	internal val sourceMemberCalls: MutableMap<MemberCallExpression, ResolvedCall.Source> = facts.identityMap { it.sourceMemberCalls }
	internal val constructorCalls: MutableMap<CallSite, ResolvedCall.Source> = facts.identityMap { it.constructorCalls }
	internal val classDeletions: MutableMap<ClassDeletionExpression, ClassDeletionPlan> = facts.identityMap { it.classDeletions }

	internal val callableBodies get() = declarations.concreteCallables + callables.publishedMethods()
	internal fun bodyOf(callable: CallableSymbol): FunctionSemanticModel = (callable as? MethodSymbol)?.let(callables::bodyOf) ?: this

	internal fun allBodyModels(): Sequence<FunctionSemanticModel> = sequenceOf(this) + callables.publishedMethods().asSequence().map(::bodyOf)

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
	internal fun declaredTypeOf(callable: CallableSymbol): FunctionType = contracts.signatures.getValue(callable)
	internal fun contextSelectorType(method: MethodSymbol): ValueType? = contracts.contextSelectors[method]

	internal fun typeOf(callable: CallableSymbol): FunctionType {
		if (callable !is MethodSymbol) return declaredTypeOf(callable)
		callables.signature(callable)?.let { return it }
		val declared = declaredTypeOf(callable)
		if (declared.results != ResultShape.Empty || callable.declaration.operator == null) return declared
		return operatorBodies.infer(this, callable, declared).also { callables.recordInferred(callable, it) }
	}

	fun selectedFunction(call: CallExpression): FunctionSymbol = (selectedCall(call) as ResolvedCall.Source).callable as FunctionSymbol
	internal fun selectedCall(call: CallExpression): ResolvedCall = calls.getValue(call)
	internal fun selectedMemberCall(call: MemberCallExpression): ResolvedCall.Source? = sourceMemberCalls[call]
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
		for (function in declarations.concreteCallables) {
			val parameters = declaredTypeOf(function).parameters
			function.parameters.forEachIndexed { index, parameter -> bindings[parameter] = parameters[index] }
		}
	}

	/** Callable bodies have their own expression facts, including when widths share syntax. */
	internal fun forCallableBody(width: Int? = null): FunctionSemanticModel = fresh(width).also { body ->
		body.initializeBindings()
		body.callables.copyFrom(callables)
	}

	private fun fresh(width: Int?) = FunctionSemanticModel(declarations, contracts, controlFlow, presence, operatorBodies, width)

	/** Dropping a trial discards all its body and callable facts. */
	internal fun trial() = Trial()

	internal inner class Trial {

		val model = fresh(operatorWidth).also { it.copyFrom(this@FunctionSemanticModel) }
		private var committed = false

		fun commit() {
			check(!committed) { "A semantic trial can only commit once" }
			copyFrom(model)
			committed = true
		}
	}

	private fun copyFrom(source: FunctionSemanticModel) {
		callables.copyFrom(source.callables)
		facts.copyFrom(source)
		types.copyFrom(source.types)
	}
}
