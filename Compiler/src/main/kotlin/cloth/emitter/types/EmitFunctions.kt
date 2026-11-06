package cloth.emitter.types

import cloth.data.*
import cloth.diagnostics.BackendErrors
import cloth.diagnostics.InternalErrors
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.types.primitives.EmitString
import cloth.emitter.types.primitives.EmitStringArena
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ConstructorDeclaration
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.PrimaryConstructorDeclaration
import cloth.parser.expression.Expression
import cloth.semantic.*
import cloth.semantic.symbols.CallableSymbol
import cloth.semantic.symbols.ConstructorSymbol
import cloth.semantic.symbols.DestructorSymbol
import cloth.semantic.symbols.MethodSymbol
import cloth.token.Operators
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import java.util.*

internal data class ConstructorAllocation(val bytes: LLVMValueRef, val tag: LLVMValueRef)

/** All signatures are declared before any body, including recursive overloads. */
internal class EmitFunctions(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI, private val semantics: FunctionSemanticModel) {

	private val llvmTypes = LLVMTypes(context, abi.layout)
	fun omissionCount(function: CallableSymbol) = function.parameters.count { it.declaration.optional }
	private val results = EmitResults(context, abi.layout)
	private val functions = IdentityHashMap<CallableSymbol, LLVMValueRef>()
	private val functionTypes = IdentityHashMap<CallableSymbol, LLVMTypeRef>()
	internal val stringStorage = EmitStringArena(context, module, abi)
	internal val classes by lazy { EmitClassRuntime(context, module, abi) }
	internal val classStorage by lazy { EmitClassStorage(context, module, abi, classes, stringStorage) }
	internal val receivers by lazy { EmitReceiverAccess(context, module, abi, classStorage) }
	internal val hasClasses = semantics.declarations.classes.isNotEmpty()
	private val classTags = semantics.declarations.classes.values.mapIndexed { index, definition -> definition.owner to index }.toMap()
	private fun classTag(owner: CClass) = LLVMConstInt(classes.indexType, classTags.getValue(owner).toLong(), 0)
	private val classComparisons = mutableMapOf<Operators, LLVMValueRef>()
	private val classFormatter by lazy { emitClassFormatter() }
	private val classFormatterType by lazy {
		PointerPointer<LLVMTypeRef>(3L).use { parameters ->
			parameters.put(0, LLVMInt64TypeInContext(context))
			parameters.put(1, LLVMInt1TypeInContext(context))
			parameters.put(2, LLVMPointerTypeInContext(context, 0))
			LLVMFunctionType(abiType(ValueType.STRING), parameters, 3, 0)
		}
	}
	private val comparisonType by lazy {
		val handle = LLVMInt64TypeInContext(context)
		val flag = LLVMInt1TypeInContext(context)
		PointerPointer<LLVMTypeRef>(4L).use { parameters ->
			parameters.put(0, handle); parameters.put(1, flag); parameters.put(2, handle); parameters.put(3, flag)
			LLVMFunctionType(flag, parameters, 4, 0)
		}
	}
	fun reference(function: CallableSymbol): LLVMValueRef = functions.getValue(function)
	fun signature(function: CallableSymbol): LLVMTypeRef = functionTypes.getValue(function)

	/** Raw binary16 bits avoid requiring external half-precision ABI conversion helpers. */
	private fun abiType(type: ValueType): LLVMTypeRef = results.abiType(type)
	fun encode(builder: LLVMBuilderRef, value: LLVMValueRef, type: ValueType): LLVMValueRef = results.encode(builder, value, type)
	fun decode(builder: LLVMBuilderRef, value: LLVMValueRef, type: ValueType): LLVMValueRef = results.decode(builder, value, type)

	private class CallLayout(callable: CallableSymbol, type: FunctionType, hasClasses: Boolean) {

