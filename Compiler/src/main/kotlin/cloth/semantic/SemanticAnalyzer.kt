package cloth.semantic

import cloth.ast.ASTStructure
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.parser.type.TypeReference
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token

/** Checks bindings, nested branch scopes, and expression types before LLVM emission. */
class SemanticAnalyzer {

	private data class Binding(val type: ValueType, val mutable: Boolean)

	fun analyze(ast: ASTStructure): SemanticModel {
		val scopes = mutableListOf(mutableMapOf<String, Binding>())
		val model = SemanticModel()

		fun lookup(name: Token): Binding = scopes.asReversed().firstNotNullOfOrNull { it[name.lexeme] }
			?: throw SemanticException("Unknown binding '${name.lexeme}' at ${name.span}")

		// Statements and value-producing conditionals can recursively contain each other.
		lateinit var checkStatement: (Statement) -> Unit

		// Only integer literals receive context. A named binding never changes type.
		fun integerContext(expression: Expression): ValueType? = when (expression) {
			is NameExpression -> lookup(expression.name).type.takeIf { it.integer }
			is CastExpression -> resolveType(expression.target).takeIf { it.integer }
			is UnaryExpression -> if (expression.operator.lexeme in setOf("+", "-")) integerContext(expression.operand) else null
			is BinaryExpression -> if (expression.operator.lexeme in arithmeticOperators) {
				integerContext(expression.left) ?: integerContext(expression.right)
			} else null
			else -> null
		}

		fun literalType(literal: IntegerLiteral, expected: ValueType?, negative: Boolean = false): ValueType {
			val type = expected as? ValueType.Integer ?: ValueType.INT
			val value = if (negative) -literal.value else literal.value
			if (value < type.minimum || value > type.maximum) {
				throw SemanticException("Integer literal '$value' is out of range for '${type.spelling}' at ${literal.token.span}")
			}
			model.expressions[literal] = type
			return type
		}

		fun typeOf(expression: Expression, expected: ValueType? = null, requireValue: Boolean = true): ValueType {
			val type = when (expression) {
				is IntegerLiteral -> literalType(expression, expected)
				is FloatingPointLiteral -> ValueType.FLOAT
				is BooleanLiteral -> ValueType.BOOL
				is CharacterLiteral -> ValueType.CHAR
				is StringLiteral -> ValueType.STRING
				is NameExpression -> lookup(expression.name).type
				is TypeCheckExpression -> {
					typeOf(expression.operand)
					// All currently supported values have a statically known primitive type.
					// Resolving the target still validates its modifiers and width.
					model.typeChecks[expression] = resolveType(expression.target)
					ValueType.BOOL
				}
				is IfExpression -> {
					if (requireValue && expression.otherwise == null) {
						throw SemanticException("A value-producing 'if' requires a final 'else' at ${expression.keyword.span}")
					}
					for (branch in expression.branches) {
						if (typeOf(branch.condition) != ValueType.BOOL) {
							throw SemanticException("An 'if' condition requires a boolean value at ${expression.keyword.span}")
						}
					}
					var resultType: ValueType? = null
					for (statements in expression.branches.map { it.statements } + listOfNotNull(expression.otherwise)) {
						if (requireValue && statements.lastOrNull() !is ExpressionStatement) {
							throw SemanticException("Each value-producing 'if' branch must end with an expression at ${expression.keyword.span}")
						}
						scopes.add(mutableMapOf())
						try {
							for ((index, statement) in statements.withIndex()) {
								if (requireValue && index == statements.lastIndex) {
									val branchType = typeOf((statement as ExpressionStatement).expression, expected)
									model.statements[statement] = branchType
									if (resultType != null && branchType != resultType) {
										throw SemanticException("'if' branches must yield the same type, got '${resultType.spelling}' and '${branchType.spelling}' at ${expression.keyword.span}")
									}
									resultType = branchType
								} else checkStatement(statement)
							}
						} finally {
							scopes.removeAt(scopes.lastIndex)
						}
					}
					if (requireValue) checkNotNull(resultType) else ValueType.VOID
				}
				is CastExpression -> {
					// A cast converts an independently typed value; its destination must
					// never change arithmetic or literal types inside the operand.
					val source = typeOf(expression.operand)
					val target = resolveType(expression.target)
					if (source != target && !(source.numeric && target.numeric)) {
						throw SemanticException("Cannot cast '${source.spelling}' to '${target.spelling}' at ${expression.operator.span}")
					}
					target
				}
				is UnaryExpression -> {
					val operand = if (expression.operator.lexeme == "-" && expression.operand is IntegerLiteral) {
						literalType(expression.operand, expected, negative = true)
					} else typeOf(expression.operand, if (expression.operator.lexeme == "!") null else expected)
					if (expression.operator.lexeme == Operators.NOT.character) {
						if (operand != ValueType.BOOL) {
							throw SemanticException("Operator '!' requires a boolean operand, got '${operand.spelling}' at ${expression.operator.span}")
						}
					} else if (!operand.numeric) {
						throw SemanticException("Operator '${expression.operator.lexeme}' requires a numeric operand, got '${operand.spelling}' at ${expression.operator.span}")
					}
					operand
				}
				is BinaryExpression -> {
					val numeric = expression.operator.lexeme in arithmeticOperators || expression.operator.lexeme in comparisonOperators
					val context = if (numeric) {
						integerContext(expression.left) ?: integerContext(expression.right)
							?: (if (expression.operator.lexeme in arithmeticOperators) expected?.takeIf { it.integer } else null)
					} else null
					val left = typeOf(expression.left, context)
					val right = typeOf(expression.right, context)
					if (numeric && left.integer && right.integer && left != right) {
						throw SemanticException("Operator '${expression.operator.lexeme}' cannot mix '${left.spelling}' and '${right.spelling}'; use an explicit conversion at ${expression.operator.span}")
					}
					if (expression.operator.lexeme in setOf(Operators.EQUAL.character, Operators.NOT_EQUAL.character)) {
						if (left != right && !(left.numeric && right.numeric)) {
							throw SemanticException("Cannot compare '${left.spelling}' and '${right.spelling}' with '${expression.operator.lexeme}' at ${expression.operator.span}")
						}
						ValueType.BOOL
					} else if (expression.operator.lexeme == Operators.PLUS.character && left == ValueType.STRING && right == ValueType.STRING) {
						ValueType.STRING
					} else if (expression.operator.lexeme in setOf(Operators.AND.character, Operators.OR.character)) {
						if (left != ValueType.BOOL || right != ValueType.BOOL) {
							throw SemanticException("Operator '${expression.operator.lexeme}' requires boolean operands, got '${left.spelling}' and '${right.spelling}' at ${expression.operator.span}")
						}
						ValueType.BOOL
					} else {
						if (!left.numeric || !right.numeric) {
							throw SemanticException("Operator '${expression.operator.lexeme}' requires numeric operands, got '${left.spelling}' and '${right.spelling}' at ${expression.operator.span}")
						}
						if (expression.operator.lexeme in setOf(Operators.LESS.character, Operators.GREATER.character,
							Operators.LESS_EQUAL.character, Operators.GREATER_EQUAL.character)) {
							ValueType.BOOL
						} else if (left.floating || right.floating) ValueType.commonFloating(left, right) else left
					}
				}
				else -> error("Unsupported expression: $expression")
			}
			model.expressions[expression] = type
			return type
		}

		fun checkType(expected: ValueType, actual: ValueType, name: Token) {
			if (actual != expected) {
				throw SemanticException("Cannot assign '${actual.spelling}' to '${expected.spelling}' binding '${name.lexeme}' at ${name.span}")
			}
		}

		checkStatement = { statement ->
			when (statement) {
				is BindingDeclaration -> {
					if (scopes.last().containsKey(statement.name.lexeme)) {
						throw SemanticException("Binding '${statement.name.lexeme}' is already declared in this scope at ${statement.name.span}")
					}
					val declaredType = statement.type?.let { resolveType(it) }
					val initializerType = typeOf(statement.initializer, declaredType)
					val bindingType = declaredType ?: initializerType
					checkType(bindingType, initializerType, statement.name)
					// A declaration is visible only after its initializer has been checked.
					scopes.last()[statement.name.lexeme] = Binding(bindingType, statement.mutable)
					model.statements[statement] = bindingType
				}
				is AssignmentStatement -> {
					val binding = lookup(statement.name)
					if (!binding.mutable) {
						throw SemanticException("Cannot reassign immutable binding '${statement.name.lexeme}' at ${statement.name.span}")
					}
					checkType(binding.type, typeOf(statement.value, binding.type), statement.name)
					model.statements[statement] = binding.type
				}
				is ExpressionStatement -> model.statements[statement] = typeOf(statement.expression,
					requireValue = statement.expression !is IfExpression)
				else -> error("Unsupported statement: $statement")
			}
		}
		ast.statements.forEach(checkStatement)
		return model
	}

