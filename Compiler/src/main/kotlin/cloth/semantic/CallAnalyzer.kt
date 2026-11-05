package cloth.semantic

import cloth.ast.SourceUnit
import cloth.builtin.BoundBuiltinCall
import cloth.builtin.BuiltinDefinition
import cloth.builtin.BuiltinOverload
import cloth.data.CArray
import cloth.data.CNullable
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
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

/** Stage 3 checks calls; FunctionAnalyzer enables stage 4 return validation. */
class CallAnalyzer internal constructor(private val validateReturns: Boolean, private val target: TargetLayout? = null) {

	constructor(target: TargetLayout? = null) : this(false, target)

	fun analyze(unit: SourceUnit): FunctionSemanticModel = analyze(DeclarationAnalyzer(target ?: TargetLayout.native()).analyze(unit))

	fun analyze(declarations: DeclarationModel): FunctionSemanticModel {
		if (target != null && target != declarations.target) throw InternalErrors.invariant("Declaration and call analysis targets differ")
		val resolver = TypeResolver(declarations.target, declarations.structs::get)
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
		model.types.loopJumps.putAll(declarations.loopJumps)
		for (function in declarations.functions) function.parameters.forEachIndexed { index, parameter -> model.bindings[parameter] = signatures.getValue(function).parameters[index] }
		val presence = NullFlowAnalysis(model)
		for (function in declarations.functions) presence.analyze(function.declaration)
		for (function in declarations.functions) {
			val checker = BodyTyping(model, function.declaration, signatures.getValue(function).results, validateReturns)
			function.parameters.forEachIndexed { index, parameter ->
				parameter.declaration.defaultValue?.let { checker.checkDefault(it, signatures.getValue(function).parameters[index], parameter.name) }
			}
		}
		for (function in declarations.functions) {
			val checker = BodyTyping(model, function.declaration, signatures.getValue(function).results, validateReturns)
			when (val body = function.declaration.body) {
				is BlockBody      -> {
					checker.check(body.statements)
					if (validateReturns && signatures.getValue(function).results != ResultShape.Empty && model.controlFlow.of(body).canFallThrough) {
						throw SemanticErrors.missingReturn(function.declaration, body)
					}
				}

				is ExpressionBody -> checker.checkExpressionBody(body)
			}
		}
		for (function in declarations.functions) {
			function.parameters.forEachIndexed { index, parameter ->
				parameter.declaration.defaultValue?.let { MetadataAnalysis(model.types, function.parameters.take(index).map { it.name.lexeme to false }, model.controlFlow).expression(it) }
			}
			val metadata = MetadataAnalysis(model.types, function.parameters.map { it.name.lexeme to it.mutable }, model.controlFlow)
			when (val body = function.declaration.body) {
				is BlockBody      -> metadata.statements(body.statements)
				is ExpressionBody -> metadata.expression(body.expression)
			}
		}
		return model
	}

	private class BodyTyping(private val facts: FunctionSemanticModel, private val function: FunctionDeclaration, private val returnResults: ResultShape, private val validateReturns: Boolean, private val probe: Boolean = false) : ExpressionTyping(facts.types, probe, declarations = true) {

		override val supportsNullable = true
		override fun namedTypeReceiver(expression: Expression) = (expression as? NameExpression)?.let { facts.declarations.namedTypeReceivers[it] }
		override fun fieldPresent(expression: FieldAccessExpression) = expression in facts.presentFields

		override fun completion(expression: Expression) = if (validateReturns) facts.controlFlow.of(expression) else ControlFlow.FALLTHROUGH
		override fun completion(statements: List<Statement>) = if (validateReturns) facts.controlFlow.of(statements) else ControlFlow.FALLTHROUGH
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

		fun checkDefault(expression: Expression, expected: ValueType, name: cloth.token.Token) {
			val actual = typeOf(expression, expected, contextualFloat = true)
			if (!compatible(actual, expected)) throw SemanticErrors.typeMismatch(expected, actual, name, expression, name)
		}

		fun checkExpressionBody(body: ExpressionBody) {
			val actual = resultOf(body.expression, returnResults, requireValue = validateReturns, allowVoidResult = validateReturns)
			if (validateReturns) checkReturnedResults(actual, body.expression)
		}