		val objectReceiver = (callable is MethodSymbol || callable is DestructorSymbol) && callable.receiver?.owner is CClass
		val enumReceiver = callable is MethodSymbol && callable.owner is CEnum
		val replayableStruct = hasClasses && callable is MethodSymbol && callable.owner is CStruct
		val explicitStart = when {
			replayableStruct                           -> 4
			callable is ConstructorSymbol             -> 2
			enumReceiver                               -> 1
			objectReceiver || callable is MethodSymbol -> 2
			else                                       -> 0
		}
		val resultArena = (explicitStart + type.parameters.size).takeIf { type.results.values.any { slot -> slot.indirect } }
		val flagStart = explicitStart + type.parameters.size + if (resultArena != null) 1 else 0
	}

	fun emitAll() {
		if (semantics.declarations.structs.isNotEmpty()) StructStorageLayout(context, module, semantics).validate()
		for ((index, function) in semantics.callableBodies.withIndex()) {
			val type = semantics.typeOf(function)
			// Indirect results copy into an invocation arena supplied by the caller.
			val layout = CallLayout(function, type, hasClasses)
			val parameterCount = layout.flagStart + omissionCount(function)
			val signature = PointerPointer<LLVMTypeRef>(parameterCount.toLong()).use { parameters ->
				val pointer = LLVMPointerTypeInContext(context, 0)
				if (layout.objectReceiver) {
					parameters.put(0, LLVMInt64TypeInContext(context)); parameters.put(1, LLVMInt1TypeInContext(context))
				} else if (layout.enumReceiver) {
					parameters.put(0, abiType(checkNotNull(function.receiver).owner))
				} else if (function is ConstructorSymbol) {
					parameters.put(0, classes.size); parameters.put(1, classes.indexType)
				} else repeat(layout.explicitStart) { parameters.put(it.toLong(), pointer) }
				type.parameters.forEachIndexed { position, parameter -> parameters.put((layout.explicitStart + position).toLong(), abiType(parameter)) }
				layout.resultArena?.let { parameters.put(it.toLong(), pointer) }
				repeat(omissionCount(function)) { parameters.put((layout.flagStart + it).toLong(), LLVMInt1TypeInContext(context)) }
				LLVMFunctionType(results.abiType(type.results), parameters, parameterCount, 0)
			}
			functionTypes[function] = signature
			val kind = when (function) {
				is MethodSymbol      -> "method"
				is ConstructorSymbol -> "constructor"
				is DestructorSymbol  -> "destructor"
				else                 -> "fn"
			}
			val value = LLVMAddFunction(module, "cloth.$kind.$index.${function.name.lexeme}", signature)
			LLVMSetLinkage(value, LLVMInternalLinkage)
			functions[function] = value
		}
		for (function in semantics.callableBodies) emitBody(function)
	}

