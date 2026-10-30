package cloth.semantic

import cloth.ast.ASTStructure
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.token.Operators
import cloth.token.Token

/** Checks the current, single statement scope before any LLVM code is emitted. */
class SemanticAnalyzer {

	private enum class ValueType(val spelling: String) { INT("int"), FLOAT("float"), BOOL("bool") }
	private data class Binding(val type: ValueType, val mutable: Boolean)

	fun analyze(ast: ASTStructure) {
		val bindings = mutableMapOf<String, Binding>()

		fun lookup(name: Token): Binding = bindings[name.lexeme]
			?: throw SemanticException("Unknown binding '${name.lexeme}' at ${name.span}")

		fun typeOf(expression: Expression): ValueType = when (expression) {
			is IntegerLiteral -> ValueType.INT
			is FloatingPointLiteral -> ValueType.FLOAT
			is BooleanLiteral -> ValueType.BOOL
			is NameExpression -> lookup(expression.name).type
			is UnaryExpression -> {
				val operand = typeOf(expression.operand)
				if (expression.operator.lexeme == Operators.NOT.character) {
					if (operand != ValueType.BOOL) {
						throw SemanticException("Operator '!' requires a boolean operand, got '${operand.spelling}' at ${expression.operator.span}")
					}
				} else if (operand == ValueType.BOOL) {
					throw SemanticException("Operator '${expression.operator.lexeme}' requires a numeric operand, got 'bool' at ${expression.operator.span}")
				}
				operand
			}
			is BinaryExpression -> {
				val left = typeOf(expression.left)
				val right = typeOf(expression.right)
				if (expression.operator.lexeme in setOf(Operators.AND.character, Operators.OR.character)) {
					if (left != ValueType.BOOL || right != ValueType.BOOL) {
						throw SemanticException("Operator '${expression.operator.lexeme}' requires boolean operands, got '${left.spelling}' and '${right.spelling}' at ${expression.operator.span}")
					}
					ValueType.BOOL
				} else {
					if (left == ValueType.BOOL || right == ValueType.BOOL) {
						throw SemanticException("Operator '${expression.operator.lexeme}' requires numeric operands, got '${left.spelling}' and '${right.spelling}' at ${expression.operator.span}")
					}
					if (left == ValueType.FLOAT || right == ValueType.FLOAT) ValueType.FLOAT else ValueType.INT
				}
			}
			else -> error("Unsupported expression: $expression")
		}

		fun checkType(expected: ValueType, actual: ValueType, name: Token) {
			if (actual != expected) {
				throw SemanticException("Cannot assign '${actual.spelling}' to '${expected.spelling}' binding '${name.lexeme}' at ${name.span}")
			}
		}

		for (statement in ast.statements) {
			when (statement) {
				is BindingDeclaration -> {
					if (bindings.containsKey(statement.name.lexeme)) {
						throw SemanticException("Binding '${statement.name.lexeme}' is already declared in this scope at ${statement.name.span}")
					}
					val declaredType = statement.type?.let { annotation ->
						ValueType.entries.firstOrNull { it.spelling == annotation.name.lexeme }
							?: throw SemanticException("Binding type '${annotation.name.lexeme}' is not supported yet; only explicit 'int', 'float', and 'bool' annotations are implemented at ${annotation.name.span}")
					}
					val initializerType = typeOf(statement.initializer)
					val bindingType = declaredType ?: initializerType
					checkType(bindingType, initializerType, statement.name)
					// A declaration is visible only after its initializer has been checked.
					bindings[statement.name.lexeme] = Binding(bindingType, statement.mutable)
				}
				is AssignmentStatement -> {
					val binding = lookup(statement.name)
					if (!binding.mutable) {
						throw SemanticException("Cannot reassign immutable binding '${statement.name.lexeme}' at ${statement.name.span}")
					}
					checkType(binding.type, typeOf(statement.value), statement.name)
				}
				is ExpressionStatement -> typeOf(statement.expression)
				else -> error("Unsupported statement: $statement")
			}
		}
	}
}
