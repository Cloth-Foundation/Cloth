package cloth.emitter.types

import cloth.builtin.BuiltinStorageContract
import cloth.data.*
import cloth.diagnostics.InternalErrors
import cloth.emitter.*
import cloth.emitter.callables.ConstructorAllocation
import cloth.emitter.callables.EmitFunctionFrame
import cloth.emitter.callables.EmitFunctions
import cloth.emitter.callables.EmitResults
import cloth.emitter.storage.*
import cloth.emitter.text.EmitString
import cloth.emitter.text.ProviderEmissionContext
import cloth.emitter.types.loops.EmitDoWhileLoop
import cloth.emitter.types.loops.EmitForEach
import cloth.emitter.types.loops.EmitRangeLoop
import cloth.emitter.types.loops.EmitWhileLoop
import cloth.emitter.types.primitives.EmitHalf
import cloth.emitter.types.primitives.EmitIntegerDivision
import cloth.emitter.types.primitives.EmitWideInteger
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.*
import cloth.semantic.flow.eagerOperands
import cloth.semantic.symbols.BindingSymbol
import cloth.semantic.symbols.LocalSymbol
import cloth.semantic.symbols.ReceiverFieldSymbol
import cloth.semantic.symbols.ReceiverSymbol
import cloth.semantic.typing.*
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

internal class EmitExpression(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val bindings: EmitBindings, private val casts: EmitCast, private val strings: EmitString, private val power: EmitPower, private val abi: NativeCABI, private val functions: EmitFunctions, private val functionSemantics: FunctionSemanticModel, private val returnResults: ResultShape, private val frame: EmitFunctionFrame) {

	val valueTransfer = EmitValueTransfer(context, builder, abi, frame)
	private val semantics = functionSemantics.types
	private val types = LLVMTypes(context, abi.layout)
	private val results = EmitResults(context, abi.layout)
	private val arrays = EmitArray(context, builder, abi)
	private val stored = EmitStoredValue(context, builder, abi.layout)
	private val nullable = EmitNullable(context, builder, abi.layout)
	private val boolType = types.value(ValueType.BOOL)
	private val places by lazy { EmitPlaces(context, builder, abi, bindings, functions, functionSemantics, this) }
	private val statements by lazy { EmitStatement(this, bindings, functionSemantics) }
	private val temporaryAnalysis = ArrayTemporaryAnalysis(functionSemantics)
	private var temporaryDepth = 0
	private var constructorResult: LLVMValueRef? = null

	private class Terminated : RuntimeException(null, null, false, false)
	private class BlockExit(val type: ValueType, val exit: LLVMBasicBlockRef) {

		val predecessors = mutableListOf<Pair<LLVMBasicBlockRef, LLVMValueRef?>>()
	}

	private val blockReturns = mutableListOf<BlockExit>()
	private val cleanupRegions = mutableListOf<() -> Unit>()

	private class LoopExit(val target: LoopTarget, val exit: LLVMBasicBlockRef, val next: LLVMBasicBlockRef, val cleanupDepth: Int) {

		var continued = false
	}

	private val loopExits = mutableListOf<LoopExit>()

	/** Destinations vary by loop form; jump resolution and crossed cleanup do not. */
	fun emitLoopBody(target: LoopTarget, exit: LLVMBasicBlockRef, next: LLVMBasicBlockRef, action: () -> Unit): Boolean {
		val loop = LoopExit(target, exit, next, cleanupRegions.size)
		loopExits.add(loop)
		try {
			action()
			return !terminated() || loop.continued
		} finally {
			loopExits.removeAt(loopExits.lastIndex)
		}
	}

	fun emitLoopJump(statement: LoopJumpStatement) {
		val target = semantics.targetOf(statement)
		val loop = loopExits.last { it.target === target }
		for (index in cleanupRegions.lastIndex downTo loop.cleanupDepth) cleanupRegions[index]()
		if (statement.isContinue) loop.continued = true
		LLVMBuildBr(builder, if (statement.isContinue) loop.next else loop.exit)
	}