	private fun emitBody(function: CallableSymbol) {
		val semantics = semantics.specializedBodies[function] ?: semantics
		val builder = LLVMCreateBuilderInContext(context)
		try {
			val value = reference(function)
			val signature = semantics.typeOf(function)
			val layout = CallLayout(function, signature, hasClasses)
			val entry = LLVMAppendBasicBlockInContext(context, value, "entry")
			LLVMPositionBuilderAtEnd(builder, entry)
			val frame = EmitFunctionFrame(context, builder, entry, signature.results, results.abiType(signature.results), stringStorage, layout.resultArena?.let { LLVMGetParam(value, it) })
			val bindings = EmitBindings(context, builder, entry, abi.layout, if (hasClasses) classStorage else null, if (hasClasses) receivers else null)
			val objectAccess = if (layout.objectReceiver) ObjectAccess(LLVMGetParam(value, 0), LLVMGetParam(value, 1)) else null
			if (objectAccess != null) {
				bindings.bindObject(checkNotNull(function.receiver), objectAccess)
				classes.check(builder, objectAccess)
			} else if (function is MethodSymbol && layout.enumReceiver) {
				bindings.declare(function.receiver, function.receiver.name.lexeme, function.owner, LLVMGetParam(value, 0))
			} else if (function is MethodSymbol) {
				val access = ReceiverAccess(LLVMGetParam(value, 0), LLVMGetParam(value, 1), if (layout.replayableStruct) LLVMGetParam(value, 2) else null, if (layout.replayableStruct) LLVMGetParam(value, 3) else null)
				bindings.bindReceiver(function.receiver, access)
				bindings.checkDestination(function.receiver)
			}
			val casts = EmitCast(context, builder, semantics.types, abi)
			val strings = EmitString(context, builder, abi)
			val expressions = EmitExpression(context, builder, bindings, semantics.types, casts, strings, EmitPower(context, builder, abi), abi, this, semantics, signature.results, frame)
			var flagIndex = layout.flagStart
			function.parameters.forEachIndexed { index, parameter ->
				val position = layout.explicitStart + index
				val type = signature.parameters[index]
				val fallback = parameter.declaration.defaultValue
				val argument = if (fallback == null) decode(builder, LLVMGetParam(value, position), type) else {
					val suppliedBlock = LLVMAppendBasicBlockInContext(context, value, "parameter_supplied")
					val defaultBlock = LLVMAppendBasicBlockInContext(context, value, "parameter_default")
					val merge = LLVMAppendBasicBlockInContext(context, value, "parameter_ready")
					LLVMBuildCondBr(builder, LLVMGetParam(value, flagIndex++), defaultBlock, suppliedBlock)
					LLVMPositionBuilderAtEnd(builder, suppliedBlock)
					// Omitted placeholders are never decoded, copied, or shape-checked.
					val supplied = decode(builder, LLVMGetParam(value, position), type)
					val suppliedEnd = LLVMGetInsertBlock(builder)
					LLVMBuildBr(builder, merge)
					LLVMPositionBuilderAtEnd(builder, defaultBlock)
					val fallbackValue = expressions.emit(fallback)
					val resolved = if (expressions.terminated()) null else {
						expressions.transferArrayValue(expressions.adaptValue(checkNotNull(fallbackValue), semantics.typeOf(fallback), type), type)
					}
					val defaultEnd = if (resolved == null) null else LLVMGetInsertBlock(builder).also { LLVMBuildBr(builder, merge) }
					LLVMPositionBuilderAtEnd(builder, merge)
					if (defaultEnd == null) supplied else {
						val phi = LLVMBuildPhi(builder, llvmTypes.value(type), "parameter_value")
						PointerPointer<LLVMValueRef>(2L).use { values ->
							PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
								values.put(0, supplied); values.put(1, resolved)
								blocks.put(0, suppliedEnd); blocks.put(1, defaultEnd)
								LLVMAddIncoming(phi, values, blocks, 2)
							}
						}
						phi
					}
				}
				bindings.declare(parameter, parameter.name.lexeme, type, argument)
			}
			objectAccess?.let { classes.check(builder, it) }
			if (layout.replayableStruct) bindings.checkDestination(checkNotNull(function.receiver))
			val constructed = if (function is ConstructorSymbol) {
				val allocation = ConstructorAllocation(LLVMGetParam(value, 0), LLVMGetParam(value, 1))
				initialize(function, allocation, builder, bindings, expressions)?.also { expressions.setConstructorResult(it) }
			} else null
			if (expressions.terminated()) {
				frame.finish()
				return
			}
			when (val body = function.declaration.body) {
				is BlockBody      -> {
					expressions.emitStatements(body.statements)
					if (!expressions.terminated()) {
						if (signature.results != ResultShape.Empty && constructed == null) throw InternalErrors.invariant("Validated non-void function fell through during emission")
						frame.emitReturn(constructed)
					}
				}

				is ExpressionBody -> {
					val result = if (signature.results == ResultShape.Empty) expressions.emitDiscarded(body.expression) else expressions.emit(body.expression)
					if (!expressions.terminated()) {
						frame.emitReturn(if (signature.results == ResultShape.Empty) null else results.encode(builder, expressions.adaptResults(checkNotNull(result), semantics.resultOf(body.expression), signature.results), signature.results))
					}
				}
			}
			frame.finish()
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}