		override fun nameType(expression: NameExpression): ValueType {
			val symbol = facts.declarations.symbolOf(expression)
			if (symbol !is BindingSymbol) reject { SemanticErrors.functionValue(expression.name) }
			val declared = facts.bindings.getValue(symbol)
			return if (declared is CNullable && expression in facts.presentNames) {
				model.payloadReads[expression] = declared
				declared.payload
			} else {
				model.payloadReads.remove(expression)
				declared
			}
		}

		override fun withScope(action: () -> Unit) = action() // Resolved symbols already encode lexical scope.
		fun check(statements: List<Statement>) = statements.forEach { statement(it) }
		override fun statement(statement: Statement) {
			when (statement) {
				is DoWhileStatement           -> if (probe) BodyTyping(facts, function, returnResults, validateReturns).statement(statement) else checkDoWhile(statement)
				is WhileStatement             -> if (probe) BodyTyping(facts, function, returnResults, validateReturns).statement(statement) else checkWhile(statement)
				is LoopJumpStatement          -> model.recordResult(statement, ResultShape.Never)
				is ForStatement               -> {
					// Loop contracts cannot be repaired by a surrounding argument candidate.
					if (probe) {
						BodyTyping(facts, function, returnResults, validateReturns).statement(statement)
					} else checkFor(statement, facts.declarations.loopSteppers[statement]) { type ->
						facts.bindings[facts.declarations.symbolOf(statement)] = type
					}
				}

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
						if (validateReturns && contract != ValueType.VOID && facts.controlFlow.of(body).canFallThrough) {
							throw SemanticErrors.missingReturn(block.declaration, body)
						}
						facts.bindings[symbol] = contract
						model.statements[statement] = contract
						model.expressions[block] = contract
						return
					}
					if (expected == ValueType.VOID) reject { SemanticErrors.voidType(statement.type.span) }
					val actual = typeOf(statement.initializer, expected)
					if (expected == null && actual.containsNullability) throw SemanticErrors.nullableContext(statement.initializer.span)
					val type = if (expected != null && expected.accepts(actual)) expected.inferArraySizes(actual) else expected ?: actual
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
					val declared = facts.bindings.getValue(facts.declarations.targetOf(statement))
					val type = if (declared is CNullable) {
						if (statement !in facts.presentUpdates) throw SemanticErrors.nullablePayload(declared, statement.span)
						model.updatePayloads[statement] = declared
						declared.payload
					} else declared
					if (!type.numeric && type != ValueType.NEVER) reject { SemanticErrors.unary(statement.operator, NameExpression(statement.name), type, boolean = false) }
					model.statements[statement] = type
				}

				is ElementAssignmentStatement -> {
					val type = typeOf(statement.target)
					val actual = typeOf(statement.value, type)
					if (type != ValueType.NEVER && !compatible(actual, type)) reject { SemanticErrors.array("Array element requires '${type.spelling}', got '${actual.spelling}'", statement.value.span) }
					model.statements[statement] = type
				}

				is FieldWriteStatement        -> checkFieldWrite(statement)

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

		private fun checkFieldWrite(statement: FieldWriteStatement) {
			val target = statement.target
			val readType = typeOf(target)
			val declared = model.payloadReads[target] ?: readType
			val operator = Operators.fromCharacterRequired(statement.operator.lexeme)
			val value = statement.value
			val actual = when {
				value == null                -> {
					if (readType is CNullable) throw SemanticErrors.nullablePayload(readType, target.span)
					if (!readType.numeric && readType != ValueType.NEVER) reject { SemanticErrors.unary(statement.operator, target, readType, boolean = false) }
					readType
				}

				operator == Operators.ASSIGN -> {
					model.payloadReads.remove(target)
					model.recordResult(target, ResultShape.of(declared))
					typeOf(value, declared)
				}

				else                         -> {
					val base = Token(TokenType.Operator, statement.operator.span, operator.compoundAssignmentBase().toString())
					// Use ordinary arithmetic checks. The synthetic expression is never lowered.
					typeOf(BinaryExpression(target, base, value), declared)
				}
			}
			if (declared != ValueType.NEVER && !declared.accepts(actual)) reject {
				SemanticErrors.structField("Field assignment requires '${declared.spelling}', got '${actual.spelling}'", value?.span ?: target.span)
			}
			model.recordResult(statement, ResultShape.of(declared))
			if (declared == ValueType.NEVER) return
			fun steps(place: Expression): List<PlaceStep> = when (place) {
				is NameExpression        -> emptyList()
				is FieldAccessExpression -> steps(place.receiver) + PlaceStep.Field(model.fields.getValue(place))
				is IndexExpression       -> steps(place.array) + PlaceStep.Index(place.index, model.typeOf(place.array) as CArray)
				else                     -> throw InternalErrors.invariant("Field write lost its resolved storage root")
			}
			model.fieldWrites[statement] = FieldWritePlan(facts.declarations.fieldRoots.getValue(statement), steps(target), declared)
		}

