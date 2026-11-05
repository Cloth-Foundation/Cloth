package cloth.semantic

import cloth.builtin.BoundBuiltinCall
import cloth.builtin.BuiltinArgument
import cloth.builtin.attachments.PrimitiveAttachments
import cloth.data.*
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.parser.type.TypeReference
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenSpan
import java.math.BigInteger

/** A rejected overload probe is not a compiler diagnostic or error recovery. */
internal class CandidateMismatch : RuntimeException(null, null, false, false)

/** Shared expression rules for snippet checking and declaration call checking. */
internal abstract class ExpressionTyping(val model: SemanticModel, private val probe: Boolean = false, private val declarations: Boolean = false) {

	private var intrinsicTyping = false

	protected abstract fun nameType(expression: NameExpression): ValueType
	protected abstract fun statement(statement: Statement)
	protected abstract fun withScope(action: () -> Unit)
	protected open val supportsNullable: Boolean = false
	protected open fun namedTypeReceiver(expression: Expression): CStruct? = null
	protected open fun fieldPresent(expression: FieldAccessExpression): Boolean = false
	protected open fun callType(expression: CallExpression): ValueType = throw SemanticErrors.unknownName(expression.name)
	protected open fun callResults(expression: CallExpression): ResultShape = ResultShape.of(callType(expression))
	protected open fun completion(expression: Expression): ControlFlow = ControlFlow.FALLTHROUGH
	protected open fun completion(statements: List<Statement>): ControlFlow = ControlFlow.FALLTHROUGH
	protected fun reject(diagnostic: () -> SemanticException): Nothing {
		if (probe && !intrinsicTyping) throw CandidateMismatch()
		throw diagnostic()
	}

	/** No enclosing overload candidate can repair an expression with its own context. */
	private inline fun <T> intrinsic(action: () -> T): T {
		val previous = intrinsicTyping
		intrinsicTyping = true
		return try {
			action()
		} finally {
			intrinsicTyping = previous
		}
	}

	protected fun resolveType(type: TypeReference) = model.typeResolver.resolve(type, declarations).also {
		if (it.containsNullability && !supportsNullable) throw SemanticErrors.nullablePending(type.span)
	}

	/** A bool/string/etc. destination supplies no floating width to its subexpressions. */
	private fun ValueType?.suppliesFloatingContext(): Boolean = when (this) {
		is CNullable -> payload.suppliesFloatingContext()
		is CArray    -> element.suppliesFloatingContext()
		is CFloat    -> true
		else         -> false
	}

	/** Only literal signs and constant providers; bindings/calls keep runtime checks. */
	private fun integerConstant(expression: Expression, type: CInteger): BigInteger? {
		val value = when (expression) {
			is IntegerLiteral           -> expression.value
			is UnaryExpression          -> {
				val operand = integerConstant(expression.operand, type) ?: return null
				when (expression.operator.lexeme) {
					Operators.PLUS.toString()  -> operand
					Operators.MINUS.toString() -> -operand
					else                       -> return null
				}
			}

			is ProviderAccessExpression -> model.providers[expression]?.result?.integerConstant ?: return null
			else                        -> return null
		}
		return type.wrap(value)
	}

	private fun operandContext(expression: Expression, floating: Boolean): ValueType? = when (expression) {
		is NameExpression           -> nameType(expression).takeIf { if (floating) it.floating else it.integer }
		is CastExpression           -> resolveType(expression.target).takeIf { if (floating) it.floating else it.integer }
		is CallExpression           -> callType(expression).takeIf { if (floating) it.floating else it.integer }
		is MemberCallExpression     -> typeOf(expression).takeIf { if (floating) it.floating else it.integer }
		is IndexExpression          -> typeOf(expression).takeIf { if (floating) it.floating else it.integer }
		is FieldAccessExpression    -> typeOf(expression).takeIf { if (floating) it.floating else it.integer }
		is ProviderAccessExpression -> typeOf(expression).takeIf { if (floating) it.floating else it.integer }
		is UnaryExpression          -> if (expression.operator.lexeme in setOf(Operators.PLUS.toString(), Operators.MINUS.toString())) operandContext(expression.operand, floating) else null
		is BinaryExpression         -> if (expression.operator.lexeme in arithmeticOperators) operandContext(expression.left, floating) ?: operandContext(expression.right, floating) else null
		else                        -> null
	}

	private fun integer(literal: IntegerLiteral, expected: ValueType?, negative: Boolean = false): ValueType {
		val type = (if (expected is CNullable) expected.payload else expected) as? CInteger ?: ValueType.INT
		val value = if (negative) -literal.value else literal.value
		if (!type.acceptsLiteral(value)) reject { SemanticErrors.integer(value, type, literal.span) }
		model.expressions[literal] = type
		return type
	}

	/** Discover fixed value types before defaulting contextual literals. This checks
	 * types only; emission still evaluates every element once in source order.
	 */
	private fun arrayElementHint(expression: Expression, contextualFloat: Boolean): ValueType? = when (expression) {
		is StructLiteral        -> null
		is IntegerLiteral       -> null
		is FloatingPointLiteral -> if (contextualFloat) null else ValueType.FLOAT
		is ArrayLiteral         -> arrayElementContext(expression.elements, contextualFloat)?.let { CArray(it, expression.elements.size) }
		is UnaryExpression      -> if (expression.operator.lexeme in setOf(Operators.PLUS.toString(), Operators.MINUS.toString())) {
			arrayElementHint(expression.operand, contextualFloat)?.let { typeOf(expression, it, contextualFloat = contextualFloat) }
		} else typeOf(expression)

		is BinaryExpression     -> if (expression.operator.lexeme in arithmeticOperators) {
			val left = arrayElementHint(expression.left, contextualFloat)
			val right = arrayElementHint(expression.right, contextualFloat)
			if (left == null && right == null) null else typeOf(expression, left ?: right, contextualFloat = contextualFloat)
		} else typeOf(expression)

		is IfExpression         -> {
			var hint: ValueType? = null
			var first: TokenSpan? = null
			val bodies = expression.branches.map { it.statements } + listOfNotNull(expression.otherwise)
			for (body in bodies) withScope {
				for ((index, item) in body.withIndex()) {
					if (index == body.lastIndex && item is ExpressionStatement && completion(body).canFallThrough) {
						val next = arrayElementHint(item.expression, contextualFloat)
						if (next != null && next != ValueType.NEVER) {
							hint = hint?.commonArrayShape(next) ?: if (hint == null) next
							else reject { SemanticErrors.branchTypes(checkNotNull(hint), next, checkNotNull(first), item.span) }
							first = first ?: item.span
						}
					} else statement(item)
				}
			}
			hint
		}

		else                    -> typeOf(expression, contextualFloat = contextualFloat)
	}

