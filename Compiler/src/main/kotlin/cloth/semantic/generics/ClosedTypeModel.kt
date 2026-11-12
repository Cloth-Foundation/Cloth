package cloth.semantic.generics

import cloth.data.*
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.*
import cloth.parser.expression.CallSite
import cloth.parser.expression.NameExpression
import cloth.parser.type.TypeArgumentList
import cloth.parser.type.TypeParameterDeclaration
import cloth.parser.type.TypeReference
import cloth.semantic.ResultShape
import cloth.semantic.declarations.FileBindings
import cloth.semantic.declarations.VirtualMethods
import cloth.semantic.interfaces.InterfaceModel
import cloth.semantic.symbols.FunctionSignature
import cloth.semantic.symbols.FunctionSymbol
import cloth.semantic.symbols.MethodSymbol
import cloth.semantic.symbols.SignatureType
import cloth.target.TargetLayout
import cloth.token.TokenSpan
import java.util.*

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

enum class ClosedTypeState { DECLARED,
	RESOLVING,
	COMPLETE
}

internal class GenericBindings(
	val environment: Map<TypeParameterSymbol, ValueType> = emptyMap(),
	val chain: List<Pair<TokenSpan, String>> = emptyList(),
	val argumentDepth: Int = 0,
	val suppliedArguments: TypeArgumentSpans? = null,
	val defaultOrigin: TypeArgumentOrigin? = null,
	private val deferredArgument: ((TypeParameterSymbol) -> ValueType?)? = null,
) {

	var canonical = suppliedArguments == null
	fun argumentOf(parameter: TypeParameterSymbol): ValueType? = environment[parameter] ?: deferredArgument?.invoke(parameter)

	// Nominal capabilities of the exact type or its guaranteed nullable payload.
	val nominalBounds = IdentityHashMap<TypeParameterDeclaration, List<CMemberType>>()
	val constructorBounds = IdentityHashMap<TypeParameterSymbol, List<GenericBound.Constructor>>()
	val facts = IdentityHashMap<TypeReference, ClosedTypePattern>()
	val receivers = IdentityHashMap<NameExpression, ValueType>()
	val inferredResults = IdentityHashMap<CallSite, ResultShape>()
}

internal data class TypeArgumentOrigin(val span: TokenSpan, val caller: GenericBindings)

internal sealed interface ClosedCallableKey {

	val declaration: CallableDeclaration
	val arguments: List<ValueType>
	val owner: CMemberType? get() = null
}

internal data class ClosedFunctionKey(val template: FunctionSymbol, override val arguments: List<ValueType>) : ClosedCallableKey {

	override val declaration get() = template.declaration
}

internal data class GenericMethodSignature(val typeParameterCount: Int, val valueParameters: FunctionSignature)

internal data class GenericParameterPatterns(val types: List<ValueType>, val slots: List<CTypeParameter>)

internal class GenericMethodTemplate(
	val owner: CMemberType,
	val declaration: MethodDeclaration,
	val signature: GenericMethodSignature,
	slots: List<CTypeParameter>,
) {

	val symbol by lazy { MethodSymbol(declaration, owner) }
	val patterns = GenericParameterPatterns(signature.valueParameters.parameterTypes.map { checkNotNull(it.exact) }, slots)
}

internal data class ClosedMethodKey(val template: GenericMethodTemplate, override val arguments: List<ValueType>) : ClosedCallableKey {

	override val declaration get() = template.declaration
	override val owner get() = template.owner
}

internal data class GenericConstructorTemplate(val declaration: CallableDeclaration, val patterns: GenericParameterPatterns)

internal data class ClosedConstructorKey(
	val definition: CClass,
	val index: Int,
	override val declaration: CallableDeclaration,
	override val arguments: List<ValueType>,
) : ClosedCallableKey {

	val application get() = NominalApplicationKey(definition, arguments)
}

internal data class NominalApplicationKey(val definition: CMemberType, val arguments: List<ValueType>)

internal fun CMemberType.application(arguments: List<ValueType>): CMemberType = when (this) {
	is CClass     -> CClass(declaration, this, arguments)
	is CStruct    -> CStruct(declaration, genericDefinition = this, typeArguments = arguments)
	is CInterface -> CInterface(declaration, this, arguments)
	is CEnum      -> throw GenericErrors.arity(name, arguments.size, 0, name)
}

internal fun CMemberType.genericDefinition(): CMemberType = when (this) {
	is CClass     -> genericDefinition ?: this
	is CStruct    -> genericDefinition ?: this
	is CInterface -> definition
	is CEnum      -> this
}

internal data class TypeArgumentSpans(val span: TokenSpan, val arguments: List<TokenSpan>, val defaulted: Set<Int> = emptySet())

internal fun TypeArgumentList.sourceSpans(count: Int = arguments.size) = TypeArgumentSpans(span, List(count) { arguments.getOrNull(it)?.span ?: span }, (arguments.size until count).toSet())

internal data class GenericCallableRequest(val key: ClosedCallableKey, val supplied: TypeArgumentSpans, val caller: GenericBindings)

internal data class GenericCallableCandidate(val bindings: GenericBindings, val signature: ClosedCallableSignature)

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
class ClosedTypeModel internal constructor(val names: GenericNameModel, val target: TargetLayout, val symbolic: Boolean, private val files: FileBindings) {

