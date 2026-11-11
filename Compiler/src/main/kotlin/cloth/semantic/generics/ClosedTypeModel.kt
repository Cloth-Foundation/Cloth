package cloth.semantic.generics

import cloth.data.CClass
import cloth.data.CMemberType
import cloth.data.ValueType
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.CallableSignature
import cloth.parser.declaration.ClassParentClause
import cloth.parser.expression.NameExpression
import cloth.parser.type.TypeArgumentList
import cloth.parser.type.TypeParameterDeclaration
import cloth.parser.type.TypeReference
import cloth.semantic.ResultShape
import cloth.semantic.declarations.VirtualMethods
import cloth.semantic.interfaces.InterfaceModel
import cloth.semantic.symbols.FunctionSymbol
import cloth.semantic.symbols.FunctionSignature
import cloth.semantic.symbols.SignatureType
import cloth.target.TargetLayout
import cloth.token.TokenSpan
import java.util.IdentityHashMap

/** Existing operator-width templates remain patterns until a call supplies a width. */
sealed interface ClosedTypePattern {
	data class Exact(val type: ValueType) : ClosedTypePattern
	data class OperatorWidth(val annotation: TypeReference) : ClosedTypePattern
}

data class ClosedCallableSignature(
	val declaration: CallableSignature,
	val parameters: List<ClosedTypePattern>,
	val results: List<ClosedTypePattern>,
	val selector: ClosedTypePattern? = null,
)

internal fun ClosedCallableSignature.fixedSignature() = FunctionSignature(declaration.name.lexeme, parameters.mapIndexed { index, pattern ->
	val type = (pattern as ClosedTypePattern.Exact).type
	if (type == ValueType.VOID) throw SemanticErrors.voidType(declaration.parameters[index].type.span)
	SignatureType.resolved(type)
})

internal fun ClosedCallableSignature.fixedResults() = ResultShape.of(results.mapIndexed { index, pattern ->
	val type = (pattern as ClosedTypePattern.Exact).type
	if (results.size > 1 && type == ValueType.VOID) throw SemanticErrors.voidResult(declaration.returnTypes[index].span)
	type
})

enum class ClosedTypeState { DECLARED, RESOLVING, COMPLETE }

internal class GenericBindings(
	val environment: Map<TypeParameterSymbol, ValueType> = emptyMap(),
	val chain: List<Pair<TokenSpan, String>> = emptyList(),
	val argumentDepth: Int = 0,
	val suppliedArguments: TypeArgumentList? = null,
) {
	val nominalBounds = IdentityHashMap<TypeParameterDeclaration, CMemberType>()
	val facts = IdentityHashMap<TypeReference, ClosedTypePattern>()
	val receivers = IdentityHashMap<NameExpression, ValueType>()
}

internal data class ClosedFunctionKey(val template: FunctionSymbol, val arguments: List<ValueType>)

/** A closed owner has its own facts; its template AST is never rewritten. */
class ClosedTypeInstance internal constructor(
	val type: CMemberType,
	val definition: CMemberType,
	val arguments: List<ValueType>,
	internal val bindings: GenericBindings,
) {
	var state: ClosedTypeState = ClosedTypeState.DECLARED
		internal set
	internal val signatureList = mutableListOf<ClosedCallableSignature>()
	val signatures: List<ClosedCallableSignature> get() = signatureList.toList()
}

/** Target-scoped semantic types and declaration signatures, without callable body facts. */
class ClosedTypeModel internal constructor(val names: GenericNameModel, val target: TargetLayout, val symbolic: Boolean = false) {
	internal val catalog = linkedMapOf<CMemberType, ClosedTypeInstance>()
	internal val rootBindings = GenericBindings()
	internal val functionInstances = linkedMapOf<ClosedFunctionKey, GenericBindings>()
	internal val functionList = mutableListOf<ClosedCallableSignature>()
	var interfaces: InterfaceModel = InterfaceModel.Empty
		internal set
	internal var virtualMethods: VirtualMethods = VirtualMethods.Empty
	val instances: List<ClosedTypeInstance> get() = catalog.values.filter { it.arguments.isNotEmpty() }
	val nominalTypes: List<CMemberType> get() = catalog.keys.toList()
	val functions: List<ClosedCallableSignature> get() = functionList.toList()
	fun instanceOf(type: CMemberType): ClosedTypeInstance = catalog.getValue(type)
	fun patternOf(reference: TypeReference, owner: CMemberType? = null): ClosedTypePattern = owner?.let { catalog.getValue(it).bindings.facts[reference] } ?: rootBindings.facts.getValue(reference)
	fun typeOf(reference: TypeReference, owner: CMemberType? = null): ValueType = (patternOf(reference, owner) as ClosedTypePattern.Exact).type
	fun receiverTypeOf(expression: NameExpression, owner: CMemberType? = null): ValueType? = owner?.let { catalog.getValue(it).bindings.receivers[expression] } ?: rootBindings.receivers[expression]
	internal fun baseClauseOf(owner: CClass): ClassParentClause? =
		owner.declaration.parents.firstOrNull { typeOf(it.type, owner) === owner.base }
	internal fun requireImplemented() = names.requireImplemented()
}
