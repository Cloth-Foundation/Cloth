package cloth.emitter.callables

import cloth.builtin.BuiltinStorageContract
import cloth.data.*
import cloth.diagnostics.BackendErrors
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.InternalErrors
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.runtime.EmitLibraryIntrinsics
import cloth.emitter.storage.*
import cloth.emitter.text.EmitString
import cloth.emitter.text.EmitTypeInfo
import cloth.emitter.types.EmitCast
import cloth.emitter.types.EmitExpression
import cloth.emitter.types.EmitPower
import cloth.emitter.types.EmitRuntimeError
import cloth.parser.declaration.*
import cloth.parser.expression.Expression
import cloth.parser.expression.IndexExpression
import cloth.semantic.*
import cloth.semantic.declarations.StateDefinition
import cloth.semantic.symbols.*
import cloth.semantic.typing.*
import cloth.token.Operators
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import java.util.*

internal val FunctionSemanticModel.nativeBodies: List<CallableContractSymbol>
	get() = callableBodies + declarations.interfaceContracts.filter { it.declaration.fallback != null } + declarations.states.callables.filter { it is MethodSymbol || it is StateActivationSymbol }

/** All signatures are declared before any body, including recursive overloads. */
internal class EmitFunctions(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI, private val semantics: FunctionSemanticModel) {

	private val llvmTypes = LLVMTypes(context, abi.layout)
	private val results = EmitResults(context, abi.layout)
	private val functions = IdentityHashMap<CallableContractSymbol, EmittedCallable>()
	internal val stringStorage = EmitStringArena(context, module, abi)
	internal val metadataTypes = EmitTypeInfo.Types()
	private val stateDefinitions = semantics.declarations.states.definitions
	private val tracksStates = stateDefinitions.isNotEmpty() || semantics.declarations.states.callables.any { it is StateMethodContractSymbol }
	private val tracksIndexValidation = semantics.declarations.methods.any { OperatorRules.isIndexValidator(it.declaration) }
	private val tracksOwnership = semantics.intrinsics.values.any { it.usesOwnership }
	internal val classes by lazy { EmitClassRuntime(context, module, abi, tracksStates, tracksIndexValidation, tracksOwnership) }
	private val intrinsics by lazy { EmitLibraryIntrinsics(context, module, abi, semantics.intrinsics, stringStorage, results, this) }
	internal val states by lazy { EmitStateRuntime(context, module, abi, classes, stringStorage, stateDefinitions) }
	private val initialStates = mutableMapOf<StateDefinition, LLVMValueRef>()
	private val initialStateType by lazy {
		PointerPointer<LLVMTypeRef>(1L).use { parameter ->
			parameter.put(0, LLVMInt64TypeInContext(context))
			LLVMFunctionType(LLVMVoidTypeInContext(context), parameter, 1, 0)
		}
	}
	internal val classStorage by lazy { EmitClassStorage(context, module, abi, classes, stringStorage) }
	internal val receivers by lazy { EmitReceiverAccess(context, module, abi, classStorage) }
	internal val hasClasses = semantics.declarations.classDefinitions.isNotEmpty()
	private val hasVirtualMethods = semantics.declarations.methods.any { it.declaration.virtualPrefix != null }
	private val hasArrayMutations = semantics.allBodyModels().any { it.types.arrayMutations.isNotEmpty() }
	private val classTags = semantics.declarations.classDefinitions.values.mapIndexed { index, definition -> definition.owner to index }.toMap()
	private fun classTag(owner: CClass) = LLVMConstInt(classes.indexType, classTags.getValue(owner).toLong(), 0)
	private val interfaceDispatch by lazy {
		EmitInterfaceDispatch(context, module, semantics.declarations, results, this)
	}
	private val virtualEntries = IdentityHashMap<MethodSymbol, EmittedCallable>()
	private val guaranteeEntries = IdentityHashMap<ConstructorGuaranteeSymbol, EmittedCallable>()
	private val stateDispatch by lazy {
		EmitStateDispatch(context, module, abi, semantics.declarations.states, results, this)
	}
	private val dispatchBodies = ArrayDeque<() -> Unit>()
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

	fun callable(function: CallableContractSymbol): EmittedCallable = functions.getValue(function)
	fun reference(function: CallableSymbol): LLVMValueRef = callable(function).reference
	fun signature(function: CallableSymbol): LLVMTypeRef = callable(function).signature
	fun deferDispatch(body: () -> Unit) {
		dispatchBodies.addLast(body)
	}

