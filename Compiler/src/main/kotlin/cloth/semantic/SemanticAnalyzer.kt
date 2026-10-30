package cloth.semantic

import cloth.ast.ASTStructure
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.*
import cloth.parser.statement.AssignmentStatement
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.Statement
import cloth.parser.statement.UpdateStatement
import cloth.parser.type.TypeReference
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenSpan

/** Checks bindings, nested branch scopes, and expression types before LLVM emission. */
class SemanticAnalyzer {

	private data class Binding(val type: ValueType, val mutable: Boolean, val declaration: Token)

	fun analyze(ast: ASTStructure): SemanticModel {
		val scopes = mutableListOf(mutableMapOf<String, Binding>())
		val model = SemanticModel()

		fun lookup(name: Token): Binding = scopes.asReversed().firstNotNullOfOrNull { it[name.lexeme] } ?: throw SemanticErrors.unknown(name)

		// Statements and value-producing conditionals can recursively contain each other.
		lateinit var checkStatement: (Statement) -> Unit

		// Only integer literals receive context. A named binding never changes type.
		fun integerContext(expression: Expression): ValueType? = when (expression) {
			is NameExpression   -> lookup(expression.name).type.takeIf { it.integer }
			is CastExpression   -> resolveType(expression.target).takeIf { it.integer }
			is UnaryExpression  -> if (expression.operator.lexeme in setOf("+", "-")) integerContext(expression.operand) else null
			is BinaryExpression -> if (expression.operator.lexeme in arithmeticOperators) {
				integerContext(expression.left) ?: integerContext(expression.right)
			} else null

			else                -> null
		}

		fun literalType(literal: IntegerLiteral, expected: ValueType?, negative: Boolean = false): ValueType {
			val type = expected as? ValueType.Integer ?: ValueType.INT
			val value = if (negative) -literal.value else literal.value
			if (value < type.minimum || value > type.maximum) {
				throw SemanticErrors.integer(value, type, literal.span)
			}
			model.expressions[literal] = type
			return type
		}

		fun typeOf(expression: Expression, expected: ValueType? = null, requireValue: Boolean = true): ValueType {
			val type = when (expression) {
				is IntegerLiteral       -> literalType(expression, expected)
				is FloatingPointLiteral -> ValueType.FLOAT
				is BooleanLiteral       -> ValueType.BOOL
				is CharacterLiteral     -> ValueType.CHAR
				is StringLiteral        -> ValueType.STRING
				is InterpolatedString   -> {
					expression.parts.forEach { typeOf(it) }
					ValueType.STRING
				}
				is NameExpression       -> lookup(expression.name).type
				is TypeCheckExpression  -> {
					typeOf(expression.operand)
					// All currently supported values have a statically known primitive type.
					// Resolving the target still validates its modifiers and width.
					model.typeChecks[expression] = resolveType(expression.target)
					ValueType.BOOL
				}

				is IfExpression         -> {
					if (requireValue && expression.otherwise == null) {
						throw SemanticErrors.missingElse(expression.span)
					}
					for (branch in expression.branches) {
						if (typeOf(branch.condition) != ValueType.BOOL) {
							throw SemanticErrors.condition(branch.condition)
						}
					}
					var resultType: ValueType? = null
					var resultSpan: TokenSpan? = null
					val bodies = expression.branches.map { it.statements to it.span } + listOfNotNull(expression.otherwise?.let { it to checkNotNull(expression.otherwiseSpan) })
					for ((statements, bodySpan) in bodies) {
						if (requireValue && statements.lastOrNull() !is ExpressionStatement) {
							throw SemanticErrors.branchValue(bodySpan)
						}
						scopes.add(mutableMapOf())
						try {
							for ((index, statement) in statements.withIndex()) {
								if (requireValue && index == statements.lastIndex) {
									val branchType = typeOf((statement as ExpressionStatement).expression, expected)
									model.statements[statement] = branchType
									if (resultType != null && branchType != resultType) {
										throw SemanticErrors.branchTypes(resultType, branchType, checkNotNull(resultSpan), statement.span)
									}
									resultType = branchType
									resultSpan = statement.span
								} else checkStatement(statement)
							}
						} finally {
							scopes.removeAt(scopes.lastIndex)
						}
					}
					if (requireValue) checkNotNull(resultType) else ValueType.VOID
				}

				is CastExpression       -> {
					// A cast converts an independently typed value; its destination must
					// never change arithmetic or literal types inside the operand.
					val source = typeOf(expression.operand)
					val target = resolveType(expression.target)
					if (source != target && !(source.numeric && target.numeric)) {
						throw SemanticErrors.cast(source, target, expression.span)
					}
					target
				}

				is UnaryExpression      -> {
					val operand = if (expression.operator.lexeme == "-" && expression.operand is IntegerLiteral) {
						literalType(expression.operand, expected, negative = true)
					} else typeOf(expression.operand, if (expression.operator.lexeme == "!") null else expected)
					if (expression.operator.lexeme == Operators.NOT.character) {
						if (operand != ValueType.BOOL) {
							throw SemanticErrors.unary(expression.operator, expression.operand, operand, boolean = true)
						}
					} else if (!operand.numeric) {
						throw SemanticErrors.unary(expression.operator, expression.operand, operand, boolean = false)
					}
					operand
				}

				is BinaryExpression     -> {
					val numeric = expression.operator.lexeme in arithmeticOperators || expression.operator.lexeme in comparisonOperators
					val context = if (numeric) {
						integerContext(expression.left) ?: integerContext(expression.right) ?: (if (expression.operator.lexeme in arithmeticOperators) expected?.takeIf { it.integer } else null)
					} else null
					val left = typeOf(expression.left, context)
					val right = typeOf(expression.right, context)
					if (expression.operator.lexeme == Operators.MUL_MUL.character && left.integer && right.integer) {
						val exponent = expression.right
						if (exponent is UnaryExpression && exponent.operator.lexeme == "-" && exponent.operand is IntegerLiteral && exponent.operand.value.signum() > 0) {
							throw SemanticErrors.negativeExponent(exponent.span)
						}
					}
					if (numeric && left.integer && right.integer && left != right) {
						throw SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right, mixedIntegers = true)
					}
					if (expression.operator.lexeme in setOf(Operators.EQUAL.character, Operators.NOT_EQUAL.character)) {
						if (left != right && !(left.numeric && right.numeric)) {
							throw SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right, comparison = true)
						}
						ValueType.BOOL
					} else if (expression.operator.lexeme == Operators.PLUS.character && left == ValueType.STRING && right == ValueType.STRING) {
						ValueType.STRING
					} else if (expression.operator.lexeme in setOf(Operators.AND.character, Operators.OR.character)) {
						if (left != ValueType.BOOL || right != ValueType.BOOL) {
							throw SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right, boolean = true)
						}
						ValueType.BOOL
					} else {
						if (!left.numeric || !right.numeric) {
							throw SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right)
						}
						if (expression.operator.lexeme in setOf(Operators.LESS.character, Operators.GREATER.character, Operators.LESS_EQUAL.character, Operators.GREATER_EQUAL.character)) {
							ValueType.BOOL
						} else if (left.floating || right.floating) ValueType.commonFloating(left, right) else left
					}
				}

				else                    -> throw InternalErrors.invariant("Unsupported expression: $expression")
			}
			model.expressions[expression] = type
			return type
		}

		fun checkType(expected: ValueType, actual: ValueType, name: Token, value: Expression, declaration: Token) {
			if (actual != expected) {
				throw SemanticErrors.typeMismatch(expected, actual, name, value, declaration)
			}
		}

		checkStatement = { statement ->
			when (statement) {
				is BindingDeclaration  -> {
					scopes.last()[statement.name.lexeme]?.let { throw SemanticErrors.duplicate(statement.name, it.declaration) }
					val declaredType = statement.type?.let { resolveType(it) }
					val initializerType = typeOf(statement.initializer, declaredType)
					val bindingType = declaredType ?: initializerType
					checkType(bindingType, initializerType, statement.name, statement.initializer, statement.name)
					// A declaration is visible only after its initializer has been checked.
					scopes.last()[statement.name.lexeme] = Binding(bindingType, statement.mutable, statement.name)
					model.statements[statement] = bindingType
				}

				is AssignmentStatement -> {
					val binding = lookup(statement.name)
					if (!binding.mutable) {
						throw SemanticErrors.immutable(statement.name, binding.declaration)
					}
					checkType(binding.type, typeOf(statement.value, binding.type), statement.name, statement.value, binding.declaration)
					model.statements[statement] = binding.type
				}

				is UpdateStatement -> {
					val binding = lookup(statement.name)
					if (!binding.mutable) throw SemanticErrors.immutable(statement.name, binding.declaration)
					if (!binding.type.numeric) throw SemanticErrors.unary(statement.operator, NameExpression(statement.name), binding.type, boolean = false)
					model.statements[statement] = binding.type
				}

				is ExpressionStatement -> model.statements[statement] = typeOf(statement.expression, requireValue = statement.expression !is IfExpression)
				else                   -> throw InternalErrors.invariant("Unsupported statement: $statement")
			}
		}
		ast.statements.forEach(checkStatement)
		return model
	}

	private fun resolveType(annotation: TypeReference): ValueType {
		val name = annotation.name.lexeme
		if (annotation.unsigned && name != Keywords.INTEGER.toString()) {
			throw SemanticErrors.unsigned(annotation)
		}
		val widths = when (name) {
			Keywords.INTEGER.toString() -> ValueType.INTEGER_WIDTHS
			Keywords.FLOAT.toString()   -> ValueType.FLOAT_WIDTHS
			else                        -> null
		}
		if (widths != null) {
			val width = annotation.width?.lexeme?.toIntOrNull() ?: if (annotation.width == null) ValueType.DEFAULT_BITS else null
			if (width == null || width !in widths) {
				throw SemanticErrors.width(annotation, widths)
			}
			return if (name == Keywords.INTEGER.toString()) ValueType.Integer(width, annotation.unsigned)
			else ValueType.Floating(width)
		}
		if (annotation.width == null) {
			if (name == Keywords.BOOLEAN.toString()) return ValueType.BOOL
			if (name == Keywords.CHARACTER.toString()) return ValueType.CHAR
			if (name == Keywords.STRING.toString()) return ValueType.STRING
		}
		throw SemanticErrors.type(annotation)
	}

	companion object {

		private val arithmeticOperators = setOf("+", "-", "*", "/", "%", "**")
		private val comparisonOperators = setOf("==", "!=", "<", ">", "<=", ">=")
	}
}
