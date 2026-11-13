package cloth.semantic.flow

import cloth.builtin.BuiltinFunctions
import cloth.builtin.attachments.ArrayMutation
import cloth.builtin.attachments.PrimitiveAttachments
import cloth.data.*
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.OperatorRules
import cloth.semantic.generics.completesTypeArguments
import cloth.semantic.symbols.*
import cloth.semantic.typing.*
import cloth.token.Keywords
import cloth.token.Operators
import java.math.BigInteger
import java.util.*

/** Context-independent presence proofs for evaluated symbol occurrences.
 * Deferred bodies have separate capture entry facts; declarations execute no effects.
 */
internal class NullFlowAnalysis(private val model: FunctionSemanticModel) {

	private sealed interface Segment { data class Field(val name: String) : Segment
		data class Index(val constant: BigInteger?) : Segment
	}

	private data class Place(
		val root: BindingSymbol,
		val path: List<Segment> = emptyList(),
		val narrowed: CMemberType? = null,
		val descendantsOnly: Boolean = false,
	) {

		fun invalidates(proof: Place): Boolean = root == proof.root && path.size <= proof.path.size && (!descendantsOnly || path.size < proof.path.size) && path.indices.all { index ->
			val write = path[index]
			val checked = proof.path[index]
			write == checked || write is Segment.Index && write.constant == null && checked is Segment.Index
		}
	}

	private data class Outcome(val after: Set<Place>, val yes: Set<Place> = after, val no: Set<Place> = after)

	private val presentResults = Collections.newSetFromMap(IdentityHashMap<Expression, Boolean>())
	private val effects = IdentityHashMap<FunctionBlockExpression, Set<Place>>()
	private val hinting = Collections.newSetFromMap(IdentityHashMap<LocalSymbol, Boolean>())
	private val jumpFacts = IdentityHashMap<LoopJumpStatement, Set<Place>>()
	private val methodsByName = (model.declarations.methods.filter { it.declaration.typeParameters == null } + model.declarations.interfaceMethods.values).groupBy { it.name.lexeme }
	private val genericInterfaceMethodNames = model.declarations.interfaces.contracts.values.flatMap { contract ->
		contract.requirements.keys.filter { it.typeParameterCount != 0 }.map { it.valueParameters.name }
	}.toSet()
	private val genericMethodNames = genericInterfaceMethodNames + model.declarations.genericViews?.types?.methodTemplates?.values.orEmpty().flatMap { it.keys }
	private val hasClasses = model.declarations.classDefinitions.isNotEmpty() || model.declarations.interfaces.contracts.isNotEmpty()
	private var classOwner: CClass? = null
	private val flow get() = model.controlFlow
	private fun block(symbol: BindingSymbol?) = (symbol as? LocalSymbol)?.declaration?.initializer as? FunctionBlockExpression
	private fun binding(value: Expression) = (value as? NameExpression)?.let { model.declarations.names[it] as? BindingSymbol }
	private fun bindingPlace(symbol: BindingSymbol): Place = if (symbol is ReceiverFieldSymbol) {
		Place(symbol.receiver, listOf(Segment.Field(symbol.name.lexeme)))
	} else Place(symbol)

	private fun place(value: Expression): Place? = when (value) {
		is NameExpression        -> binding(value)?.takeIf { block(it) == null }?.let(::bindingPlace)
		is FieldAccessExpression -> place(value.receiver)?.let { it.copy(path = it.path + Segment.Field(value.name.lexeme)) }
		is IndexExpression       -> place(value.array)?.let { it.copy(path = it.path + Segment.Index((value.index as? IntegerLiteral)?.value)) }
		else                     -> null
	}

	private fun stable(value: Expression) = place(value)?.takeIf { value !is IndexExpression && it.path.none { segment -> segment is Segment.Index && segment.constant == null } }
	private fun forget(facts: Set<Place>, writes: Collection<Place>) = facts.filterNot { proof -> writes.any { it.invalidates(proof) } }.toSet()
	private fun canAlias(place: Place): Boolean {
		if (!hasClasses || place.path.isEmpty()) return false
		// A struct method can receive a projected class field through its receiver ABI.
		if (place.root is ReceiverSymbol || place.root is StatePayloadSymbol) return true
		var type = model.bindings[place.root]
		for (segment in place.path) {
			val payload = (type as? CNullable)?.payload ?: type
			if (payload == null || payload.classBackedType() != null) return true
			type = when (segment) {
				is Segment.Field -> (payload as? CStruct)?.field(segment.name)?.type
				is Segment.Index -> (payload as? CArray)?.element
			}
		}
		return false
	}

	private fun forgetAliases(facts: Set<Place>) = if (hasClasses) facts.filterNot(::canAlias).toSet() else facts
	private fun forgetWrite(facts: Set<Place>, target: Place?): Set<Place> {
		val remaining = forget(facts, listOfNotNull(target))
		return if (target == null || canAlias(target)) forgetAliases(remaining) else remaining
	}

	private fun common(paths: List<Set<Place>>, fallback: Set<Place>) = paths.reduceOrNull { left, right -> left intersect right } ?: fallback
	private fun knownPresent(value: Expression, needsResult: Boolean = true): Boolean {
		val mayInfer = when (value) {
			is CallExpression       -> canInferCall(value)
			is MemberCallExpression -> canInferMember(value)
			is StateCallExpression  -> true
			else                    -> false
		}
		if (mayInfer) {
			if (!needsResult) return false
			return inferredCallResult(value as CallSite)?.let { !it.mayBeAbsent() && it != ValueType.VOID } == true
		}
		return value in presentResults
	}