	private fun arrayElementContext(elements: List<Expression>, contextualFloat: Boolean): ValueType? {
		var context: ValueType? = null
		for (item in elements) {
			val hint = arrayElementHint(item, contextualFloat) ?: continue
			if (hint == ValueType.NEVER) continue
			context = context?.commonArrayShape(hint) ?: if (context == null) hint
			else reject { SemanticErrors.array("Array elements require one exact type and matching dimensions; use explicit casts for differing numeric types", item.span) }
		}
		return context
	}

	/** Endpoint/element type discovery never uses the body or stepper as context. */
	protected fun checkFor(statement: ForStatement, stepperExpression: Expression?, introduce: (ValueType) -> Unit) {
		val annotation = statement.binding.type?.let { resolveType(it) }
		fun invalid(message: String, span: TokenSpan): Nothing = throw SemanticErrors.forLoop(message, span, statement.binding.span)
		if (annotation == ValueType.VOID) invalid("A loop binding cannot have type ${Keywords.VOID}", statement.binding.span)
		var array: CArray? = null
		val bindingType = when (val source = statement.source) {
			is ForSource.Range    -> {
				var required = annotation
				if (required == null) for (endpoint in listOf(source.start, source.end)) {
					val hint = arrayElementHint(endpoint, contextualFloat = true)?.takeUnless { it == ValueType.NEVER } ?: continue
					if (required != null && required != hint) invalid("Range endpoints require one exact numeric type, got '${required.spelling}' and '${hint.spelling}'", endpoint.span)
					required = hint
				}
				if (required == null) {
					required = typeOf(source.start, contextualFloat = true).takeUnless { it == ValueType.NEVER } ?: typeOf(source.end, contextualFloat = true).takeUnless { it == ValueType.NEVER } ?: invalid("Cannot infer a range counter from non-continuing endpoints; add a type annotation", statement.binding.span)
				}
				if (!required.numeric) invalid("Range counters require a non-nullable numeric type, got '${required.spelling}'", statement.binding.type?.span ?: source.span)
				for (endpoint in listOf(source.start, source.end)) {
					val actual = typeOf(endpoint, required, contextualFloat = true)
					if (actual != ValueType.NEVER && actual != required) invalid("Range endpoint requires '${required.spelling}', got '${actual.spelling}'; use an explicit cast", endpoint.span)
					if (required is CFloat && RangeConstants(model).floating(endpoint)?.isFinite() == false) invalid("Float range endpoints must be finite", endpoint.span)
				}
				required
			}

			is ForSource.Elements -> {
				val actual = typeOf(source.expression, annotation?.let { CArray(it) }, contextualFloat = true)
				if (actual == ValueType.NEVER) {
					annotation ?: invalid("A non-continuing foreach source needs an explicit element type", statement.binding.span)
				} else {
					array = actual as? CArray ?: invalid("Foreach requires an array source, got '${actual.spelling}'", source.span)
					val element = array.element
					if (annotation == null && element.containsNullability) throw SemanticErrors.nullableContext(source.span)
					if (annotation != null && !annotation.accepts(element)) invalid("Foreach element requires '${annotation.spelling}', got '${element.spelling}'", source.span)
					annotation?.inferArraySizes(element) ?: element
				}
			}
		}
		if (bindingType == ValueType.VOID) invalid("A loop binding cannot have type ${Keywords.VOID}", statement.binding.span)
		model.loops[statement] = ForLoopPlan(model.target, bindingType, array, stepperExpression)
		withScope {
			introduce(bindingType)
			loopBody(statement, statement.body.statements)
			stepperExpression?.let { expression ->
				val actual = typeOf(expression, bindingType)
				if (!bindingType.accepts(actual)) throw SemanticErrors.typeMismatch(bindingType, actual, statement.binding.name, expression, statement.binding.name)
				statement.stepper?.value?.let { value ->
					if (bindingType is CFloat && RangeConstants(model).floating(value)?.isFinite() == false) invalid("Float range step values must be finite", value.span)
				}
			}
		}
		model.recordResult(statement, ResultShape.Empty)
	}

	protected open fun loopBody(loop: LoopStatement, body: List<Statement>) = withScope { body.forEach { statement(it) } }

	protected fun checkDoWhile(statement: DoWhileStatement) {
		loopBody(statement, statement.body.statements)
		val condition = typeOf(statement.condition)
		if (condition != ValueType.BOOL && condition != ValueType.NEVER) throw SemanticErrors.condition(statement.condition, Keywords.WHILE)
		model.recordResult(statement, ResultShape.Empty)
	}

	protected fun checkWhile(statement: WhileStatement) {
		val condition = typeOf(statement.condition)
		if (condition != ValueType.BOOL && condition != ValueType.NEVER) throw SemanticErrors.condition(statement.condition, Keywords.WHILE)
		loopBody(statement, statement.body.statements)
		model.recordResult(statement, ResultShape.Empty)
	}