	fun terminated(): Boolean {
		val end = LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder))
		return end != null && !end.isNull
	}

	fun emitStatements(body: List<Statement>, preserveLastValue: Boolean = false): LLVMValueRef? {
		var result: LLVMValueRef? = null
		for ((index, statement) in body.withIndex()) {
			if (terminated()) break
			result = statements.emit(statement, discardResult = !preserveLastValue || index != body.lastIndex)
		}
		return result
	}

	fun emitValue(expression: Expression): LLVMValueRef {
		val value = emit(expression)
		if (terminated()) throw Terminated()
		return value ?: throw InternalErrors.invariant("A value was required from a ${Keywords.VOID} expression")
	}

	fun typeOf(expression: Expression) = semantics.typeOf(expression)
	private val projectedValues = java.util.IdentityHashMap<NameExpression, LLVMValueRef>()

	private fun providerServices(location: String, receiver: Expression? = null): ProviderEmissionContext {
		val symbol = (receiver as? NameExpression)?.let { functionSemantics.declarations.names[it] } as? ReceiverSymbol
		return ProviderEmissionContext(context, builder, strings, casts, location, functions, ::stringReturnArena, symbol?.let(bindings::objectAccess)?.duringDestruction)
	}

	/** A provider consumes its receiver before any temporary cleanup can run. Buffered
	 * results may borrow receiver storage, so phase 2 conservatively keeps it until
	 * the owning frame exits. Ordinary return/copy handling still applies to results.
	 */
	fun emitProvider(access: ProviderAccess): LLVMValueRef? {
		if (terminated()) return null
		val receiverType = semantics.typeOf(access.receiver)
		if (receiverType != access.result.receiverType) throw InternalErrors.invariant("Resolved provider has a mismatched receiver type")
		temporaryDepth++
		return try {
			val receiver = if (access.receiverForm == ProviderReceiverForm.TYPE) ProviderReceiver.Type(receiverType)
			else ProviderReceiver.Value(receiverType, emitValue(access.receiver))
			val location = access.span.source?.let { "${it.name}:${access.span}" } ?: access.span.toString()
			val services = providerServices(location, access.receiver)
			access.result.emit(services, receiver)
		} catch (_: Terminated) {
			null
		} finally {
			temporaryDepth--
		}
	}

	fun emitReturn(statement: ReturnStatement) {
		val value = statement.value?.let { emit(it) }
		if (terminated()) return
		blockReturns.lastOrNull()?.let { target ->
			val result = if (target.type == ValueType.VOID) null else valueTransfer.transfer(adaptValue(checkNotNull(value), semantics.typeOf(checkNotNull(statement.value)), target.type), target.type)
			target.predecessors.add(LLVMGetInsertBlock(builder) to result)
			LLVMBuildBr(builder, target.exit)
			return
		}
		constructorResult?.let { frame.emitReturn(it); return }
		val shape = returnResults
		frame.emitReturn(if (shape == ResultShape.Empty) null else results.encode(builder, adaptResults(checkNotNull(value), semantics.resultOf(checkNotNull(statement.value)), shape), shape))
	}

	fun setConstructorResult(identity: LLVMValueRef) {
		constructorResult = identity
	}

	fun emitDelegation(delegation: ConstructorDelegation, allocation: ConstructorAllocation): LLVMValueRef? = try {
		functions.emitCall(functionSemantics.selectedConstructor(delegation), delegation.arguments, this, builder, allocation)
	} catch (_: Terminated) {
		null
	}

	fun emitObjectAccess(receiver: ObjectReceiver): ObjectAccess = when (receiver) {
		is ObjectReceiver.Self     -> checkNotNull(bindings.objectAccess(receiver.symbol))
		is ObjectReceiver.Explicit -> {
			val expression = receiver.expression
			val projected = (expression as? NameExpression)?.let { semantics.projectedOperandOf(it) }
			val original = projected?.original ?: expression
			val symbol = (original as? NameExpression)?.let { functionSemantics.declarations.names[it] } as? ReceiverSymbol
			val access = symbol?.let(bindings::objectAccess)
			if (projected == null && access != null) access
			else ObjectAccess(emitValue(expression), access?.duringDestruction ?: functions.classes.permission(false))
		}
	}

	private fun emitDelete(expression: ClassDeletionExpression): LLVMValueRef? {
		val value = emitValue(expression.receiver)
		val plan = functionSemantics.deletionOf(expression)
		if (plan.nullable) nullable.choose(nullable.present(value), null, { functions.delete(plan, nullable.payload(value), builder); null }, { null })
		else functions.delete(plan, value, builder)
		return null
	}

	fun stringReturnArena(): LLVMValueRef = frame.stringReturnArena()

	fun adaptValue(value: LLVMValueRef, source: ValueType, destination: ValueType): LLVMValueRef = when {
		source == destination                                                    -> value
		source is CArray && destination is CArray && destination.accepts(source) -> value
		source is CClass && destination is CClass && destination.accepts(source) -> value
		else                                                                     -> nullable.adapt(value, source, destination)
	}

	fun adaptResults(value: LLVMValueRef, source: ResultShape, destination: ResultShape): LLVMValueRef {
		if (source == destination) return value
		if (source.values.size != destination.values.size) throw InternalErrors.invariant("Validated result adaptation changed arity")
		val values = valueTransfer.takeResults(value, source).mapIndexed { index, item -> adaptValue(item, source.values[index], destination.values[index]) }
		return valueTransfer.freshResults(results.pack(builder, values, destination), destination)
	}

	private fun emitMemberCall(call: MemberCallExpression): LLVMValueRef? {
		functionSemantics.selectedMemberCall(call)?.let { return functions.emitCall(it, call.arguments, this, builder) }
		val selected = semantics.memberCalls.getValue(call)
		temporaryDepth++
		try {
			val values = (listOf(call.receiver) + call.arguments).mapIndexed { index, expression ->
				val value = emitValue(expression)
				val type = selected.arguments[index].parameterType
				// Loading an immutable string descriptor already snapshots its value.
				// Rebinding/array replacement cannot mutate those bytes. The protected
				// operand region keeps storage live until this read-only call consumes it.
				if (type == ValueType.STRING && selected.overload.storage == BuiltinStorageContract.READ_ONLY_INDEPENDENT_RESULT) value
				else valueTransfer.snapshot(value, type)
			}
			if (terminated()) return null
			val location = call.span.source?.let { "${it.name}:${call.span}" } ?: call.span.toString()
			val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
			val providers = providerServices(location)
			return selected.emit(BuiltinEmissionContext(context, module, builder, abi, providers, strings, location), values)
		} finally {
			temporaryDepth--
		}
	}

	private fun emitCall(call: CallExpression): LLVMValueRef? = when (val selected = functionSemantics.selectedCall(call)) {
		is ResolvedCall.Source  -> functions.emitCall(selected, call.arguments, this, builder)
		is ResolvedCall.Builtin -> {
			// Arguments and text providers may borrow buffers until the operation consumes them.
			temporaryDepth++
			try {
				val values = call.arguments.mapIndexed { index, expression ->
					valueTransfer.transfer(emitValue(expression), selected.call.arguments[index].parameterType)
				}
				val location = call.span.source?.let { "${it.name}:${call.span}" } ?: call.span.toString()
				val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
				val providers = providerServices(location, call.arguments.singleOrNull())
				selected.call.emit(BuiltinEmissionContext(context, module, builder, abi, providers, strings, location), values)
			} finally {
				temporaryDepth--
			}
		}
	}

	fun emitElementAssignment(statement: ElementAssignmentStatement): LLVMValueRef? = try {
		val instance = semantics.instanceWrites[statement]
		val receiver = semantics.receiverElementWrites[statement]
		if (instance != null || receiver?.let { bindings.structAccess(it.root) } != null) {
			val target = places.captureWrite(instance, receiver)
			val type = target.initial.type
			val value = adaptValue(emitValue(statement.value), semantics.typeOf(statement.value), type)
			places.commit(target, value, type)
		} else emitOrdinaryElementAssignment(statement)
	} catch (_: Terminated) {
		null
	}

	private fun emitOrdinaryElementAssignment(statement: ElementAssignmentStatement): LLVMValueRef {
		// Resolve the complete target once, including each bounds check, before the RHS.
		val array = emitValue(statement.target.array)
		val index = emitValue(statement.target.index)
		val type = semantics.typeOf(statement.target)
		val parentType = semantics.typeOf(statement.target.array) as CArray
		val address = arrays.checkedAddress(array, type, index, semantics.typeOf(statement.target.index))
		val ownerArena = functionSemantics.declarations.storedRoot(statement.target)?.let(bindings::ownerArena)
		val value = adaptValue(emitValue(statement.value), semantics.typeOf(statement.value), type)
		return valueTransfer.storeElement(address, ArrayParent(array, parentType), value, ownerArena)
	}

	private fun emitStructLiteral(expression: StructLiteral): LLVMValueRef {
		val type = semantics.structLiterals.getValue(expression)
		val memory = EmitMemory(context, builder, abi)
		val storage = types.storage(type)
		val address = memory.stack(storage, memory.count(1), "struct_literal", "Unable to allocate struct literal storage")
		if (type.fields.isEmpty()) LLVMBuildStore(builder, LLVMConstNull(storage), address)
		for ((index, source) in expression.fields.withIndex()) {
			val field = type.fields[index]
			val value = adaptValue(emitValue(source), semantics.typeOf(source), field.type)
			// Finish the field's snapshot before the next expression can mutate its source.
			stored.store(stored.fieldAddress(address, type, field), valueTransfer.snapshot(value, field.type), field.type)
		}
		return address
	}

	private fun emitFieldRead(expression: FieldAccessExpression): LLVMValueRef {
		functionSemantics.declarations.enumCases[expression]?.let { case ->
			return LLVMConstInt(types.value(case.owner), case.ordinal.toLong(), 0)
		}
		val field = semantics.fields[expression] ?: return emitValue(expression.receiver)
		if (field is EnumField) return emitEnumPayload(emitValue(expression.receiver), field, expression)
		val address = if (field is ClassField) {
			functions.classStorage.locate(builder, emitObjectAccess(ObjectReceiver.Explicit(expression.receiver)), field).address
		} else stored.fieldAddress(emitValue(expression.receiver), semantics.typeOf(expression.receiver) as CStruct, field)
		val value = stored.load(address, field.type)
		val read = if (semantics.payloadReads.containsKey(expression)) nullable.payload(value) else value
		return if (field is ClassField) valueTransfer.snapshot(read, semantics.typeOf(expression)) else read
	}

	private fun emitEnumPayload(receiver: LLVMValueRef, field: EnumField, expression: Expression): LLVMValueRef {
		val value = functions.enumPayload(builder, receiver, field, ::stringReturnArena)
		return if (semantics.payloadReads.containsKey(expression)) nullable.payload(value) else value
	}

	fun emitReceiver(receiver: MethodReceiver, type: CStruct): ReceiverAccess = places.receiver(receiver, type)

	fun emitFieldWrite(statement: FieldWriteStatement): LLVMValueRef? = try {
		val plan = semantics.fieldWrites[statement]
		val instance = semantics.instanceWrites[statement]
		if (plan == null && instance == null) {
			// A non-continuing index still executes before the unreachable store.
			emitValue(statement.target)
			throw InternalErrors.invariant("Continuing field write has no stored place")
		}
		val target = places.captureWrite(instance, plan?.place)
		val location = target.initial
		val type = plan?.type ?: location.type
		val op = Operators.fromCharacterRequired(statement.operator.lexeme)
		val source = statement.value
		val valueType = if (op == Operators.ASSIGN) semantics.typeOf(checkNotNull(source)) else semantics.typeOf(statement.target)
		val value = if (op == Operators.ASSIGN) emitValue(checkNotNull(source)) else {
			val raw = places.load(location)
			val payload = if (location.type is CNullable) nullable.payload(raw) else raw
			val old = if (target.replayed) valueTransfer.snapshot(payload, valueType) else payload
			if (source == null) emitNumericUpdate(old, valueType, op == Operators.PLUS_PLUS)
			else {
				val right = emitValue(source)
				val base = Token(statement.operator.type, statement.operator.span, op.compoundAssignmentBase().toString())
				emitBinaryOperands(base, old, right, valueType, semantics.typeOf(source))
			}
		}
		places.commit(target, adaptValue(value, valueType, type), type)
	} catch (_: Terminated) {
		null
	}

	/** Each read expands a fresh evaluation in the physical function frame. Captures
	 * use resolved binding identities; temporary strings/arrays retain that frame's
	 * lifetime and use the ordinary copy path if returned from the actual function.
	 */
	private fun emitBlock(block: FunctionBlockExpression): LLVMValueRef? {
		val type = semantics.typeOf(block)
		val exit = LLVMAppendBasicBlockInContext(context, LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)), "block_end")
		val target = BlockExit(type, exit)
		blockReturns.add(target)
		try {
			bindings.withScope {
				emitStatements((block.declaration.body as BlockBody).statements)
				if (!terminated()) {
					check(type == ValueType.VOID)
					target.predecessors.add(LLVMGetInsertBlock(builder) to null)
					LLVMBuildBr(builder, exit)
				}
			}
		} finally {
			blockReturns.removeAt(blockReturns.lastIndex)
		}
		LLVMPositionBuilderAtEnd(builder, exit)
		if (target.predecessors.isEmpty()) {
			LLVMBuildUnreachable(builder); return null
		}
		if (type == ValueType.VOID) return null
		if (target.predecessors.size == 1) return valueTransfer.freshArray(checkNotNull(target.predecessors.single().second), type)
		val result = LLVMBuildPhi(builder, types.value(type), "block_value")
		PointerPointer<LLVMValueRef>(target.predecessors.size.toLong()).use { values ->
			PointerPointer<LLVMBasicBlockRef>(target.predecessors.size.toLong()).use { blocks ->
				target.predecessors.forEachIndexed { index, (end, value) ->
					values.put(index.toLong(), checkNotNull(value)); blocks.put(index.toLong(), end)
				}
				LLVMAddIncoming(result, values, blocks, target.predecessors.size)
			}
		}
		return valueTransfer.freshArray(result, type)
	}

	fun emitUpdate(statement: UpdateStatement, type: ValueType): LLVMValueRef {
		val stored = bindings.read(functionSemantics.declarations.targetOf(statement))
		val operand = if (semantics.updatePayloads.containsKey(statement)) nullable.payload(stored) else stored
		return emitNumericUpdate(operand, type, statement.operator.lexeme == Operators.PLUS_PLUS.toString())
	}

	private fun emitNumericUpdate(operand: LLVMValueRef, type: ValueType, increment: Boolean): LLVMValueRef {
		if (type is CFloat) {
			val workingType = EmitHalf.arithmeticType(type)
			val value = casts.toFloating(operand, type, workingType)
			val one = LLVMConstReal(types.value(workingType), 1.0)
			val result = if (increment) LLVMBuildFAdd(builder, value, one, "increment") else LLVMBuildFSub(builder, value, one, "decrement")
			return casts.toFloating(result, workingType, type)
		}
		if (type is CInteger && type.bits > 64) {
			val wide = EmitWideInteger(context, builder, abi)
			return wide.binary(operand, wide.constant(java.math.BigInteger.ONE, type.bits), type.bits, if (increment) Operators.PLUS else Operators.MINUS)
		}
		val one = LLVMConstInt(types.value(type), 1, 0)
		return if (increment) LLVMBuildAdd(builder, operand, one, "increment") else LLVMBuildSub(builder, operand, one, "decrement")
	}

	fun emitFor(statement: ForStatement) {
		val key = functionSemantics.declarations.symbolOf(statement)
		temporaryRegion(temporaryAnalysis.canReclaimLoop(statement)) {
			when (statement.source) {
				is ForSource.Range    -> EmitRangeLoop(context, builder, bindings, semantics, casts, abi, this).emit(statement, key)
				is ForSource.Elements -> EmitForEach(context, builder, bindings, semantics, abi, this).emit(statement, key)
			}
		}
	}

	fun emitIteration(statement: ForStatement, action: () -> Unit) = temporaryRegion(temporaryAnalysis.canReclaimIteration(statement.body.statements), action)
	fun emitIteration(body: List<Statement>, action: () -> Unit) = temporaryRegion(temporaryAnalysis.canReclaimIteration(body), action)

	fun emitLoopCondition(condition: Expression): LLVMValueRef? {
		var value: LLVMValueRef? = null
		temporaryRegion(temporaryAnalysis.canReclaimCondition(condition)) { value = emit(condition) }
		return value
	}

	fun emitWhile(statement: WhileStatement) {
		temporaryRegion(temporaryAnalysis.canReclaimLoop(statement)) {
			EmitWhileLoop(context, builder, bindings, this, functionSemantics.controlFlow).emit(statement)
		}
	}

	fun emitDoWhile(statement: DoWhileStatement) {
		temporaryRegion(temporaryAnalysis.canReclaimLoop(statement)) {
			EmitDoWhileLoop(context, builder, bindings, this, functionSemantics.controlFlow).emit(statement)
		}
	}

	fun emitStepper(statement: ForStatement, action: () -> Unit) = temporaryRegion(temporaryAnalysis.canReclaimStepper(statement), action)

	/** Only scalar results may cross this checkpoint; wide value bytes stay inside. */
	fun scalarTemporary(action: () -> LLVMValueRef): LLVMValueRef = temporaryRegion(true, action)

	/** Closed non-escape proof, independent of an enclosing expression's checkpoint.
	 * Return paths bypass these releases and retain the ordinary owning-frame path.
	 */
	private fun <T> temporaryRegion(reclaim: Boolean, action: () -> T): T {
		if (!reclaim) return action()
		val checkpoint = frame.temporaryCheckpoint()
		val stack = EmitStackScope(context, builder)
		val stackCheckpoint = stack.save()
		val release = { frame.releaseTemporaries(checkpoint); stack.restore(stackCheckpoint) }
		return valueTransfer.withFreshnessScope {
			cleanupRegions.add(release)
			try {
				action().also { if (!terminated()) release() }
			} finally {
				cleanupRegions.removeAt(cleanupRegions.lastIndex)
			}
		}
	}

	fun emit(expression: Expression): LLVMValueRef? {
		return emit(expression, discard = false)
	}

	fun emitDiscarded(expression: Expression): LLVMValueRef? {
		emit(expression, discard = true)
		return null
	}

	fun emitDiscarded(plan: ReceptionPlan): LLVMValueRef? {
		val constrained = plan.slots.filter { slot -> ((slot.type as? CNullable)?.payload ?: slot.type).arrayDimensions.any { it != null } }
		if (constrained.isEmpty()) return emitDiscarded(plan.producer)
		emit(plan.producer, discard = true) { value ->
			val slots = valueTransfer.takeResults(value, plan.results)
			for (slot in constrained) valueTransfer.validateDiscarded(adaptValue(slots[slot.sourceIndex], plan.results.values[slot.sourceIndex], slot.type), slot.type)
		}
		return null
	}

	private fun emit(expression: Expression, discard: Boolean, consume: ((LLVMValueRef) -> Unit)? = null): LLVMValueRef? {
		if (terminated()) return null
		fun produce(): LLVMValueRef? = try {
			emitInternal(expression).also { value -> if (!terminated() && value != null) consume?.invoke(value) }
		} catch (_: Terminated) {
			null
		}

		val reclaim = temporaryDepth == 0 && (discard || semantics.resultOf(expression).values.none { it.indirect }) && temporaryAnalysis.canReclaim(expression)
		if (!reclaim) return produce()
		temporaryDepth++
		return try {
			// Receiving constraints consume descriptors before their buffers are released.
			temporaryRegion(true, ::produce)
		} finally {
			temporaryDepth--
		}
	}

	private fun emitInternal(expression: Expression): LLVMValueRef? {
		return when (expression) {
			is StructLiteral            -> emitStructLiteral(expression)
			is FieldAccessExpression    -> emitFieldRead(expression)
			is NullLiteral              -> (semantics.typeOf(expression) as? CNullable)?.let(nullable::absent) ?: LLVMConstNull(types.value(ValueType.Null))
			is ElvisExpression          -> emitElvis(expression)
			is ResultListExpression     -> {
				// Snapshot each buffered position before subsequent expressions can mutate
				// its source. Even discarded positions execute and retain runtime guards.
				val snapshots = expression.values.map { item -> valueTransfer.snapshot(emitValue(item), semantics.typeOf(item)) }
				if (terminated()) null else valueTransfer.freshResults(results.pack(builder, snapshots, semantics.resultOf(expression)), semantics.resultOf(expression))
			}

			is ProviderAccessExpression -> if (semantics.typeOf(expression) == ValueType.NEVER) emit(expression.receiver)
			else emitProvider(semantics.providerAccess(expression))

			is ArrayLiteral             -> {
				val type = semantics.typeOf(expression) as? CArray
				if (type == null) {
					expression.elements.forEach { emitValue(it) }; null
				} else arrays.literal(type.element, expression.elements.map { item -> { valueTransfer.transfer(adaptValue(emitValue(item), semantics.typeOf(item), type.element), type.element) } })
			}

			is IndexExpression          -> {
				val array = emitValue(expression.array)
				val fresh = valueTransfer.consumeFreshArray(array)
				val index = emitValue(expression.index)
				val value = arrays.index(array, semantics.typeOf(expression), index, semantics.typeOf(expression.index))
				if (fresh) valueTransfer.freshArray(value, semantics.typeOf(expression)) else value
			}

			is IfExpression             -> emitConditional(expression, requireValue = semantics.resultOf(expression) !in setOf(ResultShape.Empty, ResultShape.Never))
			is TypeCheckExpression      -> {
				semantics.valueCheckOf(expression)?.let { return emitOperator(it.operator, it.application.eagerOperands()) }
				// Preserve evaluation (and any side effects) even for a statically known check.
				val value = emitValue(expression.operand)
				val source = semantics.typeOf(expression.operand)
				val target = semantics.targetTypeOf(expression)
				val payload = (source as? CNullable)?.payload ?: source
				if (payload is CClass && target is CClass) {
					val emitter = functions
					val symbol = (expression.operand as? NameExpression)?.let { functionSemantics.declarations.names[it] } as? ReceiverSymbol
					val permitted = symbol?.let(bindings::objectAccess)?.duringDestruction ?: emitter.classes.permission(false)
					fun matches(identity: LLVMValueRef) = emitter.isInstance(builder, ObjectAccess(identity, permitted), target)
					if (source is CNullable) nullable.choose(nullable.present(value), boolType, { matches(nullable.payload(value)) }, { emitter.classes.permission(false) })
					else matches(value)
				} else LLVMConstInt(boolType, if (source == target) 1L else 0L, 0)
			}

			is NameExpression           -> {
				projectedValues[expression]?.let { return it }
				val symbol = functionSemantics.declarations.symbolOf(expression) as? BindingSymbol ?: throw InternalErrors.invariant("Value name has no binding symbol")
				val field = (symbol as? ReceiverFieldSymbol)?.field
				if (field is EnumField) return emitEnumPayload(bindings.read(symbol.receiver), field, expression)
				val block = (symbol as? LocalSymbol)?.declaration?.initializer as? FunctionBlockExpression
				if (block != null) emitBlock(block) else {
					val stored = bindings.read(symbol)
					val read = if (semantics.payloadReads.containsKey(expression)) nullable.payload(stored) else stored
					if (bindings.mayBorrowInstance(symbol)) valueTransfer.snapshot(read, semantics.typeOf(expression)) else read
				}
			}

			is MemberCallExpression     -> emitMemberCall(expression)?.let { valueTransfer.freshResults(it, semantics.resultOf(expression)) }
			is CallExpression           -> emitCall(expression)?.let { valueTransfer.freshResults(it, semantics.resultOf(expression)) }
			is ClassCreationExpression  -> functions.emitCall(functionSemantics.selectedConstructor(expression), expression.arguments, this, builder)
			is ClassDeletionExpression  -> emitDelete(expression)
			is IntegerLiteral           -> {
				val type = semantics.typeOf(expression)
				if (type is CInteger && type.bits > 64) EmitWideInteger(context, builder, abi).constant(expression.value, type.bits)
				else LLVMConstIntOfString(types.value(type), expression.value.toString(), 10.toByte())
			}

			is FloatingPointLiteral     -> LLVMConstRealOfString(types.value(semantics.typeOf(expression)), expression.value)
			is BooleanLiteral           -> LLVMConstInt(boolType, if (expression.value) 1L else 0L, 0)
			is CharacterLiteral         -> LLVMConstInt(types.value(ValueType.CHAR), expression.value.toLong(), 0)
			is StringLiteral            -> strings.literal(expression.value, expression.span)
			is InterpolatedString       -> {
				var result: LLVMValueRef? = null
				for (part in expression.parts) {
					if (part is StringLiteral && part.value.isEmpty()) continue
					val raw = emitValue(part)
					val type = semantics.typeOf(part)
					val providers = providerServices(part.span.toString(), part)
					val recipe = checkNotNull(semantics.interpolationTextOf(part))
					val value = recipe.emit(providers, ProviderReceiver.Value(type, raw))
					result = result?.let { strings.concatenate(it, value, part.span.toString()) } ?: value
				}
				result ?: strings.literal("")
			}

			is CastExpression           -> if (semantics.typeOf(expression) == ValueType.VOID) {
				emit(expression.operand); null
			} else casts.emit(expression, emitValue(expression.operand))

			is UnaryExpression          -> {
				semantics.operatorOf(expression)?.let { return emitOperator(it, expression.eagerOperands()) }
				val operand = emitValue(expression.operand)
				when (expression.operator.lexeme) {
					Operators.NOT.toString()   -> LLVMBuildNot(builder, operand, LLVM_NOT_TEMPORARY)
					Operators.PLUS.toString()  -> operand
					Operators.MINUS.toString() -> if (semantics.typeOf(expression).floating) {
						val type = semantics.typeOf(expression) as CFloat
						val workingType = EmitHalf.arithmeticType(type)
						casts.toFloating(LLVMBuildFNeg(builder, casts.toFloating(operand, type, workingType), LLVM_NEGOTIATION_TEMPORARY), workingType, type)
					} else {
						val type = semantics.typeOf(expression)
						if (type is CInteger && type.bits > 64) EmitWideInteger(context, builder, abi).negate(operand, type.bits)
						else LLVMBuildNeg(builder, operand, LLVM_NEGOTIATION_TEMPORARY)
					}

					else                       -> throw InternalErrors.invariant("Unsupported unary operator: ${expression.operator}")
				}
			}

			is BinaryExpression         -> {
				semantics.operatorOf(expression)?.let { return emitOperator(it, expression.eagerOperands()) }
				semantics.operatorContextOf(expression)?.let { return emitOperatorContext(expression, it) }
				semantics.classComparisons[expression]?.let { return emitClassComparison(expression, it) }
				semantics.valueComparisons[expression]?.let { return emitValueComparison(expression, it) }
				if (expression.left.rangeOrNull() != null || expression.right.rangeOrNull() != null) return emitRangeMembership(expression)
				semantics.nullableComparisons[expression]?.let { return emitNullableEquality(expression, it) }
				if (expression.operator.lexeme in setOf(Operators.AND.toString(), Operators.OR.toString())) {
					return emitLogical(expression)
				}
				val left = emitValue(expression.left)
				val right = emitValue(expression.right)
				emitBinaryOperands(expression.operator, left, right, semantics.typeOf(expression.left), semantics.typeOf(expression.right))
			}

			else                        -> throw InternalErrors.invariant("Unsupported expression: $expression")
		}
	}

	private fun emitOperator(operator: ResolvedOperator, operands: List<Expression>): LLVMValueRef? = functions.emitCall(operator.call, operands, this, builder, operatorReceiver = operator.receiverIndex)?.let { valueTransfer.freshResults(it, operator.call.type.results) }

	private fun emitOperatorContext(expression: BinaryExpression, plan: ResolvedOperatorContext): LLVMValueRef {
		val names = listOf(plan.application.left as NameExpression, plan.application.right as NameExpression)
		try {
			for ((index, operand) in listOf(expression.left, expression.right).withIndex()) {
				val projection = plan.projections[index]
				val value = if (projection == null) emitValue(operand) else checkNotNull(emitOperator(projection, listOf(operand)))
				val name = names[index]
				projectedValues[name] = valueTransfer.snapshot(value, semantics.typeOf(name))
			}
			return emitValue(plan.application)
		} finally {
			names.forEach(projectedValues::remove)
		}
	}

	private fun emitBinaryOperands(operator: Token, leftOperand: LLVMValueRef, rightOperand: LLVMValueRef, leftType: ValueType, rightType: ValueType): LLVMValueRef {
		var left = leftOperand
		var right = rightOperand
		if (leftType == ValueType.TYPEINFO && rightType == ValueType.TYPEINFO) {
			val equal = strings.metadata.equal(left, right)
			return if (operator.lexeme == Operators.NOT_EQUAL.toString()) LLVMBuildNot(builder, equal, "typeinfo_not_equal") else equal
		}
		if (leftType == ValueType.STRING && rightType == ValueType.STRING) {
			return when (operator.lexeme) {
				Operators.PLUS.toString()      -> strings.concatenate(left, right, operator.span.toString())
				Operators.EQUAL.toString()     -> strings.equal(left, right)
				Operators.NOT_EQUAL.toString() -> LLVMBuildNot(builder, strings.equal(left, right), "string_not_equal")
				else                           -> throw InternalErrors.invariant("Unsupported string operator: ${operator}")
			}
		}
		val unsigned = leftType.unsigned
		val floatingPoint = leftType.floating || rightType.floating
		val commonType = if (floatingPoint) ValueType.commonFloating(leftType, rightType) else null
		// Compute half operations in float32 and explicitly round back to half.
		val workingType = commonType?.let(EmitHalf::arithmeticType)
		if (floatingPoint) {
			left = casts.toFloating(left, leftType, workingType!!)
			right = casts.toFloating(right, rightType, workingType)
		}
		if (operator.lexeme == Operators.MUL_MUL.toString()) {
			val span = operator.span
			val location = span.source?.let { "${it.name}:$span" } ?: span.toString()
			val result = power.emit(left, right, workingType ?: leftType, location)
			return if (floatingPoint) casts.toFloating(result, workingType!!, commonType) else result
		}
		val comparison = when (operator.lexeme) {
			Operators.EQUAL.toString()         -> if (floatingPoint) LLVMRealOEQ else LLVMIntEQ
			// Unordered != keeps NaN unequal to every value, including itself.
			Operators.NOT_EQUAL.toString()     -> if (floatingPoint) LLVMRealUNE else LLVMIntNE
			Operators.LESS.toString()          -> if (floatingPoint) LLVMRealOLT else if (unsigned) LLVMIntULT else LLVMIntSLT
			Operators.GREATER.toString()       -> if (floatingPoint) LLVMRealOGT else if (unsigned) LLVMIntUGT else LLVMIntSGT
			Operators.LESS_EQUAL.toString()    -> if (floatingPoint) LLVMRealOLE else if (unsigned) LLVMIntULE else LLVMIntSLE
			Operators.GREATER_EQUAL.toString() -> if (floatingPoint) LLVMRealOGE else if (unsigned) LLVMIntUGE else LLVMIntSGE
			else                               -> null
		}
		if (comparison != null) {
			if (!floatingPoint && leftType is CInteger && leftType.bits > 64) return EmitWideInteger(context, builder, abi).compare(left, right, leftType, Operators.fromCharacterRequired(operator.lexeme))
			return if (floatingPoint) {
				LLVMBuildFCmp(builder, comparison, left, right, LLVM_COMPARE_TEMPORARY)
			} else {
				LLVMBuildICmp(builder, comparison, left, right, LLVM_COMPARE_TEMPORARY)
			}
		}

		return if (floatingPoint) {
			val result = when (operator.lexeme) {
				Operators.PLUS.toString()     -> LLVMBuildFAdd(builder, left, right, LLVM_ADD_TEMPORARY)
				Operators.MINUS.toString()    -> LLVMBuildFSub(builder, left, right, LLVM_SUB_TEMPORARY)
				Operators.MULTIPLY.toString() -> LLVMBuildFMul(builder, left, right, LLVM_MUL_TEMPORARY)
				Operators.DIVIDE.toString()   -> LLVMBuildFDiv(builder, left, right, LLVM_DIV_TEMPORARY)
				Operators.MODULO.toString()   -> LLVMBuildFRem(builder, left, right, LLVM_MOD_TEMPORARY)
				else                          -> throw InternalErrors.invariant("Unsupported binary operator: ${operator}")
			}
			casts.toFloating(result, workingType!!, commonType)
		} else if (leftType is CInteger && leftType.bits > 64 && operator.lexeme in setOf(Operators.PLUS.toString(), Operators.MINUS.toString(), Operators.MULTIPLY.toString())) {
			EmitWideInteger(context, builder, abi).binary(left, right, leftType.bits, Operators.fromCharacterRequired(operator.lexeme))
		} else when (operator.lexeme) {
			Operators.PLUS.toString()                                -> LLVMBuildAdd(builder, left, right, LLVM_ADD_TEMPORARY)
			Operators.MINUS.toString()                               -> LLVMBuildSub(builder, left, right, LLVM_SUB_TEMPORARY)
			Operators.MULTIPLY.toString()                            -> LLVMBuildMul(builder, left, right, LLVM_MUL_TEMPORARY)
			Operators.DIVIDE.toString(), Operators.MODULO.toString() -> {
				val span = operator.span
				val location = span.source?.let { "${it.name}:$span" } ?: span.toString()
				EmitIntegerDivision(context, builder, abi).emit(left, right, leftType as CInteger, operator.lexeme == Operators.MODULO.toString(), location)
			}

			else                                                     -> throw InternalErrors.invariant("Unsupported binary operator: ${operator}")
		}
	}

	private fun emitRangeMembership(expression: BinaryExpression): LLVMValueRef {
		val range = expression.left.rangeOrNull() ?: checkNotNull(expression.right.rangeOrNull())
		val valueExpression = if (range === expression.left) expression.right else expression.left
		val rangeValues = EmitRangeValues(context, builder, casts, abi)
		var value: LLVMValueRef? = null
		// Preserve written order, capturing each value before a later operand can
		// mutate its binding. Endpoint guards also precede subsequent evaluation.
		if (range !== expression.left) value = emitValue(valueExpression)
		val start = emitValue(range.left)
		val type = semantics.typeOf(range.left)
		rangeValues.finite(start, type, range.left.span)
		val end = emitValue(range.right)
		rangeValues.finite(end, type, range.right.span)
		if (range === expression.left) value = emitValue(valueExpression)
		val lower = rangeValues.compare(start, checkNotNull(value), type, Operators.LESS_EQUAL)
		val upper = rangeValues.compare(value, end, type, if (range.operator.lexeme == Operators.RANGE_INCLUSIVE.toString()) Operators.LESS_EQUAL else Operators.LESS)
		val member = LLVMBuildAnd(builder, lower, upper, "range_member")
		return if (expression.operator.lexeme == Operators.NOT_EQUAL.toString()) LLVMBuildNot(builder, member, "range_not_member") else member
	}

	private fun emitElvis(expression: ElvisExpression): LLVMValueRef? {
		val left = emitValue(expression.left)
		val leftType = semantics.typeOf(expression.left) as CNullable
		if (expression.left is NullLiteral) {
			val fallback = emit(expression.fallback)
			return if (terminated()) null else adaptValue(checkNotNull(fallback), semantics.typeOf(expression.fallback), semantics.typeOf(expression))
		}
		val result = semantics.typeOf(expression)
		return nullable.choose(nullable.present(left), types.value(result), {
			adaptValue(nullable.payload(left), leftType.payload, result)
		}, {
			val fallback = emit(expression.fallback)
			if (terminated()) null else adaptValue(checkNotNull(fallback), semantics.typeOf(expression.fallback), result)
		})
	}

	private fun emitClassComparison(expression: BinaryExpression, plan: ClassComparisonPlan): LLVMValueRef = emitPresenceComparison(expression, plan.operation == Operators.NOT_EQUAL) { left, right ->
		if (!plan.checksLiveness) LLVMBuildICmp(builder, LLVMIntEQ, left, right, "raw_identity")
		else {
			val emitter = functions
			fun access(operand: Expression, value: LLVMValueRef): ObjectAccess {
				val symbol = (operand as? NameExpression)?.let { functionSemantics.declarations.names[it] } as? ReceiverSymbol
				val permission = symbol?.let(bindings::objectAccess)?.duringDestruction ?: emitter.classes.permission(false)
				return ObjectAccess(value, permission)
			}

			val a = access(expression.left, left)
			val b = access(expression.right, right)
			val overload = plan.identityOverride
			if (overload == null) emitter.compareClasses(builder, a, b, plan.operation) else {
				emitter.classes.check(builder, a)
				emitter.classes.check(builder, b)
				if (overload.receiverIndex == 0) emitter.compareWithOverride(builder, overload.call.callable, a, right)
				else emitter.compareWithOverride(builder, overload.call.callable, b, left)
			}
		}
	}

	private fun emitValueComparison(expression: BinaryExpression, plan: ValueComparisonPlan): LLVMValueRef {
		val unequal = plan.operation == Operators.NOT_EQUAL
		val overload = plan.overload
		return emitPresenceComparison(expression, unequal, snapshot = overload != null) { left, right ->
			if (overload == null) LLVMBuildICmp(builder, if (unequal) LLVMIntNE else LLVMIntEQ, left, right, "enum_case_comparison")
			else {
				val result = functions.compareValueWithOverride(builder, overload, left, right, ::stringReturnArena)
				if (plan.negate) LLVMBuildNot(builder, result, "value_not_equal") else result
			}
		}
	}

	private fun emitPresenceComparison(expression: BinaryExpression, unequal: Boolean, snapshot: Boolean = false, comparePresent: (LLVMValueRef, LLVMValueRef) -> LLVMValueRef): LLVMValueRef {
		// Both expressions are captured before inspecting presence, preserving effects.
		fun capture(operand: Expression): LLVMValueRef {
			val value = emitValue(operand)
			return if (snapshot) valueTransfer.snapshot(value, semantics.typeOf(operand)) else value
		}

		val left = capture(expression.left)
		val right = capture(expression.right)
		val leftType = semantics.typeOf(expression.left)
		val rightType = semantics.typeOf(expression.right)
		fun presence(value: LLVMValueRef, type: ValueType) = when (type) {
			is CNullable   -> nullable.present(value)
			ValueType.Null -> LLVMConstInt(boolType, 0, 0)
			else           -> LLVMConstInt(boolType, 1, 0)
		}

		val hasLeft = presence(left, leftType)
		val hasRight = presence(right, rightType)
		fun comparePresence() = LLVMBuildICmp(builder, if (unequal) LLVMIntNE else LLVMIntEQ, hasLeft, hasRight, "presence_comparison")
		if (expression.left is NullLiteral || expression.right is NullLiteral) return comparePresence()
		if (leftType !is CNullable && rightType !is CNullable) return comparePresent(left, right)
		return checkNotNull(nullable.choose(LLVMBuildAnd(builder, hasLeft, hasRight, "nullable_both_present"), boolType, {
			comparePresent(if (leftType is CNullable) nullable.payload(left) else left, if (rightType is CNullable) nullable.payload(right) else right)
		}, ::comparePresence))
	}

	private fun emitNullableEquality(expression: BinaryExpression, payloadType: ValueType): LLVMValueRef {
		val unequal = expression.operator.lexeme == Operators.NOT_EQUAL.toString()
		return emitPresenceComparison(expression, unequal) { a, b ->
			val equal = when (payloadType) {
				is CFloat          -> {
					val working = EmitHalf.arithmeticType(payloadType)
					LLVMBuildFCmp(builder, LLVMRealOEQ, casts.toFloating(a, payloadType, working), casts.toFloating(b, payloadType, working), "nullable_float_equal")
				}

				ValueType.TYPEINFO -> strings.metadata.equal(a, b)
				ValueType.STRING   -> strings.equal(a, b)
				else               -> if (payloadType is CInteger && payloadType.bits > 64) EmitWideInteger(context, builder, abi).compare(a, b, payloadType, Operators.EQUAL)
				else LLVMBuildICmp(builder, LLVMIntEQ, a, b, "nullable_payload_equal")
			}
			if (unequal) LLVMBuildNot(builder, equal, "nullable_not_equal") else equal
		}
	}

	fun emitConditional(expression: IfExpression, requireValue: Boolean): LLVMValueRef? {
		val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
		val merge = LLVMAppendBasicBlockInContext(context, function, "if_end")
		val values = mutableListOf<LLVMValueRef>()
		val ends = mutableListOf<LLVMBasicBlockRef>()

		fun emitBody(body: List<Statement>) {
			val value = bindings.withScope { emitStatements(body, preserveLastValue = requireValue) }
			if (!terminated()) {
				if (requireValue) {
					val adapted = adaptResults(checkNotNull(value), semantics.resultOf(body.last()), semantics.resultOf(expression))
					values.add(valueTransfer.snapshotResults(adapted, semantics.resultOf(expression)))
				}
				// Nested conditionals, casts, and strings can move the insertion block.
				ends.add(LLVMGetInsertBlock(builder))
				LLVMBuildBr(builder, merge)
			}
		}

		var handled = false
		for (branch in expression.branches) {
			val condition = emit(branch.condition)
			if (terminated()) {
				handled = true; break
			}
			when (functionSemantics.controlFlow.booleanValue(branch.condition)) {
				false -> continue
				true  -> {
					emitBody(branch.statements); handled = true; break
				}

				null  -> Unit
			}
			val body = LLVMAppendBasicBlockInContext(context, function, "if_then")
			val next = LLVMAppendBasicBlockInContext(context, function, "if_else")
			LLVMBuildCondBr(builder, checkNotNull(condition), body, next)
			LLVMPositionBuilderAtEnd(builder, body)
			emitBody(branch.statements)
			LLVMPositionBuilderAtEnd(builder, next)
		}
		if (!handled && !terminated()) {
			if (expression.otherwise != null) emitBody(expression.otherwise) else {
				ends.add(LLVMGetInsertBlock(builder))
				LLVMBuildBr(builder, merge)
			}
		}
		LLVMPositionBuilderAtEnd(builder, merge)
		if (ends.isEmpty()) {
			LLVMBuildUnreachable(builder); return null
		}
		if (!requireValue) return null

		val result = LLVMBuildPhi(builder, results.valueType(semantics.resultOf(expression)), "if_value")
		PointerPointer<LLVMValueRef>(values.size.toLong()).use { incomingValues ->
			PointerPointer<LLVMBasicBlockRef>(ends.size.toLong()).use { incomingBlocks ->
				values.forEachIndexed { index, value -> incomingValues.put(index.toLong(), value) }
				ends.forEachIndexed { index, block -> incomingBlocks.put(index.toLong(), block) }
				LLVMAddIncoming(result, incomingValues, incomingBlocks, values.size)
			}
		}
		return valueTransfer.freshResults(result, semantics.resultOf(expression))
	}

	private fun emitLogical(expression: BinaryExpression): LLVMValueRef {
		val conjunction = expression.operator.lexeme == Operators.AND.toString()
		val left = emitValue(expression.left)
		val known = functionSemantics.controlFlow.booleanValue(expression.left)
		if (known != null) {
			if (known != conjunction) return LLVMConstInt(boolType, if (known) 1 else 0, 0)
			return emitValue(expression.right)
		}
		// Nested expressions can move the builder; use their actual ending blocks.
		val leftBlock = LLVMGetInsertBlock(builder)
		val function = LLVMGetBasicBlockParent(leftBlock)
		val rightBlock = LLVMAppendBasicBlockInContext(context, function, "logic_rhs")
		val mergeBlock = LLVMAppendBasicBlockInContext(context, function, "logic_end")
		LLVMBuildCondBr(builder, left, if (conjunction) rightBlock else mergeBlock, if (conjunction) mergeBlock else rightBlock)

		LLVMPositionBuilderAtEnd(builder, rightBlock)
		val right = emit(expression.right)
		val rightEndBlock = LLVMGetInsertBlock(builder)
		val rightContinues = !terminated()
		if (rightContinues) LLVMBuildBr(builder, mergeBlock)

		LLVMPositionBuilderAtEnd(builder, mergeBlock)
		val result = LLVMBuildPhi(builder, boolType, "logic_value")
		val count = if (rightContinues) 2 else 1
		PointerPointer<LLVMValueRef>(count.toLong()).use { values ->
			PointerPointer<LLVMBasicBlockRef>(count.toLong()).use { blocks ->
				values.put(0, LLVMConstInt(boolType, if (conjunction) 0L else 1L, 0))
				blocks.put(0, leftBlock)
				if (rightContinues) {
					values.put(1, checkNotNull(right)); blocks.put(1, rightEndBlock)
				}
				LLVMAddIncoming(result, values, blocks, count)
			}
		}
		return result
	}
}
