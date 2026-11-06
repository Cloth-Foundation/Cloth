package cloth.semantic

import cloth.ast.SourceUnit
import cloth.builtin.BoundBuiltinCall
import cloth.builtin.BuiltinDefinition
import cloth.builtin.BuiltinOverload
import cloth.data.*
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.symbols.*
import cloth.target.TargetLayout
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

/** Stage 3 checks calls; FunctionAnalyzer enables stage 4 return validation. */
class CallAnalyzer internal constructor(private val validateReturns: Boolean, private val target: TargetLayout? = null) {

	constructor(target: TargetLayout? = null) : this(false, target)

	fun analyze(unit: SourceUnit): FunctionSemanticModel = analyze(DeclarationAnalyzer(target ?: TargetLayout.native()).analyze(unit))

	fun analyze(declarations: DeclarationModel): FunctionSemanticModel {
		if (target != null && target != declarations.target) throw InternalErrors.invariant("Declaration and call analysis targets differ")
		val resolver = TypeResolver(declarations.target, namedType = declarations::namedType)
		val signatures = declarations.concreteCallables.associateWith { function ->
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
			val operator = (function.declaration as? MethodDeclaration)?.takeIf { it.operator != null }
			val comparison = operator?.let { Operators.fromCharacter(it.name.lexeme) }
			if (operator?.name?.lexeme == Keywords.IS.toString() && parameters.single() !is CClass) {
				throw SemanticErrors.operatorDeclaration("Value-target '${Keywords.IS}' requires a non-nullable class operand", function.parameters.single().name.span)
			}
			if (operator != null && comparison in OperatorRules.classComparisons) {
				val operand = parameters.single()
				val owner = (function as MethodSymbol).owner
				val requiredType = if (owner is CClass) declarations.objectRoot else owner
				val valid = if (comparison == Operators.IDENTITY) operand is CClass else operand == requiredType
				if (!valid) {
					val required = if (comparison == Operators.IDENTITY) "a non-nullable class" else "the '${requiredType.spelling}'"
					throw SemanticErrors.operatorDeclaration("Operator '${operator.name.lexeme}' requires $required operand type", function.parameters.single().name.span)
				}
			}
			val fixed = operator?.let(OperatorRules::fixedResult)
			if (operator != null && (ValueType.VOID in results || fixed != null && results.isNotEmpty() && results.single() != fixed)) {
				val message = fixed?.let { "This operator must return '${it.spelling}'" } ?: "An operator must produce a value"
				throw SemanticErrors.operatorDeclaration(message, operator.returnTypes.single().span)
			}
			FunctionType(parameters, when {
				function is ConstructorSymbol -> ResultShape.of(function.owner)
				fixed != null                 -> ResultShape.of(fixed)
				else                          -> ResultShape.of(results)
			})
		}.toMutableMap()
		val model = FunctionSemanticModel(declarations, signatures)
		for (method in declarations.methods) {
			val selector = method.declaration.operator?.selector ?: continue
			if (selector.capturesWidth) continue
			val type = resolver.resolve(selector, declarations = true)
			if (type == ValueType.VOID) throw SemanticErrors.voidType(selector.span)
			model.contextSelectorTypes[method] = type
		}
		model.types.loopJumps.putAll(declarations.loopJumps)
		for (function in declarations.concreteCallables) function.parameters.forEachIndexed { index, parameter -> model.bindings[parameter] = signatures.getValue(function).parameters[index] }
		val presence = NullFlowAnalysis(model)
		for (function in declarations.callables) {
			presence.analyze(function.declaration)
			presence.analyzeExpressions(initializationExpressions(function.declaration, declarations))
		}
		val inferring = mutableSetOf<MethodSymbol>()
		val specializations = mutableMapOf<Pair<MethodSymbol, Int>, MethodSymbol>()
		val specializing = mutableSetOf<Pair<MethodSymbol, Int>>()
		model.specializeContext = specialize@{ template, width ->
			val key = template to width
			specializations[key]?.let { return@specialize it }
			val declaration = template.declaration
			if (!specializing.add(key)) throw SemanticErrors.operatorDeclaration("A recursive operand context requires an explicit result type", declaration.span)
			try {
				val bodyFacts = model.fork(width)
				val method = MethodSymbol(declaration, template.owner, template.receiver)
				val annotated = declaration.returnTypes.singleOrNull()?.let { bodyFacts.types.typeResolver.resolve(it, declarations = true) }
				val result = annotated ?: inferOperatorResult(bodyFacts, declaration)
				if (result == ValueType.VOID) throw SemanticErrors.operatorDeclaration("An operand context must produce a value", declaration.span)
				signatures[method] = FunctionType(emptyList(), result)
				specializations[key] = method
				model.specializedBodies[method] = bodyFacts
				val checker = BodyTyping(bodyFacts, declaration, ResultShape.of(result), validateReturns)
				checkBody(method, checker, bodyFacts)
				checkMetadata(method, bodyFacts)
				if (declarations.classes.isNotEmpty()) ClassLifetimeAnalysis(bodyFacts).analyze(listOf(method))
				method
			} finally {
				specializing.remove(key)
			}
		}
		model.inferOperatorType = { method ->
			val declaration = method.declaration
			if (!inferring.add(method)) throw SemanticErrors.operatorDeclaration("A recursive operator requires an explicit result type", declaration.span)
			try {
				val result = inferOperatorResult(model.fork(), declaration)
				FunctionType(signatures.getValue(method).parameters, result).also { signatures[method] = it }
			} finally {
				inferring.remove(method)
			}
		}
		for (function in declarations.concreteCallables) {
			val checker = BodyTyping(model, function.declaration, bodyResults(function, model), validateReturns)
			function.parameters.forEachIndexed { index, parameter ->
				parameter.declaration.defaultValue?.let { checker.checkInitializer(it, signatures.getValue(function).parameters[index], parameter.name) }
			}
		}
		model.inferOperatorType = null
		for (function in declarations.concreteCallables) {
			val returnResults = bodyResults(function, model)
			val checker = BodyTyping(model, function.declaration, returnResults, validateReturns)
			when (val declaration = function.declaration) {
				is PrimaryConstructorDeclaration -> {
					val definition = declarations.classes.getValue(declaration.name.lexeme)
					declaration.owner.base?.let { clause ->
						declarations.classes[clause.name.lexeme]?.let { checker.bindConstructor(clause, it.constructors) }
					}
					for ((field, expression) in definition.fieldInitializers) {
						checker.checkInitializer(expression, field.type, field.name)
					}
				}

				is ConstructorDeclaration        -> {
					val owner = declarations.classOwners.getValue(declaration)
					val primary = declarations.classes.getValue(owner.spelling).primary
					checker.bindConstructor(declaration.delegation, listOf(primary))
				}

				else                             -> Unit
			}
			checkBody(function, checker, model)
		}
		for (function in declarations.concreteCallables) checkMetadata(function, model)
		if (declarations.classes.isNotEmpty()) ClassLifetimeAnalysis(model).analyze()
		return model
	}