	private fun ValueType.mayBeAbsent() = this is CNullable || (this is CTypeParameter && containsNullability)
	private fun nullable(symbol: BindingSymbol) = if (model.declarations.genericViews == null) symbol.declaredType?.hasNullability == true else (when (symbol) {
		is ReceiverSymbol, is ReceiverFieldSymbol -> model.typeOf(symbol)
		else                                      -> model.bindings[symbol] ?: symbol.declaredType?.let { model.declarations.genericViews.resolvedTypes[it] }
	})?.mayBeAbsent() ?: (symbol.declaredType?.hasNullability == true)

	private fun presentAnnotation(type: cloth.parser.type.TypeReference): Boolean {
		val resolved = model.declarations.genericViews?.resolvedTypes?.get(type)
		return if (resolved == null) !type.hasNullability && type.name.lexeme != Keywords.VOID.toString()
		else !resolved.mayBeAbsent() && resolved != ValueType.VOID
	}

	/** Use resolved declaration types when available. Inferred locals can
	 * remain unknown here because this pass precedes overload typing.
	 */
	private fun receiverHint(expression: Expression, unwrapNullable: Boolean = true): ValueType? {
		val declared = when (expression) {
			is StringLiteral, is InterpolatedString -> ValueType.STRING
			is ClassCreationExpression              -> model.declarations.classCreations[expression]?.owner ?: model.declarations.guaranteedCreations[expression]?.first()?.constructedType ?: inferredCallResult(expression)
			is NameExpression                       -> binding(expression)?.let { symbol ->
				when (symbol) {
					is ReceiverSymbol, is ReceiverFieldSymbol -> model.typeOf(symbol)
					else                                      -> model.bindings[symbol] ?: (symbol as? LocalSymbol)?.let(::localHint)
				}
			}

			is FieldAccessExpression                -> model.declarations.enumCases[expression]?.owner ?: (nominalHint(expression.receiver) as? CMemberType)?.field(expression.name.lexeme)?.type
			is IndexExpression                      -> (receiverHint(expression.array) as? CArray)?.element
			is GuardedReceiver                      -> receiverHint(expression.source, unwrapNullable = false)?.let { (it as? CNullable)?.payload ?: it }
			is CallExpression                       -> callHints(expression).distinct().singleOrNull()
			is MemberCallExpression                 -> memberHints(expression).distinct().singleOrNull()
			is StateCallExpression                  -> inferredCallResult(expression)
			else                                    -> null
		}
		if (!unwrapNullable) return declared
		val payload = (declared as? CNullable)?.payload ?: declared
		return (expression as? NameExpression)?.let { model.presence.narrowedType(it, payload) } ?: payload
	}

	private fun canInferCall(call: CallExpression): Boolean {
		val supplied = call.typeArguments
		return when (val group = model.declarations.candidatesOf(call)) {
			is FunctionGroupSymbol        -> supplied?.let(group::completesTypeArguments) ?: group.overloads.any { it.typeParameterCount != 0 }
			is MethodGroupSymbol          -> supplied?.let(group::completesTypeArguments) ?: group.templates.isNotEmpty()
			is InterfaceMethodGroupSymbol -> group.templates.isNotEmpty()
			else                          -> false
		}
	}

	private fun canInferMember(call: MemberCallExpression): Boolean = call.name.lexeme in genericMethodNames && canInferMember(call, nominalHint(call.receiver))

	private fun canInferMember(call: MemberCallExpression, owner: ValueType?): Boolean {
		val name = call.name.lexeme
		if (owner == null) return name in genericMethodNames
		if (name in genericInterfaceMethodNames) {
			val views = model.boundViews(call.receiver, receiverHint(call.receiver)).filterIsInstance<CInterface>()
			if ((listOfNotNull(owner as? CInterface) + views).any { model.declarations.interfaceGroup(it, name)?.templates?.isNotEmpty() == true }) return true
		}
		return (owner is CClass || owner is CStruct) && model.declarations.methodGroup(owner, name)?.let { group ->
			call.typeArguments?.let(group::completesTypeArguments) ?: group.templates.isNotEmpty()
		} == true
	}

	private fun inferredCallResult(call: CallSite): ValueType? {
		model.presence.inferredResultsUsed.add(call)
		return model.inferredResult(call)?.values?.singleOrNull()
	}

	private fun callCandidates(call: CallExpression): List<CallableContractSymbol> {
		if (canInferCall(call)) return emptyList()
		return when (val group = model.declarations.candidatesOf(call)) {
			is FunctionGroupSymbol        -> group.overloads
			is MethodGroupSymbol          -> call.typeArguments?.let { model.genericMethodCandidates(call, it, group, classOwner, report = false) } ?: group.overloads
			is InterfaceMethodGroupSymbol -> group.overloads
			else                          -> emptyList()
		}
	}

	private fun callHints(call: CallExpression): List<ValueType> {
		if (canInferCall(call)) return listOfNotNull(inferredCallResult(call))
		return callCandidates(call).mapNotNull { model.declaredTypeOf(it).results.values.singleOrNull() }
	}

	private fun memberHints(call: MemberCallExpression): List<ValueType> {
		val hint = receiverHint(call.receiver)
		val owner = nominalHint(call.receiver, hint)
		if (canInferMember(call, owner)) return listOfNotNull(inferredCallResult(call))
		if (call.typeArguments != null) return methods(call, hint).mapNotNull { model.declaredTypeOf(it).results.values.singleOrNull() }
		if (owner != null && owner !is CMemberType) return PrimitiveAttachments[owner, call.name.lexeme, model.target]?.overloads.orEmpty().map { it.returns }
		if (owner == null && !nominalReceiver(call.receiver)) return emptyList()
		return methods(call, hint).mapNotNull { model.declaredTypeOf(it).results.values.singleOrNull() }
	}