	fun emitAll() {
		if (semantics.declarations.allStructs.isNotEmpty()) StructStorageLayout(context, module, semantics).validate()
		val bodies = semantics.nativeBodies
		for ((index, function) in bodies.withIndex()) {
			val stateBody = semantics.declarations.states.stateOf(function) != null
			val kind = when (function) {
				is InterfaceMethodSymbol -> "interface.fallback"
				is MethodSymbol          -> if (stateBody) "state.method" else "method"
				is ConstructorSymbol     -> "constructor"
				is DestructorSymbol      -> "destructor"
				is StateActivationSymbol -> "state.init"
				else                     -> "fn"
			}
			val name = "cloth.$kind.$index.${function.name.lexeme}"
			functions[function] = EmittedCallable(
				context, module, name, function, semantics.typeOf(function), hasClasses, results,
				acceptsOmissions = function !is InterfaceMethodSymbol,
				usesActivation = stateBody,
			) { classes }
		}
		for ((index, state) in stateDefinitions.withIndex()) if (state.initial != null) {
			initialStates[state] = LLVMAddFunction(module, "cloth.state.initial.$index.${state.name.lexeme}", initialStateType).also { LLVMSetLinkage(it, LLVMInternalLinkage) }
		}
		for (function in bodies) emitBody(function)
		for ((state, function) in initialStates) emitInitialState(state, function)
		// A default emitted in one dispatch entry can add another dispatch entry.
		while (dispatchBodies.isNotEmpty()) dispatchBodies.removeFirst().invoke()
	}

	private fun virtualEntry(method: MethodSymbol): EmittedCallable? {
		virtualEntries[method]?.let { return it }
		val targets = semantics.declarations.virtualTargets(method) ?: return null
		val name = "cloth.virtual.dispatch.${virtualEntries.size}.${method.name.lexeme}"
		return EmittedCallable(context, module, name, method, semantics.typeOf(method), hasClasses, results) { classes }.also { entry ->
			virtualEntries[method] = entry
			deferDispatch {
				val implementations = targets.map { (owner, body) -> owner to callable(body) }
				emitClassDispatch(entry, method, implementations, "Current class level does not provide the virtual method", classes::virtualClassTag)
			}
		}
	}

