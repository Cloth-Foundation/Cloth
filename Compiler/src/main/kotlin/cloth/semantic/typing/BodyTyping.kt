package cloth.semantic.typing

import cloth.data.*
import cloth.builtin.attachments.ArrayMutation
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.diagnostics.StateErrors
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.CallableSignature
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.IntrinsicBody
import cloth.parser.declaration.executableBody
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.parser.type.TypeReference
import cloth.semantic.*
import cloth.semantic.declarations.StateDefinition
import cloth.semantic.declarations.checkStatePayloadPresence
import cloth.semantic.flow.ControlFlow
import cloth.semantic.symbols.*
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

internal class BodyTyping(private val facts: FunctionSemanticModel, private val function: CallableSignature, private val returnResults: ResultShape, private val hasReturnContract: Boolean = true, private val probe: Boolean = false) : ExpressionTyping(facts.types, probe) {

	override fun namedTypeReceiver(expression: Expression) = facts.declarations.namedTypeReceivers[expression]
	private val overloads = CallResolution(facts, ::typingIn)
	private val operators = OperatorResolution(facts, ::typingIn, ::reject, probe)

	private fun typingIn(model: FunctionSemanticModel, probe: Boolean) = BodyTyping(model, function, returnResults, hasReturnContract, probe)

	private val classOwner = facts.declarations.classOwners[function]
	override fun fieldPresent(expression: FieldAccessExpression) = expression in facts.presence.fields
	override fun boundViews(expression: Expression, actual: ValueType): List<CMemberType> = facts.boundViews(expression, actual).also { bounds ->
		val payload = (actual as? CNullable)?.payload ?: actual
		val bound = bounds.firstOrNull { !it.accepts(payload) }
		if (bound != null) reject {
			SemanticErrors.classMember("Type '${actual.spelling}' cannot use the '${bound.spelling}' bound", expression.span)
		}
	}

	override fun validateFieldAccess(field: InstanceField, name: Token) {
		if (field is ClassField && field.visibility == null && !field.owner.sameDeclaration(classOwner)) throw SemanticErrors.privateClassMember(name, field.name)
		if (field is EnumField && field.declaration.visibility == null && field.owner != facts.declarations.enumOwners[function]) {
			throw SemanticErrors.enumMember("Enum payload field '${name.lexeme}' is private", name.span)
		}
	}

	override fun enumCase(expression: FieldAccessExpression) = facts.declarations.enumCases[expression]
	override fun valueCheck(expression: TypeCheckExpression) = facts.declarations.valueTargetOf(expression)
	override fun unboundStateName(reference: TypeReference) = facts.declarations.unboundStateName(reference)

	override fun completion(expression: Expression) = if (hasReturnContract) facts.controlFlow.of(expression) else ControlFlow.FALLTHROUGH
	override fun completion(statements: List<Statement>) = if (hasReturnContract) facts.controlFlow.of(statements) else ControlFlow.FALLTHROUGH
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

	fun checkBody() {
		when (val body = function.executableBody) {
			is BlockBody      -> {
				check(body.statements)
				if (hasReturnContract && returnResults != ResultShape.Empty && facts.controlFlow.of(body).canFallThrough) {
					throw SemanticErrors.missingReturn(function, body)
				}
			}

			is ExpressionBody -> checkExpressionBody(body)
			null, is IntrinsicBody -> Unit
		}
	}

	private fun checkExpressionBody(body: ExpressionBody) {
		val actual = resultOf(body.expression, returnResults, allowVoidResult = true)
		checkReturnedResults(actual, body.expression)
	}

	override fun nameType(expression: NameExpression): ValueType {
		model.projectedOperands[expression]?.let { return it.type }
		val symbol = facts.declarations.symbolOf(expression)
		if (symbol !is BindingSymbol) reject { SemanticErrors.functionValue(expression.name) }
		val declared = facts.typeOf(symbol)
		val narrowed = facts.presence.narrowedType(expression, declared)
		return if (declared is CNullable && (expression in facts.presence.names || narrowed != null)) {
			model.payloadReads[expression] = declared
			narrowed ?: declared.payload
		} else {
			model.payloadReads.remove(expression)
			narrowed ?: declared
		}
	}

