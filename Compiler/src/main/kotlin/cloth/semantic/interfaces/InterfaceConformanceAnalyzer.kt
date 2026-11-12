package cloth.semantic.interfaces

import cloth.data.CClass
import cloth.data.CInterface
import cloth.data.CMemberType
import cloth.diagnostics.InterfaceErrors
import cloth.diagnostics.SemanticErrors
import cloth.diagnostics.SourceLabel
import cloth.parser.declaration.InterfaceMethodDeclaration
import cloth.parser.declaration.MethodDeclaration
import cloth.parser.type.TypeReference
import cloth.semantic.generics.*
import cloth.semantic.interfaces.InterfaceImplementation.ClassMethod
import cloth.semantic.interfaces.InterfaceImplementation.Default
import cloth.token.Token
import cloth.token.TokenSpan

/** Check the original parent edges before substitution can expand a generic cycle. */
internal fun interfaceParentOrder(definitions: List<CInterface>, names: GenericNameModel): Map<CInterface, Int> {
	class Frame(val owner: CInterface, val via: TokenSpan?, val parents: Iterator<TypeReference> = owner.declaration.parents.iterator())

	val complete = linkedMapOf<CInterface, Int>()
	for (definition in definitions) {
		if (definition in complete) continue
		val stack = mutableListOf(Frame(definition, null))
		val active = mutableMapOf(definition to 0)
		while (stack.isNotEmpty()) {
			val frame = stack.last()
			if (!frame.parents.hasNext()) {
				complete[frame.owner] = complete.size
				active.remove(frame.owner)
				stack.removeLast()
				continue
			}
			val parent = frame.parents.next()
			val type = (names.nameOf(parent) as NominalTypeName).type as CInterface
			if (type in complete) continue
			active[type]?.let { start ->
				throw InterfaceErrors.cycle(type.name, parent.span, stack.drop(start + 1).mapNotNull { it.via })
			}
			active[type] = stack.size
			stack.add(Frame(type, parent.span))
		}
	}
	return complete
}

/** Resolve static contracts and body targets after all signature types have been substituted. */
internal class InterfaceConformanceAnalyzer(private val types: ClosedTypeModel, private val signatureKeys: GenericSignatureKeys) {

	private data class Route(val type: CInterface, val clause: TokenSpan, val via: TokenSpan = clause)

	private val closures = mutableMapOf<CMemberType, Map<CInterface, Route>>()
	private val declared = mutableMapOf<CInterface, List<InterfaceRequirement>>()
	private val classMethods = mutableMapOf<CClass, Map<GenericMethodSignature, List<ClassMethod>>>()
	private val classTargets = mutableMapOf<CClass, Map<InterfaceRequirement, InterfaceImplementation>>()
	private val genericOverrides = mutableMapOf<GenericMethodTemplate, InterfaceRequirement>()

	fun analyze(parentOrder: Map<CInterface, Int>, baseOrder: List<CClass>): InterfaceModel {
		val contracts = buildMap {
			for (owner in types.nominalTypes.filterIsInstance<CInterface>().sortedBy { parentOrder.getValue(it.definition) }) {
				put(owner, contract(owner))
			}
		}
		val classes = buildMap {
			for (owner in baseOrder) put(owner, conformance(owner, contracts))
		}
		for ((type, contract) in contracts) type.ancestors = contract.ancestors
		for ((type, conformance) in classes) type.implementedInterfaces = conformance.implementations.keys
		return InterfaceModel(contracts, classes, genericOverrides)
	}

	private fun closure(owner: CMemberType, parents: List<Route>, inherited: Collection<Route> = emptyList()): Map<CInterface, Route> = buildMap {
		fun include(route: Route) {
			val first = putIfAbsent(route.type.definition, route) ?: return
			if (first.type !== route.type) {
				throw InterfaceErrors.applications(owner.spelling, first.type.spelling, route.type.spelling, listOf(
					SourceLabel(route.via, "inherits '${route.type.spelling}' through here"),
					SourceLabel(first.via, "inherits '${first.type.spelling}' through here"),
					SourceLabel(route.clause, "application declared here"),
					SourceLabel(first.clause, "other application declared here"),
				))
			}
		}
		inherited.forEach(::include)
		val direct = mutableMapOf<CInterface, TokenSpan>()
		for (parent in parents) {
			direct.putIfAbsent(parent.type, parent.clause)?.let {
				throw InterfaceErrors.header("Duplicate interface clause '${parent.type.spelling}'", parent.clause, SourceLabel(it, "first clause here"))
			}
			include(parent)
			for (ancestor in closures.getValue(parent.type).values) include(ancestor.copy(via = parent.clause))
		}
	}

