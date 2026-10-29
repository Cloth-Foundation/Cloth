package cloth.semantic

import cloth.ast.ASTStructure
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.token.Token

/** Checks the current, single statement scope before any LLVM code is emitted. */
class SemanticAnalyzer {

	private enum class ValueType(val spelling: String) { INT("int"), FLOAT("float") }
	private data class Binding(val type: ValueType, val mutable: Boolean)

	fun analyze(ast: ASTStructure) {
		val bindings = mutableMapOf<String, Binding>()

		fun lookup(name: Token): Binding = bindings[name.lexeme]
			?: throw SemanticException("Unknown binding '${name.lexeme}' at ${name.span}")

		fun typeOf(expression: Expression): ValueType = when (expression) {
			is IntegerLiteral -> ValueType.INT
			is FloatingPointLiteral -> ValueType.FLOAT
			is NameExpression -> lookup(expression.name).type
			is UnaryExpression -> typeOf(expression.operand)
			is BinaryExpression -> {
				val left = typeOf(expression.left)
				val right = typeOf(expression.right)
				if (left == ValueType.FLOAT || right == ValueType.FLOAT) ValueType.FLOAT else ValueType.INT
			}
			else -> error("Unsupported expression: $expression")
		}

		fun checkType(expected: ValueType, expression: Expression, name: Token) {
			val actual = typeOf(expression)
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
					if (statement.type.name.lexeme != ValueType.INT.spelling) {
						throw SemanticException("Binding type '${statement.type.name.lexeme}' is not supported yet; only explicit 'int' bindings are implemented at ${statement.type.name.span}")
					}
					checkType(ValueType.INT, statement.initializer, statement.name)
					// A declaration is visible only after its initializer has been checked.
					bindings[statement.name.lexeme] = Binding(ValueType.INT, statement.mutable)
				}
				is AssignmentStatement -> {
					val binding = lookup(statement.name)
					if (!binding.mutable) {
						throw SemanticException("Cannot reassign immutable binding '${statement.name.lexeme}' at ${statement.name.span}")
					}
					checkType(binding.type, statement.value, statement.name)
				}
				is ExpressionStatement -> typeOf(statement.expression)
				else -> error("Unsupported statement: $statement")
			}
		}
	}
}