	override fun withScope(action: () -> Unit) = action() // Resolved symbols already encode lexical scope.
	private fun check(statements: List<Statement>) = statements.forEach { statement(it) }
	override fun statement(statement: Statement) {
		when (statement) {
			is DoWhileStatement           -> if (probe) typingIn(facts, false).statement(statement) else checkDoWhile(statement)
			is WhileStatement             -> if (probe) typingIn(facts, false).statement(statement) else checkWhile(statement)
			is LoopJumpStatement          -> model.recordResult(statement, ResultShape.Never)
			is ForStatement               -> {
				// Loop contracts cannot be repaired by a surrounding argument candidate.
				if (probe) {
					typingIn(facts, false).statement(statement)
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
					BodyTyping(facts, block.declaration, ResultShape.of(contract), hasReturnContract).checkBody()
					facts.bindings[symbol] = contract
					model.recordType(statement, contract)
					model.recordType(block, contract)
					return
				}
				if (expected == ValueType.VOID) reject { SemanticErrors.voidType(statement.type.span) }
				val actual = typeOf(statement.initializer, expected)
				if (expected == null && actual.containsNullability) throw SemanticErrors.nullableContext(statement.initializer.span)
				val type = if (expected != null && expected.accepts(actual)) expected.inferArraySizes(actual) else expected ?: actual
				if (!compatible(actual, type)) reject { SemanticErrors.typeMismatch(type, actual, statement.name, statement.initializer, statement.name) }
				facts.bindings[symbol] = type
				model.recordType(statement, type)
			}

			is AssignmentStatement        -> {
				val symbol = facts.declarations.targetOf(statement)
				val type = facts.typeOf(symbol)
				val actual = typeOf(statement.value, type)
				if (!compatible(actual, type)) reject { SemanticErrors.typeMismatch(type, actual, statement.name, statement.value, symbol.name) }
				model.recordType(statement, type)
			}

			is MultiAssignmentStatement   -> {
				val targets = facts.declarations.targetsOf(statement)
				assignmentPlan(statement, targets.map { it?.let(facts::typeOf) }, targets.map { it?.name })
			}

			is UpdateStatement            -> {
				val declared = facts.typeOf(facts.declarations.targetOf(statement))
				val type = if (declared is CNullable) {
					if (statement !in facts.presence.updates) throw SemanticErrors.nullablePayload(declared, statement.span)
					model.updatePayloads[statement] = declared
					declared.payload
				} else declared
				if (!type.supportsNumericOperations && type != ValueType.NEVER) reject { SemanticErrors.unary(statement.operator, NameExpression(statement.name), type, boolean = false) }
				model.recordType(statement, type)
			}

			is ElementAssignmentStatement -> {
				val type = typeOf(statement.target)
				checkWritablePayload(statement.target)
				val instance = instancePlace(statement.target)
				if (instance != null) model.instanceWrites[statement] = instance
				else if (type != ValueType.NEVER) {
					storedPlace(statement.target)?.let { model.storedElementWrites[statement] = it }
				}
				val actual = typeOf(statement.value, type)
				if (type != ValueType.NEVER && !compatible(actual, type)) reject { SemanticErrors.array("Array element requires '${type.spelling}', got '${actual.spelling}'", statement.value.span) }
				model.recordType(statement, type)
			}

			is FieldWriteStatement        -> checkFieldWrite(statement)

			is ExpressionStatement        -> model.recordResult(statement, resultOf(statement.expression, requireValue = false))
			is ReturnStatement            -> {
				if (statement.value == null) {
					if (hasReturnContract && returnResults != ResultShape.Empty) throw SemanticErrors.returnValueRequired(function, returnResults, statement.span)
				} else {
					// The enclosing return contract is fixed, not an overload candidate.
					// Its expression errors must stay fatal even inside an argument probe.
					val checker = if (probe && hasReturnContract) typingIn(facts, false) else this
					val actual = checker.resultOf(statement.value, returnResults, requireValue = hasReturnContract, allowVoidResult = hasReturnContract)
					if (hasReturnContract) checkReturnedResults(actual, statement.value)
				}
				model.recordType(statement, ValueType.VOID)
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
				if (!readType.supportsNumericOperations && readType != ValueType.NEVER) reject { SemanticErrors.unary(statement.operator, target, readType, boolean = false) }
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
		is GuardedReceiver       -> enumPayloadPlace(expression.source)
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

	override fun operatorType(expression: Expression, operator: Token, operands: List<Expression>): ValueType? = operators.resolve(expression, operator, operands)
	override fun equalityContextType(expression: BinaryExpression, operation: Operators, left: ValueType, right: ValueType): ValueType? = operators.equalityContext(expression, operation, left, right)

	override fun classComparisonOverride(expression: BinaryExpression, operation: Operators, left: CMemberType, right: CMemberType): ResolvedOperator? {
		if (operation == Operators.IDENTITY) facts.checkedIdentity(expression, left, right)?.let { return it.identityOverride }
		return operators.classComparison(expression, operation, left, right)
	}

	override fun valueComparisonOverride(expression: BinaryExpression, operation: Operators, owner: CMemberType): ResolvedOperator? = operators.valueComparison(expression, operation, owner)

	override fun validateFormatter(receiver: Expression, result: ProviderResult<*>) {
		val declarations = facts.declarations
		if (result.callsFormatter && receiver in declarations.readOnlyValueReceivers && declarations.formattingMayCallUserCode(result.receiverType)) {
			throw SemanticErrors.parameterDefault("A default cannot call a formatter through a read-only receiver", receiver.span)
		}
	}

	fun bindConstructor(expression: CallSite, constructors: List<ConstructorSymbol>): ResolvedCall.Source = facts.constructorCalls[expression] ?: overloads.source(expression, constructors).also { facts.constructorCalls[expression] = it }

	override fun creationType(expression: ClassCreationExpression): ValueType {
		facts.declarations.classCreations[expression]?.let { return bindConstructor(expression, it.constructors).type.returns }
		val selected = facts.constructorCalls[expression] ?: run {
			val definition = checkNotNull(facts.declarations.genericViews?.inferredCreation(expression))
			overloads.inferredConstructor(expression, definition).also { facts.constructorCalls[expression] = it }
		}
		facts.recordInferredResult(expression, selected.type.results)
		return selected.type.returns
	}

	override fun deletionType(expression: ClassDeletionExpression): ValueType {
		val receiver = typeOf(expression.receiver)
		if (receiver == ValueType.NEVER) return receiver
		val owner = receiver.classBackedType() ?: throw SemanticErrors.classLifecycle("'${Keywords.DELETE}' requires a class object", expression.receiver.span)
		facts.classDeletions[expression] = ClassDeletionPlan(owner, receiver is CNullable)
		return ValueType.VOID
	}

	override fun stateCallResults(expression: StateCallExpression): ResultShape {
		facts.stateCalls[expression]?.let { return it.type.results }
		if (expression.receiver is TypeReceiverExpression) throw SemanticErrors.stateCall(expression.name)
		val receiver = typeOf(expression.receiver, preserveResultOnExit = true)
		if (receiver == ValueType.NEVER) {
			expression.arguments.forEach { typeOf(it) }
			return ResultShape.Never
		}
		if (receiver is CNullable) throw SemanticErrors.nullablePayload(receiver, expression.receiver.span)
		val owner = stateOwner(receiver, expression.receiver) ?: throw SemanticErrors.stateCall(expression.name)
		if (expression.receiver in facts.declarations.readOnlyValueReceivers) {
			throw SemanticErrors.parameterDefault("A default cannot call a state method through a read-only receiver", expression.name.span)
		}
		val candidates = facts.declarations.states[owner]?.contracts?.values.orEmpty().filter { it.callable.name.lexeme == expression.name.lexeme }
		if (candidates.isEmpty()) throw StateErrors.target("Class '${owner.spelling}' has no state method '${expression.name.lexeme}'", expression.name)
		val visible = candidates.filter { it.public || it.owner.sameDeclaration(classOwner) }.map { it.callable }
		if (visible.isEmpty()) throw SemanticErrors.privateClassMember(expression.name, candidates.first().callable.name)
		val selected = overloads.state(expression, visible, owner)
		facts.stateCalls[expression] = selected
		return selected.type.results
	}

	override fun checkStateTest(expression: TypeCheckExpression, source: ValueType): Boolean {
		val name = unboundStateName(expression.target) ?: return false
		val owner = stateOwner(source, expression.operand) ?: return false
		if (source is CNullable) throw SemanticErrors.nullablePayload(source, expression.operand.span)
		facts.stateTests[expression] = stateTarget(owner, name)
		return true
	}

	override fun checkBareTransition(expression: CastExpression, source: ValueType): Boolean {
		if (stateOwner(source, expression.operand) == null) return false
		bindTransition(expression, expression.operand, source, expression.target.name, null)
		return true
	}

	override fun stateTransitionType(expression: StateTransitionExpression): ValueType {
		val source = typeOf(expression.receiver, preserveResultOnExit = true)
		if (source == ValueType.NEVER) {
			expression.payload?.let { typeOf(it) }
			return ValueType.NEVER
		}
		bindTransition(expression, expression.receiver, source, expression.target, expression.payload)
		return ValueType.VOID
	}

	private fun stateReceiver(type: ValueType, expression: Expression, target: Token): CClass {
		if (type is CNullable || type == ValueType.Null) throw SemanticErrors.nullablePayload(type, expression.span)
		return stateOwner(type, expression) ?: throw StateErrors.target("State '${target.lexeme}' requires a class value receiver", target)
	}

	private fun stateOwner(type: ValueType, expression: Expression): CClass? {
		val contract = (((type as? CNullable)?.payload ?: type) as? CMemberType)?.classBackedType() ?: return null
		// Ordinary member lookup still uses the declared host contract of self.
		val self = facts.declarations.directReceiverOf(expression)
		return self?.let(facts.declarations.states::attachedStateOf)?.owner ?: contract as? CClass
	}

	private fun stateTarget(owner: CClass, name: Token): StateDefinition {
		val state = facts.declarations.states[owner]?.supported?.get(name.lexeme)
			?: throw StateErrors.target("Class '${owner.spelling}' does not support state '${name.lexeme}'", name)
		if (state.declaration.visibility == null && !state.owner.sameDeclaration(classOwner)) {
			throw SemanticErrors.privateClassMember(name, state.name)
		}
		return state
	}

	private fun bindTransition(site: Expression, receiver: Expression, source: ValueType, target: Token, payload: Expression?) {
		val owner = stateReceiver(source, receiver, target)
		if (receiver in facts.declarations.readOnlyValueReceivers) {
			throw SemanticErrors.parameterDefault("A default cannot transition an object through a read-only receiver", target.span)
		}
		val state = stateTarget(owner, target)
		checkStatePayloadPresence(state.name, state.payloadType, payload != null, payload?.span ?: target.span)
		payload?.let { checkStatePayload(it, state) }
		facts.stateTransitions[site] = state
	}

	fun checkStatePayload(expression: Expression, state: StateDefinition) {
		val type = state.payloadType
		val actual = typeOf(expression, type, contextualFloat = true)
		if (!type.accepts(actual)) {
			throw StateErrors.payload("State '${state.name.lexeme}' requires payload type '${type.spelling}', got '${actual.spelling}'", expression.span, state.name)
		}
	}

	override fun callResults(expression: CallExpression): ResultShape {
		facts.calls[expression]?.let { return it.type.results }
		val target = facts.declarations.candidatesOf(expression)
		val selected = when (target) {
			is FunctionGroupSymbol -> overloads.function(expression, target)
			is InterfaceMethodGroupSymbol -> overloads.interfaceCall(expression, target, ObjectReceiver.Self(facts.declarations.implicitReceivers.getValue(expression)))
			is MethodGroupSymbol   -> {
				val self = facts.declarations.implicitReceivers.getValue(expression)
				val receiver = if (self.owner is CClass) MethodReceiver.Instance(ObjectReceiver.Self(self)) else MethodReceiver.Stored(StoredPlace(self, emptyList()))
				overloads.method(expression, expression.typeArguments, target, classOwner, receiver)
			}

			is BuiltinGroupSymbol  -> ResolvedCall.Builtin(overloads.builtin(expression, target.definition, ::validateFormatter))
		}
		facts.recordCall(expression, selected)
		return selected.type.results
	}

	override fun memberCallResults(expression: MemberCallExpression, receiver: ValueType): ResultShape =
		resolveMemberCall(expression, receiver).also { facts.recordInferredResult(expression, it) }

	private fun resolveMemberCall(expression: MemberCallExpression, receiver: ValueType): ResultShape {
		facts.selectedBoundMemberCall(expression)?.let { return it.type.results }
		if (receiver is CNullable && receiver.payload is CArray) throw SemanticErrors.nullablePayload(receiver, expression.receiver.span)
		if (expression.typeArguments == null && receiver is CArray && ArrayMutation.named(expression.name.lexeme) != null) return arrayMutation(expression, receiver)
		if (receiver is CNullable && (receiver.payload is CMemberType || receiver.payload.classBackedType() != null)) {
			throw SemanticErrors.nullablePayload(receiver, expression.receiver.span)
		}
		val bounds = boundViews(expression.receiver, receiver)
		if (bounds.isNotEmpty()) return boundMemberCall(expression, bounds, receiver)
		val owner = receiver as? CMemberType ?: return super.memberCallResults(expression, receiver)
		val group = facts.declarations.memberGroup(owner, expression.name.lexeme) ?: throw SemanticErrors.unknownMember(owner, expression.name)
		checkMethodReceiver(expression)
		if (group is InterfaceMethodGroupSymbol) {
			expression.typeArguments?.let { throw GenericErrors.callArity(expression.name, it.span, it.arguments.size, listOf(0), group.name) }
			val selected = overloads.interfaceCall(expression, group, objectReceiver(expression.receiver))
			facts.interfaceMemberCalls[expression] = selected
			return selected.type.results
		}
		val source = if (owner is CClass) MethodReceiver.Instance(ObjectReceiver.Explicit(expression.receiver)) else {
			for (part in facts.presence.lostReceiverProofs[expression].orEmpty()) {
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
		val selected = overloads.method(expression, expression.typeArguments, group as MethodGroupSymbol, classOwner, source)
		facts.recordMemberCall(expression, selected)
		return selected.type.results
	}

	private fun objectReceiver(expression: Expression): ObjectReceiver =
		facts.declarations.directReceiverOf(expression)?.let { ObjectReceiver.Self(it) } ?: ObjectReceiver.Explicit(expression)

	private fun checkMethodReceiver(expression: MemberCallExpression) {
		if (expression in facts.declarations.readOnlyDefaultReceivers) {
			throw SemanticErrors.parameterDefault("A default cannot call a user-defined method through a read-only receiver", expression.name.span)
		}
	}

	private fun boundMemberCall(expression: MemberCallExpression, bounds: List<CMemberType>, receiver: ValueType): ResultShape {
		val checked = facts.checkedBoundMethod(expression)
		val groups = if (checked == null) facts.declarations.boundMemberGroups(bounds, expression.name.lexeme) else emptyList()
		if (checked == null && groups.isEmpty()) throw SemanticErrors.unknownMember(receiver, expression.name)
		checkMethodReceiver(expression)
		val access = objectReceiver(expression.receiver)
		val selected = when (val contract = checked?.contract) {
			is MethodSymbol -> overloads.source(expression, listOf(contract), MethodReceiver.Instance(access))
			is InterfaceMethodSymbol -> overloads.interfaceCall(expression, InterfaceMethodGroupSymbol(checked.view as CInterface, listOf(contract)), access)
			else -> overloads.boundMember(expression, groups, classOwner, access)
		}
		when (selected) {
			is ResolvedCall.Source -> facts.recordMemberCall(expression, selected)
			is ResolvedCall.Interface -> facts.interfaceMemberCalls[expression] = selected
		}
		return selected.type.results
	}

	private fun arrayMutation(expression: MemberCallExpression, type: CArray): ResultShape {
		val operation = checkNotNull(ArrayMutation.named(expression.name.lexeme))
		if (type.size != null) throw SemanticErrors.arrayMutation("Method '${operation.spelling}' requires a growable array, got '${type.spelling}'", expression.name.span)
		if (operation.changesLength && expression.receiver is IndexExpression) throw SemanticErrors.arrayMutation("An embedded rectangular row cannot change its shape; copy it to an independent growable binding first", expression.receiver.span)
		if (expression in facts.declarations.readOnlyDefaultReceivers) throw SemanticErrors.parameterDefault("A default cannot mutate an array through a read-only receiver", expression.name.span)
		checkWritablePayload(expression.receiver)
		val result = super.memberCallResults(expression, type)
		for (part in facts.presence.lostReceiverProofs[expression].orEmpty()) {
			val declared = model.payloadReads[part] ?: continue
			throw SemanticErrors.receiverProofLost(declared, part.span)
		}
		val instance = instancePlace(expression.receiver)
		if (instance != null && expression in facts.declarations.fieldInitializerCalls && instance.receiver.isSelf()) throw SemanticErrors.partialClassInstance("A stored field receiver requires a fully initialized instance", expression.receiver.span)
		val place = if (instance == null) storedPlace(expression.receiver) else null
		val source = when {
			instance != null -> MethodReceiver.StoredInInstance(instance)
			place != null -> MethodReceiver.Stored(place)
			else -> MethodReceiver.Temporary(expression.receiver)
		}
		val bound = model.memberCalls.getValue(expression)
		model.arrayMutations[expression] = ArrayMutationPlan(operation, type, source, bound.arguments.drop(1), bound.overload.returns)
		return result
	}

	private fun ObjectReceiver.isSelf(): Boolean = when (this) {
		is ObjectReceiver.Self     -> true
		is ObjectReceiver.Explicit -> (expression as? NameExpression)?.let { facts.declarations.names[it] } is ReceiverSymbol
	}

}
