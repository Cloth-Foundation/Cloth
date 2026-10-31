package cloth.semantic

import cloth.ast.SourceUnit
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.symbols.*
import cloth.token.TokenSpan

/** Stage 3 checks calls; FunctionAnalyzer enables stage 4 return validation. */
class CallAnalyzer internal constructor(private val validateReturns: Boolean) {

	constructor() : this(false)

	fun analyze(unit: SourceUnit): FunctionSemanticModel = analyze(DeclarationAnalyzer().analyze(unit))

	fun analyze(declarations: DeclarationModel): FunctionSemanticModel {
		val signatures = declarations.functions.associateWith { function ->
			val parameters = function.parameters.map { parameter ->
				TypeResolver.resolve(parameter.declaredType, declarations = true).also {
					if (it == ValueType.VOID) throw SemanticErrors.voidType(parameter.declaration.type.span)
				}
			}
			FunctionType(parameters, function.declaration.returnType?.let { TypeResolver.resolve(it, declarations = true) } ?: ValueType.VOID)
		}
		val model = FunctionSemanticModel(declarations, signatures)
		for (function in declarations.functions) function.parameters.forEachIndexed { index, parameter -> model.bindings[parameter] = signatures.getValue(function).parameters[index] }
		for (function in declarations.functions) {
			val checker = BodyTyping(model, function.declaration, signatures.getValue(function).returns, validateReturns)
			when (val body = function.declaration.body) {
				is BlockBody -> {
					checker.check(body.statements)
					if (validateReturns && signatures.getValue(function).returns != ValueType.VOID && model.controlFlow.of(body).canContinue) {
						throw SemanticErrors.missingReturn(function.declaration, body)
					}
				}
				is ExpressionBody -> checker.checkExpressionBody(body)
			}
		}
		return model
	}

	private class BodyTyping(private val facts: FunctionSemanticModel, private val function: FunctionDeclaration, private val returnType: ValueType, private val validateReturns: Boolean, private val probe: Boolean = false) : ExpressionTyping(facts.types, probe, declarations = true) {

		override fun completion(expression: Expression) = if (validateReturns) facts.controlFlow.of(expression) else ControlFlow.CONTINUE
		override fun completion(statements: List<Statement>) = if (validateReturns) facts.controlFlow.of(statements) else ControlFlow.CONTINUE
		private fun compatible(actual: ValueType, expected: ValueType) = expected.accepts(actual)
		private fun checkReturnedType(actual: ValueType, span: TokenSpan) {
			if (compatible(actual, returnType)) return
			if (returnType == ValueType.VOID) throw SemanticErrors.valueInVoid(function, actual, span)
			throw SemanticErrors.returnType(function, returnType, actual, span)
		}
		fun checkExpressionBody(body: ExpressionBody) {
			val actual = typeOf(body.expression, returnType, requireValue = validateReturns, allowVoidResult = validateReturns)
			if (validateReturns) checkReturnedType(actual, body.expression.span)
		}

