package cloth.semantic

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

	protected abstract fun nameType(expression: NameExpression): ValueType
	protected abstract fun statement(statement: Statement)
	protected abstract fun withScope(action: () -> Unit)
	protected open val supportsNullable: Boolean = false
	protected open fun callType(expression: CallExpression): ValueType = throw SemanticErrors.unknownName(expression.name)
	protected open fun callResults(expression: CallExpression): ResultShape = ResultShape.of(callType(expression))
	protected open fun completion(expression: Expression): ControlFlow = ControlFlow.CONTINUE
	protected open fun completion(statements: List<Statement>): ControlFlow = ControlFlow.CONTINUE
	protected fun reject(diagnostic: () -> SemanticException): Nothing {
		if (probe) throw CandidateMismatch()
		throw diagnostic()
	}

	protected fun resolveType(type: TypeReference) = model.typeResolver.resolve(type, declarations).also {
		if (it.containsNullability && !supportsNullable) throw SemanticErrors.nullablePending(type.span)
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

			is ProviderAccessExpression -> (model.providers[expression]?.result as? ProviderResult.IntegerConstant)?.value ?: return null
			else                        -> return null
		}
		return type.wrap(value)
	}

	private fun operandContext(expression: Expression, floating: Boolean): ValueType? = when (expression) {
		is NameExpression           -> nameType(expression).takeIf { if (floating) it.floating else it.integer }
		is CastExpression           -> resolveType(expression.target).takeIf { if (floating) it.floating else it.integer }
		is CallExpression           -> callType(expression).takeIf { if (floating) it.floating else it.integer }
		is IndexExpression          -> typeOf(expression).takeIf { if (floating) it.floating else it.integer }
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
					if (index == body.lastIndex && item is ExpressionStatement && completion(body).canContinue) {
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

		else                    -> typeOf(expression)
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
				if (annotation?.containsNullability == true) throw SemanticErrors.nullablePending(checkNotNull(statement.binding.type).span)
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
			withScope { statement.body.statements.forEach { statement(it) } }
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

	fun typeOf(expression: Expression, expected: ValueType? = null, requireValue: Boolean = true, contextualFloat: Boolean = false, allowVoidResult: Boolean = false): ValueType {
		val result = resultOf(expression, expected?.let { ResultShape.of(it) }, requireValue, contextualFloat, allowVoidResult)
		// No parameter context can make multiple intrinsic results into one argument.
		return result.scalarOrNull() ?: throw SemanticErrors.scalarResults(result, expression.span)
	}

	fun resultOf(expression: Expression, expected: ResultShape? = null, requireValue: Boolean = true, contextualFloat: Boolean = false, allowVoidResult: Boolean = false, receptionContext: ReceptionContext? = null): ResultShape {
		if (!supportsNullable && (expression is NullLiteral || expression is ElvisExpression)) throw SemanticErrors.nullablePending(expression.span)
		model.metadata.remove(expression)
		val result = when (expression) {
			is CallExpression                                                                     -> callResults(expression)
			is ResultListExpression                                                               -> {
				val values = expression.values.mapIndexed { index, value ->
					typeOf(value, receptionContext?.positional?.getOrNull(index) ?: expected?.values?.getOrNull(index), contextualFloat = contextualFloat)
				}
				if (values.any { it == ValueType.NEVER }) ResultShape.Never else ResultShape.of(values)
			}

			is IfExpression                                                                       -> conditionalResults(expression, expected, requireValue, contextualFloat, allowVoidResult, receptionContext)
			is CastExpression if declarations && resolveType(expression.target) == ValueType.VOID -> {
				resultOf(expression.operand, requireValue = false)
				ResultShape.Empty
			}

			else                                                                                  -> ResultShape.of(scalarTypeOf(expression, receptionContext?.broadcast ?: expected?.scalarOrNull(), requireValue, contextualFloat, allowVoidResult))
		}
		val completed = if (completion(expression).canContinue) result else ResultShape.Never
		if (requireValue && !allowVoidResult && completed == ResultShape.Empty) reject { SemanticErrors.voidValue(expression.span) }
		model.recordResult(expression, completed)
		return completed
	}

	private fun conditionalResults(expression: IfExpression, expected: ResultShape?, requireValue: Boolean, contextualFloat: Boolean, allowVoidResult: Boolean, receptionContext: ReceptionContext?): ResultShape {
		if (requireValue && completion(expression).canContinue && expression.otherwise == null) reject { SemanticErrors.missingElse(expression.span) }
		for (branch in expression.branches) {
			val condition = typeOf(branch.condition)
			if (condition != ValueType.BOOL && condition != ValueType.NEVER) reject { SemanticErrors.condition(branch.condition) }
		}
		var result: ResultShape? = null
		var firstSpan: TokenSpan? = null
		val bodies = expression.branches.map { it.statements to it.span } + listOfNotNull(expression.otherwise?.let { it to checkNotNull(expression.otherwiseSpan) })
		for ((statements, bodySpan) in bodies) {
			val needsValue = requireValue && completion(statements).canContinue
			if (needsValue && statements.lastOrNull() !is ExpressionStatement) reject { SemanticErrors.branchValue(bodySpan) }
			withScope {
				for ((index, item) in statements.withIndex()) {
					if (needsValue && index == statements.lastIndex) {
						val next = resultOf((item as ExpressionStatement).expression, expected, contextualFloat = contextualFloat, allowVoidResult = allowVoidResult, receptionContext = receptionContext)
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
		val actual = resultOf(group.initializer, receptionContext = context)
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
		val actual = resultOf(statement.value, receptionContext = context)
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
			val type = if (definition && required is CArray && value is CArray) required.inferArraySizes(value) else required ?: value
			ReceptionSlot(target, type, source)
		}
		return ReceptionPlan(producer, actual, kind, slots).also {
			model.receptions[statement] = it
			model.recordResult(statement, it.statementResult)
		}
	}

	private fun scalarTypeOf(expression: Expression, expected: ValueType?, requireValue: Boolean, contextualFloat: Boolean, allowVoidResult: Boolean): ValueType {
		model.metadata.remove(expression)
		val type = when (expression) {
			is NullLiteral              -> expected as? CNullable ?: ValueType.Null
			is ElvisExpression          -> elvisType(expression, expected, contextualFloat)
			is TypeReceiverExpression   -> throw SemanticErrors.typeReceiver(expression.span)
			is StateCallExpression      -> throw SemanticErrors.stateCall(expression.name)
			is ProviderAccessExpression -> {
				val receiver = expression.receiver
				val receiverType = if (receiver is TypeReceiverExpression) {
					model.typeResolver.resolve(receiver.annotation, declarations = true).also {
						if (it.containsNullability && !supportsNullable) throw SemanticErrors.nullablePending(receiver.span)
						model.expressions[receiver] = it
					}
				} else typeOf(receiver)
				if (receiverType == ValueType.NEVER) ValueType.NEVER else {
					// Availability is independent of the enclosing overload's return/argument context.
					val result = receiverType.provider(expression.name.lexeme, model.target) ?: run {
						if (receiverType is CNullable && receiver is TypeReceiverExpression) throw SemanticErrors.nullablePending(expression.name.span, "Nullable type providers other than '${Operators.COLON_COLON}type' are not implemented yet")
						if (receiverType is CNullable && receiverType.payload.provider(expression.name.lexeme, model.target) != null) throw SemanticErrors.nullablePayload(receiverType, receiver.span)
						if (receiverType == ValueType.Null) throw SemanticErrors.nullableContext(receiver.span)
						throw SemanticErrors.provider(receiverType, expression.name)
					}
					val access = ProviderAccess(receiver, result, expression.name.span)
					if (access.receiverForm !in result.receiverForms) throw SemanticErrors.providerReceiver(receiverType, expression.name)
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
				val context = expected as? CArray
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
				if (element == null && !completion(expression).canContinue) ValueType.NEVER
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
				val type = if (contextualFloat) (if (expected is CNullable) expected.payload else expected) as? CFloat ?: ValueType.FLOAT else ValueType.FLOAT
				if (!type.acceptsLiteral(expression.value.toBigDecimal())) reject { SemanticErrors.floating(expression.value, type, expression.span) }
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
				else typeOf(expression.operand, if (expression.operator.lexeme == Operators.NOT.toString()) null else expected, contextualFloat = contextualFloat)
				if (operand is CNullable || operand == ValueType.Null) throw SemanticErrors.nullablePayload(operand, expression.operand.span)
				if (expression.operator.lexeme == Operators.NOT.toString()) {
					if (operand != ValueType.BOOL && operand != ValueType.NEVER) reject { SemanticErrors.unary(expression.operator, expression.operand, operand, boolean = true) }
				} else if (!operand.numeric && operand != ValueType.NEVER) reject { SemanticErrors.unary(expression.operator, expression.operand, operand, boolean = false) }
				operand
			}

			is BinaryExpression         -> {
				if (expression.operator.lexeme in setOf(Operators.EQUAL.toString(), Operators.NOT_EQUAL.toString())) {
					val nullable = nullableEquality(expression, contextualFloat)
					if (nullable != null) return nullable.also { model.expressions[expression] = it }
				}
				val numeric = expression.operator.lexeme in arithmeticOperators || expression.operator.lexeme in comparisonOperators
				val intContext = if (numeric) operandContext(expression.left, false) ?: operandContext(expression.right, false) ?: expected?.takeIf { it.integer && expression.operator.lexeme in arithmeticOperators } else null
				val floatContext = if (numeric && contextualFloat) operandContext(expression.left, true) ?: operandContext(expression.right, true) ?: expected?.takeIf { it.floating && expression.operator.lexeme in arithmeticOperators } else null
				val left = typeOf(expression.left, intContext ?: floatContext, contextualFloat = contextualFloat)
				val right = typeOf(expression.right, intContext ?: floatContext, contextualFloat = contextualFloat)
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
						if (left is CArray || right is CArray || (left != ValueType.NEVER && right != ValueType.NEVER && left != right && !(left.numeric && right.numeric))) reject { SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right, comparison = true) }
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
		val completedType = if (completion(expression).canContinue) type else ValueType.NEVER
		if (requireValue && !allowVoidResult && completedType == ValueType.VOID) reject { SemanticErrors.voidValue(expression.span) }
		model.expressions[expression] = completedType
		return completedType
	}

	private fun elvisType(expression: ElvisExpression, expected: ValueType?, contextualFloat: Boolean): ValueType {
		val context = (expected as? CNullable)?.payload ?: expected?.takeUnless { it == ValueType.VOID || it == ValueType.NEVER || it == ValueType.Null }
		if (expression.left is NullLiteral && context == null) {
			val fallback = typeOf(expression.fallback, contextualFloat = contextualFloat)
			val payload = (fallback as? CNullable)?.payload ?: fallback
			if (payload in setOf(ValueType.Null, ValueType.VOID, ValueType.NEVER)) throw SemanticErrors.nullableContext(expression.span)
			typeOf(expression.left, CNullable(payload))
			return fallback
		}
		val left = typeOf(expression.left, if (expression.left is NullLiteral) context?.let(::CNullable) else expected as? CNullable, contextualFloat = contextualFloat)
		if (left == ValueType.NEVER) {
			typeOf(expression.fallback, expected, contextualFloat = contextualFloat)
			return ValueType.NEVER
		}
		if (left !is CNullable) throw SemanticErrors.elvis("The left operand of '${Operators.ELVIS}' requires a nullable source type", expression.left.span)
		val fallback = typeOf(expression.fallback, if (expected is CNullable) left else left.payload, contextualFloat = contextualFloat)
		if (fallback == ValueType.NEVER) return left.payload
		if (!left.accepts(fallback)) throw SemanticErrors.elvis("Fallback requires '${left.payload.spelling}' or '${left.spelling}', got '${fallback.spelling}'", expression.fallback.span)
		return if (fallback is CNullable || fallback == ValueType.Null) left else left.payload
	}

	/** Only inspect fixed source contracts; literals keep the peer payload's context. */
	private fun equalityHint(value: Expression): ValueType? = when (value) {
		is NameExpression                                                   -> nameType(value)
		is CallExpression                                                   -> callType(value)
		is CastExpression                                                   -> resolveType(value.target)
		is IndexExpression, is ProviderAccessExpression, is ElvisExpression -> typeOf(value)
		else                                                                -> null
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
		if (!wrapper.accepts(left) || !wrapper.accepts(right) || payload is CArray) reject { SemanticErrors.binary(expression.operator, expression.left, expression.right, left, right, comparison = true) }
		model.nullableComparisons[expression] = payload
		return if (completion(expression).canContinue) ValueType.BOOL else ValueType.NEVER
	}

	companion object {

		private val arithmeticOperators = setOf(Operators.PLUS.toString(), Operators.MINUS.toString(), Operators.MULTIPLY.toString(), Operators.DIVIDE.toString(), Operators.MODULO.toString(), Operators.MUL_MUL.toString())
		private val comparisonOperators = setOf(Operators.LESS.toString(), Operators.GREATER.toString(), Operators.LESS_EQUAL.toString(), Operators.GREATER_EQUAL.toString(), Operators.EQUAL.toString(), Operators.NOT_EQUAL.toString())
	}
}