	private fun localHint(local: LocalSymbol): ValueType? {
		if (!hinting.add(local)) return null
		return try {
			val annotation = local.declaredType
			if (annotation != null) {
				model.declarations.genericViews?.resolvedTypes?.get(annotation)?.let { return it }
				if (annotation.arraySuffixes.isNotEmpty()) return if (annotation.capturesWidth) null else model.types.typeResolver.resolve(annotation)
				if (annotation.name.lexeme == Keywords.STRING.toString()) return if (annotation.hasNullability) CNullable(ValueType.STRING) else ValueType.STRING
				model.declarations.namedType(annotation.name)?.let { return if (annotation.hasNullability) CNullable(it) else it }
			}
			when (val initializer = local.declaration.initializer) {
				is ClassCreationExpression, is NameExpression, is CallExpression, is MemberCallExpression, is StateCallExpression, is StringLiteral, is InterpolatedString -> receiverHint(initializer, unwrapNullable = false)
				else                                                                                                                                                       -> null
			}
		} finally {
			hinting.remove(local)
		}
	}

	private fun nominalReceiver(value: Expression): Boolean {
		if (nominalHint(value) is CMemberType) return true
		val source = (binding(value) as? LocalSymbol)?.declaration?.initializer ?: value
		val hints = when (source) {
			is NameExpression       -> return source !== value && nominalReceiver(source)
			is CallExpression       -> callHints(source)
			is MemberCallExpression -> memberHints(source)
			else                    -> return false
		}
		return hints.isNotEmpty() && hints.all { it.classBackedType() != null || ((it as? CNullable)?.payload ?: it) is CMemberType }
	}

	private fun nominalHint(value: Expression, hint: ValueType? = receiverHint(value)): ValueType? = model.boundViews(value, hint).classBackedType() ?: hint?.classBackedType() ?: hint

	private fun methods(call: MemberCallExpression, hint: ValueType? = receiverHint(call.receiver)): List<CallableContractSymbol> {
		val bounds = model.boundViews(call.receiver, hint)
		val owner = bounds.classBackedType() ?: hint?.classBackedType() ?: hint
		if (canInferMember(call, owner)) return emptyList()
		if (bounds.isNotEmpty()) model.checkedBoundMethod(call, report = false)?.let { return listOf(it.contract) }
		call.typeArguments?.let { supplied ->
			if (owner !is CMemberType) return emptyList()
			val group = model.declarations.methodGroup(owner, call.name.lexeme) ?: return emptyList()
			return model.genericMethodCandidates(call, supplied, group, classOwner, report = false)
		}
		if (bounds.isNotEmpty()) return model.declarations.boundMemberGroups(bounds, call.name.lexeme).flatMap { group ->
			when (group) {
				is MethodGroupSymbol          -> group.overloads
				is InterfaceMethodGroupSymbol -> group.overloads
				else                          -> emptyList()
			}
		}.distinct()
		return when (owner) {
			is CInterface  -> model.declarations.interfaceGroup(owner, call.name.lexeme)?.overloads.orEmpty()
			is CMemberType -> model.declarations.methodGroup(owner, call.name.lexeme)?.overloads.orEmpty()
			null           -> methodsByName[call.name.lexeme].orEmpty()
			else           -> emptyList()
		}
	}

	private fun arrayMutation(call: MemberCallExpression): ArrayMutation? = ArrayMutation.named(call.name.lexeme)?.takeIf {
		if (call.typeArguments != null) return@takeIf false
		val hint = receiverHint(call.receiver)
		hint is CArray || hint == null && !nominalReceiver(call.receiver)
	}

	private fun arrayWrites(call: MemberCallExpression): List<Place> = if (arrayMutation(call)?.changesLength == true) {
		listOfNotNull(place(call.receiver)?.let { it.copy(path = it.path + Segment.Index(null)) })
	} else emptyList()

	private fun operatorMayCall(value: Expression): Boolean {
		val (operator, operands) = when (value) {
			is UnaryExpression  -> value.operator to listOf(value.operand)
			is BinaryExpression -> value.operator to listOf(value.left, value.right)
			else                -> return false
		}
		val comparison = Operators.fromCharacter(operator.lexeme)
		if (comparison in OperatorRules.classComparisons && operands.any { it is NullLiteral }) return false
		if (comparison == Operators.IDENTITY && value is BinaryExpression) {
			val left = nominalHint(value.left)
			val right = nominalHint(value.right)
			if (left is CMemberType && right is CMemberType) model.checkedIdentity(value, left, right)?.let { return it.identityOverride != null }
			if (left is CInterface || right is CInterface) return false
		}
		fun unknownCouldCarryReceiver(operand: Expression) = when (operand) {
			is IntegerLiteral, is FloatingPointLiteral, is BooleanLiteral, is CharacterLiteral, is StringLiteral,
			is ArrayLiteral, is InterpolatedString, is NullLiteral, is TypeCheckExpression,
				                                    -> false

			is UnaryExpression, is BinaryExpression -> operatorMayCall(operand)
			else                                    -> true
		}
		if (comparison == Operators.EQUAL || comparison == Operators.NOT_EQUAL) {
			val declarations = model.declarations
			if (!declarations.hasOperator(comparison.toString()) && !declarations.hasOperator(Operators.EQUAL.toString())) return false
			val arrays = operands.mapNotNull { receiverHint(it).arrayPayload() }
			if (arrays.isNotEmpty()) return arrays.any(declarations::arrayEqualityMayCall)
			// Literal element types may still be unknown during this pass.
			if (operands.any { it is ArrayLiteral } && declarations.hasOperator(Operators.EQUAL.toString())) return true
			if (value is BinaryExpression && (contextMayCall(value.left, value.right, operator.lexeme) || contextMayCall(value.right, value.left, operator.lexeme))) return true
			val hints = operands.map { nominalHint(it) }
			if (!operands.zip(hints).all { (operand, hint) -> hint is CMemberType || hint == null && unknownCouldCarryReceiver(operand) }) return false
			val owner = hints.filterIsInstance<CMemberType>().firstOrNull() ?: return true
			if (owner.classBackedType() != null) return true
			if (hints.any { it != null && it != owner }) return false
			return listOf(comparison, Operators.EQUAL).any { operation ->
				declarations.operators(owner, operation.toString(), right = false, arity = 1).isNotEmpty() || declarations.operators(owner, operation.toString(), right = true, arity = 1).isNotEmpty()
			}
		}
		if (!model.declarations.hasOperator(operator.lexeme)) return false
		if (value is BinaryExpression && (contextMayCall(value.left, value.right, operator.lexeme) || contextMayCall(value.right, value.left, operator.lexeme))) return true
		return operands.withIndex().any { (index, operand) ->
			when (val hint = if (comparison == Operators.IDENTITY) nominalHint(operand) else receiverHint(operand)) {
				is CMemberType -> model.declarations.operators(hint, operator.lexeme, index == 1, operands.size - 1).isNotEmpty()
				null           -> unknownCouldCarryReceiver(operand)
				else           -> false
			}
		}
	}

