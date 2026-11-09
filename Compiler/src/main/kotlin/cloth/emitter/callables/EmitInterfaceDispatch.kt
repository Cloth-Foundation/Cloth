package cloth.emitter.callables

import cloth.data.CClass
import cloth.data.CInterface
import cloth.diagnostics.InternalErrors
import cloth.emitter.NativeCABI
import cloth.emitter.types.EmitRuntimeError
import cloth.parser.declaration.MethodDeclaration
import cloth.semantic.declarations.DeclarationModel
import cloth.semantic.interfaces.InterfaceImplementation
import cloth.semantic.interfaces.InterfaceModel
import cloth.semantic.interfaces.InterfaceRequirement
import cloth.semantic.typing.ResolvedCall
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

internal fun InterfaceModel.dispatchTargets(plan: ResolvedCall.Interface): List<Pair<CClass, InterfaceImplementation>> =
	classes.mapNotNull { (owner, conformance) ->
		conformance.implementations[plan.owner]?.get(plan.contract.requirement)?.let { owner to it }
	}

/** A private entry per static interface slot, shared by every call site. */
internal class EmitInterfaceDispatch(
	private val context: LLVMContextRef,
	private val module: LLVMModuleRef,
	private val abi: NativeCABI,
	private val declarations: DeclarationModel,
	private val results: EmitResults,
	private val functions: EmitFunctions,
	private val tagOf: (CClass) -> LLVMValueRef,
) {
	private val slots = mutableMapOf<Pair<CInterface, InterfaceRequirement>, EmittedCallable>()
	private val pending = ArrayDeque<Pair<ResolvedCall.Interface, EmittedCallable>>()

	fun entry(plan: ResolvedCall.Interface): EmittedCallable = slots.getOrPut(plan.owner to plan.contract.requirement) {
		val name = "cloth.interface.dispatch.${slots.size}.${plan.contract.name.lexeme}"
		EmittedCallable(context, module, name, plan.contract, plan.type, hasClasses = true, results = results) { functions.classes }
			.also { pending.addLast(plan to it) }
	}

	fun emitPending() {
		// Defaults may use the same slot or add further slots to the worklist.
		while (pending.isNotEmpty()) {
			val (plan, entry) = pending.removeFirst()
			emitEntry(plan, entry)
		}
	}

	private fun emitEntry(plan: ResolvedCall.Interface, entry: EmittedCallable) {
		val views = checkNotNull(declarations.genericViews)
		val targets = declarations.interfaces.dispatchTargets(plan).map { (owner, implementation) ->
			val symbol = when (implementation) {
				is InterfaceImplementation.ClassMethod -> {
					val view = views.viewOf(implementation.declaration, implementation.owner) as MethodDeclaration
					declarations.symbolOf(view)
				}
				is InterfaceImplementation.Default -> declarations.interfaceMethods.getValue(implementation.requirement)
			}
			val target = functions.callable(symbol)
			if (target.type != plan.type) throw InternalErrors.invariant("Interface dispatch target differs from its checked signature")
			owner to target
		}
		val builder = LLVMCreateBuilderInContext(context)
		try {
			fun block(name: String) = LLVMAppendBasicBlockInContext(context, entry.reference, name)
			val start = block("entry")
			LLVMPositionBuilderAtEnd(builder, start)
			val access = checkNotNull(entry.objectAccess())
			val classes = functions.classes
			val frame = EmitFunctionFrame(context, builder, start, plan.type.results, LLVMGetReturnType(entry.signature), functions.stringStorage, entry.callerArena)
			val arguments = if (entry.hasOmissions) {
				classes.check(builder, access) // Supplied arguments may have invalidated the receiver.
				functions.bindInterfaceArguments(plan.contract, entry, builder, start, frame)
			} else plan.type.parameters.indices.map { entry.parameter(builder, it) }
			// Omission expressions can also invalidate the receiver. Resolve it again before selecting a body.
			val tag = classes.classTag(builder, access)
			val invalid = block("interface_unavailable")
			val dispatch = LLVMBuildSwitch(builder, tag, invalid, targets.size)
			for ((owner, target) in targets) {
				val branch = block("implement.${owner.name.lexeme}")
				LLVMAddCase(dispatch, tagOf(owner), branch)
				LLVMPositionBuilderAtEnd(builder, branch)
				val result = target.call(builder, returnArena = { checkNotNull(entry.callerArena) }) {
					setReceiver(access)
					arguments.forEachIndexed(::argument)
				}
				frame.emitReturn(result?.let { results.encode(builder, it, plan.type.results) })
			}
			LLVMPositionBuilderAtEnd(builder, invalid)
			EmitRuntimeError(context, builder, abi).emit("Initialized class does not implement the interface")
			// The selected body has already copied indirect results into the original caller's arena.
			frame.finish(resultsInCallerArena = true)
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}
}
