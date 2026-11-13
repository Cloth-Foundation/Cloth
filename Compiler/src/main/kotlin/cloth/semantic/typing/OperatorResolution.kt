package cloth.semantic.typing

import cloth.data.*
import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.Expression
import cloth.parser.expression.IndexExpression
import cloth.parser.expression.NameExpression
import cloth.semantic.OperatorRules
import cloth.semantic.SemanticException
import cloth.semantic.symbols.MethodSymbol
import cloth.token.*

/** Resolves full operators before applying one context per original operand. */
internal class OperatorResolution(
	private val facts: FunctionSemanticModel,
	private val typingIn: (FunctionSemanticModel, Boolean) -> ExpressionTyping,
	private val reject: (() -> SemanticException) -> Nothing,
	private val probe: Boolean,
	private val classOwner: CClass?,
) {

	private enum class FullRank { EXACT,
		COMPATIBLE,
		CONTEXTUAL
	}

	private enum class ContextRank { EXACT,
		CAPTURED_WIDTH,
		COMPATIBLE
	}

	private data class Candidate(val method: MethodSymbol, val receiver: Int, val types: List<ValueType>)
	private data class OperandProbe(val types: List<ValueType?>, val trial: FunctionSemanticModel.Trial)
	private data class Match(val candidate: Candidate, val rank: FullRank, val trial: FunctionSemanticModel.Trial)

	private val model = facts.types

	fun foreach(source: Expression, type: ValueType): CollectionForeachPlan? {
		val owner = type as? CMemberType ?: return null
		val name = Operators.FOREACH.toString()
		val method = facts.declarations.operators(owner, name, right = false, arity = 0).firstOrNull() ?: return null
		val operator = Token(TokenType.Operator, source.span, name)
		if (!methodVisible(method.owner, classOwner, method.declaration.visibility != null)) throw SemanticErrors.privateClassMember(operator, method.name)
		checkOperatorReceiver(source, operator)
		val signature = facts.declaredTypeOf(method)
		val snapshot = checkNotNull(SnapshotLayout.of(signature.returns, facts.declarations.files))
		return CollectionForeachPlan(ResolvedCall.Source(method, signature, ArgumentBinding(emptyList(), emptyList())), snapshot)
	}

	private fun candidates(operator: String, operands: List<ValueType?>): List<Candidate> = operands.flatMapIndexed { index, type ->
		val owner = type as? CMemberType ?: return@flatMapIndexed emptyList()
		facts.declarations.operators(owner, operator, index == 1, operands.size - 1).map { method ->
			val parameters = facts.declaredTypeOf(method).parameters
			val types = if (index == 0) listOf<ValueType>(method.owner) + parameters else parameters + method.owner
			Candidate(method, index, types)
		}
	}

	fun index(expression: IndexExpression, receiver: CClass): ValueType {
		val operator = Token(TokenType.Operator, expression.span, Operators.INDEX_READ.toString())
		return resolve(expression, operator, listOf(expression.array, expression.index), indexReceiver = receiver) ?: reject { SemanticErrors.index("Type '${receiver.spelling}' has no bracket read operator", expression.span) }
	}

	fun indexWrite(target: IndexExpression, receiver: CClass, value: Expression, updateTypes: List<ValueType> = emptyList()): ResolvedIndexWrite {
		val operator = Token(TokenType.Operator, target.span, Operators.INDEX_WRITE.toString())
		val candidates = indexCandidates(receiver, operator, arity = 2)
		if (candidates.isEmpty()) reject { SemanticErrors.index("Type '${receiver.spelling}' has no bracket write operator", target.span) }
		val operands = listOf(target.array, target.index, value)
		val knownTypes = listOf(receiver) + updateTypes
		val match = select(operator, target.span, operands, knownTypes, probeOperands(operands, knownTypes), candidates) ?: reject { SemanticErrors.operatorMatch(operator, target.span.through(value.span), candidates.map { it.method }, ambiguous = false) }
		checkOperatorReceiver(target.array, operator)
		val call = sourceCall(match)
		val validator = checkNotNull(facts.indexValidator(match.candidate.method, call.type.parameters.first()))
		match.trial.commit()
		return ResolvedIndexWrite(call, validator)
	}

	private fun indexCandidates(receiver: CClass, operator: Token, arity: Int): List<Candidate> {
		val available = facts.declarations.operators(receiver, operator.lexeme, right = false, arity = arity)
		val visible = available.filter { methodVisible(it.owner, classOwner, it.declaration.visibility != null) }
		if (visible.isEmpty() && available.isNotEmpty()) throw SemanticErrors.privateClassMember(operator, available.first().name)
		return visible.map { method -> Candidate(method, 0, listOf(method.owner) + facts.declaredTypeOf(method).parameters) }
	}

	fun resolve(expression: Expression, operator: Token, operands: List<Expression>, indexReceiver: CClass? = null): ValueType? {
		val operation = Operators.fromCharacter(operator.lexeme)
		if (operation in OperatorRules.classComparisons) return null
		if (!facts.declarations.hasOperator(operator.lexeme)) return null
		model.operatorCalls[expression]?.let { return it.call.type.returns }
		model.operatorContexts[expression]?.let { return model.typeOf(it.application) }
		val knownTypes = listOfNotNull(indexReceiver)
		val ordinary = probeOperands(operands, knownTypes)
		if (operator.lexeme == Keywords.IS.toString() && ordinary.types.any { it !is CClass && it != ValueType.NEVER }) {
			reject { SemanticErrors.operatorMatch(operator, expression.span, emptyList(), ambiguous = false) }
		}
		val candidates = if (indexReceiver == null) candidates(operator.lexeme, ordinary.types) else indexCandidates(indexReceiver, operator, arity = 1)
		val selected = select(operator, expression.span, operands, knownTypes, ordinary, candidates)
		if (selected == null) {
			if (expression is BinaryExpression && OperatorRules.supportsContext(operation) && operands.none { it in model.projectedOperands }) {
				resolveOperandContexts(expression, operands, ordinary.types, ordinary.trial)?.let { return it }
			}
			if (candidates.isEmpty()) return null
			reject { SemanticErrors.operatorMatch(operator, expression.span, candidates.map { it.method }, ambiguous = false) }
		}
		val candidate = selected.candidate
		checkOperatorReceiver(operands[candidate.receiver], operator)
		val call = sourceCall(selected)
		val validator = if (indexReceiver != null) facts.indexValidator(candidate.method, call.type.parameters.single()) else null
		selected.trial.model.types.operatorCalls[expression] = ResolvedOperator(call, candidate.receiver, validator)
		selected.trial.commit()
		return call.type.returns
	}

	private fun sourceCall(match: Match): ResolvedCall.Source {
		val method = match.candidate.method
		return ResolvedCall.Source(method, match.trial.model.typeOf(method), ArgumentBinding(method.parameters.indices.toList(), emptyList()))
	}

	private fun probeOperands(operands: List<Expression>, knownTypes: List<ValueType>): OperandProbe {
		val trial = facts.trial()
		val checker = typingIn(trial.model, true)
		val types = operands.mapIndexed { index, operand -> knownTypes.getOrNull(index) ?: probeCandidate { checker.typeOf(operand) } }
		return OperandProbe(types, trial)
	}

	private fun select(operator: Token, span: TokenSpan, operands: List<Expression>, knownTypes: List<ValueType>, ordinary: OperandProbe, candidates: List<Candidate>): Match? {
		val matches = candidates.mapNotNull { candidate ->
			val rank = candidate.types.zip(ordinary.types).maxOf { (expected, actual) ->
				when {
					expected == actual                         -> FullRank.EXACT
					actual != null && expected.accepts(actual) -> FullRank.COMPATIBLE
					else                                       -> FullRank.CONTEXTUAL
				}
			}
			if (rank != FullRank.CONTEXTUAL) return@mapNotNull Match(candidate, rank, ordinary.trial)
			val trial = facts.trial()
			val checker = typingIn(trial.model, true)
			val accepted = probeCandidate {
				operands.indices.all { index ->
					val expected = candidate.types[index]
					val actual = knownTypes.getOrNull(index) ?: checker.typeOf(operands[index], expected.takeUnless { index == candidate.receiver })
					expected.accepts(actual)
				}
			} == true
			if (!accepted) return@mapNotNull null
			Match(candidate, rank, trial)
		}
		if (matches.isEmpty()) return null
		val rank = matches.minOf(Match::rank)
		val best = mostSpecific(matches.filter { it.rank == rank }) { it.candidate.types }
		if (best.size != 1) throw SemanticErrors.operatorMatch(operator, span, best.map { it.candidate.method }, ambiguous = true)
		return best.single()
	}

	private fun checkOperatorReceiver(operand: Expression, operator: Token) {
		val original = (operand as? NameExpression)?.let { model.projectedOperands[it]?.original } ?: operand
		if (original in facts.declarations.readOnlyValueReceivers) {
			throw SemanticErrors.parameterDefault("A default cannot call a user-defined operator through a read-only receiver", operator.span)
		}
	}

	/** Mixed present values have no ordinary class/nominal equality overload.
	 * Keep null handling, same-type value equality and class dispatch on their existing paths.
	 */
	fun equalityContext(expression: BinaryExpression, operation: Operators, left: ValueType, right: ValueType): ValueType? {
		if (operation !in OperatorRules.equality || left == right || left.classBackedType() != null && right.classBackedType() != null) return null
		val operands = listOf(expression.left, expression.right)
		if (listOf(left, right).any { it is CNullable || it == ValueType.Null || it == ValueType.NEVER }) return null
		if (operands.any { it in model.projectedOperands }) return null
		return resolveOperandContexts(expression, operands, listOf(left, right), facts.trial())
	}

	private fun resolveOperandContexts(expression: BinaryExpression, operands: List<Expression>, originalTypes: List<ValueType?>, trial: FunctionSemanticModel.Trial): ValueType? {
		val left = originalTypes[0] ?: return null
		val right = originalTypes[1] ?: return null
		val projections = listOf(
			selectContext(trial.model, left, right, operands[0], expression.operator),
			selectContext(trial.model, right, left, operands[1], expression.operator),
		)
		if (projections.all { it == null }) return null
		val names = operands.mapIndexed { index, operand ->
			val projection = projections[index]
			val type = projection?.call?.type?.returns ?: checkNotNull(originalTypes[index])
			NameExpression(Token(TokenType.Identifier, operand.span, "<operator operand>")).also {
				trial.model.types.projectedOperands[it] = ProjectedOperand(type, operand.takeIf { projection == null })
			}
		}
		val application = BinaryExpression(names[0], expression.operator, names[1])
		val result = typingIn(trial.model, probe).typeOf(application)
		trial.model.types.operatorContexts[expression] = ResolvedOperatorContext(application, projections)
		trial.commit()
		return result
	}

	private fun selectContext(trial: FunctionSemanticModel, type: ValueType, peer: ValueType, operand: Expression, operator: Token): ResolvedOperator? {
		val owner = type as? CMemberType ?: return null
		if (peer == ValueType.NEVER || peer == ValueType.Null) return null
		data class Match(val method: MethodSymbol, val selector: ValueType, val rank: ContextRank, val width: Int? = null)

		fun matchesFor(name: String) = facts.declarations.contextOperators(owner, name).mapNotNull { method ->
			val selector = trial.contextSelectorType(method)
			val width = OperatorRules.inferredSelectorWidth(method.declaration.operator?.selector, peer)
			when {
				width != null                   -> Match(method, peer, ContextRank.CAPTURED_WIDTH, width)
				selector == peer                -> Match(method, selector, ContextRank.EXACT)
				selector?.accepts(peer) == true -> Match(method, selector, ContextRank.COMPATIBLE)
				else                            -> null
			}
		}

		val matches = matchesFor(operator.lexeme).ifEmpty {
			if (operator.lexeme == Operators.NOT_EQUAL.toString()) matchesFor(Operators.EQUAL.toString()) else emptyList()
		}
		if (matches.isEmpty()) return null
		val rank = matches.minOf(Match::rank)
		val best = mostSpecific(matches.filter { it.rank == rank }) { listOf(it.selector) }
		if (best.size != 1) throw SemanticErrors.operatorMatch(operator, operand.span, best.map { it.method }, ambiguous = true)
		checkOperatorReceiver(operand, operator)
		val match = best.single()
		val method = match.width?.let { trial.specialize(match.method, it) } ?: match.method
		val call = ResolvedCall.Source(method, trial.typeOf(method), ArgumentBinding(emptyList(), emptyList()))
		return ResolvedOperator(call, 0)
	}

	private fun <T> mostSpecific(tier: List<T>, types: (T) -> List<ValueType>): List<T> = tier.filter { match -> tier.none { moreSpecific(types(it), types(match)) } }

	private fun moreSpecific(left: List<ValueType>, right: List<ValueType>): Boolean {
		val pairs = left.zip(right)
		return pairs.all { (a, b) -> b.accepts(a) } && pairs.any { (a, b) -> !a.accepts(b) }
	}

	fun classComparison(expression: BinaryExpression, operation: Operators, left: CMemberType, right: CMemberType): ResolvedOperator? {
		val declarations = facts.declarations
		if (operation != Operators.IDENTITY) {
			if (expression.left in declarations.readOnlyValueReceivers && declarations.classDefinitions.values.any { definition ->
					val owner = definition.owner
					left.accepts(owner) && right.accepts(owner) && declarations.equalityOverride(owner, operation) != null
				}) throw SemanticErrors.parameterDefault("A default cannot call a user-defined operator through a read-only receiver", expression.operator.span)
			return null
		}
		return if (left is CClass && right is CClass) staticComparisonOverride(expression, operation, listOf(left, right)) else null
	}

	fun valueComparison(expression: BinaryExpression, operation: Operators, owner: CMemberType): ResolvedOperator? = staticComparisonOverride(expression, operation, listOf(owner, owner))

	private fun staticComparisonOverride(expression: BinaryExpression, operation: Operators, actual: List<CMemberType>): ResolvedOperator? {
		val declarations = facts.declarations
		val candidates = candidates(operation.toString(), actual).filter { candidate -> candidate.types.zip(actual).all { (expected, type) -> expected.accepts(type) } }
		if (candidates.isEmpty()) return null
		val tier = candidates.filter { it.types == actual }.ifEmpty { candidates }
		val best = mostSpecific(tier, Candidate::types)
		if (best.size != 1) throw SemanticErrors.operatorMatch(expression.operator, expression.span, best.map { it.method }, ambiguous = true)
		val selected = best.single()
		val source = if (selected.receiver == 0) expression.left else expression.right
		if (source in declarations.readOnlyValueReceivers) throw SemanticErrors.parameterDefault("A default cannot call a user-defined operator through a read-only receiver", expression.operator.span)
		return ResolvedOperator(ResolvedCall.Source(selected.method, facts.typeOf(selected.method), ArgumentBinding(listOf(0), emptyList())), selected.receiver)
	}
}
