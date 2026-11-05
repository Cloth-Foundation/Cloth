package cloth.semantic

import cloth.builtin.attachments.PrimitiveAttachments
import cloth.data.*
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.CallableDeclaration
import cloth.parser.declaration.ExpressionBody
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.symbols.*
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

	private data class Place(val root: BindingSymbol, val path: List<Segment> = emptyList()) {

		fun invalidates(proof: Place): Boolean = root == proof.root && path.size <= proof.path.size && path.indices.all { index ->
			val write = path[index]
			val checked = proof.path[index]
			write == checked || write is Segment.Index && write.constant == null && checked is Segment.Index
		}
	}

	private data class Outcome(val after: Set<Place>, val yes: Set<Place> = after, val no: Set<Place> = after)

	private val presentResults = Collections.newSetFromMap(IdentityHashMap<Expression, Boolean>())
	private val effects = IdentityHashMap<FunctionBlockExpression, Set<Place>>()
	private val jumpFacts = IdentityHashMap<LoopJumpStatement, Set<Place>>()
	private val methodsByName = model.declarations.methods.groupBy { it.name.lexeme }
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
	private fun common(paths: List<Set<Place>>, fallback: Set<Place>) = paths.reduceOrNull { left, right -> left intersect right } ?: fallback
	private fun knownPresent(value: Expression) = value in presentResults
	private fun nullable(symbol: BindingSymbol) = symbol.declaredType?.hasNullability == true

	/** Use resolved declaration types when available. Inferred locals can
	 * remain unknown here because this pass precedes overload typing.
	 */
	private fun receiverHint(expression: Expression): ValueType? {
		val declared = when (expression) {
			is NameExpression        -> binding(expression)?.let { symbol ->
				when (symbol) {
					is ReceiverSymbol, is ReceiverFieldSymbol -> model.typeOf(symbol)
					else                                      -> model.bindings[symbol]
				}
			}

			is FieldAccessExpression -> (receiverHint(expression.receiver) as? CStruct)?.field(expression.name.lexeme)?.type
			is IndexExpression       -> (receiverHint(expression.array) as? CArray)?.element
			else                     -> null
		}
		return (declared as? CNullable)?.payload ?: declared
	}

	private fun methods(call: MemberCallExpression, hint: ValueType? = receiverHint(call.receiver)): List<MethodSymbol> = when (hint) {
		is CStruct -> model.declarations.methodGroup(hint, call.name.lexeme)?.overloads.orEmpty()
		null       -> methodsByName[call.name.lexeme].orEmpty()
		else       -> emptyList()
	}

	private fun methodWrites(receiver: Place?, candidates: List<MethodSymbol>): List<Place> {
		if (receiver == null) return emptyList()
		// A fixed receiver can change its fields, not its own presence.
		return candidates.map { it.owner }.distinct().flatMap { owner ->
			owner.fields.map { field -> receiver.copy(path = receiver.path + Segment.Field(field.declaration.name.lexeme)) }
		}
	}

	private fun implicitMethodWrites(call: CallExpression): List<Place> {
		val self = model.declarations.implicitReceivers[call] ?: return emptyList()
		val group = model.declarations.candidatesOf(call) as MethodGroupSymbol
		return methodWrites(bindingPlace(self), group.overloads)
	}

	private fun presentReturns(candidates: List<CallableSymbol>) = candidates.all {
		it.declaration.returnTypes.singleOrNull()?.let { type -> !type.hasNullability && type.name.lexeme != Keywords.VOID.toString() } == true
	}

	fun analyze(function: CallableDeclaration) {
		function.parameters.forEach { it.defaultValue?.let { value -> expression(value, emptySet()) } }
		when (val body = function.body) {
			is BlockBody      -> statements(body.statements, emptySet())
			is ExpressionBody -> expression(body.expression, emptySet())
		}
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
					val target = Place(model.declarations.symbolOf(item))
					val remaining = forget(after, listOf(target))
					if (knownPresent(item.initializer)) remaining + target else remaining
				}
			}

			is BindingGroupDeclaration    -> {
				var after = expression(item.initializer, facts).after
				val results = (item.initializer as? ResultListExpression)?.values
				model.declarations.symbolsOf(item).forEachIndexed { index, symbol ->
					if (symbol != null) {
						val target = Place(symbol)
						after = forget(after, listOf(target))
						if (knownPresent(results?.getOrNull(index) ?: item.initializer)) after += target
					}
				}
				after
			}

			is AssignmentStatement        -> {
				val after = expression(item.value, facts).after
				val binding = model.declarations.targetOf(item)
				val target = bindingPlace(binding)
				val remaining = forget(after, listOf(target))
				if (binding !is ReceiverFieldSymbol && knownPresent(item.value)) remaining + target else remaining
			}

			is MultiAssignmentStatement   -> {
				var after = expression(item.value, facts).after
				val results = (item.value as? ResultListExpression)?.values
				model.declarations.targetsOf(item).forEachIndexed { index, symbol ->
					if (symbol != null) {
						val target = bindingPlace(symbol)
						after = forget(after, listOf(target))
						if (symbol !is ReceiverFieldSymbol && knownPresent(results?.getOrNull(index) ?: item.value)) after += target
					}
				}
				after
			}

			is ElementAssignmentStatement -> forget(expression(item.value, expression(item.target, facts).after).after, listOfNotNull(place(item.target)))
			is FieldWriteStatement        -> {
				val afterTarget = expression(item.target, facts).after
				val after = item.value?.let { expression(it, afterTarget).after } ?: afterTarget
				forget(after, listOfNotNull(place(item.target)))
			}

			is UpdateStatement            -> {
				val binding = model.declarations.targetOf(item)
				val target = bindingPlace(binding)
				if (target in facts) model.presentUpdates.add(item)
				if (binding is ReceiverFieldSymbol) forget(facts, listOf(target)) else facts
			}

			is ExpressionStatement        -> expression(item.expression, facts).after
			is ReturnStatement            -> item.value?.let { expression(it, facts).after } ?: facts
			else                          -> facts
		}
		return facts
	}

	private fun loop(item: ForStatement, initial: Set<Place>): Set<Place> {
		val entry = when (val source = item.source) {
			is ForSource.Range    -> expression(source.end, expression(source.start, initial).after).after
			is ForSource.Elements -> expression(source.expression, initial).after
		}
		val repeated = item.body.statements + listOfNotNull(item.stepper?.value?.let { ExpressionStatement(it) })
		val changed = writes(repeated) + Place(model.declarations.symbolOf(item))
		val stableEntry = forget(entry, changed)
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
		val entry = if (flow.canRepeat(item)) forget(initial, writes(repeated)) else initial
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
		val entry = forget(initial, changed)
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
			is NameExpression                          -> {
				val symbol = binding(value)
				val deferred = block(symbol)
				if (deferred != null) {
					if (deferred.declaration.returnType?.let { !it.hasNullability && it.name.lexeme != Keywords.VOID.toString() } == true) presentResults.add(value)
					Outcome(forget(initial, writes(deferred)))
				} else {
					if (symbol != null) {
						val proven = bindingPlace(symbol) in initial
						if (!sourceType && proven && nullable(symbol)) model.presentNames.add(value)
						if (!nullable(symbol) || proven) presentResults.add(value)
					}
					Outcome(initial)
				}
			}

			is FieldAccessExpression                   -> {
				val after = expression(value.receiver, initial).after
				if (stable(value) in after) {
					if (!sourceType) model.presentFields.add(value)
					presentResults.add(value)
				}
				Outcome(after)
			}

			is NullLiteral, is FunctionBlockExpression -> Outcome(initial)
			is UnaryExpression                         -> expression(value.operand, initial).let { if (value.operator.lexeme == Operators.NOT.toString()) Outcome(it.after, it.no, it.yes) else it }
			is BinaryExpression                        -> {
				val op = value.operator.lexeme
				val test = op in setOf(Operators.EQUAL.toString(), Operators.NOT_EQUAL.toString()) && (value.left is NullLiteral || value.right is NullLiteral)
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
					val after = expression(value.right, left.after, sourceType = test).after
					val symbol = if (test) stable(if (value.left is NullLiteral) value.right else value.left) else null
					if (symbol == null) Outcome(after) else {
						val absent = forget(after, listOf(symbol))
						if (op == Operators.NOT_EQUAL.toString()) Outcome(after, after + symbol, absent) else Outcome(after, absent, after + symbol)
					}
				}
			}

			is ElvisExpression                         -> {
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

			is IfExpression                            -> {
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

			is ProviderAccessExpression                -> {
				val annotation = stable(value.receiver)?.takeIf { it.path.isEmpty() }?.root?.declaredType
				val scalarSize = value.name.lexeme == CompilerDataProviders.SIZE.toString() && annotation?.hasNullability == true && annotation.arraySuffixes.isEmpty() && annotation.name.lexeme != Keywords.STRING.toString()
				expression(value.receiver, initial, sourceType = value.name.lexeme == CompilerDataProviders.TYPE.toString() || scalarSize)
			}

			is TypeReceiverExpression                  -> Outcome(initial)
			is CastExpression                          -> expression(value.operand, initial)
			is TypeCheckExpression                     -> expression(value.operand, initial)
			is CallExpression                          -> {
				val after = value.arguments.fold(initial) { facts, arg -> expression(arg, facts).after }
				Outcome(forget(after, implicitMethodWrites(value)))
			}

			is MemberCallExpression                    -> {
				val before = expression(value.receiver, initial).after
				val after = value.arguments.fold(before) { facts, arg -> expression(arg, facts).after }
				val candidates = methods(value)
				if (candidates.isNotEmpty() && before != after) {
					val path = generateSequence(value.receiver) {
						when (it) {
							is FieldAccessExpression -> it.receiver
							is IndexExpression       -> it.array
							else                     -> null
						}
					}.toList().asReversed()
					val lost = path.filter { part -> stable(part)?.let { it in before && it !in after } == true }
					if (lost.isNotEmpty()) model.lostReceiverProofs[value] = lost
				}
				Outcome(forget(after, methodWrites(place(value.receiver), candidates)))
			}

			is StateCallExpression                     -> Outcome(value.arguments.fold(expression(value.receiver, initial).after) { facts, arg -> expression(arg, facts).after })
			is ResultListExpression                    -> Outcome(value.values.fold(initial) { facts, item -> expression(item, facts).after })
			is ArrayLiteral                            -> Outcome(value.elements.fold(initial) { facts, item -> expression(item, facts).after })
			is StructLiteral                           -> Outcome(value.fields.fold(initial) { facts, item -> expression(item, facts).after })
			is IndexExpression                         -> expression(value.index, expression(value.array, initial).after)
			is InterpolatedString                      -> Outcome(value.parts.fold(initial) { facts, part -> expression(part, facts).after })
			else                                       -> Outcome(initial)
		}
		when (value) {
			is IntegerLiteral, is FloatingPointLiteral, is BooleanLiteral, is CharacterLiteral, is StringLiteral, is ArrayLiteral, is StructLiteral, is InterpolatedString, is BinaryExpression, is UnaryExpression, is ProviderAccessExpression, is TypeCheckExpression -> presentResults.add(value)
			is CastExpression                                                                                                                                                                                                                                            -> if (!value.target.hasNullability && value.target.name.lexeme != Keywords.VOID.toString()) presentResults.add(value)
			is CallExpression                                                                                                                                                                                                                                            -> {
				val candidates = when (val group = model.declarations.candidatesOf(value)) {
					is FunctionGroupSymbol -> group.overloads
					is MethodGroupSymbol   -> group.overloads
					else                   -> emptyList()
				}
				if (candidates.isNotEmpty() && presentReturns(candidates)) presentResults.add(value)
			}

			is MemberCallExpression                                                                                                                                                                                                                                      -> {
				val hint = receiverHint(value.receiver)
				val sources = methods(value, hint)
				val primitives = if (hint is CStruct) emptyList() else PrimitiveAttachments.all(model.target).filter { it.definition.name == value.name.lexeme }
				if ((sources.isNotEmpty() || primitives.isNotEmpty()) && presentReturns(sources) && primitives.all { it.overload.returns !is CNullable && it.overload.returns != ValueType.VOID }) presentResults.add(value)
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
				is NameExpression           -> this.block(binding(value))?.let { result.addAll(writes(it)) }
				is FunctionBlockExpression  -> Unit
				is BinaryExpression         -> {
					visit(value.left); visit(value.right)
				}

				is ElvisExpression          -> {
					visit(value.left); visit(value.fallback)
				}

				is UnaryExpression          -> visit(value.operand)
				is CastExpression           -> visit(value.operand)
				is TypeCheckExpression      -> visit(value.operand)
				is ProviderAccessExpression -> visit(value.receiver)
				is CallExpression           -> {
					value.arguments.forEach(::visit)
					result.addAll(implicitMethodWrites(value))
				}

				is MemberCallExpression     -> {
					visit(value.receiver); value.arguments.forEach(::visit)
					result.addAll(methodWrites(place(value.receiver), methods(value)))
				}

				is StateCallExpression      -> {
					visit(value.receiver); value.arguments.forEach(::visit)
				}

				is ResultListExpression     -> value.values.forEach(::visit)
				is ArrayLiteral             -> value.elements.forEach(::visit)
				is StructLiteral            -> value.fields.forEach(::visit)
				is FieldAccessExpression    -> visit(value.receiver)
				is IndexExpression          -> {
					visit(value.array); visit(value.index)
				}

				is InterpolatedString       -> value.parts.forEach(::visit)
				is IfExpression             -> {
					value.branches.forEach { visit(it.condition); result.addAll(writtenStatements(it.statements, ::visit)) }
					value.otherwise?.let { result.addAll(writtenStatements(it, ::visit)) }
				}

				else                        -> Unit
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
				visit(item.target); visit(item.value)
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
