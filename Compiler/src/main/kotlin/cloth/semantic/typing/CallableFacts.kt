package cloth.semantic.typing

import cloth.data.ValueType
import cloth.semantic.LibraryIntrinsic
import cloth.semantic.symbols.CallableContractSymbol
import cloth.semantic.symbols.MethodSymbol

/** Contracts fixed before body checking and shared by every trial. */
internal class CallableContracts(
	val signatures: Map<CallableContractSymbol, FunctionType>,
	val contextSelectors: Map<MethodSymbol, ValueType>,
	val intrinsics: Map<CallableContractSymbol, LibraryIntrinsic>,
)

/** Signatures and bodies discovered while checking one branch of analysis. */
internal class CallableFacts {

	private data class ContextKey(val template: MethodSymbol, val width: Int)
	private data class Specialization(val signature: FunctionType, val body: FunctionSemanticModel? = null)

	private val facts = FactCopies<CallableFacts>()
	private val inferred: MutableMap<MethodSymbol, FunctionType> = facts.identityMap { it.inferred }
	private val contexts: MutableMap<ContextKey, MethodSymbol> = facts.orderedMap { it.contexts }
	private val specializations: MutableMap<MethodSymbol, Specialization> = facts.orderedMap { it.specializations }

	fun signature(method: MethodSymbol): FunctionType? = specializations[method]?.signature ?: inferred[method]
	fun recordInferred(method: MethodSymbol, signature: FunctionType) {
		inferred[method] = signature
	}

	fun specialization(template: MethodSymbol, width: Int): MethodSymbol? = contexts[ContextKey(template, width)]

	fun reserve(template: MethodSymbol, width: Int, signature: FunctionType): MethodSymbol {
		val method = template.specialization()
		contexts[ContextKey(template, width)] = method
		specializations[method] = Specialization(signature)
		return method
	}

	fun complete(method: MethodSymbol, body: FunctionSemanticModel) {
		specializations[method] = specializations.getValue(method).copy(body = body)
	}

	fun bodyOf(method: MethodSymbol): FunctionSemanticModel? = specializations[method]?.body
	fun publishedMethods(): List<MethodSymbol> = specializations.entries.mapNotNull { (method, value) -> method.takeIf { value.body != null } }
	fun copyFrom(source: CallableFacts) = facts.copyFrom(source)
}