	private fun inferOperatorResult(facts: FunctionSemanticModel, declaration: MethodDeclaration): ValueType {
		val body = (declaration.body as ExpressionBody).expression
		val result = BodyTyping(facts, declaration, ResultShape.Empty, false).resultOf(body).scalarOrNull()
		if (result == null || result in setOf(ValueType.VOID, ValueType.NEVER, ValueType.Null)) {
			throw SemanticErrors.operatorDeclaration("An operator must produce one inferable value", body.span)
		}
		return result
	}

	private fun checkBody(function: CallableSymbol, checker: BodyTyping, facts: FunctionSemanticModel) {
		when (val body = function.declaration.body) {
			is BlockBody      -> {
				checker.check(body.statements)
				if (validateReturns && bodyResults(function, facts) != ResultShape.Empty && facts.controlFlow.of(body).canFallThrough) {
					throw SemanticErrors.missingReturn(function.declaration, body)
				}
			}

			is ExpressionBody -> checker.checkExpressionBody(body)
		}
	}

	private fun checkMetadata(function: CallableSymbol, model: FunctionSemanticModel) {
		function.parameters.forEachIndexed { index, parameter ->
			parameter.declaration.defaultValue?.let { MetadataAnalysis(model.types, function.parameters.take(index).map { it.name.lexeme to false }, model.controlFlow).expression(it) }
		}
		val metadata = MetadataAnalysis(model.types, function.parameters.map { it.name.lexeme to it.mutable }, model.controlFlow)
		initializationExpressions(function.declaration, model.declarations).forEach(metadata::expression)
		when (val body = function.declaration.body) {
			is BlockBody      -> metadata.statements(body.statements)
			is ExpressionBody -> metadata.expression(body.expression)
		}
	}