		override fun nameType(expression: NameExpression): ValueType {
			val symbol = facts.declarations.symbolOf(expression)
			if (symbol !is BindingSymbol) reject { SemanticErrors.functionValue(expression.name) }
			return facts.bindings.getValue(symbol)
		}
		override fun withScope(action: () -> Unit) = action() // Resolved symbols already encode lexical scope.
		fun check(statements: List<Statement>) = statements.forEach { statement(it) }
		override fun statement(statement: Statement) {
			when (statement) {
				is BindingDeclaration -> {
					val symbol = facts.declarations.symbolOf(statement)
					val expected = statement.type?.let { resolveType(it) }
					val block = statement.initializer as? FunctionBlockExpression
					if (block != null) {
						val contract = expected ?: throw SemanticErrors.blockReturnType(statement.name)
						val body = block.declaration.body as BlockBody
						val checker = BodyTyping(facts, block.declaration, contract, validateReturns)
						checker.check(body.statements)
						if (validateReturns && contract != ValueType.VOID && facts.controlFlow.of(body).canContinue) {
							throw SemanticErrors.missingReturn(block.declaration, body)
						}
						facts.bindings[symbol] = contract
						model.statements[statement] = contract
						model.expressions[block] = contract
						return
					}
					if (expected == ValueType.VOID) reject { SemanticErrors.voidType(statement.type.span) }
					val actual = typeOf(statement.initializer, expected)
					val type = if (expected is ValueType.ArrayValue && actual is ValueType.ArrayValue && expected.accepts(actual)) expected.inferArraySizes(actual) else expected ?: actual
					if (!compatible(actual, type)) reject { SemanticErrors.typeMismatch(type, actual, statement.name, statement.initializer, statement.name) }
					facts.bindings[symbol] = type
					model.statements[statement] = type
				}
				is AssignmentStatement -> {
					val symbol = facts.declarations.targetOf(statement)
					val type = facts.bindings.getValue(symbol)
					val actual = typeOf(statement.value, type)
					if (!compatible(actual, type)) reject { SemanticErrors.typeMismatch(type, actual, statement.name, statement.value, symbol.name) }
					model.statements[statement] = type
				}
				is UpdateStatement -> {
					val type = facts.bindings.getValue(facts.declarations.targetOf(statement))
					if (!type.numeric && type != ValueType.NEVER) reject { SemanticErrors.unary(statement.operator, NameExpression(statement.name), type, boolean = false) }
					model.statements[statement] = type
				}
				is ElementAssignmentStatement -> {
					val type = typeOf(statement.target)
					val actual = typeOf(statement.value, type)
					if (type != ValueType.NEVER && !compatible(actual, type)) reject { SemanticErrors.array("Array element requires '${type.spelling}', got '${actual.spelling}'", statement.value.span) }
					model.statements[statement] = type
				}
				is ExpressionStatement -> model.statements[statement] = typeOf(statement.expression, requireValue = false)
				is ReturnStatement -> {
					if (statement.value == null) {
						if (validateReturns && returnType != ValueType.VOID) throw SemanticErrors.returnValueRequired(function, returnType, statement.span)
					} else {
						// The enclosing return contract is fixed, not an overload candidate.
						// Its expression errors must stay fatal even inside an argument probe.
						val checker = if (probe && validateReturns) BodyTyping(facts, function, returnType, validateReturns) else this
						val actual = checker.typeOf(statement.value, returnType, requireValue = validateReturns, allowVoidResult = validateReturns)
						if (validateReturns) checkReturnedType(actual, statement.value.span)
					}
					model.statements[statement] = ValueType.VOID
				}
				else -> throw InternalErrors.invariant("Unsupported statement during call checking: $statement")
			}
		}

		override fun callType(expression: CallExpression): ValueType {
			facts.calls[expression]?.let { return facts.typeOf(it).returns }
			val group = facts.declarations.candidatesOf(expression).overloads
			val candidates = group.filter { facts.typeOf(it).parameters.size == expression.arguments.size }
			if (candidates.isEmpty()) throw SemanticErrors.callArity(expression, group)

			// Prefer a whole-argument exact match using each expression's ordinary type.
			val ordinary = facts.fork()
			val defaults = try {
				val checker = BodyTyping(ordinary, function, returnType, validateReturns, probe = true)
				expression.arguments.map { checker.typeOf(it) }
			} catch (_: CandidateMismatch) { null }
			val exact = candidates.singleOrNull { facts.typeOf(it).parameters == defaults }
				?: candidates.singleOrNull { candidate -> defaults != null && facts.typeOf(candidate).parameters.zip(defaults).all { (parameter, actual) -> parameter.accepts(actual) } }
			if (exact != null) {
				facts.commit(ordinary)
				facts.calls[expression] = exact
				return facts.typeOf(exact).returns
			}

			var selected: FunctionSymbol? = null
			var selectedFacts: FunctionSemanticModel? = null
			for (candidate in candidates) {
				val trial = facts.fork()
				val checker = BodyTyping(trial, function, returnType, validateReturns, probe = true)
				val matches = try {
					val parameters = facts.typeOf(candidate).parameters
					expression.arguments.indices.all { index -> compatible(checker.typeOf(expression.arguments[index], parameters[index], contextualFloat = true), parameters[index]) }
				} catch (_: CandidateMismatch) { false }
				if (matches) {
					if (selected != null) throw SemanticErrors.ambiguousCall(expression, listOf(selected, candidate))
					selected = candidate
					selectedFacts = trial
				}
			}
			if (selected == null) throw SemanticErrors.noMatchingCall(expression, candidates)
			facts.commit(checkNotNull(selectedFacts))
			facts.calls[expression] = selected
			return facts.typeOf(selected).returns
		}
	}
}