		override fun callType(expression: CallExpression): ValueType = callResults(expression).let { it.scalarOrNull() ?: throw SemanticErrors.scalarResults(it, expression.span) }

		override fun callResults(expression: CallExpression): ResultShape {
			facts.calls[expression]?.let { return it.type.results }
			val target = facts.declarations.candidatesOf(expression)
			if (target is BuiltinGroupSymbol) return ResultShape.of(builtinCallType(expression, target.definition))
			val group = (target as FunctionGroupSymbol).overloads
			ArgumentBinder.validate(expression.suppliedArguments)
			data class Candidate(val function: FunctionSymbol, val binding: ArgumentBinding)

			var firstProblem: BindingProblem? = null
			val candidates = group.mapNotNull { candidate ->
				val attempt = ArgumentBinder.bind(expression, candidate)
				if (firstProblem == null) firstProblem = attempt.problem
				attempt.binding?.let { Candidate(candidate, it) }
			}
			if (candidates.isEmpty()) {
				// Preserve the established required-only positional arity diagnostic.
				if (expression.suppliedArguments.all { it.label == null } && group.all { fn -> fn.parameters.none { it.declaration.optional } }) throw SemanticErrors.callArity(expression, group)
				throw checkNotNull(firstProblem).diagnostic()
			}

			val ordinary = facts.fork()
			val ordinaryTypes = try {
				val checker = BodyTyping(ordinary, function, returnResults, validateReturns, probe = true)
				expression.arguments.map { checker.typeOf(it) }
			} catch (_: CandidateMismatch) {
				null
			}

			fun suppliedTypes(candidate: Candidate) = candidate.binding.parameterForArgument.map { facts.typeOf(candidate.function).parameters[it] }
			data class Match(val candidate: Candidate, val trial: FunctionSemanticModel)

			val exact = if (ordinaryTypes == null) emptyList() else candidates.filter { suppliedTypes(it) == ordinaryTypes }
			val ordinaryCompatible = if (ordinaryTypes == null) emptyList() else candidates.filter { candidate -> suppliedTypes(candidate).zip(ordinaryTypes).all { (parameter, actual) -> parameter.accepts(actual) } }
			val preferred = exact.ifEmpty { ordinaryCompatible }
			val matches = if (preferred.isNotEmpty()) preferred.map { Match(it, ordinary) } else candidates.mapNotNull { candidate ->
				val trial = facts.fork()
				val checker = BodyTyping(trial, function, returnResults, validateReturns, probe = true)
				val parameters = suppliedTypes(candidate)
				val matches = try {
					expression.arguments.indices.all { index -> compatible(checker.typeOf(expression.arguments[index], parameters[index], contextualFloat = true), parameters[index]) }
				} catch (_: CandidateMismatch) {
					false
				}
				if (matches) Match(candidate, trial) else null
			}
			if (matches.isEmpty()) throw SemanticErrors.noMatchingCall(expression, candidates.map { it.function })
			// Omission count breaks ties only between identical supplied type contracts.
			// Incomparable contextual matches remain ambiguous.
			val best = if (matches.map { suppliedTypes(it.candidate) }.distinct().size == 1) {
				val minimum = matches.minOf { it.candidate.binding.omittedParameters.size }
				matches.filter { it.candidate.binding.omittedParameters.size == minimum }
			} else matches
			if (best.size > 1) throw SemanticErrors.ambiguousCall(expression, best.map { it.candidate.function })
			val selected = best.single()
			facts.commit(selected.trial)
			val candidate = selected.candidate
			facts.calls[expression] = ResolvedCall.Source(candidate.function, facts.typeOf(candidate.function), candidate.binding)
			return facts.typeOf(candidate.function).results
		}

		private fun builtinCallType(expression: CallExpression, definition: BuiltinDefinition<*>): ValueType {
			if (expression.suppliedArguments.any { it.label != null }) throw SemanticErrors.argumentBinding("Built-in functions do not publish named argument labels yet", expression.span)
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
