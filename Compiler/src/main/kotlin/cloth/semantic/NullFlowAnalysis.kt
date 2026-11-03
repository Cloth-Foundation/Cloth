package cloth.semantic

import cloth.data.CompilerDataProviders
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.symbols.BindingSymbol
import cloth.semantic.symbols.FunctionGroupSymbol
import cloth.semantic.symbols.LocalSymbol
import cloth.token.Keywords
import cloth.token.Operators
import java.util.*

/** Context-independent presence proofs for evaluated symbol occurrences.
 * Deferred bodies have separate capture entry facts; declarations execute no effects.
 */
internal class NullFlowAnalysis(private val model: FunctionSemanticModel) {

	private data class Outcome(val after: Set<BindingSymbol>, val yes: Set<BindingSymbol> = after, val no: Set<BindingSymbol> = after)

	private val presentResults = Collections.newSetFromMap(IdentityHashMap<Expression, Boolean>())
	private val effects = IdentityHashMap<FunctionBlockExpression, Set<BindingSymbol>>()
	private val jumpFacts = IdentityHashMap<LoopJumpStatement, Set<BindingSymbol>>()
	private val flow get() = model.controlFlow
	private fun block(symbol: BindingSymbol?) = (symbol as? LocalSymbol)?.declaration?.initializer as? FunctionBlockExpression
	private fun binding(value: Expression) = (value as? NameExpression)?.let { model.declarations.symbolOf(it) as? BindingSymbol }
	private fun stable(value: Expression) = binding(value)?.takeIf { block(it) == null }
	private fun common(paths: List<Set<BindingSymbol>>, fallback: Set<BindingSymbol>) = paths.reduceOrNull { left, right -> left intersect right } ?: fallback
	private fun knownPresent(value: Expression) = value in presentResults
	private fun nullable(symbol: BindingSymbol) = symbol.declaredType?.hasNullability == true

	fun analyze(function: FunctionDeclaration) {
		function.parameters.forEach { it.defaultValue?.let { value -> expression(value, emptySet()) } }
		when (val body = function.body) {
			is BlockBody      -> statements(body.statements, emptySet())
			is ExpressionBody -> expression(body.expression, emptySet())
		}
	}

	private fun statements(body: List<Statement>, initial: Set<BindingSymbol>): Set<BindingSymbol> {
		var facts = initial
		for (item in body) facts = when (item) {
			is WhileStatement             -> whileLoop(item, facts)
			is LoopJumpStatement          -> facts.also { jumpFacts[item] = it }
			is ForStatement               -> loop(item, facts)
			is BindingDeclaration         -> {
				val deferred = item.initializer as? FunctionBlockExpression
				if (deferred != null) {
					statements((deferred.declaration.body as BlockBody).statements, facts.filterNot { it.mutable }.toSet())
					facts
				} else {
					val after = expression(item.initializer, facts).after
					val symbol = model.declarations.symbolOf(item)
					if (knownPresent(item.initializer)) after + symbol else after - symbol
				}
			}

			is BindingGroupDeclaration    -> {
				var after = expression(item.initializer, facts).after
				val results = (item.initializer as? ResultListExpression)?.values
				model.declarations.symbolsOf(item).forEachIndexed { index, symbol ->
					if (symbol != null) after = if (knownPresent(results?.getOrNull(index) ?: item.initializer)) after + symbol else after - symbol
				}
				after
			}

			is AssignmentStatement        -> {
				val after = expression(item.value, facts).after
				val symbol = model.declarations.targetOf(item)
				if (knownPresent(item.value)) after + symbol else after - symbol
			}

			is MultiAssignmentStatement   -> {
				var after = expression(item.value, facts).after
				val results = (item.value as? ResultListExpression)?.values
				model.declarations.targetsOf(item).forEachIndexed { index, symbol ->
					if (symbol != null) after = if (knownPresent(results?.getOrNull(index) ?: item.value)) after + symbol else after - symbol
				}
				after
			}

			is ElementAssignmentStatement -> expression(item.value, expression(item.target, facts).after).after
			is UpdateStatement            -> facts.also { if (model.declarations.targetOf(item) in facts) model.presentUpdates.add(item) }
			is ExpressionStatement        -> expression(item.expression, facts).after
			is ReturnStatement            -> item.value?.let { expression(it, facts).after } ?: facts
			else                          -> facts
		}
		return facts
	}

	private fun loop(item: ForStatement, initial: Set<BindingSymbol>): Set<BindingSymbol> {
		val entry = when (val source = item.source) {
			is ForSource.Range    -> expression(source.end, expression(source.start, initial).after).after
			is ForSource.Elements -> expression(source.expression, initial).after
		}
		val repeated = item.body.statements + listOfNotNull(item.stepper?.value?.let { ExpressionStatement(it) })
		val changed = writes(repeated) + model.declarations.symbolOf(item)
		// Invalidate before publishing occurrence facts: a fact true on entry may
		// already be false on the second trip around this same source occurrence.
		val afterBody = statements(item.body.statements, entry - changed)
		val completion = flow.of(item.body.statements)
		val updates = completion.continues.map { jumpFacts.getValue(it) }.toMutableList()
		if (completion.canFallThrough) updates.add(afterBody)
		item.stepper?.value?.let { expression(it, common(updates, entry - changed)) }
		// Include the zero-trip path and conservatively forget repeated writes.
		return entry - changed
	}

