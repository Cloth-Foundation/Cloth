package cloth.semantic.flow

import cloth.data.ProviderResult
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.symbols.LocalSymbol
import cloth.semantic.typing.FunctionSemanticModel
import cloth.semantic.typing.ResolvedCall
import cloth.token.Operators

/** Unknown source calls can transition or delete any identity they can reach. */
internal class StateEffects(private val model: FunctionSemanticModel) {

	private val types = model.types
	private val declarations = model.declarations

	fun mayFormat(recipe: ProviderResult<*>?): Boolean = recipe?.callsFormatter == true && declarations.formattingMayCallUserCode(recipe.receiverType)

	fun changesAt(value: Expression): Boolean {
		if (value in model.stateTransitions || types.operatorOf(value) != null || types.operatorContextOf(value) != null) return true
		if (value is BinaryExpression && types.arrayEqualities[value]?.mayCallUserCode(declarations) == true) return true
		return when (value) {
			is ClassCreationExpression, is ClassDeletionExpression, is StateCallExpression -> true
			is NameExpression                                                              -> (declarations.names[value] as? LocalSymbol)?.declaration?.initializer is FunctionBlockExpression
			is CallExpression                                                              -> when (val call = model.calls[value]) {
				is ResolvedCall.Builtin -> call.call.arguments.any { mayFormat(it.text) }
				null                    -> false
				else                    -> true
			}

			is MemberCallExpression                                                        -> model.selectedBoundMemberCall(value) != null || types.memberCalls[value]?.arguments?.any { mayFormat(it.text) } == true
			is TypeCheckExpression                                                         -> types.valueCheckOf(value) != null
			is ProviderAccessExpression                                                    -> mayFormat(types.providers[value]?.result)
			is InterpolatedString                                                          -> value.parts.any { mayFormat(types.interpolationTextOf(it)) }
			is BinaryExpression                                                            -> types.valueComparisons[value]?.overload != null || types.classComparisons[value]?.let { plan ->
				plan.identityOverride != null || plan.operation != Operators.IDENTITY && value.left !is NullLiteral && value.right !is NullLiteral && (declarations.hasOperator(plan.operation.toString()) || declarations.hasOperator(Operators.EQUAL.toString()))
			} == true

			else                                                                           -> false
		}
	}

	fun mayChange(value: Expression): Boolean = changesAt(value) || when (value) {
		is IfExpression            -> value.branches.any { mayChange(it.condition) || mayChange(it.statements) } || value.otherwise?.let(::mayChange) == true
		is ElvisExpression         -> mayChange(value.left) || mayChange(value.fallback)
		is GuardedAccessExpression -> mayChange(value.receiver) || mayChange(value.continuation)
		else                       -> value.eagerOperands(declarations).any(::mayChange)
	}

	fun mayChange(items: List<Statement>): Boolean = items.any { item ->
		when (item) {
			is ExpressionStatement        -> mayChange(item.expression)
			is BindingDeclaration         -> mayChange(item.initializer)
			is BindingGroupDeclaration    -> mayChange(item.initializer)
			is AssignmentStatement        -> mayChange(item.value)
			is MultiAssignmentStatement   -> mayChange(item.value)
			is FieldWriteStatement        -> mayChangeWrite(item.target, item.compound, item.value)
			is ElementAssignmentStatement -> mayChangeWrite(item.target, item.compound, item.value)
			is ReturnStatement            -> item.value?.let(::mayChange) == true
			is WhileStatement             -> mayChange(item.condition) || mayChange(item.body.statements)
			is DoWhileStatement           -> mayChange(item.body.statements) || mayChange(item.condition)
			is ForStatement               -> {
				val source = when (val input = item.source) {
					is ForSource.Elements -> mayChange(input.expression)
					is ForSource.Range    -> mayChange(input.start) || mayChange(input.end)
				}
				source || mayChange(item.body.statements) || declarations.loopSteppers[item]?.let(::mayChange) == true
			}

			else                          -> false
		}
	}

	private fun mayChangeWrite(target: Expression, compound: BinaryExpression?, value: Expression?): Boolean = mayChange(target) || value?.let(::mayChange) == true || compound?.let(::changesAt) == true
}
