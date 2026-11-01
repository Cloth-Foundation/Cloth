package cloth.semantic

import cloth.ast.SourceUnit
import cloth.builtin.BoundBuiltinCall
import cloth.builtin.BuiltinDefinition
import cloth.builtin.BuiltinOverload
import cloth.data.CArray
import cloth.data.ValueType
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.symbols.BindingSymbol
import cloth.semantic.symbols.BuiltinGroupSymbol
import cloth.semantic.symbols.FunctionGroupSymbol
import cloth.semantic.symbols.FunctionSymbol
import cloth.target.TargetLayout

/** Stage 3 checks calls; FunctionAnalyzer enables stage 4 return validation. */
class CallAnalyzer internal constructor(private val validateReturns: Boolean, private val target: TargetLayout? = null) {

	constructor(target: TargetLayout? = null) : this(false, target)

	fun analyze(unit: SourceUnit): FunctionSemanticModel = analyze(DeclarationAnalyzer(target ?: TargetLayout.native()).analyze(unit))

	fun analyze(declarations: DeclarationModel): FunctionSemanticModel {
		if (target != null && target != declarations.target) throw InternalErrors.invariant("Declaration and call analysis targets differ")
		val resolver = TypeResolver(declarations.target)
		val signatures = declarations.functions.associateWith { function ->
			val parameters = function.parameters.map { parameter ->
				resolver.resolve(parameter.declaredType, declarations = true).also {
					if (it == ValueType.VOID) throw SemanticErrors.voidType(parameter.declaration.type.span)
				}
			}
			val results = function.declaration.returnTypes.map { annotation ->
				resolver.resolve(annotation, declarations = true).also {
					if (function.declaration.returnTypes.size > 1 && it == ValueType.VOID) throw SemanticErrors.voidResult(annotation.span)
				}
			}
			FunctionType(parameters, ResultShape.of(results))
		}
		val model = FunctionSemanticModel(declarations, signatures)
		for (function in declarations.functions) function.parameters.forEachIndexed { index, parameter -> model.bindings[parameter] = signatures.getValue(function).parameters[index] }
		for (function in declarations.functions) {
			val checker = BodyTyping(model, function.declaration, signatures.getValue(function).results, validateReturns)
			when (val body = function.declaration.body) {
				is BlockBody      -> {
					checker.check(body.statements)
					if (validateReturns && signatures.getValue(function).results != ResultShape.Empty && model.controlFlow.of(body).canContinue) {
						throw SemanticErrors.missingReturn(function.declaration, body)
					}
				}

				is ExpressionBody -> checker.checkExpressionBody(body)
			}
		}
		for (function in declarations.functions) {
			val metadata = MetadataAnalysis(model.types, function.parameters.map { it.name.lexeme to it.mutable }, model.controlFlow)
			when (val body = function.declaration.body) {
				is BlockBody      -> metadata.statements(body.statements)
				is ExpressionBody -> metadata.expression(body.expression)
			}
		}
		return model
	}

	private class BodyTyping(private val facts: FunctionSemanticModel, private val function: FunctionDeclaration, private val returnResults: ResultShape, private val validateReturns: Boolean, private val probe: Boolean = false) : ExpressionTyping(facts.types, probe, declarations = true) {

		override fun completion(expression: Expression) = if (validateReturns) facts.controlFlow.of(expression) else ControlFlow.CONTINUE
		override fun completion(statements: List<Statement>) = if (validateReturns) facts.controlFlow.of(statements) else ControlFlow.CONTINUE
		private fun compatible(actual: ValueType, expected: ValueType) = expected.accepts(actual)
		private fun checkReturnedResults(actual: ResultShape, expression: Expression) {
			if (actual == ResultShape.Never) return
			if (returnResults == ResultShape.Empty && actual != ResultShape.Empty) throw SemanticErrors.valueInVoid(function, actual, expression.span)
			if (actual.values.size != returnResults.values.size) {
				if (returnResults is ResultShape.Single && actual == ResultShape.Empty) throw SemanticErrors.returnType(function, returnResults.type, ValueType.VOID, expression.span)
				throw SemanticErrors.resultArity(function, returnResults, actual, expression.span)
			}
			for ((index, pair) in returnResults.values.zip(actual.values).withIndex()) {
				val (expected, received) = pair
				if (!compatible(received, expected)) {
					if (returnResults is ResultShape.Single) throw SemanticErrors.returnType(function, expected, received, expression.span)
					val span = (expression as? ResultListExpression)?.values?.get(index)?.span ?: expression.span
					throw SemanticErrors.resultPosition(function, expected, received, index, span)
				}
			}
		}