	fun typeOf(expression: Expression, expected: ValueType? = null, requireValue: Boolean = true, contextualFloat: Boolean = expected.suppliesFloatingContext(), allowVoidResult: Boolean = false, floatingLiteralType: CFloat? = null): ValueType {
		val result = resultOf(expression, expected?.let { ResultShape.of(it) }, requireValue, contextualFloat, allowVoidResult, floatingLiteralType = floatingLiteralType)
		// No parameter context can make multiple intrinsic results into one argument.
		return result.scalarOrNull() ?: throw SemanticErrors.scalarResults(result, expression.span)
	}

	fun resultOf(expression: Expression, expected: ResultShape? = null, requireValue: Boolean = true, contextualFloat: Boolean = expected?.values?.any { it.suppliesFloatingContext() } == true, allowVoidResult: Boolean = false, receptionContext: ReceptionContext? = null, floatingLiteralType: CFloat? = null): ResultShape {
		if (!supportsNullable && (expression is NullLiteral || expression is ElvisExpression)) throw SemanticErrors.nullablePending(expression.span)
		model.metadata.remove(expression)
		val result = when (expression) {
			is CallExpression                                                                     -> callResults(expression)
			is ResultListExpression                                                               -> {
				val values = expression.values.mapIndexed { index, value ->
					val context = receptionContext?.positional?.getOrNull(index) ?: expected?.values?.getOrNull(index)
					val floating = contextualFloat && (context == null || context.suppliesFloatingContext())
					typeOf(value, context, contextualFloat = floating, floatingLiteralType = floatingLiteralType.takeIf { floating })
				}
				if (values.any { it == ValueType.NEVER }) ResultShape.Never else ResultShape.of(values)
			}

			is IfExpression                                                                       -> conditionalResults(expression, expected, requireValue, contextualFloat, allowVoidResult, receptionContext, floatingLiteralType)
			is CastExpression if declarations && resolveType(expression.target) == ValueType.VOID -> {
				resultOf(expression.operand, requireValue = false)
				ResultShape.Empty
			}

			else                                                                                  -> ResultShape.of(scalarTypeOf(expression, receptionContext?.broadcast ?: expected?.scalarOrNull(), requireValue, contextualFloat, allowVoidResult, floatingLiteralType))
		}
		val completed = if (completion(expression).canFallThrough) result else ResultShape.Never
		if (requireValue && !allowVoidResult && completed == ResultShape.Empty) reject { SemanticErrors.voidValue(expression.span) }
		model.recordResult(expression, completed)
		return completed
	}

	private fun conditionalResults(expression: IfExpression, expected: ResultShape?, requireValue: Boolean, contextualFloat: Boolean, allowVoidResult: Boolean, receptionContext: ReceptionContext?, floatingLiteralType: CFloat?): ResultShape {
		if (requireValue && completion(expression).canFallThrough && expression.otherwise == null) reject { SemanticErrors.missingElse(expression.span) }
		for (branch in expression.branches) {
			val condition = typeOf(branch.condition)
			if (condition != ValueType.BOOL && condition != ValueType.NEVER) reject { SemanticErrors.condition(branch.condition) }
		}
		var result: ResultShape? = null
		var firstSpan: TokenSpan? = null
		val bodies = expression.branches.map { it.statements to it.span } + listOfNotNull(expression.otherwise?.let { it to checkNotNull(expression.otherwiseSpan) })
		for ((statements, bodySpan) in bodies) {
			val needsValue = requireValue && completion(statements).canFallThrough
			if (needsValue && statements.lastOrNull() !is ExpressionStatement) reject { SemanticErrors.branchValue(bodySpan) }
			withScope {
				for ((index, item) in statements.withIndex()) {
					if (needsValue && index == statements.lastIndex) {
						val next = resultOf((item as ExpressionStatement).expression, expected, contextualFloat = contextualFloat, allowVoidResult = allowVoidResult, receptionContext = receptionContext, floatingLiteralType = floatingLiteralType)
						for ((position, type) in next.values.withIndex()) if (type.containsNullability) {
							val context = receptionContext?.positional?.getOrNull(position) ?: receptionContext?.broadcast ?: expected?.values?.getOrNull(position)
							if (context !is CNullable) reject { SemanticErrors.nullableContext(item.span) }
						}
						model.recordResult(item, next)
						if (next != ResultShape.Never) {
							val previous = result
							result = if (previous == null || previous == next) next else {
								if (previous.values.size != next.values.size) {
									if (previous !is ResultShape.Multiple && next !is ResultShape.Multiple) reject { SemanticErrors.branchTypes(previous.scalar(), next.scalar(), checkNotNull(firstSpan), item.span) }
									reject { SemanticErrors.branchResultArity(previous, next, checkNotNull(firstSpan), item.span) }
								}
								ResultShape.of(previous.values.zip(next.values).mapIndexed { position, (left, right) ->
									val context = (if (next is ResultShape.Multiple) receptionContext?.positional?.getOrNull(position) else receptionContext?.broadcast) ?: expected?.values?.getOrNull(position)
									if (left == right) left else if ((context is CArray || context is CNullable) && context.accepts(left) && context.accepts(right)) context
									else reject { SemanticErrors.branchTypes(left, right, checkNotNull(firstSpan), item.span) }
								})
							}
							firstSpan = firstSpan ?: item.span
						}
					} else statement(item)
				}
			}
		}
		return if (requireValue) result ?: ResultShape.Never else ResultShape.Empty
	}