	private fun bodyResults(function: CallableSymbol, model: FunctionSemanticModel) = if (function is ConstructorSymbol) ResultShape.Empty else model.typeOf(function).results

	private fun initializationExpressions(declaration: CallableDeclaration, declarations: DeclarationModel): List<Expression> = when (declaration) {
		is PrimaryConstructorDeclaration -> declaration.owner.base?.arguments.orEmpty() + declarations.classes.getValue(declaration.name.lexeme).fieldInitializers.map { it.expression }
		is ConstructorDeclaration        -> declaration.delegation.arguments
		else                             -> emptyList()
	}

	private class BodyTyping(private val facts: FunctionSemanticModel, private val function: CallableDeclaration, private val returnResults: ResultShape, private val validateReturns: Boolean, private val probe: Boolean = false) : ExpressionTyping(facts.types, probe, declarations = true) {

		override val supportsNullable = true
		override fun namedTypeReceiver(expression: Expression) = (expression as? NameExpression)?.let { facts.declarations.namedTypeReceivers[it] }
		private val classOwner = facts.declarations.classOwners[function]
		override fun fieldPresent(expression: FieldAccessExpression) = expression in facts.presentFields

		override fun validateFieldAccess(field: InstanceField, name: Token) {
			if (field is ClassField && field.visibility == null && field.owner != classOwner) throw SemanticErrors.privateClassMember(name, field.name)
			if (field is EnumField && field.declaration.visibility == null && field.owner != facts.declarations.enumOwners[function]) {
				throw SemanticErrors.enumMember("Enum payload field '${name.lexeme}' is private", name.span)
			}
		}

		override fun enumCase(expression: FieldAccessExpression) = facts.declarations.enumCases[expression]
		override fun valueCheck(expression: TypeCheckExpression) = facts.declarations.valueChecks[expression]

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

		fun checkInitializer(expression: Expression, expected: ValueType, name: Token) {
			val actual = typeOf(expression, expected, contextualFloat = true)
			if (!compatible(actual, expected)) throw SemanticErrors.typeMismatch(expected, actual, name, expression, name)
		}

		fun checkExpressionBody(body: ExpressionBody) {
			val actual = resultOf(body.expression, returnResults, requireValue = validateReturns, allowVoidResult = validateReturns)
			if (validateReturns) checkReturnedResults(actual, body.expression)
		}

