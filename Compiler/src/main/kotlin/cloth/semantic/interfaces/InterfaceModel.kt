package cloth.semantic.interfaces

import cloth.data.CClass
import cloth.data.CInterface
import cloth.parser.declaration.InterfaceMethodDeclaration
import cloth.parser.declaration.MethodDeclaration
import cloth.semantic.ResultShape
import cloth.semantic.symbols.FunctionSignature

/** The source contract retains its owner for labels, argument defaults and any fallback body. */
class InterfaceRequirement internal constructor(
	val owner: CInterface,
	val declaration: InterfaceMethodDeclaration,
	val signature: FunctionSignature,
	val results: ResultShape,
)

class InterfaceContract internal constructor(
	val parents: List<CInterface>,
	val ancestors: Set<CInterface>,
	val methods: Map<FunctionSignature, InterfaceRequirement>,
	internal val mergedRequirements: Map<FunctionSignature, List<InterfaceRequirement>>,
)

sealed interface InterfaceImplementation { class ClassMethod internal constructor(
	val owner: CClass,
	val declaration: MethodDeclaration,
	val results: ResultShape,
) : InterfaceImplementation

	class Default internal constructor(val requirement: InterfaceRequirement) : InterfaceImplementation
}

/** The static interface and requirement select the slot. The class selects its body. */
class ClassInterfaceConformance internal constructor(
	val implementations: Map<CInterface, Map<InterfaceRequirement, InterfaceImplementation>>,
)

class InterfaceModel internal constructor(
	val contracts: Map<CInterface, InterfaceContract>,
	val classes: Map<CClass, ClassInterfaceConformance>,
) {

	internal companion object {

		val Empty = InterfaceModel(emptyMap(), emptyMap())
	}
}