		fun checkExpressionBody(body: ExpressionBody) {
			val actual = resultOf(body.expression, returnResults, requireValue = validateReturns, allowVoidResult = validateReturns)
			if (validateReturns) checkReturnedResults(actual, body.expression)
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
				is BindingGroupDeclaration    -> {
					val plan = definitionPlan(statement)
					facts.declarations.symbolsOf(statement).forEachIndexed { index, symbol ->
						if (symbol != null) facts.bindings[symbol] = plan.slots[index].type
					}
				}

				is BindingDeclaration         -> {
					val symbol = facts.declarations.symbolOf(statement)
					val expected = statement.type?.let { resolveType(it) }
					val block = statement.initializer as? FunctionBlockExpression
					if (block != null) {
						val contract = expected ?: throw SemanticErrors.blockReturnType(statement.name)
						val body = block.declaration.body as BlockBody
						val checker = BodyTyping(facts, block.declaration, ResultShape.of(contract), validateReturns)
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
					val type = if (expected is CArray && actual is CArray && expected.accepts(actual)) expected.inferArraySizes(actual) else expected ?: actual
					if (!compatible(actual, type)) reject { SemanticErrors.typeMismatch(type, actual, statement.name, statement.initializer, statement.name) }
					facts.bindings[symbol] = type
					model.statements[statement] = type
				}

				is AssignmentStatement        -> {
					val symbol = facts.declarations.targetOf(statement)
					val type = facts.bindings.getValue(symbol)
					val actual = typeOf(statement.value, type)
					if (!compatible(actual, type)) reject { SemanticErrors.typeMismatch(type, actual, statement.name, statement.value, symbol.name) }
					model.statements[statement] = type
				}

				is MultiAssignmentStatement   -> {
					val targets = facts.declarations.targetsOf(statement)
					assignmentPlan(statement, targets.map { it?.let { facts.bindings.getValue(it) } }, targets.map { it?.name })
				}

				is UpdateStatement            -> {
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

				is ExpressionStatement        -> model.recordResult(statement, resultOf(statement.expression, requireValue = false))
				is ReturnStatement            -> {
					if (statement.value == null) {
						if (validateReturns && returnResults != ResultShape.Empty) throw SemanticErrors.returnValueRequired(function, returnResults, statement.span)
					} else {
						// The enclosing return contract is fixed, not an overload candidate.
						// Its expression errors must stay fatal even inside an argument probe.
						val checker = if (probe && validateReturns) BodyTyping(facts, function, returnResults, validateReturns) else this
						val actual = checker.resultOf(statement.value, returnResults, requireValue = validateReturns, allowVoidResult = validateReturns)
						if (validateReturns) checkReturnedResults(actual, statement.value)
					}
					model.statements[statement] = ValueType.VOID
				}

				else                          -> throw InternalErrors.invariant("Unsupported statement during call checking: $statement")
			}
		}

		override fun callType(expression: CallExpression): ValueType = callResults(expression).let { it.scalarOrNull() ?: throw SemanticErrors.scalarResults(it, expression.span) }

		override fun callResults(expression: CallExpression): ResultShape {
			facts.calls[expression]?.let { return it.type.results }
			val target = facts.declarations.candidatesOf(expression)
			if (target is BuiltinGroupSymbol) return ResultShape.of(builtinCallType(expression, target.definition))
			val group = (target as FunctionGroupSymbol).overloads
			val candidates = group.filter { facts.typeOf(it).parameters.size == expression.arguments.size }
			if (candidates.isEmpty()) throw SemanticErrors.callArity(expression, group)

			// Prefer a whole-argument exact match using each expression's ordinary type.
			val ordinary = facts.fork()
			val defaults = try {
				val checker = BodyTyping(ordinary, function, returnResults, validateReturns, probe = true)
				expression.arguments.map { checker.typeOf(it) }
			} catch (_: CandidateMismatch) {
				null
			}
			val exact = candidates.singleOrNull { facts.typeOf(it).parameters == defaults } ?: candidates.singleOrNull { candidate -> defaults != null && facts.typeOf(candidate).parameters.zip(defaults).all { (parameter, actual) -> parameter.accepts(actual) } }
			if (exact != null) {
				facts.commit(ordinary)
				facts.calls[expression] = ResolvedCall.Source(exact, facts.typeOf(exact))
				return facts.typeOf(exact).results
			}

			var selected: FunctionSymbol? = null
			var selectedFacts: FunctionSemanticModel? = null
			for (candidate in candidates) {
				val trial = facts.fork()
				val checker = BodyTyping(trial, function, returnResults, validateReturns, probe = true)
				val matches = try {
					val parameters = facts.typeOf(candidate).parameters
					expression.arguments.indices.all { index -> compatible(checker.typeOf(expression.arguments[index], parameters[index], contextualFloat = true), parameters[index]) }
				} catch (_: CandidateMismatch) {
					false
				}
				if (matches) {
					if (selected != null) throw SemanticErrors.ambiguousCall(expression, listOf(selected, candidate))
					selected = candidate
					selectedFacts = trial
				}
			}
			if (selected == null) throw SemanticErrors.noMatchingCall(expression, candidates)
			facts.commit(checkNotNull(selectedFacts))
			facts.calls[expression] = ResolvedCall.Source(selected, facts.typeOf(selected))
			return facts.typeOf(selected).results
		}

		private fun builtinCallType(expression: CallExpression, definition: BuiltinDefinition<*>): ValueType {
			val candidates = definition.function.overloads.filter { it.parameters.size == expression.arguments.size }
			if (candidates.isEmpty()) throw SemanticErrors.builtinArity(expression, definition)

			fun bind(overload: BuiltinOverload, trial: FunctionSemanticModel, contextual: Boolean): BoundBuiltinCall? {
				val checker = BodyTyping(trial, function, returnResults, validateReturns, probe = true)
				val arguments = expression.arguments.mapIndexed { index, argument ->
					val parameter = overload.parameters[index]
					val actual = checker.typeOf(argument, if (contextual) parameter.expectedType else null, contextualFloat = contextual && parameter.expectedType != null)
					parameter.bind(actual, trial.target) ?: return null
				}
				return BoundBuiltinCall(definition, overload, arguments)
			}

			// With a fixed contract, preserve diagnostics from the argument itself.
			// In particular Text supplies no contextual numeric type or boxing.
			if (candidates.size == 1) {
				val overload = candidates.single()
				val checker = BodyTyping(facts, function, returnResults, validateReturns)
				val arguments = expression.arguments.mapIndexed { index, argument ->
					val parameter = overload.parameters[index]
					val actual = checker.typeOf(argument, parameter.expectedType, contextualFloat = parameter.expectedType != null)
					parameter.bind(actual, facts.target) ?: throw SemanticErrors.builtinArgument(expression, argument, actual, parameter)
				}
				facts.calls[expression] = ResolvedCall.Builtin(BoundBuiltinCall(definition, overload, arguments))
				return overload.returns
			}

			data class Match(val call: BoundBuiltinCall, val trial: FunctionSemanticModel)

			fun matches(contextual: Boolean) = candidates.mapNotNull { candidate ->
				val trial = facts.fork()
				val call = try {
					bind(candidate, trial, contextual)
				} catch (_: CandidateMismatch) {
					null
				}
				call?.let { Match(it, trial) }
			}

			val ordinary = matches(contextual = false)
			val exact = ordinary.filter { match -> match.call.overload.parameters.zip(match.call.arguments).all { (parameter, argument) -> parameter.expectedType == argument.sourceType } }
			val fixed = ordinary.filter { match -> match.call.overload.parameters.all { it.expectedType != null } }
			val choices = exact.ifEmpty { fixed }.ifEmpty { ordinary }.ifEmpty { matches(contextual = true) }
			if (choices.isEmpty()) throw SemanticErrors.builtinNoMatch(expression, definition)
			if (choices.size > 1) throw SemanticErrors.builtinAmbiguous(expression, definition)
			val selected = choices.single()
			facts.commit(selected.trial)
			facts.calls[expression] = ResolvedCall.Builtin(selected.call)
			return selected.call.overload.returns
		}
	}
}