	private fun contextMayCall(value: Expression, other: Expression, operator: String): Boolean {
		if (!OperatorRules.supportsContext(Operators.fromCharacter(operator))) return false
		val declarations = model.declarations
		if (Operators.fromCharacter(operator) in OperatorRules.equality) {
			if (value is NullLiteral || other is NullLiteral) return false
			val left = receiverHint(value)
			val right = receiverHint(other)
			if (left != null && left == right || left?.classBackedType() != null && right?.classBackedType() != null) return false
		}
		val names = if (operator == Operators.NOT_EQUAL.toString()) setOf(operator, Operators.EQUAL.toString()) else setOf(operator)
		val candidates = when (val hint = receiverHint(value)) {
			is CMemberType -> names.flatMap { declarations.contextOperators(hint, it) }
			null           -> when (value) {
				is NumberLiteral, is BooleanLiteral, is CharacterLiteral, is StringLiteral, is InterpolatedString,
				is NullLiteral, is ArrayLiteral, is TypeCheckExpression,
					                                    -> return false

				is UnaryExpression, is BinaryExpression -> if (!operatorMayCall(value)) return false else null
				else                                    -> null
			}

			else           -> return false
		} ?: declarations.methods.filter { it.name.lexeme in names && it.declaration.operator?.selector != null }
		val peer = receiverHint(other, unwrapNullable = false)
		return candidates.any { method ->
			val selector = model.contextSelectorType(method)
			fun matches(type: ValueType) = selector?.accepts(type) ?: (OperatorRules.inferredSelectorWidth(method.declaration.operator?.selector, type) != null)
			peer == null || matches(peer) || peer is CNullable && matches(peer.payload)
		}
	}

	/** A statically applicable full overload keeps the left context from running
	 * before the right expression. Unknown contracts retain conservative effects.
	 */
	private fun knownFullOperator(value: BinaryExpression): Boolean {
		val left = receiverHint(value.left, unwrapNullable = false) ?: return false
		val right = receiverHint(value.right, unwrapNullable = false) ?: return false
		fun matches(receiver: ValueType, operand: ValueType, onRight: Boolean): Boolean = receiver is CMemberType && model.declarations.operators(receiver, value.operator.lexeme, onRight, 1).any {
			model.declaredTypeOf(it).parameters.single().accepts(operand)
		}
		return matches(left, right, false) || matches(right, left, true)
	}

	private fun formattingMayCall(value: Expression): Boolean {
		val provider = CompilerDataProviders.STR.toString()
		if (!model.declarations.hasOperator(provider)) return false
		val hint = receiverHint(value)
		if (hint != null) return model.declarations.formattingMayCallUserCode(hint)
		return when (value) {
			is IntegerLiteral, is FloatingPointLiteral, is BooleanLiteral, is CharacterLiteral, is StringLiteral,
			is NullLiteral, is InterpolatedString, is TypeCheckExpression,
				            -> false

			is ArrayLiteral -> value.elements.any(::formattingMayCall)
			else            -> true
		}
	}

	private fun methodWrites(receiver: Place?, candidates: List<CallableContractSymbol>, mayUseGenericContract: Boolean): List<Place> {
		if (receiver == null) return emptyList()
		if (mayUseGenericContract && candidates.isEmpty()) {
			// A generic signature may depend on typing the receiver or a captured width.
			return listOf(receiver.copy(descendantsOnly = true))
		}
		// A fixed receiver can change its fields, not its own presence.
		return candidates.mapNotNull { it.receiver?.owner }.distinct().flatMap { owner ->
			owner.fields.map { field -> receiver.copy(path = receiver.path + Segment.Field(field.name.lexeme)) }
		}
	}

	private fun implicitMethodWrites(call: CallExpression): List<Place> {
		val self = model.declarations.implicitReceivers[call] ?: return emptyList()
		return methodWrites(bindingPlace(self), callCandidates(call), call.typeArguments != null || canInferCall(call))
	}

	private fun presentReturns(candidates: List<CallableContractSymbol>) = candidates.all {
		it.declaration.returnTypes.singleOrNull()?.let(::presentAnnotation) == true
	}

