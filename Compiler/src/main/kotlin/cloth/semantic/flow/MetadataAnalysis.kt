package cloth.semantic.flow

import cloth.data.*
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.ResultShape
import cloth.semantic.scalar
import cloth.semantic.symbols.BindingSymbol
import cloth.semantic.symbols.CallableSymbol
import cloth.semantic.symbols.ParameterSymbol
import cloth.semantic.typing.FunctionSemanticModel
import cloth.token.Operators
import java.util.IdentityHashMap

internal fun checkCallableMetadata(function: CallableSymbol, model: FunctionSemanticModel) {
	function.parameters.forEachIndexed { index, parameter ->
		parameter.declaration.defaultValue?.let { MetadataAnalysis(model, function.parameters.take(index), readOnlyParameters = true).expression(it) }
	}
	val metadata = MetadataAnalysis(model, function.parameters)
	model.declarations.initializationExpressions(function.declaration).forEach(metadata::expression)
	when (val body = function.declaration.body) {
		is BlockBody      -> metadata.statements(body.statements)
		is ExpressionBody -> metadata.expression(body.expression)
	}
}

/** Definite metadata facts follow evaluation order, independently of overload/type previews.
 * Unknown facts are not errors: the provider emits its checked runtime operation.
 */
private class MetadataAnalysis(semantics: FunctionSemanticModel, parameters: List<ParameterSymbol>, readOnlyParameters: Boolean = false) {

	private class Slot(var represented: ValueType?, val mutable: Boolean, val deferred: Boolean = false)

	private val model = semantics.types
	private val declarations = semantics.declarations
	private val flow = semantics.controlFlow
	private val slots = IdentityHashMap<BindingSymbol, Slot>()
	private val scopes = mutableListOf(mutableListOf<BindingSymbol>())
	private val jumpFacts = IdentityHashMap<LoopJumpStatement, Map<Slot, ValueType?>>()

	init {
		parameters.forEach { declare(it, null, mutable = !readOnlyParameters && it.mutable) }
	}

	private fun declare(symbol: BindingSymbol, represented: ValueType?, deferred: Boolean = false, mutable: Boolean = symbol.mutable) {
		slots[symbol] = Slot(represented, mutable, deferred)
		scopes.last().add(symbol)
	}

	private fun snapshot(): Map<Slot, ValueType?> = slots.values.associateWith { it.represented }
	private fun restore(facts: Map<Slot, ValueType?>) = facts.forEach { (slot, type) -> slot.represented = type }
	private fun merge(paths: List<Map<Slot, ValueType?>>) {
		if (paths.isEmpty()) return
		for (slot in snapshot().keys) {
			val first = paths.first()[slot]
			slot.represented = first.takeIf { paths.all { path -> path[slot] == first } }
		}
	}

	private fun forgetMutable() = snapshot().keys.filter { it.mutable }.forEach { it.represented = null }
	private fun scoped(body: List<Statement>): ValueType? = inScope { statements(body) }

	private inline fun <T> inScope(action: () -> T): T {
		val locals = mutableListOf<BindingSymbol>()
		scopes.add(locals)
		return try {
			action()
		} finally {
			locals.forEach(slots::remove)
			scopes.removeAt(scopes.lastIndex)
		}
	}