	internal val catalog = linkedMapOf<CMemberType, ClosedTypeInstance>()
	internal val applications = linkedMapOf<NominalApplicationKey, ClosedTypeInstance>()
	internal val definitions by lazy {
		IdentityHashMap<TypeDeclaration, CMemberType>().apply { files.typeOrder.forEach { put(it.declaration, it) } }
	}
	internal val objectRoot = files.objectRoot
	internal val rootBindings = GenericBindings()
	internal val methodTemplates = mutableMapOf<CMemberType, Map<String, List<GenericMethodTemplate>>>()
	internal val constructorTemplates = IdentityHashMap<ClassDeclaration, List<GenericConstructorTemplate>>()
	internal val functionPatterns = IdentityHashMap<FunctionDeclaration, GenericParameterPatterns>()
	internal val callableInstances = linkedMapOf<ClosedCallableKey, GenericBindings>()
	internal val callableCandidates = linkedMapOf<ClosedCallableKey, GenericCallableCandidate>()
	internal val rejectedCallables = mutableMapOf<ClosedCallableKey, GenericBoundFailure>()
	internal val constructorProbeBindings = mutableMapOf<ClosedCallableKey, GenericBindings>()
	internal val completedTypeArguments = IdentityHashMap<Declaration, MutableMap<Pair<CMemberType?, List<ValueType?>>, List<ValueType>>>()
	internal val functionList = mutableListOf<ClosedCallableSignature>()
	var interfaces: InterfaceModel = InterfaceModel.Empty
		internal set
	internal var virtualMethods: VirtualMethods = VirtualMethods.Empty
	val instances: List<ClosedTypeInstance> get() = catalog.values.filter { it.arguments.isNotEmpty() }
	val nominalTypes: List<CMemberType> get() = catalog.keys.toList()
	val functions: List<ClosedCallableSignature> get() = functionList + callableCandidates.filterKeys { it is ClosedFunctionKey }.values.map { it.signature }
	fun instanceOf(type: CMemberType): ClosedTypeInstance = catalog.getValue(type)
	internal fun isCanonicalApplication(type: CMemberType): Boolean = type.typeArguments.isEmpty() || instanceOf(type).bindings.canonical
	internal fun hasConstructor(type: CClass, parameters: List<ValueType>): Boolean = catalog[type]?.signatureList?.any { signature ->
		(signature.declaration is PrimaryConstructorDeclaration || signature.declaration is ConstructorDeclaration) && signature.parameters.size == parameters.size && signature.parameters.indices.all { index ->
			(signature.parameters[index] as? ClosedTypePattern.Exact)?.type == parameters[index]
		}
	} == true

	internal fun candidateFailure(key: ClosedCallableKey, supplied: TypeArgumentSpans, caller: GenericBindings): GenericBoundFailure? {
		rejectedCallables[key]?.let { return it }
		val owner = if (key is ClosedConstructorKey) key.definition.declaration else key.declaration
		val callee = callableInstances[key] ?: callableCandidates[key]?.bindings ?: (key as? ClosedConstructorKey)?.let { applications[it.application]?.bindings } ?: constructorProbeBindings[key] ?: return null
		return constructorAccessFailure(owner, callee, supplied, caller)
	}

	internal fun constructorAccessFailure(declaration: Declaration, callee: GenericBindings, supplied: TypeArgumentSpans, caller: GenericBindings): GenericBoundFailure? {
		if (callee.constructorBounds.isEmpty()) return null
		for (parameter in names.parametersOf(declaration)) {
			val argument = callee.environment[parameter] as? CClass ?: continue
			val use = supplied.arguments[parameter.index]
			for (bound in callee.constructorBounds[parameter].orEmpty()) {
				val inheritedDefault = parameter.index in supplied.defaulted && defaultProvesConstructor(parameter, argument, bound, callee)
				if (!inheritedDefault && !constructorAccessible(argument, bound.parameterTypes, use, caller)) {
					return GenericBoundFailure.InaccessibleConstructor(parameter, argument, bound)
				}
			}
		}
		return null
	}

	private fun defaultProvesConstructor(parameter: TypeParameterSymbol, argument: CClass, bound: GenericBound.Constructor, callee: GenericBindings): Boolean {
		val source = parameter.declaration.defaultType?.let(names::nameOf) as? TypeParameterSymbol ?: return false
		return callee.environment[source] === argument && callee.constructorBounds[source].orEmpty().any { it.parameterTypes == bound.parameterTypes }
	}

	private fun constructorAccessible(argument: CClass, parameters: List<ValueType>, use: TokenSpan, caller: GenericBindings): Boolean {
		if (files.typeAccessibleAt(argument, use) || caller.constructorBounds.any { (parameter, bounds) ->
				caller.environment[parameter] === argument && bounds.any { it.parameterTypes == parameters }
			}) return true
		// A dependent default can refer to an earlier type argument from the application.
		val origin = caller.defaultOrigin ?: return false
		return argument in caller.environment.values && constructorAccessible(argument, parameters, origin.span, origin.caller)
	}

	fun patternOf(reference: TypeReference, owner: CMemberType? = null): ClosedTypePattern = owner?.let { catalog.getValue(it).bindings.facts[reference] } ?: rootBindings.facts.getValue(reference)
	fun typeOf(reference: TypeReference, owner: CMemberType? = null): ValueType = (patternOf(reference, owner) as ClosedTypePattern.Exact).type
	fun receiverTypeOf(expression: NameExpression, owner: CMemberType? = null): ValueType? = owner?.let { catalog.getValue(it).bindings.receivers[expression] } ?: rootBindings.receivers[expression]
	internal fun baseClauseOf(owner: CClass): ClassParentClause? = owner.declaration.parents.firstOrNull { typeOf(it.type, owner) === owner.base }
	internal fun requireImplemented() = names.requireImplemented()
}