	fun analyze(function: CallableSignature) {
		classOwner = model.declarations.classOwners[function]
		function.parameters.forEach { it.defaultValue?.let { value -> expression(value, emptySet()) } }
		when (val body = function.executableBody) {
			is BlockBody           -> statements(body.statements, emptySet())
			is ExpressionBody      -> expression(body.expression, emptySet())
			null, is IntrinsicBody -> Unit
		}
	}

	fun analyzeExpressions(expressions: List<Expression>) {
		expressions.fold(emptySet<Place>()) { facts, value -> expression(value, facts).after }
	}

	private fun statements(body: List<Statement>, initial: Set<Place>): Set<Place> {
		var facts = initial
		for (item in body) facts = when (item) {
			is DoWhileStatement           -> doWhileLoop(item, facts)
			is WhileStatement             -> whileLoop(item, facts)
			is LoopJumpStatement          -> facts.also { jumpFacts[item] = it }
			is ForStatement               -> loop(item, facts)
			is BindingDeclaration         -> {
				val deferred = item.initializer as? FunctionBlockExpression
				if (deferred != null) {
					statements((deferred.declaration.body as BlockBody).statements, facts.filter { it.path.isEmpty() && !it.root.mutable }.toSet())
					facts
				} else {
					val after = expression(item.initializer, facts).after
					val symbol = model.declarations.symbolOf(item)
					val target = Place(symbol)
					val remaining = forget(after, listOf(target))
					if (knownPresent(item.initializer, needsResult = nullable(symbol))) remaining + target else remaining
				}
			}

			is BindingGroupDeclaration    -> {
				var after = expression(item.initializer, facts).after
				val results = (item.initializer as? ResultListExpression)?.values
				model.declarations.symbolsOf(item).forEachIndexed { index, symbol ->
					if (symbol != null) {
						val target = Place(symbol)
						after = forget(after, listOf(target))
						if (knownPresent(results?.getOrNull(index) ?: item.initializer, needsResult = nullable(symbol))) after += target
					}
				}
				after
			}

			is AssignmentStatement        -> {
				val after = expression(item.value, facts).after
				val binding = model.declarations.targetOf(item)
				val target = bindingPlace(binding)
				val remaining = forgetWrite(after, target)
				if (binding !is ReceiverFieldSymbol && knownPresent(item.value, needsResult = nullable(binding))) remaining + target else remaining
			}

			is MultiAssignmentStatement   -> {
				var after = expression(item.value, facts).after
				val results = (item.value as? ResultListExpression)?.values
				model.declarations.targetsOf(item).forEachIndexed { index, symbol ->
					if (symbol != null) {
						val target = bindingPlace(symbol)
						after = forgetWrite(after, target)
						if (symbol !is ReceiverFieldSymbol && knownPresent(results?.getOrNull(index) ?: item.value, needsResult = nullable(symbol))) after += target
					}
				}
				after
			}

			is ElementAssignmentStatement -> write(item.target, item.compound, item.value, facts)
			is FieldWriteStatement        -> write(item.target, item.compound, item.value, facts)

			is UpdateStatement            -> {
				val binding = model.declarations.targetOf(item)
				val target = bindingPlace(binding)
				if (target in facts) model.presence.updates.add(item)
				if (binding is ReceiverFieldSymbol) forgetWrite(facts, target) else facts
			}

			is ExpressionStatement        -> expression(item.expression, facts).after
			is ReturnStatement            -> item.value?.let { expression(it, facts).after } ?: facts
			else                          -> facts
		}
		return facts
	}

	private fun write(target: Expression, compound: BinaryExpression?, value: Expression?, facts: Set<Place>): Set<Place> {
		val afterTarget = expression(target, facts).after
		val afterValue = value?.let { expression(it, afterTarget).after } ?: afterTarget
		val afterOperation = if (compound != null && operatorMayCall(compound)) forgetAliases(afterValue) else afterValue
		return forgetWrite(afterOperation, place(target))
	}

	private fun loop(item: ForStatement, initial: Set<Place>): Set<Place> {
		val entry = when (val source = item.source) {
			is ForSource.Range    -> expression(source.end, expression(source.start, initial).after).after
			is ForSource.Elements -> expression(source.expression, initial).after
		}
		val repeated = item.body.statements + listOfNotNull(item.stepper?.value?.let { ExpressionStatement(it) })
		val changed = writes(repeated) + Place(model.declarations.symbolOf(item))
		val stableEntry = forgetAliases(forget(entry, changed))
		// Invalidate before publishing occurrence facts: a fact true on entry may
		// already be false on the second trip around this same source occurrence.
		val afterBody = statements(item.body.statements, stableEntry)
		val completion = flow.of(item.body.statements)
		val updates = completion.continues.map { jumpFacts.getValue(it) }.toMutableList()
		if (completion.canFallThrough) updates.add(afterBody)
		item.stepper?.value?.let { expression(it, common(updates, stableEntry)) }
		// Include the zero-trip path and conservatively forget repeated writes.
		return stableEntry
	}

	private fun doWhileLoop(item: DoWhileStatement, initial: Set<Place>): Set<Place> {
		val repeated = item.body.statements + ExpressionStatement(item.condition)
		val entry = if (flow.canRepeat(item)) forgetAliases(forget(initial, writes(repeated))) else initial
		// No test precedes first entry. Invalidate writes only if this body can repeat.
		val afterBody = statements(item.body.statements, entry)
		val completion = flow.of(item.body.statements)
		val inputs = completion.continues.map { jumpFacts.getValue(it) }.toMutableList()
		if (completion.canFallThrough) inputs.add(afterBody)
		// Still visit an unreachable test for ordinary source checking, but its
		// facts cannot enter the loop's runtime exits or an enclosing loop join.
		val condition = expression(item.condition, common(inputs, entry))
		val exits = completion.breaks.map { jumpFacts.getValue(it) }.toMutableList()
		if (inputs.isNotEmpty() && flow.of(item.condition).canFallThrough && flow.booleanValue(item.condition) != true) exits.add(condition.no)
		return common(exits, entry)
	}