	private fun resolveType(annotation: TypeReference): ValueType {
		val name = annotation.name.lexeme
		if (annotation.unsigned && name != Keywords.INTEGER.toString()) {
			throw SemanticException("'${Keywords.UNSIGNED}' applies only to '${Keywords.INTEGER}', got '${annotation.spelling}' at ${annotation.name.span}")
		}
		val widths = when (name) {
			Keywords.INTEGER.toString() -> ValueType.INTEGER_WIDTHS
			Keywords.FLOAT.toString() -> ValueType.FLOAT_WIDTHS
			else -> null
		}
		if (widths != null) {
			val width = annotation.width?.lexeme?.toIntOrNull() ?: if (annotation.width == null) ValueType.DEFAULT_BITS else null
			if (width == null || width !in widths) {
				throw SemanticException("Type '${annotation.spelling}' has an unsupported width; supported widths are ${widths.joinToString()} at ${annotation.width?.span}")
			}
			return if (name == Keywords.INTEGER.toString()) ValueType.Integer(width, annotation.unsigned)
				else ValueType.Floating(width)
		}
		if (annotation.width == null) {
			if (name == Keywords.BOOLEAN.toString()) return ValueType.BOOL
			if (name == Keywords.CHARACTER.toString()) return ValueType.CHAR
			if (name == Keywords.STRING.toString()) return ValueType.STRING
		}
		throw SemanticException("Binding type '${annotation.spelling}' is not supported yet at ${annotation.name.span}")
	}

	companion object {
		private val arithmeticOperators = setOf("+", "-", "*", "/", "%")
		private val comparisonOperators = setOf("==", "!=", "<", ">", "<=", ">=")
	}
}