		override fun nameType(expression: NameExpression): ValueType {
			model.projectedOperands[expression]?.let { return it.type }
			val symbol = facts.declarations.symbolOf(expression)
			if (symbol !is BindingSymbol) reject { SemanticErrors.functionValue(expression.name) }
			val declared = facts.typeOf(symbol)
			val payload = (declared as? CNullable)?.payload ?: declared
			val narrowed = facts.narrowedNames[expression]?.takeIf { payload is CClass && payload.accepts(it) }
			return if (declared is CNullable && (expression in facts.presentNames || narrowed != null)) {
				model.payloadReads[expression] = declared
				narrowed ?: declared.payload
			} else {
				model.payloadReads.remove(expression)
				narrowed ?: declared
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
					val type = facts.typeOf(symbol)
					val actual = typeOf(statement.value, type)
					if (!compatible(actual, type)) reject { SemanticErrors.typeMismatch(type, actual, statement.name, statement.value, symbol.name) }
					model.statements[statement] = type
				}

				is MultiAssignmentStatement   -> {
					val targets = facts.declarations.targetsOf(statement)
					assignmentPlan(statement, targets.map { it?.let(facts::typeOf) }, targets.map { it?.name })
				}

				is UpdateStatement            -> {
					val declared = facts.typeOf(facts.declarations.targetOf(statement))
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
					checkWritablePayload(statement.target)
					val instance = instancePlace(statement.target)
					if (instance != null) model.instanceWrites[statement] = instance
					else if (type != ValueType.NEVER && facts.declarations.storedRoot(statement.target) is ReceiverSymbol) {
						storedPlace(statement.target)?.let { model.receiverElementWrites[statement] = it }
					}
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
			checkWritablePayload(target)
			val declared = model.payloadReads[target] ?: readType
			val instance = instancePlace(target)
			val stored = if (instance == null && declared != ValueType.NEVER) storedPlace(target) ?: throw SemanticErrors.fieldTarget(target.span) else null
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
			if (instance != null) model.instanceWrites[statement] = instance
			if (stored != null) model.fieldWrites[statement] = FieldWritePlan(stored, declared)
		}

		private fun instancePlace(expression: Expression): InstancePlace? = when (expression) {
			is NameExpression        -> {
				val field = facts.declarations.names[expression] as? ReceiverFieldSymbol
				if (field?.field is ClassField) InstancePlace(ObjectReceiver.Self(field.receiver), listOf(PlaceStep.Field(field.field))) else null
			}

			is FieldAccessExpression -> {
				val field = model.fields[expression]
				when {
					field is EnumField  -> null
					field is ClassField -> InstancePlace(ObjectReceiver.Explicit(expression.receiver), listOf(PlaceStep.Field(field)))
					field != null       -> instancePlace(expression.receiver)?.let { it.copy(steps = it.steps + PlaceStep.Field(field)) }
					else                -> null
				}
			}

			is IndexExpression       -> (model.typeOf(expression.array) as? CArray)?.let { array ->
				instancePlace(expression.array)?.let { it.copy(steps = it.steps + PlaceStep.Index(expression.index, array)) }
			}

			else                     -> null
		}

		private fun checkWritablePayload(expression: Expression) {
			enumPayloadPlace(expression)?.let {
				throw SemanticErrors.enumMember("Enum payload fields are read-only", it.span)
			}
		}

		private fun enumPayloadPlace(expression: Expression): Expression? = when (expression) {
			is FieldAccessExpression -> if (model.fields[expression] is EnumField) expression else enumPayloadPlace(expression.receiver)
			is IndexExpression       -> enumPayloadPlace(expression.array)
			is NameExpression        -> expression.takeIf { (facts.declarations.names[it] as? ReceiverFieldSymbol)?.field is EnumField }
			else                     -> null
		}

		private fun storedPlace(expression: Expression): StoredPlace? {
			if (enumPayloadPlace(expression) != null) return null
			val root = facts.declarations.storedRoot(expression) ?: return null
			fun steps(place: Expression): List<PlaceStep> = when (place) {
				is NameExpression        -> (facts.declarations.symbolOf(place) as? ReceiverFieldSymbol)?.let { listOf(PlaceStep.Field(it.field)) }.orEmpty()
				is FieldAccessExpression -> steps(place.receiver) + PlaceStep.Field(model.fields.getValue(place))
				is IndexExpression       -> steps(place.array) + PlaceStep.Index(place.index, model.typeOf(place.array) as CArray)
				else                     -> throw InternalErrors.invariant("A stored path has no resolved root")
			}
			return StoredPlace(root, steps(expression))
		}

		override fun callType(expression: CallExpression): ValueType = callResults(expression).let { it.scalarOrNull() ?: throw SemanticErrors.scalarResults(it, expression.span) }

		override fun operatorType(expression: Expression, operator: Token, operands: List<Expression>): ValueType? {
			val operation = Operators.fromCharacter(operator.lexeme)
			if (operation in OperatorRules.classComparisons) return null
			if (!facts.declarations.hasOperator(operator.lexeme)) return null
			model.operatorCalls[expression]?.let { return it.call.type.returns }
			model.operatorContexts[expression]?.let { return model.typeOf(it.application) }
			val ordinaryTrial = facts.fork()
			val ordinaryChecker = BodyTyping(ordinaryTrial, function, returnResults, validateReturns, probe = true)
			val ordinary = operands.map { operand ->
				try {
					ordinaryChecker.typeOf(operand)
				} catch (_: CandidateMismatch) {
					null
				}
			}
			if (operator.lexeme == Keywords.IS.toString() && ordinary.any { it !is CClass && it != ValueType.NEVER }) {
				reject { SemanticErrors.operatorMatch(operator, expression.span, emptyList(), ambiguous = false) }
			}
			data class Candidate(val method: MethodSymbol, val receiver: Int, val types: List<ValueType>)

			val candidates = ordinary.flatMapIndexed { index, type ->
				val owner = type as? CMemberType ?: return@flatMapIndexed emptyList()
				facts.declarations.operators(owner, operator.lexeme, index == 1, operands.size - 1).map { method ->
					val parameters = facts.signatures.getValue(method).parameters
					val types = if (index == 0) listOf<ValueType>(method.owner) + parameters else parameters + method.owner
					Candidate(method, index, types)
				}
			}

			data class Match(val candidate: Candidate, val quality: Int, val trial: FunctionSemanticModel)

			val matches = candidates.mapNotNull { candidate ->
				val quality = candidate.types.zip(ordinary).maxOf { (expected, actual) ->
					when {
						expected == actual                         -> 0
						actual != null && expected.accepts(actual) -> 1
						else                                       -> 2
					}
				}
				if (quality < 2) return@mapNotNull Match(candidate, quality, ordinaryTrial)
				val trial = facts.fork()
				val checker = BodyTyping(trial, function, returnResults, validateReturns, probe = true)
				val accepted = try {
					operands.indices.all { index ->
						val expected = candidate.types[index]
						val actual = checker.typeOf(operands[index], expected.takeUnless { index == candidate.receiver })
						expected.accepts(actual)
					}
				} catch (_: CandidateMismatch) {
					false
				}
				if (!accepted) return@mapNotNull null
				Match(candidate, quality, trial)
			}
			if (matches.isEmpty()) {
				if (expression is BinaryExpression && OperatorRules.supportsContext(operation) && operands.none { it in model.projectedOperands }) {
					resolveOperandContexts(expression, operands, ordinary, ordinaryTrial)?.let { return it }
				}
				if (candidates.isEmpty()) return null
				reject { SemanticErrors.operatorMatch(operator, expression.span, candidates.map { it.method }, ambiguous = false) }
			}
			val quality = matches.minOf(Match::quality)
			val tier = matches.filter { it.quality == quality }
			val best = tier.filter { match -> tier.none { moreSpecific(it.candidate.types, match.candidate.types) } }
			if (best.size != 1) throw SemanticErrors.operatorMatch(operator, expression.span, best.map { it.candidate.method }, ambiguous = true)
			val selected = best.single()
			val candidate = selected.candidate
			checkOperatorReceiver(operands[candidate.receiver], operator)
			facts.commit(selected.trial)
			val type = facts.typeOf(candidate.method)
			val arguments = ArgumentBinding(candidate.method.parameters.indices.toList(), emptyList())
			model.operatorCalls[expression] = ResolvedOperator(ResolvedCall.Source(candidate.method, type, arguments), candidate.receiver)
			return type.returns
		}

		private fun checkOperatorReceiver(operand: Expression, operator: Token) {
			val original = (operand as? NameExpression)?.let { model.projectedOperands[it]?.original } ?: operand
			if (original in facts.declarations.readOnlyValueReceivers) {
				throw SemanticErrors.parameterDefault("A default cannot call a user-defined operator through a read-only receiver", operator.span)
			}
		}

		private fun resolveOperandContexts(expression: BinaryExpression, operands: List<Expression>, originalTypes: List<ValueType?>, trial: FunctionSemanticModel): ValueType? {
			val left = originalTypes[0] ?: return null
			val right = originalTypes[1] ?: return null
			val projections = listOf(
				selectContext(left, right, operands[0], expression.operator),
				selectContext(right, left, operands[1], expression.operator),
			)
			if (projections.all { it == null }) return null
			val names = operands.mapIndexed { index, operand ->
				val projection = projections[index]
				val type = projection?.call?.type?.returns ?: checkNotNull(originalTypes[index])
				NameExpression(Token(TokenType.Identifier, operand.span, "<operator operand>")).also {
					trial.types.projectedOperands[it] = ProjectedOperand(type, operand.takeIf { projection == null })
				}
			}
			val application = BinaryExpression(names[0], expression.operator, names[1])
			val result = BodyTyping(trial, function, returnResults, validateReturns, probe).typeOf(application)
			facts.commit(trial)
			model.operatorContexts[expression] = ResolvedOperatorContext(application, projections)
			return result
		}

		private fun selectContext(type: ValueType, peer: ValueType, operand: Expression, operator: Token): ResolvedOperator? {
			val owner = type as? CMemberType ?: return null
			if (peer == ValueType.NEVER || peer == ValueType.Null) return null
			data class Match(val method: MethodSymbol, val selector: ValueType, val rank: Int, val width: Int? = null)

			val matches = facts.declarations.contextOperators(owner, operator.lexeme).mapNotNull { method ->
				val selector = facts.contextSelectorTypes[method]
				val width = OperatorRules.inferredSelectorWidth(method.declaration.operator?.selector, peer)
				when {
					width != null                   -> Match(method, peer, 1, width)
					selector == peer                -> Match(method, selector, 0)
					selector?.accepts(peer) == true -> Match(method, selector, 2)
					else                            -> null
				}
			}
			if (matches.isEmpty()) return null
			val rank = matches.minOf(Match::rank)
			val tier = matches.filter { it.rank == rank }
			val best = tier.filter { match -> tier.none { other -> moreSpecific(listOf(other.selector), listOf(match.selector)) } }
			if (best.size != 1) throw SemanticErrors.operatorMatch(operator, operand.span, best.map { it.method }, ambiguous = true)
			checkOperatorReceiver(operand, operator)
			val match = best.single()
			val method = match.width?.let { checkNotNull(facts.specializeContext)(match.method, it) } ?: match.method
			val call = ResolvedCall.Source(method, facts.typeOf(method), ArgumentBinding(emptyList(), emptyList()))
			return ResolvedOperator(call, 0)
		}

		private fun moreSpecific(left: List<ValueType>, right: List<ValueType>): Boolean {
			val pairs = left.zip(right)
			return pairs.all { (a, b) -> b.accepts(a) } && pairs.any { (a, b) -> !a.accepts(b) }
		}

		override fun classComparisonOverride(expression: BinaryExpression, operation: Operators, left: CClass, right: CClass): ResolvedOperator? {
			val declarations = facts.declarations
			if (operation != Operators.IDENTITY) {
				if (expression.left in declarations.readOnlyValueReceivers && declarations.classes.values.any { definition ->
						val owner = definition.owner
						left.accepts(owner) && right.accepts(owner) && declarations.equalityOverride(owner, operation) != null
					}) throw SemanticErrors.parameterDefault("A default cannot call a user-defined operator through a read-only receiver", expression.operator.span)
				return null
			}
			return staticComparisonOverride(expression, operation, listOf(left, right))
		}

		override fun valueComparisonOverride(expression: BinaryExpression, operation: Operators, owner: CMemberType): ResolvedOperator? = staticComparisonOverride(expression, operation, listOf(owner, owner))

		private fun staticComparisonOverride(expression: BinaryExpression, operation: Operators, actual: List<CMemberType>): ResolvedOperator? {
			val declarations = facts.declarations

			data class Candidate(val method: MethodSymbol, val receiver: Int, val types: List<ValueType>)

			val candidates = actual.flatMapIndexed { index, owner ->
				declarations.operators(owner, operation.toString(), index == 1, 1).map { method ->
					val parameter = facts.typeOf(method).parameters.single()
					val types = if (index == 0) listOf(method.owner, parameter) else listOf(parameter, method.owner)
					Candidate(method, index, types)
				}
			}.filter { candidate -> candidate.types.zip(actual).all { (expected, type) -> expected.accepts(type) } }
			if (candidates.isEmpty()) return null
			val tier = candidates.filter { it.types == actual }.ifEmpty { candidates }
			val best = tier.filter { match -> tier.none { moreSpecific(it.types, match.types) } }
			if (best.size != 1) throw SemanticErrors.operatorMatch(expression.operator, expression.span, best.map { it.method }, ambiguous = true)
			val selected = best.single()
			val source = if (selected.receiver == 0) expression.left else expression.right
			if (source in declarations.readOnlyValueReceivers) throw SemanticErrors.parameterDefault("A default cannot call a user-defined operator through a read-only receiver", expression.operator.span)
			return ResolvedOperator(ResolvedCall.Source(selected.method, facts.typeOf(selected.method), ArgumentBinding(listOf(0), emptyList())), selected.receiver)
		}

		override fun validateFormatter(receiver: Expression, result: ProviderResult<*>) {
			val declarations = facts.declarations
			if (result.callsFormatter && receiver in declarations.readOnlyValueReceivers && declarations.formattingMayCallUserCode(result.receiverType)) {
				throw SemanticErrors.parameterDefault("A default cannot call a formatter through a read-only receiver", receiver.span)
			}
		}

		fun bindConstructor(expression: CallSite, constructors: List<ConstructorSymbol>): ResolvedCall.Source = facts.constructorCalls[expression] ?: selectSourceCall(expression, constructors).also { facts.constructorCalls[expression] = it }

		override fun creationType(expression: ClassCreationExpression): ValueType {
			val definition = facts.declarations.classCreations.getValue(expression)
			return bindConstructor(expression, definition.constructors).type.returns
		}

		override fun deletionType(expression: ClassDeletionExpression): ValueType {
			val receiver = typeOf(expression.receiver)
			if (receiver == ValueType.NEVER) return receiver
			val owner = ((receiver as? CNullable)?.payload ?: receiver) as? CClass ?: throw SemanticErrors.classLifecycle("'${Keywords.DELETE}' requires a class object", expression.receiver.span)
			facts.classDeletions[expression] = ClassDeletionPlan(owner, receiver is CNullable)
			return ValueType.VOID
		}

		override fun callResults(expression: CallExpression): ResultShape {
			facts.calls[expression]?.let { return it.type.results }
			val target = facts.declarations.candidatesOf(expression)
			if (target is BuiltinGroupSymbol) return ResultShape.of(builtinCallType(expression, target.definition))
			val selected = when (target) {
				is FunctionGroupSymbol -> selectSourceCall(expression, target.overloads)
				is MethodGroupSymbol   -> {
					val self = facts.declarations.implicitReceivers.getValue(expression)
					val receiver = if (self.owner is CClass) MethodReceiver.Instance(ObjectReceiver.Self(self)) else MethodReceiver.Stored(StoredPlace(self, emptyList()))
					selectSourceCall(expression, visibleMethods(target, expression.name), receiver)
				}

				is BuiltinGroupSymbol  -> throw InternalErrors.invariant("Built-in call reached source overload selection")
			}
			facts.calls[expression] = selected
			return selected.type.results
		}

		override fun memberCallResults(expression: MemberCallExpression, receiver: ValueType): ResultShape {
			facts.sourceMemberCalls[expression]?.let { return it.type.results }
			if (receiver is CNullable && receiver.payload is CMemberType) {
				throw SemanticErrors.nullablePayload(receiver, expression.receiver.span)
			}
			if (receiver !is CMemberType) return super.memberCallResults(expression, receiver)
			val group = facts.declarations.methodGroup(receiver, expression.name.lexeme) ?: throw SemanticErrors.unknownMember(receiver, expression.name)
			if (expression in facts.declarations.readOnlyDefaultReceivers) {
				throw SemanticErrors.parameterDefault("A default cannot call a user-defined method through a read-only receiver", expression.name.span)
			}
			val candidates = visibleMethods(group, expression.name)
			val source = if (receiver is CClass) MethodReceiver.Instance(ObjectReceiver.Explicit(expression.receiver)) else {
				for (part in facts.lostReceiverProofs[expression].orEmpty()) {
					val declared = model.payloadReads[part] ?: continue
					throw SemanticErrors.receiverProofLost(declared, part.span)
				}
				val instance = instancePlace(expression.receiver)
				if (instance != null && expression in facts.declarations.fieldInitializerCalls && instance.receiver.isSelf()) {
					throw SemanticErrors.partialClassInstance("A stored field receiver requires a fully initialized instance", expression.receiver.span)
				}
				val place = if (instance == null) storedPlace(expression.receiver) else null
				when {
					instance != null -> MethodReceiver.StoredInInstance(instance)
					place != null    -> MethodReceiver.Stored(place)
					else             -> MethodReceiver.Temporary(expression.receiver)
				}
			}
			val selected = selectSourceCall(expression, candidates, source)
			facts.sourceMemberCalls[expression] = selected
			return selected.type.results
		}

		private fun ObjectReceiver.isSelf(): Boolean = when (this) {
			is ObjectReceiver.Self     -> true
			is ObjectReceiver.Explicit -> (expression as? NameExpression)?.let { facts.declarations.names[it] } is ReceiverSymbol
		}

		private fun visibleMethods(group: MethodGroupSymbol, name: Token): List<MethodSymbol> = group.overloads.filter {
			it.owner !is CClass || it.owner == classOwner || it.declaration.visibility != null
		}.ifEmpty { throw SemanticErrors.privateClassMember(name, group.name) }

		private fun selectSourceCall(expression: CallSite, group: List<CallableSymbol>, receiver: MethodReceiver? = null): ResolvedCall.Source {
			ArgumentBinder.validate(expression.suppliedArguments)
			data class Candidate(val callable: CallableSymbol, val binding: ArgumentBinding)

			var firstProblem: BindingProblem? = null
			val candidates = group.mapNotNull { candidate ->
				val attempt = ArgumentBinder.bind(expression, candidate.name, candidate.parameters)
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

			fun suppliedTypes(candidate: Candidate) = candidate.binding.parameterForArgument.map { facts.typeOf(candidate.callable).parameters[it] }
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
			if (matches.isEmpty()) throw SemanticErrors.noMatchingCall(expression, candidates.map { it.callable })
			// Omission count breaks ties only between identical supplied type contracts.
			// Incomparable contextual matches remain ambiguous.
			val best = if (matches.map { suppliedTypes(it.candidate) }.distinct().size == 1) {
				val minimum = matches.minOf { it.candidate.binding.omittedParameters.size }
				matches.filter { it.candidate.binding.omittedParameters.size == minimum }
			} else matches
			if (best.size > 1) throw SemanticErrors.ambiguousCall(expression, best.map { it.candidate.callable })
			val selected = best.single()
			facts.commit(selected.trial)
			val candidate = selected.candidate
			return ResolvedCall.Source(candidate.callable, facts.typeOf(candidate.callable), candidate.binding, receiver)
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
					val bound = parameter.bind(actual, facts.target) ?: throw SemanticErrors.builtinArgument(expression, argument, actual, parameter)
					bound.text?.let { validateFormatter(argument, it) }
					bound
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