	fun definitionPlan(group: BindingGroupDeclaration): ReceptionPlan {
		if (group.initializer is FunctionBlockExpression) throw SemanticErrors.blockReception(group.span)
		val annotations = group.types.map { annotation ->
			resolveType(annotation).also { if (it == ValueType.VOID) reject { SemanticErrors.voidType(annotation.span) } }
		}
		if (annotations.size > 1 && annotations.size != group.targets.size) reject {
			SemanticErrors.receptionAnnotations("Expected ${group.targets.size} positional type annotations, got ${annotations.size}", group.span)
		}
		val context = ReceptionContext(annotations.singleOrNull(), if (annotations.size > 1) annotations else List(group.targets.size) { null })
		val actual = resultOf(group.initializer, contextualFloat = annotations.any { it.suppliesFloatingContext() }, receptionContext = context)
		if (actual is ResultShape.Multiple && annotations.size == 1) reject {
			SemanticErrors.receptionAnnotations("A multiple-result definition requires one explicit type per position, or an inferred type list", group.types.single().span)
		}
		if (actual is ResultShape.Single && annotations.size > 1) reject {
			SemanticErrors.receptionAnnotations("A scalar broadcast accepts one shared type annotation", group.span)
		}
		val expected = if (annotations.size == 1) List(group.targets.size) { annotations.single() } else List(group.targets.size) { annotations.getOrNull(it) }
		return receive(group, group.initializer, group.targets, actual, expected, group.targets.map { it.token }, definition = true, group = group)
	}

	fun assignmentPlan(statement: MultiAssignmentStatement, types: List<ValueType?>, declarations: List<Token?>): ReceptionPlan {
		val context = ReceptionContext(types.firstOrNull { it != null }, types)
		val actual = resultOf(statement.value, contextualFloat = types.any { it.suppliesFloatingContext() }, receptionContext = context)
		return receive(statement, statement.value, statement.targets, actual, types, declarations, definition = false)
	}

	private fun receive(statement: Statement, producer: Expression, targets: List<ReceivingTarget>, actual: ResultShape, expected: List<ValueType?>, declarations: List<Token?>, definition: Boolean, group: BindingGroupDeclaration? = null): ReceptionPlan {
		val kind = when (actual) {
			is ResultShape.Single   -> ReceptionKind.BROADCAST
			is ResultShape.Multiple -> {
				if (actual.values.size != targets.size) reject { SemanticErrors.receptionArity(targets.size, actual, statement.span) }
				ReceptionKind.POSITIONAL
			}

			ResultShape.Never       -> ReceptionKind.NONCONTINUING
			ResultShape.Empty       -> reject { SemanticErrors.voidValue(producer.span) }
		}
		val slots = targets.mapIndexed { index, target ->
			val source = if (kind == ReceptionKind.BROADCAST) 0 else index
			val value = if (actual == ResultShape.Never) ValueType.NEVER else actual.values[source]
			val required = expected[index]
			val location = if (kind == ReceptionKind.POSITIONAL && producer is ResultListExpression) producer.values[source] else producer
			if (definition && target is ReceivingTarget.Named && required == null && value.containsNullability) throw SemanticErrors.nullableContext(location.span)
			if (required != null && !required.accepts(value)) reject {
				if (target is ReceivingTarget.Discard) SemanticErrors.discardedType(required, value, index, location.span, group?.types?.getOrNull(index)?.span ?: target.span)
				else SemanticErrors.typeMismatch(required, value, target.token, location, declarations[index] ?: target.token)
			}
			val type = if (definition && required != null) required.inferArraySizes(value) else required ?: value
			ReceptionSlot(target, type, source)
		}
		return ReceptionPlan(producer, actual, kind, slots).also {
			model.receptions[statement] = it
			model.recordResult(statement, it.statementResult)
		}
	}

	/** A receiver that never returns has no concrete family. Use argument hints
	 * when names are shared; zero-argument calls can use a catalog representative.
	 * Normal operand typing still checks every written argument afterward.
	 */
	private fun nonReturningMember(expression: MemberCallExpression): cloth.builtin.attachments.Attachment? {
		val candidates = PrimitiveAttachments.all(model.target).filter { it.definition.name == expression.name.lexeme }
		if (candidates.size <= 1) return candidates.singleOrNull()
		// Preserve label/arity priority before inspecting arguments for family hints.
		if (expression.suppliedArguments.any { it.label != null } || candidates.all { it.parameters.size != expression.arguments.size }) return candidates.first()
		val hint = expression.arguments.firstNotNullOfOrNull { arrayElementHint(it, contextualFloat = true)?.takeUnless { type -> type == ValueType.NEVER } } ?: expression.arguments.firstNotNullOfOrNull { arrayElementHint(it, contextualFloat = false)?.takeUnless { type -> type == ValueType.NEVER } }
		return hint?.let { PrimitiveAttachments[it, expression.name.lexeme, model.target] } ?: candidates.first()
	}