	fun statements(body: List<Statement>): ValueType? {
		var result: ValueType? = null
		for (item in body) result = when (item) {
			is DoWhileStatement           -> {
				if (flow.canRepeat(item)) forgetMutable()
				scoped(item.body.statements)
				val completion = flow.of(item.body.statements)
				val inputs = completion.continues.map { jumpFacts.getValue(it) }.toMutableList()
				if (completion.canFallThrough) inputs.add(snapshot())
				if (inputs.isEmpty()) forgetMutable() else merge(inputs)
				expression(item.condition)
				val exits = completion.breaks.map { jumpFacts.getValue(it) }.toMutableList()
				if (inputs.isNotEmpty() && flow.of(item.condition).canFallThrough && flow.booleanValue(item.condition) != true) exits.add(snapshot())
				if (exits.isEmpty()) forgetMutable() else merge(exits)
				null
			}

			is WhileStatement             -> {
				forgetMutable()
				expression(item.condition)
				scoped(item.body.statements)
				forgetMutable()
				null
			}

			is LoopJumpStatement          -> {
				jumpFacts[item] = snapshot(); null
			}

			is ForStatement               -> {
				when (val source = item.source) {
					is ForSource.Range    -> {
						expression(source.start); expression(source.end)
					}

					is ForSource.Elements -> expression(source.expression)
				}
				// A single optimistic pass would reuse first-iteration metadata on
				// a later backedge. Unknown mutable metadata uses runtime checking.
				forgetMutable()
				inScope {
					declare(declarations.symbolOf(item), null)
					scoped(item.body.statements)
					val completion = flow.of(item.body.statements)
					val updates = completion.continues.map { jumpFacts.getValue(it) }.toMutableList()
					if (completion.canFallThrough) updates.add(snapshot())
					if (updates.isEmpty()) forgetMutable() else merge(updates)
					model.loopOf(item).stepperExpression?.let { expression(it) }
				}
				forgetMutable()
				null
			}

			is BindingGroupDeclaration    -> {
				expression(item.initializer)
				val facts = model.representedResultsOf(item.initializer)
				val plan = model.receptionOf(item)
				for ((index, symbol) in declarations.symbolsOf(item).withIndex()) {
					if (symbol != null) declare(symbol, facts.getOrNull(plan.slots[index].sourceIndex))
				}
				null
			}

			is BindingDeclaration         -> {
				val block = item.initializer as? FunctionBlockExpression
				val represented = if (block == null) expression(item.initializer) else {
					// A block executes later. Captured mutable bindings may have changed by then.
					val before = snapshot()
					forgetMutable()
					scoped((block.declaration.body as BlockBody).statements)
					restore(before)
					null
				}
				declare(declarations.symbolOf(item), represented, deferred = block != null)
				null
			}

			is AssignmentStatement        -> {
				val represented = expression(item.value)
				slots[declarations.targetOf(item)]?.represented = represented
				null
			}

			is MultiAssignmentStatement   -> {
				expression(item.value)
				// Snapshot all represented result facts before writing even the first target.
				val facts = model.representedResultsOf(item.value)
				val plan = model.receptionOf(item)
				for ((index, symbol) in declarations.targetsOf(item).withIndex()) {
					if (symbol != null) slots[symbol]?.represented = facts.getOrNull(plan.slots[index].sourceIndex)
				}
				null
			}

			is ElementAssignmentStatement -> {
				expression(item.target); expression(item.value); null
			}

			is FieldWriteStatement        -> {
				expression(item.target); item.value?.let { expression(it) }; null
			}

			is UpdateStatement            -> null
			is ExpressionStatement        -> expression(item.expression)
			is ReturnStatement            -> {
				item.value?.let { expression(it) }; null
			}

			else -> throw InternalErrors.invariant("Unsupported statement during metadata analysis: $item")
		}
		return result
	}