	private fun whileLoop(item: WhileStatement, initial: Set<Place>): Set<Place> {
		// The same condition occurrence runs after body/continue writes too.
		val changed = writes(listOf(ExpressionStatement(item.condition)) + item.body.statements)
		val entry = forgetAliases(forget(initial, changed))
		val condition = expression(item.condition, entry)
		statements(item.body.statements, condition.yes)
		val exits = mutableListOf<Set<Place>>()
		if (flow.booleanValue(item.condition) != true && flow.of(item.condition).canFallThrough) exits.add(condition.no)
		if (flow.booleanValue(item.condition) != false && flow.of(item.condition).canFallThrough) {
			flow.of(item.body.statements).breaks.forEach { exits.add(jumpFacts.getValue(it)) }
		}
		return common(exits, entry)
	}

	private fun expression(value: Expression, initial: Set<Place>, sourceType: Boolean = false): Outcome {
		val result = when (value) {
			is NameExpression                                          -> {
				val symbol = binding(value)
				val deferred = block(symbol)
				if (deferred != null) {
					if (deferred.declaration.returnType?.let(::presentAnnotation) == true) presentResults.add(value)
					Outcome(forgetAliases(forget(initial, writes(deferred))))
				} else {
					if (symbol != null) {
						val proven = bindingPlace(symbol) in initial
						if (!sourceType && proven && nullable(symbol)) model.presence.names.add(value)
						if (!sourceType) {
							val proofs = initial.filter { it.root == symbol && it.path.isEmpty() }.mapNotNull { it.narrowed }
							if (proofs.isNotEmpty()) model.presence.narrowedNames[value] = proofs
						}
						if (!nullable(symbol) || proven) presentResults.add(value)
					}
					Outcome(initial)
				}
			}

			is FieldAccessExpression                                   -> {
				val after = expression(value.receiver, initial).after
				if (stable(value) in after) {
					if (!sourceType) model.presence.fields.add(value)
					presentResults.add(value)
				}
				Outcome(after)
			}

			is NullLiteral, is FunctionBlockExpression                 -> Outcome(initial)
			is UnaryExpression                                         -> expression(value.operand, initial).let { if (value.operator.lexeme == Operators.NOT.toString()) Outcome(it.after, it.no, it.yes) else it }
			is BinaryExpression                                        -> {
				val op = value.operator.lexeme
				val test = Operators.fromCharacter(op) in OperatorRules.classComparisons && (value.left is NullLiteral || value.right is NullLiteral)
				val left = expression(value.left, initial, sourceType = test)
				if (op == Operators.AND.toString() || op == Operators.OR.toString()) {
					val and = op == Operators.AND.toString()
					val right = expression(value.right, if (and) left.yes else left.no)
					val skip = if (and) left.no else left.yes
					val known = flow.booleanValue(value.left)
					val after = when {
						known == !and                        -> left.after
						known == and                         -> right.after
						!flow.of(value.right).canFallThrough -> skip
						else                                 -> skip intersect right.after
					}
					val yes = if (and) right.yes else when (known) {
						true -> left.yes; false -> right.yes; null -> left.yes intersect right.yes
					}
					val no = if (!and) right.no else when (known) {
						true -> right.no; false -> left.no; null -> left.no intersect right.no
					}
					Outcome(after, yes, no)
				} else {
					val beforeRight = if (contextMayCall(value.left, value.right, op) && !knownFullOperator(value)) forgetAliases(left.after) else left.after
					val after = expression(value.right, beforeRight, sourceType = test).after
					val symbol = if (test) stable(if (value.left is NullLiteral) value.right else value.left) else null
					if (symbol == null) Outcome(after) else {
						val absent = forget(after, listOf(symbol))
						if (op == Operators.NOT_EQUAL.toString()) Outcome(after, after + symbol, absent) else Outcome(after, absent, after + symbol)
					}
				}
			}

			is ElvisExpression                                         -> {
				val left = expression(value.left, initial, sourceType = true)
				val symbol = stable(value.left)
				val present = if (symbol == null) left.after else left.after + symbol
				val absent = if (symbol == null) left.after else forget(left.after, listOf(symbol))
				val right = expression(value.fallback, absent)
				val after = when {
					value.left is NullLiteral               -> right.after
					knownPresent(value.left)                -> present
					!flow.of(value.fallback).canFallThrough -> present
					else                                    -> present intersect right.after
				}
				if (knownPresent(value.left) || knownPresent(value.fallback)) presentResults.add(value)
				Outcome(after)
			}

			is GuardedAccessExpression                                 -> {
				val receiver = expression(value.receiver, initial, sourceType = true)
				val subject = stable(value.receiver)
				val present = receiver.after + listOfNotNull(subject)
				val absent = forget(receiver.after, listOfNotNull(subject))
				val accessed = expression(value.continuation, present)
				Outcome(when {
					!flow.of(value.receiver).canFallThrough     -> receiver.after
					!flow.of(value.continuation).canFallThrough -> absent
					knownPresent(value.receiver)                -> accessed.after
					else                                        -> absent intersect accessed.after
				})
			}

			is IfExpression                                            -> {
				var remaining = initial
				var reachable = true
				val continuing = mutableListOf<Set<Place>>()
				for (branch in value.branches) {
					val condition = expression(branch.condition, remaining)
					val after = statements(branch.statements, condition.yes)
					val known = flow.booleanValue(branch.condition)
					if (reachable && flow.of(branch.condition).canFallThrough && known != false && flow.of(branch.statements).canFallThrough) continuing.add(after)
					reachable = reachable && flow.of(branch.condition).canFallThrough && known != true
					remaining = condition.no
				}
				val afterElse = value.otherwise?.let { statements(it, remaining) } ?: remaining
				if (reachable && (value.otherwise?.let { flow.of(it).canFallThrough } != false)) continuing.add(afterElse)
				Outcome(common(continuing, initial))
			}

			is ProviderAccessExpression                                -> {
				val annotation = stable(value.receiver)?.takeIf { it.path.isEmpty() }?.root?.declaredType
				val scalarSize = value.name.lexeme == CompilerDataProviders.SIZE.toString() && annotation?.hasNullability == true && annotation.arraySuffixes.isEmpty() && annotation.name.lexeme != Keywords.STRING.toString()
				val outcome = expression(value.receiver, initial, sourceType = value.name.lexeme == CompilerDataProviders.TYPE.toString() || scalarSize)
				if (value.name.lexeme == CompilerDataProviders.STR.toString() && formattingMayCall(value.receiver)) Outcome(forgetAliases(outcome.after)) else outcome
			}

			is TypeReceiverExpression                                  -> Outcome(initial)
			is CastExpression                                          -> {
				val operand = expression(value.operand, initial)
				if (model.declarations.unboundStateName(value.target) == null) operand else Outcome(forgetAliases(operand.after))
			}

			is TypeCheckExpression                                     -> {
				model.declarations.valueTargetOf(value)?.let { return expression(it, initial) }
				val after = expression(value.operand, initial).after
				if (model.declarations.unboundStateName(value.target) != null) return Outcome(after)
				val subject = stable(value.operand)
				val target = value.target.takeIf { !it.hasNullability && it.arraySuffixes.isEmpty() }?.let {
					val resolved = if (model.declarations.genericViews != null) model.types.typeResolver.resolve(it) else model.declarations.namedType(it.name)
					(resolved as? CMemberType)?.classBackedType()
				}
				if (subject == null || target == null) Outcome(after)
				else {
					// Only bindings receive a new nominal type. Stable field paths can prove presence.
					val types = if (subject.path.isNotEmpty()) emptySet() else if (target is CInterface) target.ancestors + target else setOf(target)
					Outcome(after, after + subject + types.map { subject.copy(narrowed = it) }, after)
				}
			}

			is CallExpression                                          -> {
				val after = value.arguments.fold(initial) { facts, arg -> expression(arg, facts).after }
				val remaining = forget(after, implicitMethodWrites(value))
				val group = model.declarations.candidatesOf(value)
				val formats = group is BuiltinGroupSymbol && (group.definition === BuiltinFunctions.PRINT || group.definition === BuiltinFunctions.PRINTLN)
				Outcome(if (group is BuiltinGroupSymbol && !(formats && value.arguments.any(::formattingMayCall))) remaining else forgetAliases(remaining))
			}

			is MemberCallExpression                                    -> {
				val before = expression(value.receiver, initial).after
				val after = value.arguments.fold(before) { facts, arg -> expression(arg, facts).after }
				val candidates = methods(value)
				val mutation = arrayMutation(value)
				val mayUseGenericContract = value.typeArguments != null || canInferMember(value)
				val mayCall = candidates.isNotEmpty() || mayUseGenericContract
				if ((mayCall || mutation != null) && before != after) {
					val path = generateSequence(value.receiver) {
						when (it) {
							is FieldAccessExpression -> it.receiver
							is IndexExpression       -> it.array
							else                     -> null
						}
					}.toList().asReversed()
					val lost = path.filter { part -> stable(part)?.let { it in before && it !in after } == true }
					if (lost.isNotEmpty()) model.presence.lostReceiverProofs[value] = lost
				}
				val remaining = forget(after, methodWrites(place(value.receiver), candidates, mayUseGenericContract) + arrayWrites(value))
				val aliases = mutation?.changesLength == true && (place(value.receiver)?.let(::canAlias) != false)
				Outcome(when {
					mayCall -> forgetAliases(remaining)
					aliases -> remaining.filterNot { canAlias(it) && it.path.any { segment -> segment is Segment.Index } }.toSet()
					else    -> remaining
				})
			}

			is ClassCreationExpression                                 -> Outcome(forgetAliases(value.eagerOperands().fold(initial) { facts, arg -> expression(arg, facts).after }))
			is ClassDeletionExpression                                 -> Outcome(forgetAliases(expression(value.receiver, initial).after))
			is StateCallExpression, is StateTransitionExpression       -> Outcome(forgetAliases(value.eagerOperands().fold(initial) { facts, arg -> expression(arg, facts).after }))
			is ResultListExpression, is ArrayLiteral, is StructLiteral -> Outcome(value.eagerOperands().fold(initial) { facts, item -> expression(item, facts).after })
			is IndexExpression                                         -> expression(value.index, expression(value.array, initial).after)
			is InterpolatedString                                      -> Outcome(value.parts.fold(initial) { facts, part ->
				val after = expression(part, facts).after
				if (formattingMayCall(part)) forgetAliases(after) else after
			})

			else                                                       -> Outcome(initial)
		}
		if (operatorMayCall(value)) return Outcome(forgetAliases(result.after))
		when (value) {
			is ClassCreationExpression                                                                                                                                                                                                                                   -> presentResults.add(value)
			is IntegerLiteral, is FloatingPointLiteral, is BooleanLiteral, is CharacterLiteral, is StringLiteral, is ArrayLiteral, is StructLiteral, is InterpolatedString, is BinaryExpression, is UnaryExpression, is ProviderAccessExpression, is TypeCheckExpression -> presentResults.add(value)
			is CastExpression                                                                                                                                                                                                                                            -> if (model.declarations.unboundStateName(value.target) == null && presentAnnotation(value.target)) presentResults.add(value)
			is CallExpression                                                                                                                                                                                                                                            -> {
				val candidates = callCandidates(value)
				if (candidates.isNotEmpty() && presentReturns(candidates)) presentResults.add(value)
			}

			is MemberCallExpression                                                                                                                                                                                                                                      -> {
				val hint = receiverHint(value.receiver)
				if (canInferMember(value, nominalHint(value.receiver, hint))) return result
				if (arrayMutation(value) != null) {
					if (ArrayMutation.named(value.name.lexeme) == ArrayMutation.REMOVE_AT && hint is CArray && !hint.element.mayBeAbsent()) presentResults.add(value)
					return result
				}
				val sources = methods(value, hint)
				val primitives = if (hint is CMemberType || nominalReceiver(value.receiver)) emptyList() else PrimitiveAttachments.all(model.target).filter { it.definition.name == value.name.lexeme }
				if ((sources.isNotEmpty() || primitives.isNotEmpty()) && presentReturns(sources) && primitives.all { member -> member.overloads.all { it.returns !is CNullable && it.returns != ValueType.VOID } }) presentResults.add(value)
			}

			else                                                                                                                                                                                                                                                         -> Unit
		}
		return result
	}