	private fun memberCallType(expression: MemberCallExpression): ValueType = intrinsic {
		val receiver = typeOf(expression.receiver)
		val attachment = PrimitiveAttachments[receiver, expression.name.lexeme, model.target] ?: if (receiver == ValueType.NEVER) nonReturningMember(expression) else null
		val selected = attachment ?: throw SemanticErrors.unknownMember(receiver, expression.name)
		if (selected.overload.returns.containsNullability && !supportsNullable) throw SemanticErrors.nullablePending(expression.span)
		if (expression.suppliedArguments.any { it.label != null }) throw SemanticErrors.argumentBinding("Built-in member methods do not publish named argument labels yet", expression.span)
		if (expression.arguments.size != selected.parameters.size) throw SemanticErrors.memberArity(expression.name, expression.arguments.size, selected.parameters.size)
		val arguments = listOf(BuiltinArgument(receiver, selected.receiver)) + expression.arguments.mapIndexed { index, argument ->
			val parameter = selected.parameters[index]
			val actual = typeOf(argument, parameter.expectedType, contextualFloat = parameter.expectedType != null)
			parameter.bind(actual, model.target) ?: throw SemanticErrors.memberArgument(expression.name, argument, actual, parameter)
		}
		if (receiver is CInteger) {
			val constants = RangeConstants(model)
			val methods = PrimitiveAttachments.integer(receiver, model.target)
			when (selected) {
				methods.ABS             -> if (!receiver.unsigned && constants.integer(expression.receiver) == receiver.minimum) throw SemanticErrors.integerAbs(receiver, expression.name.span)
				methods.CLAMP           -> {
					val minimum = constants.integer(expression.arguments[0])
					val maximum = constants.integer(expression.arguments[1])
					if (minimum != null && maximum != null && minimum > maximum) throw SemanticErrors.clampBounds(expression.name.span)
				}

				methods.TO_STRING_RADIX -> {
					val radix = constants.integer(expression.arguments.single())
					if (radix != null && (radix < BigInteger.TWO || radix > BigInteger.valueOf(36))) throw SemanticErrors.integerRadix(expression.arguments.single().span)
				}
			}
		}
		if (receiver is CFloat) {
			val constants = RangeConstants(model)
			val methods = PrimitiveAttachments.floating(receiver)
			when (selected) {
				methods.SIGNUM -> if (constants.floating(expression.receiver)?.isNaN() == true) throw SemanticErrors.floatSignum(expression.name.span)
				methods.CLAMP  -> {
					val minimum = constants.floating(expression.arguments[0])
					val maximum = constants.floating(expression.arguments[1])
					if (minimum?.isNaN() == true || maximum?.isNaN() == true || minimum != null && maximum != null && minimum > maximum) throw SemanticErrors.floatClampBounds(expression.name.span)
				}
			}
		}
		model.memberCalls[expression] = BoundBuiltinCall(selected.definition, selected.overload, arguments)
		selected.overload.returns
	}