	fun expression(value: Expression): ValueType? {
		model.resultMetadata.remove(value)
		val represented: ValueType? = when (value) {
			is ResultListExpression     -> {
				model.resultMetadata[value] = value.values.map { expression(it) }
				null
			}

			is NameExpression           -> {
				val symbol = declarations.names[value] as? BindingSymbol
				val slot = symbol?.let { slots[it] }
				if (slot?.deferred == true) {
					forgetMutable(); null
				} else slot?.represented
			}

			is ProviderAccessExpression -> {
				val receiver = if (model.providers[value]?.receiverForm == ProviderReceiverForm.TYPE) null else expression(value.receiver)
				when (val result = model.providers[value]?.result) {
					is ProviderResult.TypeMetadata     -> result.receiverType
					is ProviderResult.MetadataProperty -> {
						if (receiver != null && !result.query.accepts(receiver)) throw SemanticErrors.metadata(receiver, value.name, result.query.requirement)
						if (result.query == MetadataQuery.ELEMENT && receiver != null) (result.query.subject(receiver) as? CArray)?.element else null
					}

					else                               -> null
				}
			}

			is CastExpression           -> expression(value.operand).takeIf { model.typeOf(value) == CTypeInfo }
			is FieldAccessExpression    -> {
				if (model.resultOf(value.receiver) != ResultShape.Empty) expression(value.receiver)
				null
			}

			is ElvisExpression          -> {
				val left = expression(value.left)
				val before = snapshot()
				val right = expression(value.fallback)
				if (value.left is NullLiteral) right else {
					val continuing = mutableListOf(before)
					if (flow.of(value.fallback).canFallThrough) continuing.add(snapshot())
					merge(continuing)
					left.takeIf { !flow.of(value.fallback).canFallThrough || left == right }
				}
			}

			is BinaryExpression         -> {
				if (value.operator.lexeme in setOf(Operators.AND.toString(), Operators.OR.toString())) {
					expression(value.left)
					val before = snapshot()
					expression(value.right)
					val truth = flow.booleanValue(value.left)
					val runsWhen = value.operator.lexeme == Operators.AND.toString()
					val paths = mutableListOf<Map<Slot, ValueType?>>()
					if (truth != runsWhen) paths.add(before)
					if ((truth == null || truth == runsWhen) && flow.of(value.right).canFallThrough) paths.add(snapshot())
					merge(paths)
				} else value.eagerOperands().forEach(::expression)
				null
			}

			is IfExpression             -> {
				val paths = mutableListOf<Map<Slot, ValueType?>>()
				val results = mutableListOf<ValueType?>()
				val positionalResults = mutableListOf<List<ValueType?>>()
				fun resultFacts(body: List<Statement>, scalar: ValueType?): List<ValueType?> {
					val tail = (body.lastOrNull() as? ExpressionStatement)?.expression
					return tail?.let { model.representedResultsOf(it) } ?: listOf(scalar)
				}

				var reachesCondition = true
				for (branch in value.branches) {
					expression(branch.condition)
					val nextCondition = snapshot()
					val represented = scoped(branch.statements)
					val truth = flow.booleanValue(branch.condition)
					val tested = reachesCondition && flow.of(branch.condition).canFallThrough
					// All source is checked, but only continuing, executable paths contribute facts.
					if (tested && truth != false && flow.of(branch.statements).canFallThrough) {
						results.add(represented)
						positionalResults.add(resultFacts(branch.statements, represented))
						paths.add(snapshot())
					}
					restore(nextCondition)
					reachesCondition = tested && truth != true
				}
				val otherwise = value.otherwise
				val represented = otherwise?.let { scoped(it) }
				if (reachesCondition && (otherwise == null || flow.of(otherwise).canFallThrough)) {
					results.add(represented)
					positionalResults.add(otherwise?.let { resultFacts(it, represented) } ?: emptyList())
					paths.add(snapshot())
				}
				merge(paths)
				if (model.resultOf(value) is ResultShape.Multiple) {
					model.resultMetadata[value] = model.resultOf(value).values.indices.map { index ->
						val first = positionalResults.firstOrNull()?.getOrNull(index)
						first.takeIf { positionalResults.all { it.getOrNull(index) == first } }
					}
				}
				results.firstOrNull().takeIf { first -> results.all { it == first } }
			}

			else -> {
				value.eagerOperands(declarations).forEach(::expression)
				null
			}
		}
		if (represented == null) model.metadata.remove(value) else model.metadata[value] = represented
		when (val result = model.resultOf(value)) {
			is ResultShape.Single   -> model.resultMetadata[value] = listOf(represented)
			is ResultShape.Multiple -> if (!model.resultMetadata.containsKey(value)) model.resultMetadata[value] = result.values.map { null }
			else                    -> model.resultMetadata.remove(value)
		}
		return represented
	}
}
