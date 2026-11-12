package cloth.emitter.callables

import cloth.data.CClass
import cloth.diagnostics.InternalErrors
import cloth.emitter.NativeCABI
import cloth.emitter.types.EmitRuntimeError
import cloth.semantic.declarations.StateCatalog
import cloth.semantic.symbols.CallableContractSymbol
import cloth.semantic.typing.ResolvedStateCall
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.global.LLVM.*

/** Each entry resolves a shared contract before reading the object's current activation. */
internal class EmitStateDispatch(
	private val context: LLVMContextRef,
	private val module: LLVMModuleRef,
	private val abi: NativeCABI,
	private val catalog: StateCatalog,
	private val results: EmitResults,
	private val functions: EmitFunctions,
) {

	private val entries = mutableMapOf<Pair<CClass, CallableContractSymbol>, EmittedCallable>()

	fun entry(plan: ResolvedStateCall): EmittedCallable = entries.getOrPut(plan.owner to plan.contract) {
		val name = "cloth.state.dispatch.${entries.size}.${plan.contract.name.lexeme}"
		EmittedCallable(context, module, name, plan.contract, plan.type, hasClasses = true, results = results) { functions.classes }.also { entry -> functions.deferDispatch { emitEntry(plan, entry) } }
	}

	private fun emitEntry(plan: ResolvedStateCall, entry: EmittedCallable) {
		val targets = catalog.definitions.mapNotNull { state ->
			if (!plan.owner.accepts(state.owner) && !state.owner.accepts(plan.owner)) return@mapNotNull null
			val method = state.methods[plan.contract.signature] ?: return@mapNotNull null
			val body = functions.callable(method)
			if (body.type != plan.type) throw InternalErrors.invariant("State dispatch target differs from its shared contract")
			state to body
		}
		val builder = LLVMCreateBuilderInContext(context)
		try {
			fun block(name: String) = LLVMAppendBasicBlockInContext(context, entry.reference, name)
			val start = block("entry")
			LLVMPositionBuilderAtEnd(builder, start)
			val access = checkNotNull(entry.objectAccess())
			val frame = EmitFunctionFrame(context, builder, start, plan.type.results, LLVMGetReturnType(entry.signature), functions.stringStorage, entry.callerArena)
			val arguments = if (entry.hasOmissions) functions.bindDispatchArguments(plan.contract, entry, builder, start, frame)
			else plan.type.parameters.indices.map { entry.parameter(builder, it) }
			val states = functions.states
			val activation = states.current(builder, access)
			val select = block("select_state")
			val invalid = block("state_unavailable")
			LLVMBuildCondBr(builder, LLVMBuildIsNotNull(builder, activation, "has_active_state"), select, invalid)
			LLVMPositionBuilderAtEnd(builder, select)
			val dispatch = LLVMBuildSwitch(builder, states.tagOf(builder, activation), invalid, targets.size)
			for ((state, body) in targets) {
				val branch = block("implement.${state.name.lexeme}")
				LLVMAddCase(dispatch, states.tagOf(state), branch)
				LLVMPositionBuilderAtEnd(builder, branch)
				states.retain(builder, activation)
				val result = body.call(builder, returnArena = { checkNotNull(entry.callerArena) }) {
					setReceiver(access)
					setActivation(activation)
					arguments.forEachIndexed(::argument)
				}
				states.release(builder, activation)
				frame.emitReturn(result?.let { results.encode(builder, it, plan.type.results) })
			}
			LLVMPositionBuilderAtEnd(builder, invalid)
			EmitRuntimeError(context, builder, abi).emit("Invalid state for '${plan.contract.signature.spelling}'")
			frame.finish(resultsInCallerArena = true)
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}
}