	private fun scalarTypeOf(expression: Expression, expected: ValueType?, requireValue: Boolean, contextualFloat: Boolean, allowVoidResult: Boolean, floatingLiteralType: CFloat?): ValueType {
		model.metadata.remove(expression)
		val type = when (expression) {
			is StructLiteral            -> structLiteralType(expression, expected)
			is FieldAccessExpression    -> intrinsic { fieldType(expression) }
			is NullLiteral              -> expected as? CNullable ?: ValueType.Null
			is ElvisExpression          -> elvisType(expression, expected, contextualFloat, floatingLiteralType)
			is TypeReceiverExpression   -> throw SemanticErrors.typeReceiver(expression.span)
			is StateCallExpression      -> throw SemanticErrors.stateCall(expression.name)
			is MemberCallExpression     -> memberCallType(expression)
			is ProviderAccessExpression -> {
				val receiver = expression.receiver
				val named = namedTypeReceiver(receiver)
				val typeReceiver = named != null || receiver is TypeReceiverExpression
				val receiverType = if (typeReceiver) {
					(named ?: model.typeResolver.resolve((receiver as TypeReceiverExpression).annotation, declarations = true)).also {
						if (it.containsNullability && !supportsNullable) throw SemanticErrors.nullablePending(receiver.span)
						model.expressions[receiver] = it
					}
				} else {
					val readType = typeOf(receiver)
					val declaredField = (receiver as? FieldAccessExpression)?.let { model.payloadReads[it] }
					// Scalar storage size describes the stored nullable field even after a guard.
					if (declaredField != null && expression.name.lexeme == CompilerDataProviders.SIZE.toString() && declaredField.provider(expression.name.lexeme, model.target) is ProviderResult.StorageSize) {
						model.payloadReads.remove(receiver)
						model.recordResult(receiver, ResultShape.of(declaredField))
						declaredField
					} else readType
				}
				if (receiverType == ValueType.NEVER) ValueType.NEVER else {
					// Availability is independent of the enclosing overload's return/argument context.
					val result = receiverType.provider(expression.name.lexeme, model.target) ?: run {
						if (receiverType is CNullable && receiverType.payload.provider(expression.name.lexeme, model.target) != null) {
							if (typeReceiver) throw SemanticErrors.providerReceiver(receiverType, expression.name)
							throw SemanticErrors.nullablePayload(receiverType, receiver.span)
						}
						if (receiverType == ValueType.Null) throw SemanticErrors.nullableContext(receiver.span)
						throw SemanticErrors.provider(receiverType, expression.name)
					}
					val access = ProviderAccess(receiver, result, expression.name.span, if (typeReceiver) ProviderReceiverForm.TYPE else ProviderReceiverForm.VALUE)
					if (access.receiverForm !in result.receiverForms) {
						if (receiverType is CNullable && !typeReceiver && result is ProviderResult.NullableType) throw SemanticErrors.nullablePayload(receiverType, receiver.span)
						throw SemanticErrors.providerReceiver(receiverType, expression.name)
					}
					model.providers[expression] = access
					when (result) {
						is ProviderResult.TypeMetadata     -> model.metadata[expression] = receiverType
						is ProviderResult.MetadataProperty -> model.metadata[receiver]?.let { represented ->
							if (!result.query.accepts(represented)) throw SemanticErrors.metadata(represented, expression.name, result.query.requirement)
							if (result.query == MetadataQuery.ELEMENT) model.metadata[expression] = (result.query.subject(represented) as CArray).element
						}

						else                               -> Unit
					}
					result.type
				}
			}

			is ArrayLiteral             -> {
				val context = ((expected as? CNullable)?.payload ?: expected) as? CArray
				if (context?.size != null && context.size != expression.elements.size.toBigInteger()) reject { SemanticErrors.array("Expected ${context.size} array elements, got ${expression.elements.size}; every element must be initialized", expression.span) }
				val elementContext = context?.element ?: arrayElementContext(expression.elements, contextualFloat)
				if (context == null && elementContext?.containsNullability == true) throw SemanticErrors.nullableContext(expression.span)
				var element: ValueType? = null
				for (item in expression.elements) {
					val actual = typeOf(item, elementContext ?: element, contextualFloat = contextualFloat)
					val required = elementContext ?: element
					if (required != null && !required.accepts(actual)) reject { SemanticErrors.array("Array element requires '${required.spelling}', got '${actual.spelling}'", item.span) }
					if (actual != ValueType.NEVER) {
						val candidate = elementContext?.inferArraySizes(actual) ?: actual
						element = element?.commonArrayShape(candidate) ?: if (element == null) candidate
						else reject { SemanticErrors.array("Rectangular arrays require matching element types and dimensions", item.span) }
					}
				}
				if (element == null && !completion(expression).canFallThrough) ValueType.NEVER
				else CArray(element ?: elementContext ?: reject { SemanticErrors.array("An empty array needs an explicit element type", expression.span) }, expression.elements.size)
			}

			is IndexExpression          -> {
				val array = typeOf(expression.array)
				val index = typeOf(expression.index)
				if (array !is CArray && array != ValueType.NEVER) reject { SemanticErrors.index("Indexing requires an array", expression.array.span) }
				if (!index.integer && index != ValueType.NEVER) reject { SemanticErrors.index("Array index must be an integer", expression.index.span) }
				(array as? CArray)?.element ?: ValueType.NEVER
			}

			is IntegerLiteral           -> integer(expression, expected)
			is FloatingPointLiteral     -> {
				val type = if (contextualFloat) floatingLiteralType ?: ((if (expected is CNullable) expected.payload else expected) as? CFloat) ?: ValueType.FLOAT else ValueType.FLOAT
				if (!type.acceptsLiteral(expression.value.toBigDecimal())) reject { SemanticErrors.floating(expression.token.lexeme, type, expression.span) }
				type
			}

			is BooleanLiteral           -> ValueType.BOOL
			is CharacterLiteral         -> ValueType.CHAR
			is StringLiteral            -> ValueType.STRING
			is NameExpression           -> nameType(expression)
			is CallExpression           -> callType(expression)
			is InterpolatedString       -> {
				expression.parts.forEach { part ->
					val type = typeOf(part)
					val payload = if (type is CNullable) type.payload else type
					val text = payload.provider(CompilerDataProviders.STR.toString(), model.target)
					if (payload != ValueType.NEVER && payload != ValueType.Null && (text?.type != ValueType.STRING || ProviderReceiverForm.VALUE !in text.receiverForms)) {
						// Holes receive no overload context; this failure cannot be fixed by another candidate.
						throw SemanticErrors.interpolation(type, part.span)
					}
				}
				ValueType.STRING
			}

			is TypeCheckExpression      -> {
				val source = typeOf(expression.operand)
				if (source == ValueType.Null) throw SemanticErrors.nullableContext(expression.operand.span)
				val target = resolveType(expression.target)
				model.typeChecks[expression] = target
				ValueType.BOOL
			}

			is CastExpression           -> {
				val target = resolveType(expression.target)
				val source = typeOf(expression.operand, expected = (target as? CNullable)?.takeIf { expression.operand is NullLiteral }, requireValue = !(declarations && target == ValueType.VOID))
				if ((source is CNullable || source == ValueType.Null) && target !is CNullable && target != ValueType.VOID) throw SemanticErrors.nullablePayload(source, expression.operand.span)
				val sourcePayload = (source as? CNullable)?.payload ?: source
				val targetPayload = (target as? CNullable)?.payload ?: target
				if (source != ValueType.NEVER && sourcePayload != targetPayload && !(sourcePayload.numeric && targetPayload.numeric) && !(declarations && target == ValueType.VOID)) reject { SemanticErrors.cast(source, target, expression.span) }
				if (target == ValueType.TYPEINFO) model.metadata[expression.operand]?.let { model.metadata[expression] = it }
				target
			}

			is UnaryExpression          -> {
				val operand = if (expression.operator.lexeme == Operators.MINUS.toString() && expression.operand is IntegerLiteral) integer(expression.operand, expected, negative = true)
				else typeOf(expression.operand, if (expression.operator.lexeme == Operators.NOT.toString()) null else expected, contextualFloat = contextualFloat, floatingLiteralType = floatingLiteralType.takeUnless { expression.operator.lexeme == Operators.NOT.toString() })
				if (operand is CNullable || operand == ValueType.Null) throw SemanticErrors.nullablePayload(operand, expression.operand.span)
				if (expression.operator.lexeme == Operators.NOT.toString()) {
					if (operand != ValueType.BOOL && operand != ValueType.NEVER) reject { SemanticErrors.unary(expression.operator, expression.operand, operand, boolean = true) }
				} else if (!operand.numeric && operand != ValueType.NEVER) reject { SemanticErrors.unary(expression.operator, expression.operand, operand, boolean = false) }
				operand
			}

			is BinaryExpression         -> {
				if (expression.rangeOrNull() != null) throw SemanticErrors.rangeMembership("A range requires a numeric operand and '${Operators.EQUAL}' or '${Operators.NOT_EQUAL}'", expression.span)
				if (expression.operator.lexeme in setOf(Operators.EQUAL.toString(), Operators.NOT_EQUAL.toString())) {
					if (expression.left.rangeOrNull() != null || expression.right.rangeOrNull() != null) {
						return intrinsic { rangeMembership(expression) }.also { model.expressions[expression] = it }
					}
					val nullable = nullableEquality(expression, contextualFloat)
					if (nullable != null) return nullable.also { model.expressions[expression] = it }
				}
				val numeric = expression.operator.lexeme in arithmeticOperators || expression.operator.lexeme in comparisonOperators
				val payloadContext = (expected as? CNullable)?.payload ?: expected
				val intContext = if (numeric) operandContext(expression.left, false) ?: operandContext(expression.right, false) ?: payloadContext?.takeIf { it.integer && expression.operator.lexeme in arithmeticOperators } else null
				// Integer peers supply integer literal widths, independently of floating
				// literal context. Carry the floating format through nested arithmetic
				// even when the ordinary expected type carries an integer peer's identity.
				val floatContext = if (numeric && contextualFloat) {
					(operandContext(expression.left, true) as? CFloat) ?: (operandContext(expression.right, true) as? CFloat) ?: floatingLiteralType ?: (payloadContext as? CFloat)?.takeIf { expression.operator.lexeme in arithmeticOperators }
				} else null
				val left = typeOf(expression.left, intContext ?: floatContext, contextualFloat = contextualFloat, floatingLiteralType = floatContext)
				val right = typeOf(expression.right, intContext ?: floatContext, contextualFloat = contextualFloat, floatingLiteralType = floatContext)
				if (left is CNullable || left == ValueType.Null) throw SemanticErrors.nullablePayload(left, expression.left.span)
				if (right is CNullable || right == ValueType.Null) throw SemanticErrors.nullablePayload(right, expression.right.span)
				if (expression.operator.lexeme == Operators.MUL_MUL.toString() && left.integer && right.integer) {
					val exponent = expression.right
					if (exponent is UnaryExpression && exponent.operator.lexeme == Operators.MINUS.toString() && exponent.operand is IntegerLiteral && exponent.operand.value.signum() > 0) reject { SemanticErrors.negativeExponent(exponent.span) }
				}
				if (numeric && left.integer && right.integer && left != right) reject { SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right, mixedIntegers = true) }
				if (expression.operator.lexeme in setOf(Operators.DIVIDE.toString(), Operators.MODULO.toString()) && left is CInteger && right == left) {
					val divisor = integerConstant(expression.right, left)
					if (divisor == BigInteger.ZERO) throw SemanticErrors.zeroDivisor(expression.operator, expression.right)
					if (!left.unsigned && divisor == -BigInteger.ONE && integerConstant(expression.left, left) == left.minimum) {
						reject { SemanticErrors.divisionOverflow(expression.operator, left) }
					}
				}
				when (expression.operator.lexeme) {
					Operators.EQUAL.toString(), Operators.NOT_EQUAL.toString()                                                                                                                                               -> {
						if (left is CArray || right is CArray || left is CStruct || right is CStruct || (left != ValueType.NEVER && right != ValueType.NEVER && left != right && !(left.numeric && right.numeric))) reject { SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right, comparison = true) }
						ValueType.BOOL
					}

					Operators.PLUS.toString() if (left == ValueType.STRING || left == ValueType.NEVER) && (right == ValueType.STRING || right == ValueType.NEVER) && (left == ValueType.STRING || right == ValueType.STRING) -> ValueType.STRING
					Operators.AND.toString(), Operators.OR.toString()                                                                                                                                                        -> {
						if ((left != ValueType.BOOL && left != ValueType.NEVER) || (right != ValueType.BOOL && right != ValueType.NEVER)) reject { SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right, boolean = true) }
						ValueType.BOOL
					}

					else                                                                                                                                                                                                     -> {
						if ((!left.numeric && left != ValueType.NEVER) || (!right.numeric && right != ValueType.NEVER)) reject { SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right) }
						if (expression.operator.lexeme in comparisonOperators) ValueType.BOOL else if (left.floating || right.floating) ValueType.commonFloating(left, right) else left
					}
				}
			}

			else                        -> throw InternalErrors.invariant("Unsupported expression: $expression")
		}
		val completedType = if (completion(expression).canFallThrough) type else ValueType.NEVER
		if (requireValue && !allowVoidResult && completedType == ValueType.VOID) reject { SemanticErrors.voidValue(expression.span) }
		model.expressions[expression] = completedType
		return completedType
	}

	private fun structLiteralType(expression: StructLiteral, expected: ValueType?): CStruct {
		if (!declarations) throw SemanticErrors.structSnippet(expression.span)
		val type = ((expected as? CNullable)?.payload ?: expected) as? CStruct ?: reject { SemanticErrors.structLiteral("A struct literal requires an expected struct type", expression.span) }
		if (expression.fields.size != type.fields.size) reject {
			SemanticErrors.structLiteral("Struct '${type.spelling}' requires ${type.fields.size} field values, got ${expression.fields.size}", expression.span, type.declaration.name.span)
		}
		for ((index, value) in expression.fields.withIndex()) {
			val field = type.fields[index]
			val actual = typeOf(value, field.type)
			if (!field.type.accepts(actual)) reject {
				SemanticErrors.structField("Field '${field.declaration.name.lexeme}' requires '${field.type.spelling}', got '${actual.spelling}'", value.span, field.declaration.span)
			}
		}
		model.structLiterals[expression] = type
		return type
	}

	private fun fieldType(expression: FieldAccessExpression): ValueType {
		if (!declarations) throw SemanticErrors.structSnippet(expression.span)
		val receiver = typeOf(expression.receiver)
		if (receiver == ValueType.NEVER) return receiver
		if (receiver is CNullable || receiver == ValueType.Null) throw SemanticErrors.nullablePayload(receiver, expression.receiver.span)
		val field = (receiver as? CStruct)?.field(expression.name.lexeme) ?: throw SemanticErrors.structField("Type '${receiver.spelling}' has no field '${expression.name.lexeme}'", expression.name.span)
		model.fields[expression] = field
		val type = field.type
		return if (type is CNullable && fieldPresent(expression)) {
			model.payloadReads[expression] = type
			type.payload
		} else {
			model.payloadReads.remove(expression)
			type
		}
	}

	private fun rangeMembership(expression: BinaryExpression): ValueType {
		val range = expression.left.rangeOrNull() ?: checkNotNull(expression.right.rangeOrNull())
		val value = if (range === expression.left) expression.right else expression.left
		fun invalid(message: String, source: Expression): Nothing = reject { SemanticErrors.rangeMembership(message, source.span) }
		if (value.rangeOrNull() != null) invalid("Range membership requires one numeric value and one range", expression)
		val operands = listOf(value, range.left, range.right)
		// Discover fixed contracts before assigning literal widths, including when the
		// range is written first. This is type discovery, never runtime evaluation.
		val required = operands.firstNotNullOfOrNull {
			// An exiting expression has no value contract to contribute, even if
			// its unreachable alternative happens to end with a typed value.
			if (completion(it).canFallThrough) arrayElementHint(it, contextualFloat = true)?.takeUnless { type -> type == ValueType.NEVER } else null
		} ?: operands.firstNotNullOfOrNull { typeOf(it, contextualFloat = true).takeUnless { type -> type == ValueType.NEVER } }
		// When every operand exits, no numeric value reaches a comparison. A
		// literal context still lets us check all syntax without rejecting never.
		?: ValueType.INT
		if (!required.numeric) invalid("Range membership requires a non-nullable numeric type, got '${required.spelling}'", value)
		for (operand in operands) {
			val actual = typeOf(operand, required, contextualFloat = true)
			if (actual != ValueType.NEVER && actual != required) invalid("Range membership requires '${required.spelling}', got '${actual.spelling}'; use an explicit cast", operand)
			if (operand !== value && required is CFloat && RangeConstants(model).floating(operand)?.isFinite() == false) invalid("Float range endpoints must be finite", operand)
		}
		// Traversal passes visit the range syntax, but it supplies no stored value.
		model.recordResult(range, ResultShape.Empty)
		return if (completion(expression).canFallThrough) ValueType.BOOL else ValueType.NEVER
	}

	private fun elvisType(expression: ElvisExpression, expected: ValueType?, contextualFloat: Boolean, floatingLiteralType: CFloat?): ValueType {
		val context = (expected as? CNullable)?.payload ?: expected?.takeUnless { it == ValueType.VOID || it == ValueType.NEVER || it == ValueType.Null }
		if (expression.left is NullLiteral && context == null) {
			val fallback = typeOf(expression.fallback, contextualFloat = contextualFloat, floatingLiteralType = floatingLiteralType)
			val payload = (fallback as? CNullable)?.payload ?: fallback
			if (payload in setOf(ValueType.Null, ValueType.VOID, ValueType.NEVER)) throw SemanticErrors.nullableContext(expression.span)
			typeOf(expression.left, CNullable(payload))
			return fallback
		}
		val left = typeOf(expression.left, if (expression.left is NullLiteral) context?.let(::CNullable) else expected as? CNullable, contextualFloat = contextualFloat, floatingLiteralType = floatingLiteralType)
		if (left == ValueType.NEVER) {
			typeOf(expression.fallback, expected, contextualFloat = contextualFloat, floatingLiteralType = floatingLiteralType)
			return ValueType.NEVER
		}
		if (left !is CNullable) throw SemanticErrors.elvis("The left operand of '${Operators.ELVIS}' requires a nullable source type", expression.left.span)
		val fallbackContext = if (expected is CNullable) left else left.payload
		// Elvis owns its payload context even when consumed by a nonnumeric call
		// or used as a member receiver, neither of which supplies a float context.
		val fallback = typeOf(expression.fallback, fallbackContext, contextualFloat = contextualFloat || left.payload is CFloat, floatingLiteralType = (left.payload as? CFloat) ?: floatingLiteralType)
		if (fallback == ValueType.NEVER) return left.payload
		if (!left.accepts(fallback)) throw SemanticErrors.elvis("Fallback requires '${left.payload.spelling}' or '${left.spelling}', got '${fallback.spelling}'", expression.fallback.span)
		return if (fallback is CNullable || fallback == ValueType.Null) left else left.payload
	}

	/** Only inspect fixed source contracts; literals keep the peer payload's context. */
	private fun equalityHint(value: Expression): ValueType? = when (value) {
		is NameExpression                                                                             -> nameType(value)
		is CallExpression                                                                             -> callType(value)
		is MemberCallExpression                                                                       -> typeOf(value)
		is CastExpression                                                                             -> resolveType(value.target)
		is IndexExpression, is FieldAccessExpression, is ProviderAccessExpression, is ElvisExpression -> typeOf(value)
		else                                                                                          -> null
	}

	private fun nullableEquality(expression: BinaryExpression, contextualFloat: Boolean): ValueType? {
		val leftHint = equalityHint(expression.left)
		val rightHint = equalityHint(expression.right)
		val context = (leftHint as? CNullable) ?: (rightHint as? CNullable)
		if (context == null && expression.left !is NullLiteral && expression.right !is NullLiteral) return null
		val left = typeOf(expression.left, if (expression.left is NullLiteral) context else context?.payload, contextualFloat = contextualFloat)
		val right = typeOf(expression.right, if (expression.right is NullLiteral) context else context?.payload, contextualFloat = contextualFloat)
		val wrapper = left as? CNullable ?: right as? CNullable
		if (wrapper == null) throw SemanticErrors.nullComparison("A ${Keywords.NULL} comparison requires a typed nullable operand", expression.span)
		val payload = wrapper.payload
		if (!wrapper.accepts(left) || !wrapper.accepts(right) || (payload is CArray || payload is CStruct) && expression.left !is NullLiteral && expression.right !is NullLiteral) reject { SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right, comparison = true) }
		model.nullableComparisons[expression] = payload
		return if (completion(expression).canFallThrough) ValueType.BOOL else ValueType.NEVER
	}

	companion object {

		private val arithmeticOperators = setOf(Operators.PLUS.toString(), Operators.MINUS.toString(), Operators.MULTIPLY.toString(), Operators.DIVIDE.toString(), Operators.MODULO.toString(), Operators.MUL_MUL.toString())
		private val comparisonOperators = setOf(Operators.LESS.toString(), Operators.GREATER.toString(), Operators.LESS_EQUAL.toString(), Operators.GREATER_EQUAL.toString(), Operators.EQUAL.toString(), Operators.NOT_EQUAL.toString())
	}
}
