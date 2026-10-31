package cloth.semantic

import cloth.ast.SourceUnit
import cloth.builtin.BuiltinFunctions
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.symbols.*
import cloth.token.Token

/** Collects every function signature, then resolves lexical names in each body. */
class DeclarationAnalyzer {

	fun analyze(unit: SourceUnit): DeclarationModel {
		val fileScope = LexicalScope()
		val functions = mutableListOf<FunctionSymbol>()
		val signatures = linkedMapOf<FunctionSignature, FunctionSymbol>()
		val groups = linkedMapOf<String, MutableList<FunctionSymbol>>()
		for (declaration in unit.declarations) {
			val function = when (declaration) {
				is FunctionDeclaration -> FunctionSymbol(declaration)
			}
			signatures[function.signature]?.let { throw SemanticErrors.duplicateFunction(function.signature.spelling, it.name, function.name) }
			signatures[function.signature] = function
			functions.add(function)
			groups.getOrPut(function.name.lexeme) { mutableListOf() }.add(function)
		}
		for (overloads in groups.values) fileScope.declare(FunctionGroupSymbol(overloads.toList()))
		val model = DeclarationModel(fileScope, functions.toList())
		for (function in functions) {
			model.functionSymbols[function.declaration] = function
			val scope = LexicalScope(fileScope)
			model.functionScopes[function.declaration] = scope
			for (parameter in function.parameters) {
				scope.declare(parameter)
				model.parameterSymbols[parameter.declaration] = parameter
			}
		}
		for (function in functions) {
			val resolver = BodyResolver(model)
			val scope = model.scopeOf(function.declaration)
			when (val body = function.declaration.body) {
				is BlockBody      -> {
					model.blockScopes[body] = scope
					resolver.statements(body.statements, scope)
				}

				is ExpressionBody -> resolver.expression(body.expression, scope)
			}
		}
		return model
	}

	private class BodyResolver(private val model: DeclarationModel) {

		private fun lookup(name: Token, scope: LexicalScope): Symbol = scope.lookup(name.lexeme) ?: BuiltinFunctions[name.lexeme]?.let { BuiltinGroupSymbol(it, name) } ?: throw SemanticErrors.unknownName(name)

		private fun assignmentTarget(name: Token, scope: LexicalScope): BindingSymbol {
			val symbol = lookup(name, scope)
			if (symbol !is BindingSymbol) throw SemanticErrors.bindingRequired(name, symbol.name.takeUnless { symbol is BuiltinGroupSymbol })
			if (symbol is LocalSymbol && symbol.declaration.initializer is FunctionBlockExpression) throw SemanticErrors.blockAssignment(name, symbol.name)
			if (!symbol.mutable) {
				if (symbol is ParameterSymbol) throw SemanticErrors.immutableParameter(name, symbol.name)
				throw SemanticErrors.immutable(name, symbol.name)
			}
			return symbol
		}

		fun statements(statements: List<Statement>, scope: LexicalScope) {
			for (statement in statements) when (statement) {
				is BindingDeclaration         -> {
					scope.checkAvailable(statement.name)
					// An initializer can read an outer binding before its local shadow exists.
					expression(statement.initializer, scope)
					val symbol = LocalSymbol(statement)
					scope.declare(symbol)
					model.localSymbols[statement] = symbol
				}

				is AssignmentStatement        -> {
					model.assignments[statement] = assignmentTarget(statement.name, scope)
					expression(statement.value, scope)
				}

				is ElementAssignmentStatement -> {
					expression(statement.target, scope)
					expression(statement.value, scope)
				}

				is UpdateStatement            -> model.updates[statement] = assignmentTarget(statement.name, scope)
				is ExpressionStatement        -> expression(statement.expression, scope)
				is ReturnStatement            -> statement.value?.let { expression(it, scope) }
				else                          -> throw InternalErrors.invariant("Unsupported statement during name resolution: $statement")
			}
		}

		fun expression(expression: Expression, scope: LexicalScope) {
			when (expression) {
				is TypeReceiverExpression                                                                            -> Unit
				is ProviderAccessExpression                                                                          -> expression(expression.receiver, scope)
				is StateCallExpression                                                                               -> {
					expression(expression.receiver, scope)
					expression.arguments.forEach { expression(it, scope) }
				}

				is FunctionBlockExpression                                                                           -> {
					if (expression.declaration.returnType == null) throw SemanticErrors.blockReturnType(expression.declaration.name)
					val body = expression.declaration.body as BlockBody
					val child = LexicalScope(scope)
					model.blockScopes[body] = child
					statements(body.statements, child)
				}

				is ArrayLiteral                                                                                      -> expression.elements.forEach { expression(it, scope) }
				is IndexExpression                                                                                   -> {
					expression(expression.array, scope); expression(expression.index, scope)
				}

				is NameExpression                                                                                    -> model.names[expression] = lookup(expression.name, scope)
				is CallExpression                                                                                    -> {
					val symbol = lookup(expression.name, scope)
					if (symbol !is CallableGroupSymbol) throw SemanticErrors.notCallable(expression.name, symbol.name)
					model.calls[expression] = symbol
					expression.arguments.forEach { expression(it, scope) }
				}

				is UnaryExpression                                                                                   -> expression(expression.operand, scope)
				is BinaryExpression                                                                                  -> {
					expression(expression.left, scope)
					expression(expression.right, scope)
				}

				is CastExpression                                                                                    -> expression(expression.operand, scope)
				is TypeCheckExpression                                                                               -> expression(expression.operand, scope)
				is InterpolatedString                                                                                -> expression.parts.forEach { expression(it, scope) }
				is IfExpression                                                                                      -> {
					for (branch in expression.branches) {
						expression(branch.condition, scope)
						val child = LexicalScope(scope)
						model.branchScopes[branch] = child
						statements(branch.statements, child)
					}
					expression.otherwise?.let {
						val child = LexicalScope(scope)
						model.elseScopes[expression] = child
						statements(it, child)
					}
				}

				is IntegerLiteral, is FloatingPointLiteral, is BooleanLiteral, is CharacterLiteral, is StringLiteral -> Unit
				else                                                                                                 -> throw InternalErrors.invariant("Unsupported expression during name resolution: $expression")
			}
		}
	}
}