	private fun contract(owner: CInterface): InterfaceContract {
		val parents = owner.declaration.parents.map { Route(types.typeOf(it, owner) as CInterface, it.span) }
		val ancestors = closure(owner, parents)
		closures[owner] = ancestors
		val own = buildMap {
			fun add(requirement: InterfaceRequirement) {
				val method = requirement.declaration
				val labels = mutableMapOf<String, Token>()
				for (parameter in method.parameters) {
					labels.putIfAbsent(parameter.name.lexeme, parameter.name)?.let { throw SemanticErrors.duplicate(parameter.name, it) }
				}
				putIfAbsent(requirement.key, requirement)?.let { throw SemanticErrors.duplicateMethod(owner.name, requirement.signature.spelling, it.declaration.name, method.name) }
			}

			val instance = types.instanceOf(owner)
			for (closed in instance.signatures) {
				val method = closed.declaration as InterfaceMethodDeclaration
				add(InterfaceRequirement(owner, method, GenericMethodSignature(0, closed.fixedSignature()), closed.fixedResults()))
			}
			for (method in owner.declaration.methods) {
				val count = method.typeParameters?.parameters?.size ?: continue
				val environment = instance.bindings.environment
				val key = GenericMethodSignature(count, signatureKeys.signature(method, environment))
				add(InterfaceRequirement(
					owner, method, key, signatureKeys.results(method, environment),
					signatureKeys.bounds(method, environment), signatureKeys.parameterSlots(method),
				))
			}
		}
		declared[owner] = own.values.toList()
		val groups = (own.values + requirements(ancestors)).groupBy { it.key }
		val mergedRequirements = mutableMapOf<GenericMethodSignature, List<InterfaceRequirement>>()
		val methods = buildMap {
			for ((key, group) in groups) {
				checkRequirements(owner, group)
				val candidates = mostSpecific(group)
				val selected = candidates.first()
				if (selected.owner === owner && selected.declaration.fallback == null) {
					group.firstOrNull { it.declaration.fallback != null }?.let {
						throw InterfaceErrors.requirement(owner.name, selected.signature.spelling, "A signature-only redeclaration cannot remove an inherited default.", it.declaration.name, selected.declaration.name)
					}
				}
				checkInheritedCallContract(owner, candidates)
				if (candidates.size > 1) mergedRequirements[key] = candidates
				put(key, selected)
			}
		}
		return InterfaceContract(
			buildList { parents.forEach { add(it.type) } },
			buildSet { ancestors.values.forEach { add(it.type) } },
			methods,
			mergedRequirements,
		)
	}

	private fun requirements(closure: Map<CInterface, Route>): List<InterfaceRequirement> = closure.values.flatMap { declared.getValue(it.type) }

	private fun mostSpecific(members: List<InterfaceRequirement>): List<InterfaceRequirement> = members.filter { member ->
		members.none { other -> closures.getValue(other.owner)[member.owner.definition]?.type === member.owner }
	}

	private fun checkRequirements(owner: CMemberType, group: List<InterfaceRequirement>) {
		val first = group.first()
		group.firstOrNull { it.results != first.results }?.let {
			throw InterfaceErrors.requirement(owner.name, first.signature.spelling, "Ordered result types differ.", first.declaration.name, it.declaration.name)
		}
		group.firstOrNull { it.bounds != first.bounds }?.let {
			throw InterfaceErrors.requirement(owner.name, first.signature.spelling, "Method type parameter bounds differ.", first.declaration.name, it.declaration.name)
		}
	}

