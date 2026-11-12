package cloth.semantic.interfaces

import cloth.data.CClass
import cloth.data.CInterface
import cloth.data.CTypeParameter
import cloth.data.ValueType
import cloth.parser.declaration.InterfaceMethodDeclaration
import cloth.parser.declaration.MethodDeclaration
import cloth.semantic.ResultShape
import cloth.semantic.generics.*
import cloth.semantic.symbols.FunctionSignature

/** The source contract retains its owner for labels, argument defaults and any fallback body. */
class InterfaceRequirement internal constructor(
	val owner: CInterface,
	val declaration: InterfaceMethodDeclaration,
	internal val key: GenericMethodSignature,
	val results: ResultShape,
	internal val bounds: List<Set<GenericBoundSignature>> = emptyList(),
	slots: List<CTypeParameter> = emptyList(),
) {

	val signature: FunctionSignature get() = key.valueParameters
	internal val patterns = GenericParameterPatterns(signature.parameterTypes.map { checkNotNull(it.exact) }, slots)
}

class InterfaceContract internal constructor(
	val parents: List<CInterface>,
	val ancestors: Set<CInterface>,
	internal val requirements: Map<GenericMethodSignature, InterfaceRequirement>,
	internal val mergedRequirements: Map<GenericMethodSignature, List<InterfaceRequirement>>,
) {

	val methods: Map<FunctionSignature, InterfaceRequirement> = requirements.values.filter { it.key.typeParameterCount == 0 }.associateBy { it.signature }
}

sealed interface InterfaceImplementation { class ClassMethod internal constructor(
	val owner: CClass,
	val declaration: MethodDeclaration,
	val results: ResultShape,
	internal val template: GenericMethodTemplate? = null,
) : InterfaceImplementation

	class Default internal constructor(val requirement: InterfaceRequirement) : InterfaceImplementation
}

internal fun InterfaceImplementation.specialize(arguments: List<ValueType>): ClosedCallableKey = when (this) {
	is InterfaceImplementation.ClassMethod -> ClosedMethodKey(checkNotNull(template), arguments)
	is InterfaceImplementation.Default     -> ClosedInterfaceMethodKey(requirement.owner, requirement.declaration, arguments)
}

/** The static interface and requirement select the slot. The class selects its body. */
class ClassInterfaceConformance internal constructor(
	val implementations: Map<CInterface, Map<InterfaceRequirement, InterfaceImplementation>>,
)

class InterfaceModel internal constructor(
	val contracts: Map<CInterface, InterfaceContract>,
	val classes: Map<CClass, ClassInterfaceConformance>,
	internal val genericOverrides: Map<GenericMethodTemplate, InterfaceRequirement> = emptyMap(),
) {

	internal fun requirementOf(owner: CInterface, declaration: InterfaceMethodDeclaration): InterfaceRequirement = contracts.getValue(owner).requirements.values.single { it.declaration === declaration }

	internal fun dispatchTargets(view: CInterface, requirement: InterfaceRequirement): List<Pair<CClass, InterfaceImplementation>> = classes.mapNotNull { (owner, conformance) -> conformance.implementations[view]?.get(requirement)?.let { owner to it } }

	internal companion object {

		val Empty = InterfaceModel(emptyMap(), emptyMap())
	}
}
