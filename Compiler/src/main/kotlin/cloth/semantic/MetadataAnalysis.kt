package cloth.semantic

import cloth.data.*
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.*
import cloth.parser.statement.*

/** Definite metadata facts follow evaluation order, independently of overload/type previews.
 * Unknown facts are not errors: the provider emits its checked runtime operation.
 */
internal class MetadataAnalysis(
	private val model: SemanticModel,
	parameters: List<Pair<String, Boolean>> = emptyList(),
	private val flow: ControlFlowModel = ControlFlowModel(),
) {

	private class Slot(var represented: ValueType?, val mutable: Boolean, val block: FunctionBlockExpression? = null)

	private val scopes = mutableListOf(mutableMapOf<String, Slot>())

	init {
		parameters.forEach { (name, mutable) -> scopes.first()[name] = Slot(null, mutable) }
	}

	private fun lookup(name: String): Slot? = scopes.asReversed().firstNotNullOfOrNull { it[name] }
	private fun snapshot(): Map<Slot, ValueType?> = scopes.flatMap { it.values }.associateWith { it.represented }
	private fun restore(facts: Map<Slot, ValueType?>) = facts.forEach { (slot, type) -> slot.represented = type }
	private fun merge(paths: List<Map<Slot, ValueType?>>) {
		if (paths.isEmpty()) return
		for (slot in snapshot().keys) {
			val first = paths.first()[slot]
			slot.represented = first.takeIf { paths.all { path -> path[slot] == first } }
		}
	}

	private fun forgetMutable() = snapshot().keys.filter { it.mutable }.forEach { it.represented = null }
	private fun scoped(body: List<Statement>): ValueType? {
		scopes.add(mutableMapOf())
		return try {
			statements(body)
		} finally {
			scopes.removeAt(scopes.lastIndex)
		}
	}

	fun statements(body: List<Statement>): ValueType? {
		var result: ValueType? = null
		for (item in body) result = when (item) {
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
				scopes.last()[item.name.lexeme] = Slot(represented, item.mutable, block)
				null
			}

			is AssignmentStatement        -> {
				val represented = expression(item.value)
				lookup(item.name.lexeme)?.represented = represented
				null
			}

			is ElementAssignmentStatement -> {
				expression(item.target); expression(item.value); null
			}

			is UpdateStatement            -> null
			is ExpressionStatement        -> expression(item.expression)
			is ReturnStatement            -> {
				item.value?.let { expression(it) }; null
			}

			else                          -> null
		}
		return result
	}

	fun expression(value: Expression): ValueType? {
		val represented: ValueType? = when (value) {
			is NameExpression           -> {
				val slot = lookup(value.name.lexeme)
				if (slot?.block != null) {
					forgetMutable(); null
				} else slot?.represented
			}

			is ProviderAccessExpression -> {
				val receiver = if (value.receiver is TypeReceiverExpression) null else expression(value.receiver)
				when (val result = model.providers[value]?.result) {
					is ProviderResult.TypeMetadata     -> result.receiverType
					is ProviderResult.MetadataProperty -> {
						if (receiver != null && !result.query.accepts(receiver)) throw SemanticErrors.metadata(receiver, value.name, result.query.requirement)
						if (result.query == MetadataQuery.ELEMENT) (receiver as? CArray)?.element else null
					}

					else                               -> null
				}
			}

			is CastExpression           -> expression(value.operand).takeIf { model.typeOf(value) == CTypeInfo }
			is TypeCheckExpression      -> {
				expression(value.operand); null
			}

			is CallExpression           -> {
				value.arguments.forEach { expression(it) }; null
			}

			is ArrayLiteral             -> {
				value.elements.forEach { expression(it) }; null
			}

			is IndexExpression          -> {
				expression(value.array); expression(value.index); null
			}

			is InterpolatedString       -> {
				value.parts.forEach { expression(it) }; null
			}

			is UnaryExpression          -> {
				expression(value.operand); null
			}

			is BinaryExpression         -> {
				expression(value.left)
				val before = snapshot()
				expression(value.right)
				if (value.operator.lexeme in setOf("&&", "||")) {
					val truth = flow.booleanValue(value.left)
					val runsWhen = value.operator.lexeme == "&&"
					val paths = mutableListOf<Map<Slot, ValueType?>>()
					if (truth != runsWhen) paths.add(before)
					if ((truth == null || truth == runsWhen) && flow.of(value.right).canContinue) paths.add(snapshot())
					merge(paths)
				}
				null
			}

			is IfExpression             -> {
				val paths = mutableListOf<Map<Slot, ValueType?>>()
				val results = mutableListOf<ValueType?>()
				var reachesCondition = true
				for (branch in value.branches) {
					expression(branch.condition)
					val nextCondition = snapshot()
					val represented = scoped(branch.statements)
					val truth = flow.booleanValue(branch.condition)
					val tested = reachesCondition && flow.of(branch.condition).canContinue
					// All source is checked, but only continuing, executable paths contribute facts.
					if (tested && truth != false && flow.of(branch.statements).canContinue) {
						results.add(represented)
						paths.add(snapshot())
					}
					restore(nextCondition)
					reachesCondition = tested && truth != true
				}
				val otherwise = value.otherwise
				val represented = otherwise?.let { scoped(it) }
				if (reachesCondition && (otherwise == null || flow.of(otherwise).canContinue)) {
					results.add(represented)
					paths.add(snapshot())
				}
				merge(paths)
				results.firstOrNull().takeIf { first -> results.all { it == first } }
			}

			else                        -> null
		}
		if (represented == null) model.metadata.remove(value) else model.metadata[value] = represented
		return represented
	}
}