	internal fun emitClassDispatch(
		entry: EmittedCallable,
		contract: CallableContractSymbol,
		targets: List<Pair<CClass, EmittedCallable>>,
		unavailable: String,
		selectClassTag: (LLVMBuilderRef, ObjectAccess) -> LLVMValueRef = classes::classTag,
	) {
		val builder = LLVMCreateBuilderInContext(context)
		try {
			fun block(name: String) = LLVMAppendBasicBlockInContext(context, entry.reference, name)
			val start = block("entry")
			LLVMPositionBuilderAtEnd(builder, start)
			val access = checkNotNull(entry.objectAccess())
			val type = entry.type
			val frame = EmitFunctionFrame(context, builder, start, type.results, LLVMGetReturnType(entry.signature), stringStorage, entry.callerArena)
			val arguments = if (entry.hasOmissions) {
				classes.check(builder, access)
				bindDispatchArguments(contract, entry, builder, start, frame)
			} else type.parameters.indices.map { entry.parameter(builder, it) }
			// Supplied arguments and defaults can invalidate the captured receiver.
			val tag = selectClassTag(builder, access)
			val invalid = block("dispatch_unavailable")
			val dispatch = LLVMBuildSwitch(builder, tag, invalid, targets.size)
			for ((owner, target) in targets) {
				if (target.type != type) throw InternalErrors.invariant("Class dispatch target differs from its checked signature")
				val branch = block("implement.${owner.name.lexeme}")
				LLVMAddCase(dispatch, classTag(owner), branch)
				LLVMPositionBuilderAtEnd(builder, branch)
				val result = target.call(builder, returnArena = { checkNotNull(entry.callerArena) }) {
					setReceiver(access)
					arguments.forEachIndexed(::argument)
				}
				frame.emitReturn(result?.let { results.encode(builder, it, type.results) })
			}
			LLVMPositionBuilderAtEnd(builder, invalid)
			EmitRuntimeError(context, builder, abi).emit(unavailable)
			// The selected body has copied indirect results into the original caller's arena.
			frame.finish(resultsInCallerArena = true)
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}

	private fun emitBody(function: CallableContractSymbol) {
		val semantics = semantics.bodyOf(function)
		val builder = LLVMCreateBuilderInContext(context)
		try {
			val callable = callable(function)
			val value = callable.reference
			val signature = callable.type
			val entry = LLVMAppendBasicBlockInContext(context, value, "entry")
			LLVMPositionBuilderAtEnd(builder, entry)
			val frame = EmitFunctionFrame(context, builder, entry, signature.results, LLVMGetReturnType(callable.signature), stringStorage, callable.callerArena)
			val bindings = bindings(builder, entry, semantics, frame)
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
			semantics.declarations.states.payloadOf(function.declaration)?.let { payload ->
				val activation = callable.activation()
				bindings.bindStatePayload(payload, states.payload(builder, activation, payload.type), states.arena(builder, activation), states.payloadValidationAddress(builder, activation))
			}
			val expressions = expressions(builder, bindings, semantics, signature.results, frame)
			val arguments = bindParameters(function, callable, builder, bindings, expressions)
			objectAccess?.let { classes.check(builder, it) }
			if (callable.replayableStruct) bindings.checkDestination(checkNotNull(function.receiver))
			val constructed = if (function is ConstructorSymbol) {
				initialize(function, callable.allocation(), builder, bindings, expressions)?.also { expressions.setConstructorResult(it) }
			} else null
			if (expressions.terminated()) {
				frame.finish()
				return
			}
			when (val body = checkNotNull(function.declaration.executableBody)) {
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

				is IntrinsicBody  -> intrinsics.emit(function, signature.results, builder, arguments, objectAccess, frame)
			}
			frame.finish()
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}

	private fun bindings(builder: LLVMBuilderRef, entry: LLVMBasicBlockRef, semantics: FunctionSemanticModel, frame: EmitFunctionFrame): EmitBindings {
		val rebindings = (semantics.declarations.assignments.values + semantics.declarations.multiAssignments.values.flatMap { it.filterNotNull() }).filterNot { it is ReceiverFieldSymbol }.filter { ((semantics.bindings[it] as? CNullable)?.payload ?: semantics.bindings[it]) == ValueType.STRING }.toSet()
		return EmitBindings(context, builder, entry, abi, if (hasClasses) classStorage else null, if (hasClasses) receivers else null, frame, rebindings, relocatableArrays = hasArrayMutations)
	}

	private fun expressions(builder: LLVMBuilderRef, bindings: EmitBindings, semantics: FunctionSemanticModel, result: ResultShape, frame: EmitFunctionFrame): EmitExpression {
		val casts = EmitCast(context, builder, semantics.types, abi)
		val strings = EmitString(context, builder, abi, arena = { frame.stringReturnArena() }, metadataTypes = metadataTypes)
		return EmitExpression(context, builder, bindings, casts, strings, EmitPower(context, builder, abi), abi, this, semantics, result, frame)
	}

	fun bindDispatchArguments(contract: CallableContractSymbol, callable: EmittedCallable, builder: LLVMBuilderRef, entry: LLVMBasicBlockRef, frame: EmitFunctionFrame): List<LLVMValueRef> {
		val bindings = bindings(builder, entry, semantics, frame)
		contract.receiver?.let { bindings.bindObject(it, checkNotNull(callable.objectAccess())) }
		val expressions = expressions(builder, bindings, semantics, callable.type.results, frame)
		return bindParameters(contract, callable, builder, bindings, expressions)
	}

	private fun bindParameters(function: CallableContractSymbol, callable: EmittedCallable, builder: LLVMBuilderRef, bindings: EmitBindings, expressions: EmitExpression): List<LLVMValueRef> = function.parameters.mapIndexed { index, parameter ->
		val type = callable.type.parameters[index]
		val omitted = callable.omissionFlag(index)
		val argument = if (omitted == null) callable.parameter(builder, index) else {
			val fallback = checkNotNull(parameter.declaration.defaultValue)
			val suppliedBlock = LLVMAppendBasicBlockInContext(context, callable.reference, "parameter_supplied")
			val defaultBlock = LLVMAppendBasicBlockInContext(context, callable.reference, "parameter_default")
			val merge = LLVMAppendBasicBlockInContext(context, callable.reference, "parameter_ready")
			LLVMBuildCondBr(builder, omitted, defaultBlock, suppliedBlock)
			LLVMPositionBuilderAtEnd(builder, suppliedBlock)
			// Omitted placeholders are never decoded, copied, or shape-checked.
			val supplied = callable.parameter(builder, index)
			val suppliedEnd = LLVMGetInsertBlock(builder)
			LLVMBuildBr(builder, merge)
			LLVMPositionBuilderAtEnd(builder, defaultBlock)
			val fallbackValue = expressions.emit(fallback)
			val resolved = if (expressions.terminated()) null else {
				expressions.valueTransfer.transfer(expressions.adaptValue(checkNotNull(fallbackValue), expressions.typeOf(fallback), type), type)
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
		argument
	}

	private fun initialize(constructor: ConstructorSymbol, allocation: ConstructorAllocation, builder: LLVMBuilderRef, bindings: EmitBindings, expressions: EmitExpression): LLVMValueRef? {
		val owner = constructor.owner
		return when (val declaration = constructor.declaration) {
			is PrimaryConstructorDeclaration -> {
				val base = semantics.declarations.classDefinitions.getValue(owner).baseCall
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

	private fun emitInitialState(state: StateDefinition, function: LLVMValueRef) {
		val builder = LLVMCreateBuilderInContext(context)
		try {
			val entry = LLVMAppendBasicBlockInContext(context, function, "entry")
			LLVMPositionBuilderAtEnd(builder, entry)
			val frame = EmitFunctionFrame(context, builder, entry, ResultShape.Empty, LLVMVoidTypeInContext(context), stringStorage, null)
			val identity = LLVMGetParam(function, 0)
			val payload = state.initialPayload?.let { initial ->
				val access = ObjectAccess(identity, classes.permission(false))
				classes.check(builder, access)
				val bindings = bindings(builder, entry, semantics, frame)
				bindings.bindObject(initial.receiver, access)
				val expression = initial.declaration.expression
				val expressions = expressions(builder, bindings, semantics, ResultShape.Empty, frame)
				val value = expressions.emit(expression)
				if (expressions.terminated()) {
					frame.finish()
					return
				}
				expressions.adaptValue(checkNotNull(value), expressions.typeOf(expression), state.payloadType)
			}
			val activation = states.publish(builder, identity, state, payload, initial = true)
			runStateInitializer(builder, identity, state, activation)
			frame.emitReturn(null)
			frame.finish()
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}

	fun runStateInitializer(builder: LLVMBuilderRef, identity: LLVMValueRef, state: StateDefinition, activation: LLVMValueRef) {
		val initializer = state.activation ?: return
		// The hook can replace this activation or delete the object before it returns.
		states.retain(builder, activation)
		callable(initializer).call(builder) {
			setReceiver(ObjectAccess(identity, classes.permission(false)))
			setActivation(activation)
		}
		states.release(builder, activation)
	}

	private fun activateInitial(builder: LLVMBuilderRef, identity: LLVMValueRef, owner: CClass) {
		val state = semantics.declarations.states[owner]?.initial ?: return
		PointerPointer<LLVMValueRef>(1L).use { argument ->
			argument.put(0, identity)
			LLVMBuildCall2(builder, initialStateType, initialStates.getValue(state), argument, 1, "")
		}
	}

	fun delete(plan: ClassDeletionPlan, identity: LLVMValueRef, builder: LLVMBuilderRef) {
		delete(builder, identity, plan.owner)
	}

	fun deleteOwned(builder: LLVMBuilderRef, owner: ObjectAccess, identity: LLVMValueRef) {
		delete(builder, identity, semantics.declarations.objectRoot, owner)
	}

	private fun delete(builder: LLVMBuilderRef, identity: LLVMValueRef, view: CMemberType, owner: ObjectAccess? = null) {
		val access = classes.beginDelete(builder, identity, owner)
		val candidates = classTags.keys.filter(view::accepts)
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
		if (tracksStates) states.detach(builder, access)
		classes.finishDelete(builder, identity)
	}

	private fun destroy(builder: LLVMBuilderRef, access: ObjectAccess, concrete: CClass) {
		for (owner in concrete.ancestry()) {
			semantics.declarations.classDefinitions[owner]?.destructor?.let { destructor ->
				if (hasVirtualMethods) classes.enterDestructor(builder, access, classTag(owner))
				callable(destructor).call(builder) { setReceiver(access) }
			}
		}
		intrinsics.releaseInstance(concrete, builder, access)
		for (owner in concrete.ancestry()) classStorage.release(builder, access, owner)
	}

	fun isInstance(builder: LLVMBuilderRef, access: ObjectAccess, target: CMemberType): LLVMValueRef {
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
			val strings = EmitString(context, builder, abi, metadataTypes = metadataTypes)
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

	fun emitCall(plan: ResolvedCall.Interface, supplied: List<Expression>, expressions: EmitExpression, builder: LLVMBuilderRef): LLVMValueRef? {
		val access = expressions.emitObjectAccess(plan.receiver)
		classes.check(builder, access)
		val entry = interfaceDispatch.entry(plan)
		return entry.call(builder, omittedParameters = plan.binding.omittedParameters, returnArena = expressions::stringReturnArena) {
			setReceiver(access)
			supplied.forEachIndexed { index, expression ->
				val destination = plan.binding.parameterForArgument[index]
				argument(destination, evaluateArgument(expressions, expression, plan.type.parameters[destination], transfer = true))
			}
		}
	}

	fun emitStateCall(plan: ResolvedStateCall, receiver: Expression, supplied: List<Expression>, expressions: EmitExpression, builder: LLVMBuilderRef): LLVMValueRef? {
		val access = expressions.emitObjectAccess(ObjectReceiver.Explicit(receiver))
		return stateDispatch.entry(plan).call(builder, omittedParameters = plan.binding.omittedParameters, returnArena = expressions::stringReturnArena) {
			setReceiver(access)
			supplied.forEachIndexed { index, expression ->
				val destination = plan.binding.parameterForArgument[index]
				argument(destination, evaluateArgument(expressions, expression, plan.type.parameters[destination], transfer = true))
			}
		}
	}

	private fun evaluateArgument(expressions: EmitExpression, expression: Expression, parameter: ValueType, transfer: Boolean): LLVMValueRef {
		val adapted = expressions.adaptValue(expressions.emitValue(expression), expressions.typeOf(expression), parameter)
		return if (transfer) expressions.valueTransfer.transfer(adapted, parameter) else expressions.valueTransfer.snapshot(adapted, parameter)
	}

	fun emitGuaranteedConstruction(
		plan: ResolvedGuaranteedConstruction,
		supplied: List<Expression>,
		expressions: EmitExpression,
		builder: LLVMBuilderRef,
	): LLVMValueRef? {
		val constructor = checkNotNull(plan.constructor)
		if (plan.binding.omittedParameters.isEmpty()) {
			return emitCall(ResolvedCall.Source(constructor, plan.type, plan.binding), supplied, expressions, builder)
		}
		val entry = guaranteeEntries.getOrPut(plan.contract) {
			val name = "cloth.constructor.guarantee.${guaranteeEntries.size}"
			EmittedCallable(context, module, name, plan.contract, plan.type, hasClasses, results) { classes }.also { entry -> deferDispatch { emitConstructorGuarantee(entry, plan.contract, constructor) } }
		}
		return entry.call(builder, omittedParameters = plan.binding.omittedParameters) {
			supplied.forEachIndexed { index, expression ->
				val destination = plan.binding.parameterForArgument[index]
				argument(destination, evaluateArgument(expressions, expression, plan.type.parameters[destination], transfer = true))
			}
		}
	}

	private fun emitConstructorGuarantee(entry: EmittedCallable, contract: ConstructorGuaranteeSymbol, constructor: ConstructorSymbol) {
		val builder = LLVMCreateBuilderInContext(context)
		try {
			val start = LLVMAppendBasicBlockInContext(context, entry.reference, "entry")
			LLVMPositionBuilderAtEnd(builder, start)
			val frame = EmitFunctionFrame(context, builder, start, entry.type.results, LLVMGetReturnType(entry.signature), stringStorage, entry.callerArena)
			val arguments = bindDispatchArguments(contract, entry, builder, start, frame)
			val target = callable(constructor)
			if (target.type != entry.type) throw InternalErrors.invariant("Proven constructor differs from its guarantee")
			val identity = checkNotNull(target.call(builder) {
				arguments.forEachIndexed(::argument)
				setAllocation(constructorAllocation(builder, constructor.owner))
			})
			activateInitial(builder, identity, constructor.owner)
			frame.emitReturn(results.encode(builder, identity, entry.type.results))
			frame.finish()
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}

	private fun constructorAllocation(builder: LLVMBuilderRef, owner: CClass) = ConstructorAllocation(classes.instanceBytes(builder, intrinsics.instanceStorage(owner)), classTag(owner))

	internal data class CapturedIndex(val receiver: ObjectAccess, val value: LLVMValueRef, val type: ValueType)

	fun captureIndex(expression: IndexExpression, type: ValueType, expressions: EmitExpression, builder: LLVMBuilderRef): CapturedIndex {
		val receiver = expressions.emitObjectAccess(ObjectReceiver.Explicit(expression.array))
		classes.check(builder, receiver)
		return CapturedIndex(receiver, evaluateArgument(expressions, expression.index, type, transfer = false), type)
	}

	fun validateIndex(index: CapturedIndex, validator: MethodSymbol, expressions: EmitExpression, builder: LLVMBuilderRef) {
		val receiver = index.receiver
		val previous = classes.setIndexValidation(builder, receiver, classes.permission(true))
		val activation = if (tracksStates) states.current(builder, receiver) else null
		val previousPayload = activation?.let { states.setIndexValidation(builder, it, classes.permission(true)) }
		val accepted = checkNotNull(callable(validator).call(builder) {
			setReceiver(receiver)
			argument(0, expressions.valueTransfer.snapshot(index.value, index.type))
		})
		if (previousPayload != null) states.setIndexValidation(builder, checkNotNull(activation), previousPayload)
		classes.setIndexValidation(builder, receiver, previous)
		EmitMemory(context, builder, abi).guard(accepted, "Index out of bounds")
	}

	fun callIndex(plan: ResolvedCall.Source, index: CapturedIndex, expressions: EmitExpression, builder: LLVMBuilderRef, value: LLVMValueRef? = null, keepIndex: Boolean = false): LLVMValueRef? {
		return callable(plan.callable).call(builder, returnArena = expressions::stringReturnArena) {
			setReceiver(index.receiver)
			argument(0, if (keepIndex) expressions.valueTransfer.snapshot(index.value, index.type) else index.value)
			value?.let { argument(1, it) }
		}?.let { expressions.valueTransfer.freshResults(it, plan.type.results) }
	}

	fun emitCall(plan: ResolvedCall.Source, supplied: List<Expression>, expressions: EmitExpression, builder: LLVMBuilderRef, allocation: ConstructorAllocation? = null, operatorReceiver: Int? = null, returnArena: () -> LLVMValueRef = expressions::stringReturnArena): LLVMValueRef? {
		val selected = plan.callable
		val type = plan.type
		val target = (selected as? MethodSymbol)?.let(::virtualEntry) ?: callable(selected)
		if (target.type != type) throw InternalErrors.invariant("Resolved call type differs from emitted signature")
		fun invoke(structReceiver: (() -> ReceiverAccess)?): LLVMValueRef? = target.call(builder, omittedParameters = plan.binding.omittedParameters, returnArena = returnArena) {
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
				argument(destination, evaluateArgument(expressions, expression, type.parameters[destination], transfer = operatorReceiver == null))
			}
			if (structReceiver == null) plan.receiver?.let(::putReceiver)
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
			structReceiver?.let { setReceiver(it()) }
			if (selected is ConstructorSymbol) {
				setAllocation(allocation ?: constructorAllocation(builder, selected.owner))
			}
		}

		val receiver = plan.receiver
		val result = if (operatorReceiver == null && receiver != null && receiver !is MethodReceiver.Instance && !target.enumReceiver) {
			expressions.withReceiver(receiver, (selected as MethodSymbol).owner as CStruct) { invoke(it) }
		} else invoke(null)
		// Delegated constructors share the allocation and leave activation to the outer call.
		if (selected is ConstructorSymbol && allocation == null) activateInitial(builder, checkNotNull(result), selected.owner)
		return result
	}

	companion object {

		fun validate(semantics: FunctionSemanticModel) {
			semantics.declarations.genericViews?.types?.takeIf { it.symbolic }?.names?.genericSpan?.let { throw GenericErrors.compilation(it) }
			for (body in semantics.allBodyModels()) {
				val calls = body.types.memberCalls.entries.sortedWith(compareBy({ it.key.span.source?.name.orEmpty() }, { it.key.span.startLine }, { it.key.span.startColumn }))
				for ((call, bound) in calls) if (bound.overload.storage == BuiltinStorageContract.MUTATING_RECEIVER && body.types.arrayMutations[call]?.let(EmitArrayMutation::supported) != true) throw BackendErrors.arrayMutation(call.name.span)
			}
			fun supported(type: ValueType): Boolean = when (type) {
				is CArray     -> type.element != ValueType.VOID && supported(type.element)
				is CNullable  -> supported(type.payload)
				is CStruct    -> true // Field types and layouts are validated at their declarations.
				is CClass     -> true
				is CInterface -> true
				is CEnum      -> true
				else          -> type.numeric || type in setOf(ValueType.VOID, ValueType.BOOL, ValueType.CHAR, ValueType.STRING, ValueType.TYPEINFO)
			}
			for (function in semantics.nativeBodies) {
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