	private fun checkInheritedCallContract(owner: CInterface, candidates: List<InterfaceRequirement>) {
		if (candidates.size < 2) return
		val first = candidates.first()
		val labels = first.declaration.parameters.map { it.name.lexeme }
		val optional = first.declaration.parameters.any { it.optional }
		val typeDefaults = declaredTypeDefaults(first)
		for (other in candidates.drop(1)) {
			val second = other.declaration
			if (optional || second.parameters.any { it.optional } || labels != second.parameters.map { it.name.lexeme }) {
				throw InterfaceErrors.requirement(owner.name, first.signature.spelling, "Declare this method on the child interface to select its labels and argument defaults.", first.declaration.name, second.name)
			}
			if (typeDefaults != declaredTypeDefaults(other)) {
				throw InterfaceErrors.requirement(owner.name, first.signature.spelling, "Declare this method on the child interface to select its type argument defaults.", first.declaration.name, second.name)
			}
		}
	}

	private fun declaredTypeDefaults(requirement: InterfaceRequirement) = signatureKeys.declaredTypeDefaults(requirement.declaration, types.instanceOf(requirement.owner).bindings.environment)

	private fun conformance(owner: CClass, contracts: Map<CInterface, InterfaceContract>): ClassInterfaceConformance {
		val parents = owner.declaration.parents.mapNotNull { parent ->
			(types.typeOf(parent.type, owner) as? CInterface)?.let { Route(it, parent.span) }
		}
		val baseRoutes = owner.base?.let(closures::get).orEmpty().values
		val inherited = if (baseRoutes.isEmpty()) emptyList() else {
			val via = checkNotNull(types.baseClauseOf(owner)).span
			baseRoutes.map { it.copy(via = via) }
		}
		val interfaces = closure(owner, parents, inherited)
		closures[owner] = interfaces
		val own = methods(owner)
		classMethods[owner] = own
		val groups = requirements(interfaces).groupBy { it.key }
		for ((key, methods) in own) for (method in methods) {
			val requirement = groups[key]?.first()
			val prefix = method.declaration.overridePrefix
			val signature = key.valueParameters.spelling
			if (prefix != null && requirement == null) {
				val virtual = if (method.template == null) types.virtualMethods.familyOf(owner, method.declaration) != null
				else types.virtualMethods.genericFamilyOf(method.template) != null
				if (!virtual) {
					if (method.template != null) throw SemanticErrors.virtualMethod("'$signature' has no matching generic virtual base method or interface requirement", prefix)
					throw InterfaceErrors.invalidOverride("'$signature' has no matching virtual base method or interface requirement", prefix)
				}
			}
			if (prefix == null && requirement != null) {
				throw InterfaceErrors.invalidOverride("'$signature' requires '${InterfaceErrors.overridePrefix}'", method.declaration.name.span, requirement.declaration.name)
			}
		}
		val inheritedTargets = owner.base?.let(classTargets::get).orEmpty()
		val targets = buildMap<InterfaceRequirement, InterfaceImplementation> {
			for ((key, group) in groups) {
				checkRequirements(owner, group)
				val first = group.first()
				val signature = first.signature.spelling
				val candidates = own[key].orEmpty()
				checkClassCandidates(owner, first, candidates)
				val method = candidates.singleOrNull()
				if (method != null && method.results != first.results) {
					throw InterfaceErrors.invalidOverride("'$signature' returns '${method.results.spelling}'. The interface requires '${first.results.spelling}'.", method.declaration.name.span, first.declaration.name)
				}
				method?.template?.let { checkGenericOverride(it, group) }
				val inheritedMethod = if (method == null) inheritedPublicMethod(owner, first) else null
				val uncovered = mutableListOf<InterfaceRequirement>()
				for (requirement in group) {
					val target = method ?: (inheritedTargets[requirement] as? ClassMethod) ?: inheritedMethod
					if (target == null) uncovered.add(requirement) else put(requirement, target)
				}
				if (uncovered.isNotEmpty()) {
					val defaults = mostSpecific(group.filter { it.declaration.fallback != null })
					if (defaults.size > 1) {
						throw InterfaceErrors.defaults(owner.name, signature, defaults[0].declaration.name, defaults[1].declaration.name)
					}
					val fallback = defaults.singleOrNull() ?: throw InterfaceErrors.missing(owner.name, signature, uncovered.first().declaration.name)
					val target = Default(fallback)
					for (requirement in uncovered) put(requirement, target)
				}
			}
		}
		classTargets[owner] = targets
		return ClassInterfaceConformance(buildMap {
			for (route in interfaces.values) {
				val contract = contracts.getValue(route.type)
				put(route.type, buildMap {
					for (requirement in contract.requirements.values) {
						// The representative determines the call contract. Any eligible
						// class targets for this signature share the nearest class method.
						val classTarget = contract.mergedRequirements[requirement.key]?.firstNotNullOfOrNull {
							targets.getValue(it) as? ClassMethod
						}
						put(requirement, classTarget ?: targets.getValue(requirement))
					}
				})
			}
		})
	}

