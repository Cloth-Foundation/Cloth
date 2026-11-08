package cloth.emitter.callables

import cloth.builtin.BuiltinStorageContract
import cloth.data.*
import cloth.diagnostics.BackendErrors
import cloth.diagnostics.InternalErrors
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.storage.*
import cloth.emitter.text.EmitString
import cloth.emitter.types.EmitCast
import cloth.emitter.types.EmitExpression
import cloth.emitter.types.EmitPower
import cloth.emitter.types.EmitRuntimeError
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ConstructorDeclaration
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.PrimaryConstructorDeclaration
import cloth.parser.expression.Expression
import cloth.semantic.ClassDeletionPlan
import cloth.semantic.MethodReceiver
import cloth.semantic.ObjectReceiver
import cloth.semantic.ResultShape
import cloth.semantic.symbols.CallableSymbol
import cloth.semantic.symbols.ConstructorSymbol
import cloth.semantic.symbols.DestructorSymbol
import cloth.semantic.symbols.MethodSymbol
import cloth.semantic.symbols.ReceiverFieldSymbol
import cloth.semantic.typing.FunctionSemanticModel
import cloth.semantic.typing.ResolvedCall
import cloth.semantic.typing.ResolvedOperator
import cloth.token.Operators
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import java.util.*

/** All signatures are declared before any body, including recursive overloads. */
internal class EmitFunctions(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI, private val semantics: FunctionSemanticModel) {