	private fun initialize(constructor: ConstructorSymbol, allocation: ConstructorAllocation, builder: LLVMBuilderRef, bindings: EmitBindings, expressions: EmitExpression): LLVMValueRef? {
		val owner = constructor.owner
		return when (val declaration = constructor.declaration) {
			is PrimaryConstructorDeclaration -> {
				val base = declaration.owner.base
				// Allocate the concrete layout at the first source base constructor.
				val access = if (base != null && owner.base?.base != null) {
					val identity = expressions.emitDelegation(base, allocation) ?: return null
					ObjectAccess(identity, classes.permission(false))
				} else classes.create(builder, allocation.bytes, allocation.tag)
				bindings.bindObject(constructor.receiver, access)
				for ((index, input) in declaration.owner.parameters.withIndex()) if (input.fieldKeyword != null) {
					val field = checkNotNull(owner.field(input.parameter.name.lexeme))
					classStorage.replace(builder, access, field, bindings.read(constructor.parameters[index]), initializing = true)
				}
				for ((field, expression) in semantics.declarations.classes.getValue(owner.spelling).fieldInitializers) {
					val value = expressions.emit(expression)
					if (expressions.terminated()) return null
					val adapted = expressions.adaptValue(checkNotNull(value), semantics.typeOf(expression), field.type)
					classStorage.replace(builder, access, field, adapted, initializing = true)
				}
				classes.finishClass(builder, access, classTag(owner))
				access.identity
			}

			is ConstructorDeclaration        -> {
				val identity = expressions.emitDelegation(declaration.delegation, allocation) ?: return null
				bindings.bindObject(constructor.receiver, ObjectAccess(identity, classes.permission(false)))
				identity
			}

			else                             -> throw InternalErrors.invariant("Invalid constructor declaration")
		}
	}

	fun delete(plan: ClassDeletionPlan, identity: LLVMValueRef, builder: LLVMBuilderRef) {
		val access = classes.beginDelete(builder, identity)
		val candidates = classTags.keys.filter(plan.owner::accepts)
		if (candidates.size == 1) destroy(builder, access, candidates.single())
		else {
			val tag = classes.classTag(builder, access)
			val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
			val invalid = LLVMAppendBasicBlockInContext(context, function, "invalid_concrete_class")
			val done = LLVMAppendBasicBlockInContext(context, function, "destruction_complete")
			val dispatch = LLVMBuildSwitch(builder, tag, invalid, candidates.size)
			for (owner in candidates) {
				val branch = LLVMAppendBasicBlockInContext(context, function, "destroy.${owner.spelling}")
				LLVMAddCase(dispatch, classTag(owner), branch)
				LLVMPositionBuilderAtEnd(builder, branch)
				destroy(builder, access, owner)
				LLVMBuildBr(builder, done)
			}
			LLVMPositionBuilderAtEnd(builder, invalid)
			EmitMemory(context, builder, abi).guard(classes.permission(false), "Invalid concrete class during deletion")
			LLVMBuildUnreachable(builder)
			LLVMPositionBuilderAtEnd(builder, done)
		}
		classes.finishDelete(builder, identity)
	}

	private fun destroy(builder: LLVMBuilderRef, access: ObjectAccess, concrete: CClass) {
		for (owner in concrete.ancestry()) {
			semantics.declarations.classes[owner.spelling]?.destructor?.let { destructor ->
				PointerPointer<LLVMValueRef>(2L).use { arguments ->
					arguments.put(0, access.identity); arguments.put(1, access.duringDestruction)
					LLVMBuildCall2(builder, signature(destructor), reference(destructor), arguments, 2, "")
				}
			}
		}
		for (owner in concrete.ancestry()) classStorage.release(builder, access, owner)
	}

	fun isInstance(builder: LLVMBuilderRef, access: ObjectAccess, target: CClass): LLVMValueRef {
		val tag = classes.classTag(builder, access)
		return classTags.keys.filter(target::accepts).fold(classes.permission(false)) { result, owner ->
			val matches = LLVMBuildICmp(builder, LLVMIntEQ, tag, classTag(owner), "class_match")
			LLVMBuildOr(builder, result, matches, "instance_match")
		}
	}

