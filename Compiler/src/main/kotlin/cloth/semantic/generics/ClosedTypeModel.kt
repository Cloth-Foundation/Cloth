package cloth.semantic.generics

import cloth.data.CMemberType
import cloth.data.ValueType
import cloth.parser.declaration.CallableDeclaration
import cloth.parser.expression.NameExpression
import cloth.parser.type.TypeReference
import cloth.target.TargetLayout
import cloth.token.TokenSpan
import java.util.IdentityHashMap

/** Existing operator-width templates remain patterns until a call supplies a width. */
sealed interface ClosedTypePattern {
	data class Exact(val type: ValueType) : ClosedTypePattern
	data class OperatorWidth(val annotation: TypeReference) : ClosedTypePattern
}

data class ClosedCallableSignature(
	val declaration: CallableDeclaration,
	val parameters: List<ClosedTypePattern>,
	val results: List<ClosedTypePattern>,
	val selector: ClosedTypePattern? = null,
)

enum class ClosedTypeState { DECLARED, RESOLVING, COMPLETE }

/** A closed owner has its own facts; its template AST is never rewritten. */
class ClosedTypeInstance internal constructor(
	val type: CMemberType,
	val definition: CMemberType,
	val arguments: List<ValueType>,
	internal val environment: Map<TypeParameterSymbol, ValueType>,
	internal val chain: List<Pair<TokenSpan, String>>,
	internal val argumentDepth: Int,
) {
	var state: ClosedTypeState = ClosedTypeState.DECLARED
		internal set
	internal val facts = IdentityHashMap<TypeReference, ClosedTypePattern>()
	internal val receivers = IdentityHashMap<NameExpression, ValueType>()
	internal val signatureList = mutableListOf<ClosedCallableSignature>()
	val signatures: List<ClosedCallableSignature> get() = signatureList.toList()
}

/** Target-scoped semantic types and declaration signatures, without callable body facts. */
class ClosedTypeModel internal constructor(val names: GenericNameModel, val target: TargetLayout, val symbolic: Boolean = false) {
	internal val catalog = linkedMapOf<CMemberType, ClosedTypeInstance>()
	internal val roots = IdentityHashMap<TypeReference, ClosedTypePattern>()
	internal val receivers = IdentityHashMap<NameExpression, ValueType>()
	internal val functionList = mutableListOf<ClosedCallableSignature>()
	val instances: List<ClosedTypeInstance> get() = catalog.values.filter { it.arguments.isNotEmpty() }
	val nominalTypes: List<CMemberType> get() = catalog.keys.toList()
	val functions: List<ClosedCallableSignature> get() = functionList.toList()
	fun instanceOf(type: CMemberType): ClosedTypeInstance = catalog.getValue(type)
	fun patternOf(reference: TypeReference, owner: CMemberType? = null): ClosedTypePattern = owner?.let { catalog.getValue(it).facts[reference] } ?: roots.getValue(reference)
	fun typeOf(reference: TypeReference, owner: CMemberType? = null): ValueType = (patternOf(reference, owner) as ClosedTypePattern.Exact).type
	fun receiverTypeOf(expression: NameExpression, owner: CMemberType? = null): ValueType? = owner?.let { catalog.getValue(it).receivers[expression] } ?: receivers[expression]
	internal fun requireImplemented() = names.requireImplemented()
}