	private fun whileLoop(item: WhileStatement, initial: Set<BindingSymbol>): Set<BindingSymbol> {
		// The same condition occurrence runs after body/continue writes too.
		val changed = writes(listOf(ExpressionStatement(item.condition)) + item.body.statements)
		val entry = initial - changed
		val condition = expression(item.condition, entry)
		statements(item.body.statements, condition.yes)
		val exits = mutableListOf<Set<BindingSymbol>>()
		if (flow.booleanValue(item.condition) != true && flow.of(item.condition).canFallThrough) exits.add(condition.no)
		if (flow.booleanValue(item.condition) != false && flow.of(item.condition).canFallThrough) {
			flow.of(item.body.statements).breaks.forEach { exits.add(jumpFacts.getValue(it)) }
		}
		return common(exits, entry)
	}

	private fun expression(value: Expression, initial: Set<BindingSymbol>, sourceType: Boolean = false): Outcome {
		val result = when (value) {
			is NameExpression                          -> {
				val symbol = binding(value)
				val deferred = block(symbol)
				if (deferred != null) {
					if (deferred.declaration.returnType?.let { !it.hasNullability && it.name.lexeme != Keywords.VOID.toString() } == true) presentResults.add(value)
					Outcome(initial - writes(deferred))
				} else {
					if (!sourceType && symbol != null && symbol in initial && nullable(symbol)) model.presentNames.add(value)
					if (symbol != null && (!nullable(symbol) || symbol in initial)) presentResults.add(value)
					Outcome(initial)
				}
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
					if (symbol == null) Outcome(after) else if (op == Operators.NOT_EQUAL.toString()) Outcome(after, after + symbol, after - symbol) else Outcome(after, after - symbol, after + symbol)
				}
			}

			is ElvisExpression                         -> {
				val left = expression(value.left, initial, sourceType = true)
				val symbol = stable(value.left)
				val present = if (symbol == null) left.after else left.after + symbol
				val absent = if (symbol == null) left.after else left.after - symbol
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
				val continuing = mutableListOf<Set<BindingSymbol>>()
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
				val annotation = stable(value.receiver)?.declaredType
				val scalarSize = value.name.lexeme == CompilerDataProviders.SIZE.toString() && annotation?.hasNullability == true && annotation.arraySuffixes.isEmpty() && annotation.name.lexeme != Keywords.STRING.toString()
				expression(value.receiver, initial, sourceType = value.name.lexeme == CompilerDataProviders.TYPE.toString() || scalarSize)
			}

			is TypeReceiverExpression                  -> Outcome(initial)
			is CastExpression                          -> expression(value.operand, initial)
			is TypeCheckExpression                     -> expression(value.operand, initial)
			is CallExpression                          -> Outcome(value.arguments.fold(initial) { facts, arg -> expression(arg, facts).after })
			is StateCallExpression                     -> Outcome(value.arguments.fold(expression(value.receiver, initial).after) { facts, arg -> expression(arg, facts).after })
			is ResultListExpression                    -> Outcome(value.values.fold(initial) { facts, item -> expression(item, facts).after })
			is ArrayLiteral                            -> Outcome(value.elements.fold(initial) { facts, item -> expression(item, facts).after })
			is IndexExpression                         -> expression(value.index, expression(value.array, initial).after)
			is InterpolatedString                      -> Outcome(value.parts.fold(initial) { facts, part -> expression(part, facts).after })
			else                                       -> Outcome(initial)
		}
		when (value) {
			is IntegerLiteral, is FloatingPointLiteral, is BooleanLiteral, is CharacterLiteral, is StringLiteral, is ArrayLiteral, is InterpolatedString, is BinaryExpression, is UnaryExpression, is ProviderAccessExpression, is IndexExpression, is TypeCheckExpression -> presentResults.add(value)
			is CastExpression                                                                                                                                                                                                                                              -> if (!value.target.hasNullability && value.target.name.lexeme != Keywords.VOID.toString()) presentResults.add(value)
			is CallExpression                                                                                                                                                                                                                                              -> {
				val candidates = (model.declarations.candidatesOf(value) as? FunctionGroupSymbol)?.overloads
				if (!candidates.isNullOrEmpty() && candidates.all { it.declaration.returnTypes.singleOrNull()?.let { type -> !type.hasNullability && type.name.lexeme != Keywords.VOID.toString() } == true }) presentResults.add(value)
			}

			else                                                                                                                                                                                                                                                           -> Unit
		}
		return result
	}

	private fun writes(block: FunctionBlockExpression): Set<BindingSymbol> = effects.getOrPut(block) {
		writes((block.declaration.body as BlockBody).statements)
	}

	private fun writes(body: List<Statement>): Set<BindingSymbol> {
		val result = mutableSetOf<BindingSymbol>()
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
				is CallExpression           -> value.arguments.forEach(::visit)
				is StateCallExpression      -> {
					visit(value.receiver); value.arguments.forEach(::visit)
				}

				is ResultListExpression     -> value.values.forEach(::visit)
				is ArrayLiteral             -> value.elements.forEach(::visit)
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

	private fun writtenStatements(body: List<Statement>, visit: (Expression) -> Unit): Set<BindingSymbol> = buildSet {
		for (item in body) when (item) {
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
				add(model.declarations.symbolOf(item))
				addAll(writtenStatements(item.body.statements, visit))
				item.stepper?.value?.let(visit)
			}

			is AssignmentStatement        -> {
				add(model.declarations.targetOf(item)); visit(item.value)
			}

			is MultiAssignmentStatement   -> {
				addAll(model.declarations.targetsOf(item).filterNotNull()); visit(item.value)
			}

			is UpdateStatement            -> add(model.declarations.targetOf(item))
			is BindingDeclaration         -> if (item.initializer !is FunctionBlockExpression) visit(item.initializer)
			is BindingGroupDeclaration    -> visit(item.initializer)
			is ElementAssignmentStatement -> {
				visit(item.target); visit(item.value)
			}

			is ExpressionStatement        -> visit(item.expression)
			is ReturnStatement            -> item.value?.let(visit)
			else                          -> Unit
		}
	}
}
