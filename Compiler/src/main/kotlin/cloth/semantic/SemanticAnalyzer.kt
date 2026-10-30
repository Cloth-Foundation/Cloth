package cloth.semantic

import cloth.ast.ASTStructure
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.token.Token

/** Legacy snippet checking; declaration bodies share its expression rules. */
class SemanticAnalyzer {

	private data class Binding(val type: ValueType, val mutable: Boolean, val declaration: Token)

	fun analyze(ast: ASTStructure): SemanticModel {
		val scopes = mutableListOf(mutableMapOf<String, Binding>())
		val model = SemanticModel()
		fun lookup(name: Token): Binding = scopes.asReversed().firstNotNullOfOrNull { it[name.lexeme] } ?: throw SemanticErrors.unknown(name)
		val typing = object : ExpressionTyping(model) {
			override fun nameType(expression: NameExpression) = lookup(expression.name).type
			override fun withScope(action: () -> Unit) {
				scopes.add(mutableMapOf())
				try { action() } finally { scopes.removeAt(scopes.lastIndex) }
			}
			override fun statement(statement: Statement) {
				when (statement) {
					is BindingDeclaration -> {
						scopes.last()[statement.name.lexeme]?.let { throw SemanticErrors.duplicate(statement.name, it.declaration) }
						val declaredType = statement.type?.let { resolveType(it) }
						val initializerType = typeOf(statement.initializer, declaredType)
						val bindingType = if (declaredType is ValueType.ArrayValue && initializerType is ValueType.ArrayValue && declaredType.accepts(initializerType)) initializerType else declaredType ?: initializerType
						checkType(bindingType, initializerType, statement.name, statement.initializer, statement.name)
						scopes.last()[statement.name.lexeme] = Binding(bindingType, statement.mutable, statement.name)
						model.statements[statement] = bindingType
					}
					is AssignmentStatement -> {
						val binding = lookup(statement.name)
						if (!binding.mutable) throw SemanticErrors.immutable(statement.name, binding.declaration)
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
					else -> throw InternalErrors.invariant("Unsupported statement: $statement")
				}
			}
			fun check(statements: List<Statement>) = statements.forEach { statement(it) }
			private fun checkType(expected: ValueType, actual: ValueType, name: Token, value: Expression, declaration: Token) {
				if (!expected.accepts(actual)) throw SemanticErrors.typeMismatch(expected, actual, name, value, declaration)
			}
		}
		typing.check(ast.statements)
		return model
	}
}
