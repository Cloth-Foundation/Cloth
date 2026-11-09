package cloth.semantic.interfaces

import cloth.data.CClass
import cloth.data.CInterface
import cloth.data.CMemberType
import cloth.data.ValueType
import cloth.diagnostics.InterfaceErrors
import cloth.diagnostics.SemanticErrors
import cloth.diagnostics.SourceLabel
import cloth.parser.declaration.InterfaceMethodDeclaration
import cloth.parser.declaration.MethodDeclaration
import cloth.parser.type.TypeReference
import cloth.semantic.ResultShape
import cloth.semantic.generics.ClosedCallableSignature
import cloth.semantic.generics.ClosedTypeModel
import cloth.semantic.generics.ClosedTypePattern
import cloth.semantic.generics.GenericNameModel
import cloth.semantic.generics.NominalTypeName
import cloth.semantic.interfaces.InterfaceImplementation.ClassMethod
import cloth.semantic.interfaces.InterfaceImplementation.Default
import cloth.semantic.symbols.FunctionSignature
import cloth.semantic.symbols.SignatureType
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
internal class InterfaceConformanceAnalyzer(private val types: ClosedTypeModel) {
	private data class Route(val type: CInterface, val clause: TokenSpan, val via: TokenSpan = clause)

	private val closures = mutableMapOf<CMemberType, Map<CInterface, Route>>()
	private val declared = mutableMapOf<CInterface, List<InterfaceRequirement>>()
	private val classMethods = mutableMapOf<CClass, Map<FunctionSignature, ClassMethod>>()
	private val classTargets = mutableMapOf<CClass, Map<InterfaceRequirement, InterfaceImplementation>>()

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
		return InterfaceModel(contracts, classes)
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
			for (closed in types.instanceOf(owner).signatures) {
				val method = closed.declaration as InterfaceMethodDeclaration
				val key = closed.key()
				val labels = mutableMapOf<String, Token>()
				for (parameter in method.parameters) {
					labels.putIfAbsent(parameter.name.lexeme, parameter.name)?.let { throw SemanticErrors.duplicate(parameter.name, it) }
				}
				val requirement = InterfaceRequirement(owner, method, key, closed.resultShape())
				putIfAbsent(key, requirement)?.let { throw SemanticErrors.duplicateMethod(owner.name, key.spelling, it.declaration.name, method.name) }
			}
		}
		declared[owner] = own.values.toList()
		val groups = (own.values + requirements(ancestors)).groupBy { it.signature }
		val methods = buildMap {
			for ((key, group) in groups) {
				checkResults(owner, key, group)
				val candidates = mostSpecific(group)
				val selected = candidates.first()
				if (selected.owner === owner && selected.declaration.fallback == null) {
					group.firstOrNull { it.declaration.fallback != null }?.let {
						throw InterfaceErrors.requirement(owner.name, key.spelling, "A signature-only redeclaration cannot remove an inherited default.", it.declaration.name, selected.declaration.name)
					}
				}
				checkInheritedCallContract(owner, key, candidates)
				put(key, selected)
			}
		}
		return InterfaceContract(
			buildList { parents.forEach { add(it.type) } },
			buildSet { ancestors.values.forEach { add(it.type) } },
			methods,
		)
	}

	private fun requirements(closure: Map<CInterface, Route>): List<InterfaceRequirement> =
		closure.values.flatMap { declared.getValue(it.type) }

	private fun mostSpecific(members: List<InterfaceRequirement>): List<InterfaceRequirement> = members.filter { member ->
		members.none { other -> closures.getValue(other.owner)[member.owner.definition]?.type === member.owner }
	}

	private fun checkResults(owner: CMemberType, key: FunctionSignature, group: List<InterfaceRequirement>) {
		val first = group.first()
		group.firstOrNull { it.results != first.results }?.let {
			throw InterfaceErrors.requirement(owner.name, key.spelling, "Ordered result types differ.", first.declaration.name, it.declaration.name)
		}
	}

	private fun checkInheritedCallContract(owner: CInterface, key: FunctionSignature, candidates: List<InterfaceRequirement>) {
		if (candidates.size < 2) return
		val first = candidates.first().declaration
		val labels = first.parameters.map { it.name.lexeme }
		val optional = first.parameters.any { it.optional }
		for (other in candidates.drop(1)) {
			val second = other.declaration
			if (optional || second.parameters.any { it.optional } || labels != second.parameters.map { it.name.lexeme }) {
				throw InterfaceErrors.requirement(owner.name, key.spelling, "Declare this method on the child interface to select its labels and argument defaults.", first.name, second.name)
			}
		}
	}

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
		val own = ordinaryMethods(owner)
		classMethods[owner] = own
		val groups = requirements(interfaces).groupBy { it.signature }
		for ((key, method) in own) {
			val requirement = groups[key]?.first()
			val prefix = method.declaration.overridePrefix
			if (prefix != null && requirement == null) {
				throw InterfaceErrors.invalidOverride("'${key.spelling}' has no matching interface requirement", prefix)
			}
			if (prefix == null && requirement != null) {
				throw InterfaceErrors.invalidOverride("'${key.spelling}' requires '${InterfaceErrors.overridePrefix}'", method.declaration.name.span, requirement.declaration.name)
			}
		}
		val inheritedTargets = owner.base?.let(classTargets::get).orEmpty()
		val targets = buildMap<InterfaceRequirement, InterfaceImplementation> {
			for ((key, group) in groups) {
				checkResults(owner, key, group)
				val first = group.first()
				val method = own[key]
				if (method != null && method.results != first.results) {
					throw InterfaceErrors.invalidOverride("'${key.spelling}' returns '${method.results.spelling}'. The interface requires '${first.results.spelling}'.", method.declaration.name.span, first.declaration.name)
				}
				val inheritedMethod = if (method == null) inheritedPublicMethod(owner, key, first.results) else null
				val uncovered = mutableListOf<InterfaceRequirement>()
				for (requirement in group) {
					val target = method ?: (inheritedTargets[requirement] as? ClassMethod) ?: inheritedMethod
					if (target == null) uncovered.add(requirement) else put(requirement, target)
				}
				if (uncovered.isNotEmpty()) {
					val defaults = mostSpecific(group.filter { it.declaration.fallback != null })
					if (defaults.size > 1) {
						throw InterfaceErrors.defaults(owner.name, key.spelling, defaults[0].declaration.name, defaults[1].declaration.name)
					}
					val fallback = defaults.singleOrNull() ?: throw InterfaceErrors.missing(owner.name, key.spelling, uncovered.first().declaration.name)
					val target = Default(fallback)
					for (requirement in uncovered) put(requirement, target)
				}
			}
		}
		classTargets[owner] = targets
		return ClassInterfaceConformance(buildMap {
			for (route in interfaces.values) {
				put(route.type, buildMap {
					for (requirement in contracts.getValue(route.type).methods.values) put(requirement, targets.getValue(requirement))
				})
			}
		})
	}

	private fun ordinaryMethods(owner: CClass): Map<FunctionSignature, ClassMethod> = buildMap {
		for (closed in types.instanceOf(owner).signatures) {
			val method = closed.declaration as? MethodDeclaration ?: continue
			if (method.operator != null) continue
			val key = closed.key()
			putIfAbsent(key, ClassMethod(owner, method, closed.resultShape()))?.let {
				throw SemanticErrors.duplicateMethod(owner.name, key.spelling, it.declaration.name, method.name)
			}
		}
	}

	private fun inheritedPublicMethod(owner: CClass, key: FunctionSignature, results: ResultShape): ClassMethod? {
		val nearest = owner.base?.ancestry()?.firstNotNullOfOrNull { classMethods[it]?.get(key) }
		return nearest?.takeIf { it.declaration.visibility != null && it.results == results }
	}

	private fun ClosedCallableSignature.key() = FunctionSignature(declaration.name.lexeme, parameters.mapIndexed { index, pattern ->
		val type = (pattern as ClosedTypePattern.Exact).type
		if (type == ValueType.VOID) throw SemanticErrors.voidType(declaration.parameters[index].type.span)
		SignatureType.resolved(type)
	})

	private fun ClosedCallableSignature.resultShape() = ResultShape.of(results.mapIndexed { index, pattern ->
		val type = (pattern as ClosedTypePattern.Exact).type
		if (results.size > 1 && type == ValueType.VOID) throw SemanticErrors.voidResult(declaration.returnTypes[index].span)
		type
	})
}
