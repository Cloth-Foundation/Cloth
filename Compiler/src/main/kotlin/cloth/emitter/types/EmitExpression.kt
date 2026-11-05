package cloth.emitter.types

import cloth.builtin.BuiltinStorageContract
import cloth.data.*
import cloth.diagnostics.InternalErrors
import cloth.emitter.*
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.*
import cloth.semantic.symbols.LocalSymbol
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

internal class EmitExpression(private val context: LLVMContextRef, private val builder: LLVMBuilderRef, private val bindings: EmitBindings, private val semantics: SemanticModel, private val casts: EmitCast, private val strings: EmitString, private val power: EmitPower, private val abi: NativeCABI, private val functions: EmitFunctions? = null, private val functionSemantics: FunctionSemanticModel? = null, private val returnResults: ResultShape? = null, private val frame: EmitFunctionFrame? = null) {

	private val types = LLVMTypes(context, abi.layout)
	private val results = EmitResults(context, abi.layout)
	private val arrays = EmitArray(context, builder, abi)
	private val stored = EmitStoredValue(context, builder, abi.layout)
	private val nullable = EmitNullable(context, builder, abi.layout)
	private val boolType = types.value(ValueType.BOOL)
	private val statements by lazy { EmitStatement(this, bindings, semantics, functionSemantics) }
	private val temporaryAnalysis = ArrayTemporaryAnalysis(semantics, functionSemantics)
	private var temporaryDepth = 0

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

	// One-use ownership of recursively independent buffers in this physical frame.
	// Storing a value consumes its freshness; reading the binding never restores it.
	private val freshArrays = mutableSetOf<Long>()
	private val freshCarriers = mutableSetOf<Long>()
	private fun freshArray(value: LLVMValueRef, type: ValueType): LLVMValueRef {
		if (type is CArray) freshArrays.add(value.address())
		return value
	}

	private fun freshResults(value: LLVMValueRef, shape: ResultShape): LLVMValueRef {
		if (shape is ResultShape.Multiple) freshCarriers.add(value.address())
		else if (shape is ResultShape.Single) freshArray(value, shape.type)
		return value
	}

	/** Taking a carrier consumes its ownership proof. Each array field can then
	 * transfer once; extracting an old carrier again never creates fresh ownership.
	 */
	fun takeResults(value: LLVMValueRef, shape: ResultShape): List<LLVMValueRef> {
		val owned = shape is ResultShape.Multiple && freshCarriers.remove(value.address())
		return results.unpack(builder, value, shape).also { slots ->
			if (owned) slots.forEachIndexed { index, slot -> freshArray(slot, shape.values[index]) }
		}
	}

	fun snapshotValue(value: LLVMValueRef, type: ValueType): LLVMValueRef = when {
		type is CInteger || type is CNullable && type.payload is CInteger -> value
		type.isStructValue                                                -> checkNotNull(frame).copyValue(value, type)
		type is CNullable && type.buffered                                -> checkNotNull(frame).copyValue(value, type)
		type is CArray                                                    -> transferArrayValue(value, type)
		type == ValueType.STRING                                          -> checkNotNull(frame).copyValue(value, type)
		else                                                              -> value
	}