	fun formatClass(builder: LLVMBuilderRef, access: ObjectAccess, arena: LLVMValueRef): LLVMValueRef =
		PointerPointer<LLVMValueRef>(3L).use { arguments ->
			arguments.put(0, access.identity); arguments.put(1, access.duringDestruction); arguments.put(2, arena)
			LLVMBuildCall2(builder, classFormatterType, classFormatter, arguments, 3, "class_text")
		}

	private fun emitClassFormatter(): LLVMValueRef {
		val function = LLVMAddFunction(module, "cloth.objects.format", classFormatterType)
		LLVMSetLinkage(function, LLVMInternalLinkage)
		val builder = LLVMCreateBuilderInContext(context)
		try {
			fun block(name: String) = LLVMAppendBasicBlockInContext(context, function, name)
			LLVMPositionBuilderAtEnd(builder, block("entry"))
			val access = ObjectAccess(LLVMGetParam(function, 0), LLVMGetParam(function, 1))
			val tag = classes.classTag(builder, access)
			val invalid = block("invalid_class")
			val dispatch = LLVMBuildSwitch(builder, tag, invalid, classTags.size)
			val strings = EmitString(context, builder, abi)
			for (owner in classTags.keys) {
				val branch = block("format.${owner.spelling}")
				LLVMAddCase(dispatch, classTag(owner), branch)
				LLVMPositionBuilderAtEnd(builder, branch)
				val formatter = semantics.declarations.classFormatter(owner)
				val result = if (formatter == null) strings.literal(owner.spelling) else PointerPointer<LLVMValueRef>(3L).use { arguments ->
					arguments.put(0, access.identity); arguments.put(1, access.duringDestruction); arguments.put(2, LLVMGetParam(function, 2))
					LLVMBuildCall2(builder, signature(formatter), reference(formatter), arguments, 3, "formatted_instance")
				}
				LLVMBuildRet(builder, result)
			}
			LLVMPositionBuilderAtEnd(builder, invalid)
			EmitRuntimeError(context, builder, abi).emit("Class object is still under construction")
			return function
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}

	fun compareClasses(builder: LLVMBuilderRef, left: ObjectAccess, right: ObjectAccess, operation: Operators): LLVMValueRef {
		val function = classComparisons.getOrPut(operation) { emitClassComparison(operation) }
		return PointerPointer<LLVMValueRef>(4L).use { arguments ->
			arguments.put(0, left.identity); arguments.put(1, left.duringDestruction)
			arguments.put(2, right.identity); arguments.put(3, right.duringDestruction)
			LLVMBuildCall2(builder, comparisonType, function, arguments, 4, "class_comparison")
		}
	}

	fun compareWithOverride(builder: LLVMBuilderRef, method: CallableSymbol, receiver: ObjectAccess, other: LLVMValueRef): LLVMValueRef =
		PointerPointer<LLVMValueRef>(3L).use { arguments ->
			arguments.put(0, receiver.identity); arguments.put(1, receiver.duringDestruction); arguments.put(2, other)
			LLVMBuildCall2(builder, signature(method), reference(method), arguments, 3, "comparison_override")
		}

	fun enumPayload(builder: LLVMBuilderRef, value: LLVMValueRef, field: EnumField, returnArena: () -> LLVMValueRef): LLVMValueRef =
		emitEnumReader(builder, value, semantics.declarations.symbolOf(field.reader), returnArena)

	fun formatEnum(builder: LLVMBuilderRef, value: LLVMValueRef, owner: CEnum, returnArena: () -> LLVMValueRef): LLVMValueRef =
		emitEnumReader(builder, value, semantics.declarations.enumFormatter(owner), returnArena)

	private fun emitEnumReader(builder: LLVMBuilderRef, value: LLVMValueRef, method: MethodSymbol, returnArena: () -> LLVMValueRef): LLVMValueRef {
		val result = semantics.typeOf(method).returns
		val count = if (result.indirect) 2 else 1
		return PointerPointer<LLVMValueRef>(count.toLong()).use { arguments ->
			arguments.put(0, value)
			if (result.indirect) arguments.put(1, returnArena())
			decode(builder, LLVMBuildCall2(builder, signature(method), reference(method), arguments, count, "enum_value"), result)
		}
	}

	fun compareValueWithOverride(builder: LLVMBuilderRef, operator: ResolvedOperator, left: LLVMValueRef, right: LLVMValueRef, arena: () -> LLVMValueRef): LLVMValueRef {
		val method = operator.call.callable as MethodSymbol
		val layout = CallLayout(method, operator.call.type, hasClasses)
		val receiver = if (operator.receiverIndex == 0) left else right
		val operand = if (operator.receiverIndex == 0) right else left
		val count = layout.explicitStart + 1
		return PointerPointer<LLVMValueRef>(count.toLong()).use { arguments ->
			if (layout.enumReceiver) arguments.put(0, receiver)
			else putStructReceiver(arguments, ReceiverAccess(receiver, arena()), layout)
			arguments.put(layout.explicitStart.toLong(), operand)
			LLVMBuildCall2(builder, signature(method), reference(method), arguments, count, "value_comparison")
		}
	}

	private fun putStructReceiver(arguments: PointerPointer<LLVMValueRef>, access: ReceiverAccess, layout: CallLayout) {
		arguments.put(0, access.address); arguments.put(1, access.arena)
		if (layout.replayableStruct) {
			arguments.put(2, access.resolver ?: receivers.absent); arguments.put(3, access.environment ?: receivers.absent)
		}
	}

	private fun emitClassComparison(operation: Operators): LLVMValueRef {
		val function = LLVMAddFunction(module, "cloth.objects.${operation.name.lowercase()}", comparisonType)
		LLVMSetLinkage(function, LLVMInternalLinkage)
		val builder = LLVMCreateBuilderInContext(context)
		try {
			fun block(name: String) = LLVMAppendBasicBlockInContext(context, function, name)
			LLVMPositionBuilderAtEnd(builder, block("entry"))
			val left = ObjectAccess(LLVMGetParam(function, 0), LLVMGetParam(function, 1))
			val right = ObjectAccess(LLVMGetParam(function, 2), LLVMGetParam(function, 3))
			val leftTag = classes.classTag(builder, left)
			val rightTag = classes.classTag(builder, right)
			val same = LLVMBuildICmp(builder, LLVMIntEQ, left.identity, right.identity, "same_instance")
			val unequal = operation == Operators.NOT_EQUAL
			val fallback = block("identity_default")
			val cases = classTags.keys.mapNotNull { owner -> semantics.declarations.equalityOverride(owner, operation)?.let { owner to it } }
			if (cases.isEmpty()) LLVMBuildBr(builder, fallback) else {
				val select = block("select_override")
				val matching = LLVMBuildICmp(builder, LLVMIntEQ, leftTag, rightTag, "same_class")
				LLVMBuildCondBr(builder, matching, select, fallback)
				LLVMPositionBuilderAtEnd(builder, select)
				val dispatch = LLVMBuildSwitch(builder, leftTag, fallback, cases.size)
				for ((owner, method) in cases) {
					val branch = block("compare.${owner.spelling}")
					LLVMAddCase(dispatch, classTag(owner), branch)
					LLVMPositionBuilderAtEnd(builder, branch)
					val equalityBody = method.name.lexeme == Operators.EQUAL.toString()
					if (equalityBody) {
						val distinct = block("compare_distinct")
						LLVMBuildCondBr(builder, same, fallback, distinct)
						LLVMPositionBuilderAtEnd(builder, distinct)
					}
					val result = compareWithOverride(builder, method, left, right.identity)
					LLVMBuildRet(builder, if (unequal && equalityBody) LLVMBuildNot(builder, result, "negated_equality") else result)
				}
			}
			LLVMPositionBuilderAtEnd(builder, fallback)
			LLVMBuildRet(builder, if (unequal) LLVMBuildNot(builder, same, "different_instances") else same)
			return function
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}

	fun emitCall(plan: ResolvedCall.Source, supplied: List<Expression>, expressions: EmitExpression, builder: LLVMBuilderRef, allocation: ConstructorAllocation? = null, operatorReceiver: Int? = null): LLVMValueRef? {
		val selected = plan.callable
		val type = plan.type
		val layout = CallLayout(selected, type, hasClasses)
		val argumentCount = layout.flagStart + omissionCount(selected)
		val result = PointerPointer<LLVMValueRef>(argumentCount.toLong()).use { arguments ->
			fun putReceiver(receiver: MethodReceiver) {
				if (receiver is MethodReceiver.Instance) {
					val access = expressions.emitObjectAccess(receiver.receiver)
					classes.check(builder, access)
					arguments.put(0, access.identity); arguments.put(1, access.duringDestruction)
				} else if (layout.enumReceiver) {
					arguments.put(0, expressions.emitValue((receiver as MethodReceiver.Temporary).expression))
				} else {
					val access = expressions.emitReceiver(receiver, (selected as MethodSymbol).owner as CStruct)
					putStructReceiver(arguments, access, layout)
				}
			}
			fun putArgument(expression: Expression, destination: Int) {
				val parameter = type.parameters[destination]
				val adapted = expressions.adaptValue(expressions.emitValue(expression), expressions.typeOf(expression), parameter)
				val snapshot = if (operatorReceiver == null) expressions.transferArrayValue(adapted, parameter) else expressions.snapshotValue(adapted, parameter)
				arguments.put((layout.explicitStart + destination).toLong(), encode(builder, snapshot, parameter))
			}
			plan.receiver?.let(::putReceiver)
			// Operators interleave the receiver with the argument in source order.
			supplied.forEachIndexed { index, expression ->
				if (index == operatorReceiver) {
					val receiver = if ((selected as MethodSymbol).owner is CClass) MethodReceiver.Instance(ObjectReceiver.Explicit(expression))
					else MethodReceiver.Temporary(expression)
					putReceiver(receiver)
				} else {
					val argument = if (operatorReceiver != null && index > operatorReceiver) index - 1 else index
					putArgument(expression, plan.binding.parameterForArgument[argument])
				}
			}
			if (selected is ConstructorSymbol) {
				val concrete = allocation ?: ConstructorAllocation(classes.instanceBytes(builder, selected.owner), classTag(selected.owner))
				arguments.put(0, concrete.bytes); arguments.put(1, concrete.tag)
			}
			for (index in plan.binding.omittedParameters) arguments.put((layout.explicitStart + index).toLong(), LLVMConstNull(abiType(type.parameters[index])))
			layout.resultArena?.let { arguments.put(it.toLong(), expressions.stringReturnArena()) }
			var flag = layout.flagStart
			selected.parameters.forEachIndexed { index, parameter ->
				if (parameter.declaration.optional) arguments.put((flag++).toLong(), LLVMConstInt(LLVMInt1TypeInContext(context), if (index in plan.binding.omittedParameters) 1 else 0, 0))
			}
			LLVMBuildCall2(builder, signature(selected), reference(selected), arguments, argumentCount, if (type.results == ResultShape.Empty) "" else "call_result")
		}
		return if (type.results == ResultShape.Empty) null else results.decode(builder, result, type.results)
	}

	companion object {

		fun validate(semantics: FunctionSemanticModel) {
			fun supported(type: ValueType): Boolean = when (type) {
				is CArray    -> type.element != ValueType.VOID && supported(type.element)
				is CNullable -> supported(type.payload)
				is CStruct   -> true // Field types and layouts are validated at their declarations.
				is CClass    -> true
				is CEnum     -> true
				else         -> type.numeric || type in setOf(ValueType.VOID, ValueType.BOOL, ValueType.CHAR, ValueType.STRING, ValueType.TYPEINFO)
			}
			for (function in semantics.callableBodies) {
				val type = semantics.typeOf(function)
				type.results.values.forEachIndexed { index, result ->
					if (!supported(result)) throw BackendErrors.functionType(result, function.declaration.returnTypes[index].span)
				}
				function.parameters.forEachIndexed { index, parameter ->
					if (!supported(type.parameters[index])) throw BackendErrors.functionType(type.parameters[index], parameter.declaration.type.span)
				}
			}
		}
	}
}