	private val llvmTypes = LLVMTypes(context, abi.layout)
	private val results = EmitResults(context, abi.layout)
	private val functions = IdentityHashMap<CallableSymbol, EmittedCallable>()
	internal val stringStorage = EmitStringArena(context, module, abi)
	internal val classes by lazy { EmitClassRuntime(context, module, abi) }
	internal val classStorage by lazy { EmitClassStorage(context, module, abi, classes, stringStorage) }
	internal val receivers by lazy { EmitReceiverAccess(context, module, abi, classStorage) }
	internal val hasClasses = semantics.declarations.classDefinitions.isNotEmpty()
	private val classTags = semantics.declarations.classDefinitions.values.mapIndexed { index, definition -> definition.owner to index }.toMap()
	private fun classTag(owner: CClass) = LLVMConstInt(classes.indexType, classTags.getValue(owner).toLong(), 0)
	private val classComparisons = mutableMapOf<Operators, LLVMValueRef>()
	private val classFormatter by lazy { emitClassFormatter() }
	private val classFormatterType by lazy {
		PointerPointer<LLVMTypeRef>(3L).use { parameters ->
			parameters.put(0, LLVMInt64TypeInContext(context))
			parameters.put(1, LLVMInt1TypeInContext(context))
			parameters.put(2, LLVMPointerTypeInContext(context, 0))
			LLVMFunctionType(results.abiType(ValueType.STRING), parameters, 3, 0)
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

	fun callable(function: CallableSymbol): EmittedCallable = functions.getValue(function)
	fun reference(function: CallableSymbol): LLVMValueRef = callable(function).reference
	fun signature(function: CallableSymbol): LLVMTypeRef = callable(function).signature

	fun emitAll() {
		if (semantics.declarations.allStructs.isNotEmpty()) StructStorageLayout(context, module, semantics).validate()
		val bodies = semantics.callableBodies
		for ((index, function) in bodies.withIndex()) {
			val kind = when (function) {
				is MethodSymbol      -> "method"
				is ConstructorSymbol -> "constructor"
				is DestructorSymbol  -> "destructor"
				else                 -> "fn"
			}
			val name = "cloth.$kind.$index.${function.name.lexeme}"
			functions[function] = EmittedCallable(context, module, name, function, semantics.typeOf(function), hasClasses, results) { classes }
		}
		for (function in bodies) emitBody(function)
	}

	private fun emitBody(function: CallableSymbol) {
		val semantics = semantics.bodyOf(function)
		val builder = LLVMCreateBuilderInContext(context)
		try {
			val callable = callable(function)
			val value = callable.reference
			val signature = callable.type
			val entry = LLVMAppendBasicBlockInContext(context, value, "entry")
			LLVMPositionBuilderAtEnd(builder, entry)
			val frame = EmitFunctionFrame(context, builder, entry, signature.results, LLVMGetReturnType(callable.signature), stringStorage, callable.callerArena)
			val rebindings = (semantics.declarations.assignments.values + semantics.declarations.multiAssignments.values.flatMap { it.filterNotNull() })
				.filterNot { it is ReceiverFieldSymbol }
				.filter { ((semantics.bindings[it] as? CNullable)?.payload ?: semantics.bindings[it]) == ValueType.STRING }.toSet()
			val bindings = EmitBindings(context, builder, entry, abi, if (hasClasses) classStorage else null, if (hasClasses) receivers else null, frame, rebindings)
			val objectAccess = callable.objectAccess()
			if (objectAccess != null) {
				bindings.bindObject(checkNotNull(function.receiver), objectAccess)
				classes.check(builder, objectAccess)
			} else if (function is MethodSymbol && callable.enumReceiver) {
				bindings.declare(function.receiver, function.owner, callable.enumValue())
			} else if (function is MethodSymbol) {
				bindings.bindReceiver(function.receiver, callable.structAccess())
				bindings.checkDestination(function.receiver)
			}
			val casts = EmitCast(context, builder, semantics.types, abi)
			val strings = EmitString(context, builder, abi, arena = { frame.stringReturnArena() })
			val expressions = EmitExpression(context, builder, bindings, casts, strings, EmitPower(context, builder, abi), abi, this, semantics, signature.results, frame)
			function.parameters.forEachIndexed { index, parameter ->
				val type = signature.parameters[index]
				val fallback = parameter.declaration.defaultValue
				val argument = if (fallback == null) callable.parameter(builder, index) else {
					val suppliedBlock = LLVMAppendBasicBlockInContext(context, value, "parameter_supplied")
					val defaultBlock = LLVMAppendBasicBlockInContext(context, value, "parameter_default")
					val merge = LLVMAppendBasicBlockInContext(context, value, "parameter_ready")
					LLVMBuildCondBr(builder, callable.omissionFlag(index), defaultBlock, suppliedBlock)
					LLVMPositionBuilderAtEnd(builder, suppliedBlock)
					// Omitted placeholders are never decoded, copied, or shape-checked.
					val supplied = callable.parameter(builder, index)
					val suppliedEnd = LLVMGetInsertBlock(builder)
					LLVMBuildBr(builder, merge)
					LLVMPositionBuilderAtEnd(builder, defaultBlock)
					val fallbackValue = expressions.emit(fallback)
					val resolved = if (expressions.terminated()) null else {
						expressions.valueTransfer.transfer(expressions.adaptValue(checkNotNull(fallbackValue), semantics.typeOf(fallback), type), type)
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
				bindings.declare(parameter, type, argument)
			}
			objectAccess?.let { classes.check(builder, it) }
			if (callable.replayableStruct) bindings.checkDestination(checkNotNull(function.receiver))
			val constructed = if (function is ConstructorSymbol) {
				initialize(function, callable.allocation(), builder, bindings, expressions)?.also { expressions.setConstructorResult(it) }
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
				for ((field, expression) in semantics.declarations.classDefinitions.getValue(owner).fieldInitializers) {
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
			semantics.declarations.classDefinitions[owner]?.destructor?.let { destructor ->
				callable(destructor).call(builder) { setReceiver(access) }
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

	fun formatClass(builder: LLVMBuilderRef, access: ObjectAccess, arena: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(3L).use { arguments ->
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
				val formatter = semantics.declarations.formatterOverride(owner)
				val result = if (formatter == null) strings.literal(owner.spelling) else checkNotNull(
					callable(formatter).call(builder, returnArena = { LLVMGetParam(function, 2) }, resultName = "formatted_instance") {
						setReceiver(access)
					},
				)
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

	fun compareWithOverride(builder: LLVMBuilderRef, method: CallableSymbol, access: ObjectAccess, other: LLVMValueRef): LLVMValueRef = checkNotNull(callable(method).call(builder, resultName = "comparison_override") {
		setReceiver(access)
		argument(0, other)
	})

	fun enumPayload(builder: LLVMBuilderRef, value: LLVMValueRef, field: EnumField, returnArena: () -> LLVMValueRef): LLVMValueRef = emitEnumReader(builder, value, semantics.declarations.symbolOf(field.reader), returnArena)

	fun enumName(builder: LLVMBuilderRef, value: LLVMValueRef, owner: CEnum, returnArena: () -> LLVMValueRef): LLVMValueRef = emitEnumReader(builder, value, semantics.declarations.enumNameReader(owner), returnArena)

	fun formatEnum(builder: LLVMBuilderRef, value: LLVMValueRef, owner: CEnum, returnArena: () -> LLVMValueRef): LLVMValueRef = emitEnumReader(builder, value, semantics.declarations.enumFormatter(owner), returnArena)

	private fun emitEnumReader(builder: LLVMBuilderRef, value: LLVMValueRef, method: MethodSymbol, returnArena: () -> LLVMValueRef): LLVMValueRef = checkNotNull(callable(method).call(builder, returnArena = returnArena, resultName = "enum_value") { setReceiver(value) })

	fun compareValueWithOverride(builder: LLVMBuilderRef, operator: ResolvedOperator, left: LLVMValueRef, right: LLVMValueRef, arena: () -> LLVMValueRef): LLVMValueRef {
		val target = callable(operator.call.callable)
		val self = if (operator.receiverIndex == 0) left else right
		val other = if (operator.receiverIndex == 0) right else left
		return checkNotNull(target.call(builder, resultName = "value_comparison") {
			if (target.enumReceiver) setReceiver(self) else setReceiver(ReceiverAccess(self, arena()))
			argument(0, other)
		})
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
		val target = callable(selected)
		if (target.type != type) throw InternalErrors.invariant("Resolved call type differs from emitted signature")
		return target.call(builder, omittedParameters = plan.binding.omittedParameters, returnArena = expressions::stringReturnArena) {
			fun putReceiver(receiver: MethodReceiver) {
				if (receiver is MethodReceiver.Instance) {
					val access = expressions.emitObjectAccess(receiver.receiver)
					classes.check(builder, access)
					setReceiver(access)
				} else if (target.enumReceiver) {
					setReceiver(expressions.emitValue((receiver as MethodReceiver.Temporary).expression))
				} else {
					setReceiver(expressions.emitReceiver(receiver, (selected as MethodSymbol).owner as CStruct))
				}
			}

			fun putArgument(expression: Expression, destination: Int) {
				val parameter = type.parameters[destination]
				val adapted = expressions.adaptValue(expressions.emitValue(expression), expressions.typeOf(expression), parameter)
				val snapshot = if (operatorReceiver == null) expressions.valueTransfer.transfer(adapted, parameter) else expressions.valueTransfer.snapshot(adapted, parameter)
				argument(destination, snapshot)
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
				setAllocation(concrete)
			}
		}
	}

	companion object {

		fun validate(semantics: FunctionSemanticModel) {
			for (body in semantics.allBodyModels()) {
				val calls = body.types.memberCalls.entries.sortedWith(compareBy({ it.key.span.source?.name.orEmpty() }, { it.key.span.startLine }, { it.key.span.startColumn }))
				for ((call, bound) in calls) if (bound.overload.storage == BuiltinStorageContract.MUTATING_RECEIVER) throw BackendErrors.arrayMutation(call.name.span)
			}
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