	private fun writes(block: FunctionBlockExpression): Set<Place> = effects.getOrPut(block) {
		writes((block.declaration.body as BlockBody).statements)
	}

	private fun writes(body: List<Statement>): Set<Place> {
		val result = mutableSetOf<Place>()
		fun visit(value: Expression) {
			when (value) {
				is NameExpression                                    -> this.block(binding(value))?.let { result.addAll(writes(it)) }
				is FunctionBlockExpression                           -> Unit
				is BinaryExpression                                  -> {
					visit(value.left); visit(value.right)
				}

				is ElvisExpression                                   -> {
					visit(value.left); visit(value.fallback)
				}

				is GuardedAccessExpression                           -> {
					visit(value.receiver); visit(value.continuation)
				}

				is UnaryExpression                                   -> visit(value.operand)
				is CastExpression                                    -> visit(value.operand)
				is TypeCheckExpression                               -> visit(model.declarations.valueTargetOf(value) ?: value.operand)
				is ProviderAccessExpression                          -> visit(value.receiver)
				is CallExpression                                    -> {
					value.arguments.forEach(::visit)
					result.addAll(implicitMethodWrites(value))
				}

				is MemberCallExpression                              -> {
					visit(value.receiver); value.arguments.forEach(::visit)
					val mayUseGenericContract = value.typeArguments != null || canInferMember(value)
					result.addAll(methodWrites(place(value.receiver), methods(value), mayUseGenericContract) + arrayWrites(value))
				}

				is ClassCreationExpression                           -> value.arguments.forEach(::visit)
				is ClassDeletionExpression                           -> visit(value.receiver)
				is StateCallExpression, is StateTransitionExpression -> value.eagerOperands().forEach(::visit)

				is ResultListExpression                              -> value.values.forEach(::visit)
				is ArrayLiteral                                      -> value.elements.forEach(::visit)
				is StructLiteral                                     -> value.fields.forEach(::visit)
				is FieldAccessExpression                             -> visit(value.receiver)
				is IndexExpression                                   -> {
					visit(value.array); visit(value.index)
				}

				is InterpolatedString                                -> value.parts.forEach(::visit)
				is IfExpression                                      -> {
					value.branches.forEach { visit(it.condition); result.addAll(writtenStatements(it.statements, ::visit)) }
					value.otherwise?.let { result.addAll(writtenStatements(it, ::visit)) }
				}

				else                                                 -> Unit
			}
		}
		result.addAll(writtenStatements(body, ::visit))
		return result
	}