	private fun snapshotResults(value: LLVMValueRef, shape: ResultShape): LLVMValueRef {
		if (shape !is ResultShape.Multiple) return transferArrayValue(value, (shape as ResultShape.Single).type)
		val snapshots = takeResults(value, shape).mapIndexed { index, slot -> snapshotValue(slot, shape.values[index]) }
		return freshResults(results.pack(builder, snapshots, shape), shape)
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
			val services = ProviderEmissionContext(context, builder, strings, casts, location)
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
			val result = if (target.type == ValueType.VOID) null else transferArrayValue(adaptValue(checkNotNull(value), semantics.typeOf(checkNotNull(statement.value)), target.type), target.type)
			target.predecessors.add(LLVMGetInsertBlock(builder) to result)
			LLVMBuildBr(builder, target.exit)
			return
		}
		val shape = returnResults ?: throw InternalErrors.invariant("Return outside emitted function")
		checkNotNull(frame).emitReturn(if (shape == ResultShape.Empty) null else results.encode(builder, adaptResults(checkNotNull(value), semantics.resultOf(checkNotNull(statement.value)), shape), shape))
	}

	fun stringReturnArena(): LLVMValueRef = checkNotNull(frame).stringReturnArena()

	fun adaptValue(value: LLVMValueRef, source: ValueType, destination: ValueType): LLVMValueRef = if (source == destination || source is CArray && destination is CArray && destination.accepts(source)) value
	else nullable.adapt(value, source, destination)

	fun adaptResults(value: LLVMValueRef, source: ResultShape, destination: ResultShape): LLVMValueRef {
		if (source == destination) return value
		if (source.values.size != destination.values.size) throw InternalErrors.invariant("Validated result adaptation changed arity")
		val values = takeResults(value, source).mapIndexed { index, item -> adaptValue(item, source.values[index], destination.values[index]) }
		return freshResults(results.pack(builder, values, destination), destination)
	}

	private fun emitMemberCall(call: MemberCallExpression): LLVMValueRef? {
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
				else snapshotValue(value, type)
			}
			if (terminated()) return null
			val location = call.span.source?.let { "${it.name}:${call.span}" } ?: call.span.toString()
			val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
			val providers = ProviderEmissionContext(context, builder, strings, casts, location)
			return selected.emit(BuiltinEmissionContext(context, module, builder, abi, providers, strings, location), values)
		} finally {
			temporaryDepth--
		}
	}

	private fun emitCall(call: CallExpression): LLVMValueRef? = when (val selected = checkNotNull(functionSemantics).selectedCall(call)) {
		is ResolvedCall.Source  -> checkNotNull(functions).emitCall(call, this, builder)
		is ResolvedCall.Builtin -> {
			// Arguments and text providers may borrow buffers until the operation consumes them.
			temporaryDepth++
			try {
				val values = call.arguments.mapIndexed { index, expression ->
					transferArrayValue(emitValue(expression), selected.call.arguments[index].parameterType)
				}
				val location = call.span.source?.let { "${it.name}:${call.span}" } ?: call.span.toString()
				val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
				val providers = ProviderEmissionContext(context, builder, strings, casts, location)
				selected.call.emit(BuiltinEmissionContext(context, module, builder, abi, providers, strings, location), values)
			} finally {
				temporaryDepth--
			}
		}
	}

	/** Transfer an unaliased, frame-owned result once; otherwise take a recursive
	 * snapshot. Full shape metadata still checks every destination dimension.
	 */
	fun transferArrayValue(value: LLVMValueRef, type: ValueType): LLVMValueRef = transferArrayValue(value, type, null)

	private fun transferArrayValue(value: LLVMValueRef, type: ValueType, shape: LLVMValueRef?, message: String = "Array shape mismatch: rectangular arrays require matching dimensions"): LLVMValueRef {
		if (type.isStructValue) return checkNotNull(frame).copyValue(value, type)
		if (type is CNullable && type.buffered) return nullable.map(value, types.value(type)) { transferArrayValue(it, type.payload, shape, message) }
		if (type !is CArray) return value
		val fresh = freshArrays.remove(value.address())
		val checked = if (shape == null) value else arrays.matchShape(value, shape, type, merge = true, message = message)
		if (!fresh) return checkNotNull(frame).copyArray(checked, type)
		val constrained = arrays.constrain(checked, type)
		return if (type.hasNullableRows) arrays.share(constrained, type, arrays.shapePointer(constrained, type)) else constrained
	}

	fun transferBindingAssignment(key: Any, value: LLVMValueRef, type: ValueType): LLVMValueRef {
		if (type is CNullable && type.payload is CArray) return checkNotNull(nullable.choose(nullable.present(value), types.value(type), {
			val previous = bindings.nullableArrayShape(key, type)
			val payload = nullable.payload(value)
			val checked = checkNotNull(nullable.choose(nullable.present(previous), types.value(type.payload), {
				arrays.matchShape(payload, nullable.payload(previous), type.payload, merge = true, message = "Array shape mismatch: assignment must preserve the binding's initial shape")
			}, { arrays.constrain(payload, type.payload) }))
			val snapshot = transferArrayValue(checked, type.payload)
			val retained = if (!type.payload.hasNullableRows) snapshot else checkNotNull(nullable.choose(nullable.present(previous), types.value(type.payload), {
				arrays.share(snapshot, type.payload, arrays.shapePointer(nullable.payload(previous), type.payload))
			}, { snapshot }))
			val result = nullable.wrap(retained, type)
			bindings.saveNullableArrayShape(key, result, type)
			result
		}, { nullable.absent(type) }))
		val shape = if (type is CArray && (type.hasNullableRows || type.arrayDimensions.any { it == null })) bindings.initialArrayShape(key) else null
		val snapshot = transferArrayValue(value, type, shape, "Array shape mismatch: assignment must preserve the binding's initial shape")
		return if (type is CArray && type.hasNullableRows) arrays.share(snapshot, type, arrays.shapePointer(checkNotNull(shape), type)) else snapshot
	}

	/** Ignored positions still enforce explicit extents, without allocating storage. */
	fun validateDiscardedResult(value: LLVMValueRef, type: ValueType) {
		if (type is CNullable) {
			nullable.map(value, types.value(type)) { validateDiscardedResult(it, type.payload); it }; return
		}
		if (type is CArray) arrays.constrain(value, type)
	}

	fun emitElementAssignment(statement: ElementAssignmentStatement): LLVMValueRef? = try {
		// Resolve the complete target once, including each bounds check, before the RHS.
		val array = emitValue(statement.target.array)
		val index = emitValue(statement.target.index)
		val type = semantics.typeOf(statement.target)
		val parentType = semantics.typeOf(statement.target.array) as CArray
		val address = arrays.checkedAddress(array, type, index, semantics.typeOf(statement.target.index))
		val value = adaptValue(emitValue(statement.value), semantics.typeOf(statement.value), type)
		if (parentType.hasNullableRows) {
			val snapshot = arrays.shareChild(transferArrayValue(value, type), array, parentType)
			arrays.storeAt(address, type, snapshot)
			snapshot
		} else {
			val shape = if (type is CArray) {
				val previous = LLVMBuildLoad2(builder, types.storage(type), address, "replaced_array")
				arrays.childShape(previous, array, type)
			} else null
			val snapshot = transferArrayValue(value, type, shape)
			arrays.storeAt(address, type, snapshot)
			snapshot
		}
	} catch (_: Terminated) {
		null
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
			stored.store(stored.fieldAddress(address, type, field), snapshotValue(value, field.type), field.type)
		}
		return address
	}

	private fun emitFieldRead(expression: FieldAccessExpression): LLVMValueRef {
		val receiver = emitValue(expression.receiver)
		val field = semantics.fields.getValue(expression)
		val address = stored.fieldAddress(receiver, semantics.typeOf(expression.receiver) as CStruct, field)
		val value = stored.load(address, field.type)
		return if (semantics.payloadReads.containsKey(expression)) nullable.payload(value) else value
	}

	fun emitFieldWrite(statement: FieldWriteStatement): LLVMValueRef? = try {
		val plan = semantics.fieldWrites[statement]
		if (plan == null) {
			// A non-continuing index still executes before the unreachable store.
			emitValue(statement.target)
			throw InternalErrors.invariant("Continuing field write has no stored place")
		}
		var address = bindings.address(plan.root)
		var storageType = checkNotNull(functionSemantics).typeOf(plan.root)
		var parent: Pair<LLVMValueRef, CArray>? = null
		fun load(): LLVMValueRef {
			val raw = stored.load(address, storageType)
			return parent?.let { arrays.readChild(raw, it.first, it.second) } ?: raw
		}
		for (step in plan.steps) when (step) {
			is PlaceStep.Field -> {
				val wrapper = storageType as? CNullable
				if (wrapper != null) address = stored.payloadAddress(address, wrapper)
				address = stored.fieldAddress(address, (wrapper?.payload ?: storageType) as CStruct, step.field)
				storageType = step.field.type
				parent = null
			}

			is PlaceStep.Index -> {
				val raw = load()
				val array = if (storageType is CNullable) nullable.payload(raw) else raw
				val index = emitValue(step.index)
				address = arrays.checkedAddress(array, step.array.element, index, semantics.typeOf(step.index))
				storageType = step.array.element
				parent = array to step.array
			}
		}
		val op = Operators.fromCharacterRequired(statement.operator.lexeme)
		val source = statement.value
		val valueType = if (op == Operators.ASSIGN) semantics.typeOf(checkNotNull(source)) else semantics.typeOf(statement.target)
		val value = if (op == Operators.ASSIGN) emitValue(checkNotNull(source)) else {
			val raw = load()
			val old = if (storageType is CNullable) nullable.payload(raw) else raw
			if (source == null) emitNumericUpdate(old, valueType, op == Operators.PLUS_PLUS)
			else {
				val right = emitValue(source)
				val base = Token(statement.operator.type, statement.operator.span, op.compoundAssignmentBase().toString())
				emitBinaryOperands(base, old, right, valueType, semantics.typeOf(source))
			}
		}
		val prepared = snapshotValue(adaptValue(value, valueType, plan.type), plan.type)
		val snapshot = parent?.let { arrays.shareChild(prepared, it.first, it.second) } ?: prepared
		stored.store(address, snapshot, plan.type)
		snapshot
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
		if (target.predecessors.size == 1) return freshArray(checkNotNull(target.predecessors.single().second), type)
		val result = LLVMBuildPhi(builder, types.value(type), "block_value")
		PointerPointer<LLVMValueRef>(target.predecessors.size.toLong()).use { values ->
			PointerPointer<LLVMBasicBlockRef>(target.predecessors.size.toLong()).use { blocks ->
				target.predecessors.forEachIndexed { index, (end, value) ->
					values.put(index.toLong(), checkNotNull(value)); blocks.put(index.toLong(), end)
				}
				LLVMAddIncoming(result, values, blocks, target.predecessors.size)
			}
		}
		return freshArray(result, type)
	}

	fun emitUpdate(statement: UpdateStatement, type: ValueType): LLVMValueRef {
		val stored = bindings.read(functionSemantics?.declarations?.targetOf(statement) ?: statement.name.lexeme)
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
		val key = functionSemantics?.declarations?.symbolOf(statement) ?: statement.binding.name.lexeme
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
			EmitWhileLoop(context, builder, bindings, this, functionSemantics?.controlFlow ?: ControlFlowModel()).emit(statement)
		}
	}

	fun emitDoWhile(statement: DoWhileStatement) {
		temporaryRegion(temporaryAnalysis.canReclaimLoop(statement)) {
			EmitDoWhileLoop(context, builder, bindings, this, functionSemantics?.controlFlow ?: ControlFlowModel()).emit(statement)
		}
	}

	fun emitStepper(statement: ForStatement, action: () -> Unit) = temporaryRegion(temporaryAnalysis.canReclaimStepper(statement), action)

	/** Only scalar results may cross this checkpoint; wide value bytes stay inside. */
	fun scalarTemporary(action: () -> LLVMValueRef): LLVMValueRef {
		var result: LLVMValueRef? = null
		temporaryRegion(true) { result = action() }
		return checkNotNull(result)
	}

	/** Closed non-escape proof, independent of an enclosing expression's checkpoint.
	 * Return paths bypass these releases and retain the ordinary owning-frame path.
	 */
	private fun temporaryRegion(reclaim: Boolean, action: () -> Unit) {
		if (!reclaim) {
			action(); return
		}
		val checkpoint = checkNotNull(frame).temporaryCheckpoint()
		val stack = EmitStackScope(context, builder)
		val stackCheckpoint = stack.save()
		val previousFresh = freshArrays.toSet()
		val previousCarriers = freshCarriers.toSet()
		val release = { frame.releaseTemporaries(checkpoint); stack.restore(stackCheckpoint) }
		cleanupRegions.add(release)
		try {
			action()
			if (!terminated()) {
				release()
			}
		} finally {
			cleanupRegions.removeAt(cleanupRegions.lastIndex)
			freshArrays.retainAll(previousFresh)
			freshCarriers.retainAll(previousCarriers)
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
			val slots = takeResults(value, plan.results)
			for (slot in constrained) validateDiscardedResult(adaptValue(slots[slot.sourceIndex], plan.results.values[slot.sourceIndex], slot.type), slot.type)
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
		val checkpoint = checkNotNull(frame).temporaryCheckpoint()
		val previousFresh = freshArrays.toSet()
		val previousCarriers = freshCarriers.toSet()
		val stack = EmitStackScope(context, builder)
		val stackCheckpoint = stack.save()
		temporaryDepth++
		val release = { frame.releaseTemporaries(checkpoint); stack.restore(stackCheckpoint) }
		cleanupRegions.add(release)
		return try {
			// Receiving constraints consume descriptors before their buffers are released.
			val result = produce()
			// Loop jumps release crossed regions; returns use ordinary frame cleanup.
			if (!terminated()) release()
			freshArrays.retainAll(previousFresh)
			freshCarriers.retainAll(previousCarriers)
			result
		} finally {
			cleanupRegions.removeAt(cleanupRegions.lastIndex)
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
				val snapshots = expression.values.map { item -> snapshotValue(emitValue(item), semantics.typeOf(item)) }
				if (terminated()) null else freshResults(results.pack(builder, snapshots, semantics.resultOf(expression)), semantics.resultOf(expression))
			}

			is ProviderAccessExpression -> if (semantics.typeOf(expression) == ValueType.NEVER) emit(expression.receiver)
			else emitProvider(semantics.providerAccess(expression))

			is ArrayLiteral             -> {
				val type = semantics.typeOf(expression) as? CArray
				if (type == null) {
					expression.elements.forEach { emitValue(it) }; null
				} else arrays.literal(type.element, expression.elements.map { item -> { transferArrayValue(adaptValue(emitValue(item), semantics.typeOf(item), type.element), type.element) } })
			}

			is IndexExpression          -> {
				val array = emitValue(expression.array)
				val fresh = freshArrays.remove(array.address())
				val index = emitValue(expression.index)
				val value = arrays.index(array, semantics.typeOf(expression), index, semantics.typeOf(expression.index))
				if (fresh) freshArray(value, semantics.typeOf(expression)) else value
			}

			is IfExpression             -> emitConditional(expression, requireValue = semantics.resultOf(expression) !in setOf(ResultShape.Empty, ResultShape.Never))
			is TypeCheckExpression      -> {
				// Preserve evaluation (and any side effects) even for a statically known check.
				emitValue(expression.operand)
				val matches = semantics.typeOf(expression.operand) == semantics.targetTypeOf(expression)
				LLVMConstInt(boolType, if (matches) 1L else 0L, 0)
			}

			is NameExpression           -> {
				val symbol = functionSemantics?.declarations?.symbolOf(expression)
				val block = (symbol as? LocalSymbol)?.declaration?.initializer as? FunctionBlockExpression
				if (block != null) emitBlock(block) else {
					val stored = bindings.read(symbol ?: expression.name.lexeme)
					if (semantics.payloadReads.containsKey(expression)) nullable.payload(stored) else stored
				}
			}

			is MemberCallExpression     -> emitMemberCall(expression)?.let { freshResults(it, semantics.resultOf(expression)) }
			is CallExpression           -> emitCall(expression)?.let { freshResults(it, semantics.resultOf(expression)) }
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
					val providers = ProviderEmissionContext(context, builder, strings, casts, part.span.toString())
					fun text(value: LLVMValueRef, type: ValueType): LLVMValueRef = checkNotNull(type.provider("str", abi.layout)).emit(providers, ProviderReceiver.Value(type, value))
					val value = when (type) {
						ValueType.Null -> strings.literal(Keywords.NULL.toString())
						is CNullable   -> checkNotNull(nullable.choose(nullable.present(raw), types.value(ValueType.STRING), { text(nullable.payload(raw), type.payload) }, { strings.literal(Keywords.NULL.toString()) }))
						else           -> text(raw, type)
					}
					result = result?.let { strings.concatenate(it, value, part.span.toString()) } ?: value
				}
				result ?: strings.literal("")
			}

			is CastExpression           -> if (semantics.typeOf(expression) == ValueType.VOID) {
				emit(expression.operand); null
			} else casts.emit(expression, emitValue(expression.operand))

			is UnaryExpression          -> {
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

	private fun emitNullableEquality(expression: BinaryExpression, payloadType: ValueType): LLVMValueRef {
		// Both expressions are captured before inspecting presence, preserving effects.
		val left = emitValue(expression.left)
		val right = emitValue(expression.right)
		val leftType = semantics.typeOf(expression.left)
		val rightType = semantics.typeOf(expression.right)
		fun presence(value: LLVMValueRef, type: ValueType) = when (type) {
			is CNullable   -> nullable.present(value)
			ValueType.Null -> LLVMConstInt(boolType, 0, 0)
			else           -> LLVMConstInt(boolType, 1, 0)
		}

		fun payload(value: LLVMValueRef, type: ValueType) = when (type) {
			is CNullable   -> nullable.payload(value)
			ValueType.Null -> LLVMConstNull(types.value(payloadType))
			else           -> value
		}

		val hasLeft = presence(left, leftType)
		val hasRight = presence(right, rightType)
		if (expression.left is NullLiteral || expression.right is NullLiteral) {
			return LLVMBuildICmp(builder, if (expression.operator.lexeme == Operators.NOT_EQUAL.toString()) LLVMIntNE else LLVMIntEQ, hasLeft, hasRight, "nullable_null_test")
		}
		val equal = checkNotNull(nullable.choose(LLVMBuildAnd(builder, hasLeft, hasRight, "nullable_both_present"), boolType, {
			val a = payload(left, leftType)
			val b = payload(right, rightType)
			when (payloadType) {
				is CFloat          -> {
					val working = EmitHalf.arithmeticType(payloadType)
					LLVMBuildFCmp(builder, LLVMRealOEQ, casts.toFloating(a, payloadType, working), casts.toFloating(b, payloadType, working), "nullable_float_equal")
				}

				ValueType.TYPEINFO -> strings.metadata.equal(a, b)
				ValueType.STRING   -> strings.equal(a, b)
				else               -> if (payloadType is CInteger && payloadType.bits > 64) EmitWideInteger(context, builder, abi).compare(a, b, payloadType, Operators.EQUAL)
				else LLVMBuildICmp(builder, LLVMIntEQ, a, b, "nullable_payload_equal")
			}
		}, { LLVMBuildICmp(builder, LLVMIntEQ, hasLeft, hasRight, "nullable_absence_equal") }))
		return if (expression.operator.lexeme == Operators.NOT_EQUAL.toString()) LLVMBuildNot(builder, equal, "nullable_not_equal") else equal
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
					values.add(snapshotResults(adapted, semantics.resultOf(expression)))
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
			when (functionSemantics?.controlFlow?.booleanValue(branch.condition)) {
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
		return freshResults(result, semantics.resultOf(expression))
	}

	private fun emitLogical(expression: BinaryExpression): LLVMValueRef {
		val conjunction = expression.operator.lexeme == Operators.AND.toString()
		val left = emitValue(expression.left)
		val known = functionSemantics?.controlFlow?.booleanValue(expression.left)
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
