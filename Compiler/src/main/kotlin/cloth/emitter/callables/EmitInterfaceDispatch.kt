package cloth.emitter.callables

import cloth.data.CInterface
import cloth.parser.declaration.MethodDeclaration
import cloth.semantic.declarations.DeclarationModel
import cloth.semantic.interfaces.InterfaceImplementation
import cloth.semantic.symbols.InterfaceMethodSymbol
import cloth.semantic.typing.ResolvedCall
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMModuleRef

/** A private entry per static interface view and specialized contract. */
internal class EmitInterfaceDispatch(
	private val context: LLVMContextRef,
	private val module: LLVMModuleRef,
	private val declarations: DeclarationModel,
	private val results: EmitResults,
	private val functions: EmitFunctions,
) {

	private val slots = mutableMapOf<Pair<CInterface, InterfaceMethodSymbol>, EmittedCallable>()

	fun entry(plan: ResolvedCall.Interface): EmittedCallable = slots.getOrPut(plan.owner to plan.contract) {
		val name = "cloth.interface.dispatch.${slots.size}.${plan.contract.name.lexeme}"
		EmittedCallable(context, module, name, plan.contract, plan.type, hasClasses = true, results = results) { functions.classes }.also { entry -> functions.deferDispatch { emitEntry(plan, entry) } }
	}

	private fun emitEntry(plan: ResolvedCall.Interface, entry: EmittedCallable) {
		val views = checkNotNull(declarations.genericViews)
		val targets = declarations.interfaces.dispatchTargets(plan.owner, plan.contract.requirement).map { (owner, implementation) ->
			val symbol = views.interfaceTarget(plan.contract, implementation) ?: when (implementation) {
				is InterfaceImplementation.ClassMethod -> {
					val view = views.viewOf(implementation.declaration, implementation.owner) as MethodDeclaration
					declarations.symbolOf(view)
				}

				is InterfaceImplementation.Default     -> declarations.interfaceMethods.getValue(implementation.requirement)
			}
			owner to functions.callable(symbol)
		}
		functions.emitClassDispatch(entry, plan.contract, targets, "Initialized class does not implement the interface")
	}
}
