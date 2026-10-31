package cloth.emitter.types

import cloth.parser.declaration.BlockBody
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.FunctionSemanticModel
import cloth.semantic.SemanticModel
import cloth.semantic.ValueType
import cloth.semantic.symbols.LocalSymbol
import java.util.IdentityHashMap

/** A closed, conservative proof that evaluating an expression cannot publish
 * newly allocated buffers into surrounding bindings. Ordinary Cloth functions
 * receive value arguments and can only return buffers into the caller; inline
 * function blocks need inspection because they can write captured bindings.
 * Unrecognized operations and all declarations/writes fall back to frame lifetime.
 */
internal class ArrayTemporaryAnalysis(private val types: SemanticModel, private val functions: FunctionSemanticModel?) {
	private data class Effects(val arrays: Boolean = false, val safe: Boolean = true) {
		operator fun plus(other: Effects) = Effects(arrays || other.arrays, safe && other.safe)
	}
	private val memo = IdentityHashMap<Expression, Effects>()
	private val active = java.util.Collections.newSetFromMap(IdentityHashMap<Expression, Boolean>())
	private val unknown = Effects(arrays = true, safe = false)
	fun canReclaim(expression: Expression): Boolean = effects(expression).let { it.arrays && it.safe }

	private fun statements(body: List<Statement>): Effects = body.fold(Effects()) { result, statement ->
		result + when (statement) {
			is ExpressionStatement -> effects(statement.expression)
			is ReturnStatement -> statement.value?.let(::effects) ?: Effects()
			else -> unknown
		}
	}

	private fun effects(expression: Expression): Effects {
		memo[expression]?.let { return it }
		if (!active.add(expression)) return unknown
		val result = when (expression) {
			is IntegerLiteral, is FloatingPointLiteral, is BooleanLiteral, is CharacterLiteral, is StringLiteral -> Effects()
			is NameExpression -> {
				val symbol = functions?.declarations?.symbolOf(expression)
				val block = (symbol as? LocalSymbol)?.declaration?.initializer as? FunctionBlockExpression
				if (block == null) Effects() else effects(block)
			}
			is FunctionBlockExpression -> Effects(types.typeOf(expression) is ValueType.ArrayValue) + statements((expression.declaration.body as BlockBody).statements)
			is CallExpression -> {
				if (functions == null) unknown else {
					val type = functions.typeOf(functions.selectedFunction(expression))
					expression.arguments.fold(Effects(type.returns is ValueType.ArrayValue || type.parameters.any { it is ValueType.ArrayValue })) { result, argument -> result + effects(argument) }
				}
			}
			is ArrayLiteral -> expression.elements.fold(Effects()) { result, element ->
				result + Effects(types.typeOf(element) is ValueType.ArrayValue) + effects(element)
			}
			is IndexExpression -> effects(expression.array) + effects(expression.index)
			is UnaryExpression -> effects(expression.operand)
			is BinaryExpression -> effects(expression.left) + effects(expression.right)
			is CastExpression -> effects(expression.operand)
			is TypeCheckExpression -> effects(expression.operand)
			is InterpolatedString -> expression.parts.fold(Effects()) { result, part -> result + effects(part) }
			is IfExpression -> expression.branches.fold(Effects(types.typeOf(expression) is ValueType.ArrayValue)) { result, branch ->
				result + effects(branch.condition) + statements(branch.statements)
			} + (expression.otherwise?.let(::statements) ?: Effects())
			else -> unknown
		}
		active.remove(expression)
		memo[expression] = result
		return result
	}
}