	private fun methods(owner: CClass): Map<GenericMethodSignature, List<ClassMethod>> = buildMap<GenericMethodSignature, MutableList<ClassMethod>> {
		for (closed in types.instanceOf(owner).signatures) {
			val method = closed.declaration as? MethodDeclaration ?: continue
			if (method.operator != null) continue
			val key = closed.fixedSignature()
			putIfAbsent(GenericMethodSignature(0, key), mutableListOf(ClassMethod(owner, method, closed.fixedResults())))?.let {
				throw SemanticErrors.duplicateMethod(owner.name, key.spelling, it.first().declaration.name, method.name)
			}
		}
		val environment = types.instanceOf(owner).bindings.environment
		for (group in types.methodTemplates[owner].orEmpty().values) for (template in group) {
			val method = template.declaration
			getOrPut(template.signature) { mutableListOf() }.add(ClassMethod(owner, method, signatureKeys.results(method, environment), template))
		}
	}

	private fun checkGenericOverride(template: GenericMethodTemplate, requirements: List<InterfaceRequirement>) {
		val method = template.declaration
		val requirement = requirements.first()
		if (requirements.size > 1 && requirement.bounds.any { bounds -> bounds.any { it is GenericBoundSignature.Constructor } }) {
			val first = constructorContracts(requirement)
			requirements.drop(1).firstOrNull { constructorContracts(it) != first }?.let {
				throw InterfaceErrors.requirement(
					template.owner.name, requirement.signature.spelling, "Constructor guarantee labels or defaults differ.",
					requirement.declaration.name, it.declaration.name,
				)
			}
		}
		val virtualBase = types.virtualMethods.genericFamilyOf(template)?.base
		if (virtualBase != null) {
			if (bounds(virtualBase) != requirement.bounds) throw InterfaceErrors.invalidOverride(
				"Generic virtual base bounds differ from the interface requirement", method.name.span, requirement.declaration.name,
			)
			return
		}
		for (parameter in checkNotNull(method.typeParameters).parameters) parameter.bounds.firstOrNull()?.let {
			throw InterfaceErrors.invalidOverride("Generic overrides inherit bounds from the interface requirement", it.span, requirement.declaration.name)
		}
		genericOverrides[template] = requirement
	}

	private fun constructorContracts(requirement: InterfaceRequirement) = signatureKeys.constructorContracts(requirement.declaration, types.instanceOf(requirement.owner).bindings.environment)

	private fun bounds(template: GenericMethodTemplate): List<Set<GenericBoundSignature>> = signatureKeys.bounds(template.declaration, types.instanceOf(template.owner).bindings.environment)

	private fun inheritedPublicMethod(owner: CClass, requirement: InterfaceRequirement): ClassMethod? {
		val nearest = owner.base?.ancestry()?.firstNotNullOfOrNull { classMethods[it]?.get(requirement.key) }
		val candidates = nearest.orEmpty().filter { method ->
			method.declaration.visibility != null && method.results == requirement.results && matchesBounds(method, requirement)
		}
		checkClassCandidates(owner, requirement, candidates)
		return candidates.singleOrNull()
	}

	private fun matchesBounds(method: ClassMethod, requirement: InterfaceRequirement): Boolean {
		val template = method.template ?: return true
		val base = types.virtualMethods.genericFamilyOf(template)?.base ?: template
		return (genericOverrides[template]?.bounds ?: bounds(base)) == requirement.bounds
	}

	private fun checkClassCandidates(owner: CClass, requirement: InterfaceRequirement, candidates: List<ClassMethod>) {
		if (candidates.size > 1) throw InterfaceErrors.requirement(
			owner.name, requirement.signature.spelling, "Multiple class methods match this interface requirement.",
			candidates[0].declaration.name, candidates[1].declaration.name,
		)
	}
}