	private fun writtenStatements(body: List<Statement>, visit: (Expression) -> Unit): Set<Place> = buildSet {
		for (item in body) when (item) {
			is DoWhileStatement           -> {
				addAll(writtenStatements(item.body.statements, visit))
				visit(item.condition)
			}

			is WhileStatement             -> {
				visit(item.condition)
				addAll(writtenStatements(item.body.statements, visit))
			}

			is ForStatement               -> {
				when (val source = item.source) {
					is ForSource.Range    -> {
						visit(source.start); visit(source.end)
					}

					is ForSource.Elements -> visit(source.expression)
				}
				add(Place(model.declarations.symbolOf(item)))
				addAll(writtenStatements(item.body.statements, visit))
				item.stepper?.value?.let(visit)
			}

			is AssignmentStatement        -> {
				add(bindingPlace(model.declarations.targetOf(item))); visit(item.value)
			}

			is MultiAssignmentStatement   -> {
				addAll(model.declarations.targetsOf(item).filterNotNull().map(::bindingPlace)); visit(item.value)
			}

			is UpdateStatement            -> add(bindingPlace(model.declarations.targetOf(item)))
			is BindingDeclaration         -> if (item.initializer !is FunctionBlockExpression) visit(item.initializer)
			is BindingGroupDeclaration    -> visit(item.initializer)
			is ElementAssignmentStatement -> {
				place(item.target)?.let(::add)
				visit(item.target); item.value?.let(visit)
			}

			is FieldWriteStatement        -> {
				place(item.target)?.let(::add)
				visit(item.target); item.value?.let(visit)
			}

			is ExpressionStatement        -> visit(item.expression)
			is ReturnStatement            -> item.value?.let(visit)
			else                          -> Unit
		}
	}
}
