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
import cloth.target.TargetLayout
import cloth.token.Token

/** Collects every function signature, then resolves lexical names in each body. */
class DeclarationAnalyzer(private val target: TargetLayout = TargetLayout.native()) {

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
		val model = DeclarationModel(fileScope, functions.toList(), target)
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
			val defaultScope = LexicalScope(fileScope)
			for ((index, parameter) in function.parameters.withIndex()) {
				parameter.declaration.defaultValue?.let { fallback ->
					BodyResolver(model, defaultContext = true, unavailable = function.parameters.drop(index).map { it.name.lexeme }.toSet()).expression(fallback, defaultScope)
				}
				defaultScope.declare(parameter)
			}
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

	private class BodyResolver(private val model: DeclarationModel, private val defaultContext: Boolean = false, private val unavailable: Set<String> = emptySet(), private val allowDefaultReturn: Boolean = false) {

		private fun lookup(name: Token, scope: LexicalScope): Symbol {
			if (name.lexeme in unavailable && scope.lookup(name.lexeme) !is LocalSymbol && scope.lookup(name.lexeme) !is GroupLocalSymbol) throw SemanticErrors.parameterDefault("A default cannot read its own or a later parameter '${name.lexeme}'", name.span)
			return scope.lookup(name.lexeme) ?: BuiltinFunctions[name.lexeme]?.let { BuiltinGroupSymbol(it, name) } ?: throw SemanticErrors.unknownName(name)
		}

		private fun assignmentTarget(name: Token, scope: LexicalScope): BindingSymbol {
			val symbol = lookup(name, scope)
			if (defaultContext && symbol is ParameterSymbol) throw SemanticErrors.parameterDefault("Earlier parameters are read-only in defaults", name.span)
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
				is BindingGroupDeclaration    -> {
					if (statement.initializer is FunctionBlockExpression) throw SemanticErrors.blockReception(statement.span)
					val seen = mutableMapOf<String, Token>()
					for (target in statement.targets) if (target is ReceivingTarget.Named) {
						scope.checkAvailable(target.token)
						seen.putIfAbsent(target.token.lexeme, target.token)?.let { throw SemanticErrors.duplicate(target.token, it) }
					}
					// All producer positions resolve before any receiving name is introduced.
					expression(statement.initializer, scope)
					model.groupSymbols[statement] = statement.targets.mapIndexed { index, target ->
						if (target is ReceivingTarget.Discard) null else GroupLocalSymbol(statement, index).also { scope.declare(it) }
					}
				}

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

				is MultiAssignmentStatement   -> {
					model.multiAssignments[statement] = statement.targets.map { if (it is ReceivingTarget.Discard) null else assignmentTarget(it.token, scope) }
					expression(statement.value, scope)
				}

				is ElementAssignmentStatement -> {
					expression(statement.target, scope)
					var root: Expression = statement.target.array
					while (root is IndexExpression) root = root.array
					if (defaultContext && root is NameExpression && model.symbolOf(root) is ParameterSymbol) throw SemanticErrors.parameterDefault("Earlier parameter arrays are read-only in defaults", statement.target.span)
					expression(statement.value, scope)
				}

				is UpdateStatement            -> model.updates[statement] = assignmentTarget(statement.name, scope)
				is ExpressionStatement        -> expression(statement.expression, scope)
				is ReturnStatement            -> {
					if (defaultContext && !allowDefaultReturn) throw SemanticErrors.parameterDefault("A return cannot escape from a parameter default", statement.span)
					statement.value?.let { expression(it, scope) }
				}

				else                          -> throw InternalErrors.invariant("Unsupported statement during name resolution: $statement")
			}
		}

		fun expression(expression: Expression, scope: LexicalScope) {
			when (expression) {
				is ResultListExpression                                                                                              -> expression.values.forEach { expression(it, scope) }
				is TypeReceiverExpression                                                                                            -> Unit
				is ProviderAccessExpression                                                                                          -> expression(expression.receiver, scope)
				is StateCallExpression                                                                                               -> {
					expression(expression.receiver, scope)
					expression.arguments.forEach { expression(it, scope) }
				}

				is FunctionBlockExpression                                                                                           -> {
					if (expression.declaration.returnType == null) throw SemanticErrors.blockReturnType(expression.declaration.name)
					val body = expression.declaration.body as BlockBody
					val child = LexicalScope(scope)
					model.blockScopes[body] = child
					BodyResolver(model, defaultContext, unavailable, allowDefaultReturn = true).statements(body.statements, child)
				}

				is ArrayLiteral                                                                                                      -> expression.elements.forEach { expression(it, scope) }
				is IndexExpression                                                                                                   -> {
					expression(expression.array, scope); expression(expression.index, scope)
				}

				is NameExpression                                                                                                    -> model.names[expression] = lookup(expression.name, scope)
				is CallExpression                                                                                                    -> {
					ArgumentBinder.validate(expression.suppliedArguments)
					val symbol = lookup(expression.name, scope)
					if (symbol !is CallableGroupSymbol) throw SemanticErrors.notCallable(expression.name, symbol.name)
					model.calls[expression] = symbol
					expression.arguments.forEach { expression(it, scope) }
				}

				is UnaryExpression                                                                                                   -> expression(expression.operand, scope)
				is ElvisExpression                                                                                                   -> {
					expression(expression.left, scope)
					expression(expression.fallback, scope)
				}

				is BinaryExpression                                                                                                  -> {
					expression(expression.left, scope)
					expression(expression.right, scope)
				}

				is CastExpression                                                                                                    -> expression(expression.operand, scope)
				is TypeCheckExpression                                                                                               -> expression(expression.operand, scope)
				is InterpolatedString                                                                                                -> expression.parts.forEach { expression(it, scope) }
				is IfExpression                                                                                                      -> {
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

				is NullLiteral, is IntegerLiteral, is FloatingPointLiteral, is BooleanLiteral, is CharacterLiteral, is StringLiteral -> Unit
				else                                                                                                                 -> throw InternalErrors.invariant("Unsupported expression during name resolution: $expression")
			}
		}
	}
}
