package cloth.semantic.generics

import cloth.ast.SourceUnit
import cloth.data.*
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.*
import cloth.parser.expression.CallExpression
import cloth.parser.type.ConstructorBound
import cloth.parser.type.TypeReference
import cloth.parser.type.maximumTypeArgumentNesting
import cloth.semantic.declarations.*
import cloth.semantic.interfaces.InterfaceConformanceAnalyzer
import cloth.semantic.interfaces.interfaceParentOrder
import cloth.semantic.interfaces.specialize
import cloth.semantic.symbols.FunctionGroupSymbol
import cloth.source.SourceGraph
import cloth.target.TargetLayout
import cloth.token.Token
import cloth.token.TokenSpan
import java.util.IdentityHashMap
import kotlin.collections.ArrayDeque

internal const val maximumGenericDependencyDepth = 64
internal const val maximumGenericInstances = 4096

/** A newly available type or committed call contract requires fresh semantic facts. */
internal class GenericAnalysisRestart : RuntimeException(null, null, false, false)

/** Discover closed requirements with a worklist, then validate complete layout graphs. */
class ClosedTypeAnalyzer(private val target: TargetLayout) {

	fun analyze(unit: SourceUnit): ClosedTypeModel = analyze(FileBindings.single(unit))
	internal fun analyze(graph: SourceGraph): ClosedTypeModel = analyze(FileBindings.graph(graph))
	internal fun analyze(files: FileBindings, names: GenericNameModel = GenericAnalyzer(target).analyze(files)): ClosedTypeModel {
		if ((names.interfaceSpan != null || names.hasVirtualMethods || names.hasBounds) && names.owners.isNotEmpty()) {
			val templates = files.fork()
			worklist(templates, GenericAnalyzer(target).analyze(templates), symbolic = true, checkCallBounds = true)
		}
		return worklist(files, names, checkCallBounds = true).model
	}

	internal fun worklist(files: FileBindings, names: GenericNameModel, symbolic: Boolean = false, checkCallBounds: Boolean = false): Worklist = Worklist(files, names, target, symbolic, checkCallBounds).also { it.initialize() }

	internal class Worklist(
		private val files: FileBindings,
		private val names: GenericNameModel,
		private val target: TargetLayout,
		private val symbolic: Boolean,
		checkCallBounds: Boolean,
	) {

		private data class ConstructorApplication(val instance: ClosedTypeInstance, val supplied: TypeArgumentSpans, val caller: GenericBindings)

		val model = ClosedTypeModel(names, target, symbolic, files)
		private val work = ArrayDeque<() -> Unit>()
		private val ownerUses = IdentityHashMap<Declaration?, MutableList<GenericTypeUse>>()
		private val ownerCalls = IdentityHashMap<Declaration?, MutableList<CallExpression>>()
		private val pendingFunctions = linkedSetOf<GenericCallableRequest>()
		private val pendingMethodBodies = mutableListOf<GenericMethodTemplate>()
		private val virtualCalls = mutableMapOf<ClosedMethodKey, GenericCallableRequest>()
		private val interfaceCalls = mutableMapOf<Pair<CInterface, ClosedInterfaceMethodKey>, GenericCallableRequest>()
		private val stateCalls = mutableMapOf<Pair<CClass, ClosedStateContractKey>, GenericCallableRequest>()
		private val checkedStateContracts = mutableSetOf<GenericStateContract>()
		private val checkedStateMethods = mutableSetOf<GenericStateMethod>()
		private val callBounds = if (checkCallBounds) mutableListOf<List<GenericCallableRequest>>() else null
		private val boundChecks = mutableListOf<GenericBoundCheck>()
		private val constructorApplications = mutableListOf<ConstructorApplication>()
		private val signatureKeys = model.signatureKeys
		private val typeDefaults = TypeArgumentDefaults(names)
		private lateinit var interfaceOrder: Map<CInterface, Int>
		private var bindings = model.rootBindings
		private var closedCount = 0
		private var symbolicCount = 0

		fun initialize() {
			// Check source interface cycles before any application can expand them.
			interfaceOrder = interfaceParentOrder(files.typeOrder.filterIsInstance<CInterface>(), names)
			for (use in names.uses) if (use.boundFor == null && use.defaultFor == null) ownerUses.getOrPut(use.owner) { mutableListOf() }.add(use)
			for (use in names.functionUses) ownerCalls.getOrPut(use.owner) { mutableListOf() }.add(use.call)
			for (definition in files.typeOrder) if (names.parametersOf(definition.declaration).isEmpty()) register(definition, definition, emptyList(), GenericBindings())
			if (symbolic) {
				for (definition in files.typeOrder) {
					val arguments = symbolicArguments(definition.declaration)
					if (arguments.isNotEmpty()) instantiate(definition, arguments, definition.name.span)
				}
				for (file in files.files) for (function in file.functions) {
					val arguments = symbolicArguments(function.declaration)
					if (arguments.isNotEmpty()) instantiate(ClosedFunctionKey(function, arguments), function.name.span)
				}
			}
			bindOwner(null)
			for (file in files.files) for (function in file.functions) {
				if (function.typeParameterCount == 0) model.functionList.add(signature(function.declaration))
			}
			complete()
			for (candidates in callBounds.orEmpty()) {
				val failures = candidates.map { (key, supplied, caller) -> model.candidateFailure(key, supplied, caller) }
				if (failures.all { it != null }) throw checkNotNull(failures.first()).diagnostic(candidates.first().supplied)
			}
		}

		fun expand(requests: Sequence<GenericCallableRequest>): Boolean {
			var newDispatch = false
			for (request in requests) {
				val (key, supplied, caller) = request
				if (!symbolic && key is ClosedMethodKey && model.virtualMethods.genericFamilyOf(key.template)?.base === key.template) {
					virtualCalls.putIfAbsent(key, request)
				}
				if (!symbolic && key is ClosedInterfaceMethodKey) (request.dispatchView as? CInterface)?.let { view ->
					if (interfaceCalls.putIfAbsent(view to key, request) == null) newDispatch = true
				}
				if (!symbolic && key is ClosedStateContractKey) (request.dispatchView as? CClass)?.let { view ->
					if (stateCalls.putIfAbsent(view to key, request) == null) newDispatch = true
				}
				if (key in model.callableInstances) continue
				withBindings(caller) { instantiate(key, supplied.span, supplied) }
			}
			if (work.isEmpty() && !newDispatch) return false
			complete()
			return true
		}

		fun prepareStates(catalog: StateCatalog) {
			if (symbolic) {
				for (contract in catalog.genericContracts()) {
					if (!model.isCanonicalApplication(contract.owner) || !checkedStateContracts.add(contract)) continue
					withBindings(model.instanceOf(contract.owner).bindings) {
						val source = contract.declaration
						instantiate(ClosedStateContractKey(contract, symbolicArguments(source)), source.name.span)
					}
				}
				for (state in catalog.definitions) for (method in state.genericMethods.values) {
					if (!model.isCanonicalApplication(method.owner) || !checkedStateMethods.add(method)) continue
					withBindings(model.instanceOf(method.owner).bindings) {
						val source = method.declaration
						val inherited = catalog.inheritedBounds(method)
						instantiate(ClosedStateMethodKey(method, symbolicArguments(source, inherited)), source.name.span, inherited = inherited)
					}
				}
			}
			// The catalog is rebuilt from the current owners after discovering a class.
			for ((use, request) in stateCalls) {
				val (view, key) = use
				withBindings(request.caller) {
					for (state in catalog.dispatchStates(view)) {
						val method = state.genericMethods[key.template.signature] ?: continue
						instantiate(ClosedStateMethodKey(method, key.arguments), request.supplied.span, request.supplied, catalog.inheritedBounds(method))
					}
				}
			}
			if (work.isNotEmpty()) {
				complete()
				throw GenericAnalysisRestart()
			}
		}

		fun prepareCallables(requests: List<GenericCallableRequest>, constructorAccessChecked: Boolean = false) {
			// A default can introduce an actual type whose ancestry still needs to be built.
			if (work.isEmpty()) {
				for (request in requests) if (!hasContract(request.key)) prepareCandidate(request, constructorAccessChecked)
			}
			if (work.isNotEmpty()) {
				complete()
				throw GenericAnalysisRestart()
			}
			checkConstructorAccess()
		}

		fun prepareApplication(key: NominalApplicationKey, caller: GenericBindings, supplied: TypeArgumentSpans): Nothing {
			withBindings(caller) { instantiate(key.definition, key.arguments, supplied.span, supplied) }
			complete()
			throw GenericAnalysisRestart()
		}

		fun completeCallTypeArguments(
			declaration: Declaration,
			owner: CMemberType?,
			supplied: List<ValueType?>,
			caller: GenericBindings,
			use: TokenSpan,
		): List<ValueType> = completeTypeArguments(
			declaration, owner?.let(model::instanceOf)?.bindings, supplied, caller, use,
		)

		private fun completeTypeArguments(
			declaration: Declaration,
			enclosing: GenericBindings?,
			supplied: List<ValueType?>,
			caller: GenericBindings,
			use: TokenSpan,
			probeApplications: MutableMap<NominalApplicationKey, CMemberType>? = null,
		): List<ValueType> = typeDefaults.complete(declaration, enclosing?.environment.orEmpty(), supplied) { annotation, environment ->
			val defaults = GenericBindings(environment, caller.chain, caller.argumentDepth, defaultOrigin = TypeArgumentOrigin(use, caller))
			if (enclosing != null) defaults.constructorBounds.putAll(enclosing.constructorBounds)
			withBindings(defaults) {
				resolve(annotation, probeApplications)
			}
		}

		private fun hasContract(key: ClosedCallableKey) = key in model.callableInstances || key in model.callableCandidates || key in model.rejectedCallables

		private fun complete() {
			do {
				while (work.isNotEmpty()) work.removeFirst()()
				val baseOrder = validateInheritance()
				validateStructs()
				if (names.hasVirtualMethods) model.virtualMethods = VirtualMethods.analyze(model, baseOrder, signatureKeys)
				if (names.interfaceSpan != null) model.interfaces = InterfaceConformanceAnalyzer(model, signatureKeys).analyze(interfaceOrder, baseOrder)
				checkBounds()
				checkConstructorAccess()
				val requests = pendingFunctions.toList()
				pendingFunctions.clear()
				for (request in requests) if (!hasContract(request.key)) prepareCandidate(request)
				registerMethodBodies()
				registerVirtualTargets()
				registerInterfaceTargets()
			} while (work.isNotEmpty() || constructorApplications.isNotEmpty())
			model.catalog.values.forEach { it.state = ClosedTypeState.COMPLETE }
		}

		private fun symbolicArguments(declaration: Declaration, inherited: InheritedMethodBounds? = null): List<ValueType> {
			val parameters = names.parametersOf(declaration)
			if (parameters.isEmpty()) return emptyList()
			val source = inherited?.declaration ?: declaration
			val sourceParameters = names.parametersOf(source)
			val resolved = mutableMapOf<TypeParameterSymbol, ValueType>()
			val active = mutableSetOf<TypeParameterSymbol>()
			fun argument(parameter: TypeParameterSymbol): ValueType = resolved.getOrPut(parameter) {
				val bounds = sourceParameters[parameter.index].declaration.typeBounds
				val array = bounds.firstOrNull { it.arraySuffixes.isNotEmpty() }
				if (array == null) {
					val number = bounds.any { names.nameOf(it) === NumberBoundName }
					val nullable = bounds.any { it.nullable != null }
					val projection = if (nullable) listOf(TypeParameterProjection.PAYLOAD) else emptyList()
					val exact = CTypeParameter(parameter.owner, parameter.declaration, parameter.index, number, projection)
					if (nullable) CNullable(exact) else exact
				} else {
					if (!active.add(parameter)) throw GenericErrors.cyclicArrayBound(parameter.name, array.span)
					if (active.size > maximumTypeArgumentNesting) throw GenericErrors.nesting(array.span, maximumTypeArgumentNesting)
					try {
						val bound = arrayBound(array)
						if (bound.numericElement) {
							val element = CTypeParameter(parameter.owner, parameter.declaration, parameter.index, numberBound = true, projection = elementProjection(bound.shape))
							resolver().withSuffixes(array, element)
						} else bound.shape
					} finally {
						active.remove(parameter)
					}
				}
			}

			val environment = inherited?.let { model.instanceOf(it.owner).bindings.environment } ?: bindings.environment
			val context = GenericBindings(environment, bindings.chain, bindings.argumentDepth, deferredArgument = { parameter ->
				if (parameter.owner === source) argument(parameters[parameter.index]) else null
			})
			return withBindings(context) { parameters.map(::argument) }
		}

		private fun elementProjection(shape: ValueType): List<TypeParameterProjection> = buildList {
			var type = shape
			while (true) type = when (val part = type) {
				is CArray    -> {
					add(TypeParameterProjection.ELEMENT)
					part.element
				}

				is CNullable -> {
					add(TypeParameterProjection.PAYLOAD)
					part.payload
				}

				else         -> break
			}
		}

		private fun bindOwner(owner: Declaration?) {
			for (use in ownerUses[owner].orEmpty()) bind(use)
			for (call in ownerCalls[owner].orEmpty()) {
				// Publish candidate contracts from the file group. The lexical call
				// resolver checks which group is actually visible at this site.
				val supplied = checkNotNull(call.typeArguments)
				val group = files.context(call.name).scope.lookup(call.name.lexeme) as? FunctionGroupSymbol ?: continue
				if (group.completesTypeArguments(supplied)) continue
				val templates = group.overloads.filter { it.acceptsTypeArguments(supplied.arguments.size) }
				if (templates.isEmpty()) continue
				val arguments = supplied.arguments.map(::resolve)
				val spans = supplied.sourceSpans()
				val requests = templates.map { GenericCallableRequest(ClosedFunctionKey(it, arguments), spans, bindings) }
				pendingFunctions.addAll(requests.filterNot { hasContract(it.key) })
				callBounds?.add(requests)
			}
		}

		private fun bind(use: GenericTypeUse) {
			val pattern = pattern(use.type)
			if (pattern is ClosedTypePattern.Exact) use.receiver?.let { bindings.receivers[it] = pattern.type }
		}

		private fun pattern(annotation: TypeReference): ClosedTypePattern = bindings.facts.getOrPut(annotation) {
			if (annotation.capturesWidth) ClosedTypePattern.OperatorWidth(annotation) else ClosedTypePattern.Exact(resolve(annotation))
		}

		private fun resolve(annotation: TypeReference, probeApplications: MutableMap<NominalApplicationKey, CMemberType>? = null): ValueType {
			(bindings.facts[annotation] as? ClosedTypePattern.Exact)?.let { return it.type }
			return resolver(probeApplications).resolve(annotation).also { bindings.facts[annotation] = ClosedTypePattern.Exact(it) }
		}

		private fun resolver(probeApplications: MutableMap<NominalApplicationKey, CMemberType>? = null) = TypeResolver(target, substitutedType = { annotation ->
			when (val identity = names.names[annotation]) {
				is TypeParameterSymbol -> bindings.argumentOf(identity) ?: throw InternalErrors.invariant("Unsolved owner parameter '${identity.name.lexeme}' in a closed type")
				else                   -> {
					val definition = (identity as? NominalTypeName)?.type ?: files.nominal(annotation.name)
					definition?.let {
						val application = annotation.application ?: return@let it
						val supplied = application.arguments.map { argument -> resolve(argument, probeApplications) }
						val arguments = completeTypeArguments(it.declaration, null, supplied, bindings, application.span, probeApplications)
						if (probeApplications == null) instantiate(it, arguments, annotation.span, application.sourceSpans(arguments.size))
						else {
							val key = NominalApplicationKey(it, arguments)
							model.applications[key]?.type ?: probeApplications.getOrPut(key) { it.application(arguments) }
						}
					}
				}
			}
		})

		private fun register(type: CMemberType, definition: CMemberType, arguments: List<ValueType>, bindings: GenericBindings): ClosedTypeInstance = ClosedTypeInstance(type, definition, arguments, bindings).also {
			model.catalog[type] = it
			work.addLast { define(it) }
		}

		private fun <T> withBindings(instance: GenericBindings, define: () -> T): T {
			val previous = bindings
			bindings = instance
			return try {
				define()
			} finally {
				bindings = previous
			}
		}

		private fun define(instance: ClosedTypeInstance) = withBindings(instance.bindings) {
			instance.state = ClosedTypeState.RESOLVING
			bindBounds(instance.definition.declaration)
			checkDefaultTypes(instance.definition.declaration)
			val signatures = instance.signatureList
			when (val type = instance.type) {
				is CClass     -> {
					defineClass(type)
					signatures.add(signature(PrimaryConstructorDeclaration(type.declaration)))
					val members = type.declaration.members.filterIsInstance<CallableDeclaration>()
					members.filterNot { it is MethodDeclaration && it.typeParameters != null }.mapTo(signatures, ::signature)
					if (symbolic && type.typeArguments.isNotEmpty() && model.isCanonicalApplication(type)) {
						registerConstructorTemplates(type, signatures)
					}
					registerMethods(type, members.filterIsInstance<MethodDeclaration>())
				}

				is CStruct    -> {
					defineStruct(type)
					type.declaration.methods.filter { it.typeParameters == null }.mapTo(signatures, ::signature)
					registerMethods(type, type.declaration.methods)
				}

				is CEnum      -> {
					EnumModel.defineFields(type, resolver())
					type.declaration.operators.mapTo(signatures, ::signature)
				}

				is CInterface -> {
					type.declaration.methods.filter { it.typeParameters == null }.mapTo(signatures, ::signature)
					if (symbolic && model.isCanonicalApplication(type)) registerInterfaceMethods(type)
				}
			}
			bindOwner(instance.definition.declaration)
		}

		private fun registerConstructorTemplates(owner: CClass, signatures: List<ClosedCallableSignature>) {
			model.constructorTemplates[owner.declaration] = signatures.filter { it.declaration is PrimaryConstructorDeclaration || it.declaration is ConstructorDeclaration }.map { signature ->
				val declaration = signature.declaration as CallableDeclaration
				GenericConstructorTemplate(declaration, signatureKeys.inferencePatterns(declaration, owner.declaration))
			}
		}

		private fun registerInterfaceMethods(owner: CInterface) {
			for (method in owner.declaration.methods) {
				if (method.typeParameters == null) continue
				instantiate(ClosedInterfaceMethodKey(owner, method, symbolicArguments(method)), method.name.span)
			}
		}

		private fun registerMethods(owner: CMemberType, declarations: List<MethodDeclaration>) {
			val methods = declarations.filter { it.typeParameters != null }
			if (methods.isEmpty()) return
			val canonical = model.isCanonicalApplication(owner)
			val seen = mutableMapOf<GenericMethodSignature, MethodDeclaration>()
			model.methodTemplates[owner] = methods.map { declaration ->
				val count = names.parametersOf(declaration).size
				val key = GenericMethodSignature(count, signatureKeys.signature(declaration, bindings.environment))
				if (canonical) seen.putIfAbsent(key, declaration)?.let { prior ->
					throw SemanticErrors.duplicateMethod(owner.name, key.valueParameters.spelling, prior.name, declaration.name)
				}
				GenericMethodTemplate(owner, declaration, key, signatureKeys.parameterSlots(declaration)).also {
					if (symbolic && canonical) {
						if (declaration.virtualPrefix != null || declaration.overridePrefix != null) pendingMethodBodies.add(it)
						else instantiate(ClosedMethodKey(it, symbolicArguments(declaration)), declaration.name.span)
					}
				}
			}.groupBy { it.declaration.name.lexeme }
		}

		private fun inheritedMethodBounds(template: GenericMethodTemplate): InheritedMethodBounds? {
			val base = model.virtualMethods.genericFamilyOf(template)?.base
			if (base != null && base !== template) return InheritedMethodBounds(base.owner, base.declaration)
			return model.interfaces.genericOverrides[template]?.let { InheritedMethodBounds(it.owner, it.declaration) }
		}

		private fun registerMethodBodies() {
			for (template in pendingMethodBodies) {
				withBindings(model.instanceOf(template.owner).bindings) {
					val declaration = template.declaration
					val arguments = symbolicArguments(declaration, inheritedMethodBounds(template))
					instantiate(ClosedMethodKey(template, arguments), declaration.name.span)
				}
			}
			pendingMethodBodies.clear()
		}

		private fun registerVirtualTargets() {
			// A later body can discover another closed subclass of an already requested family.
			for ((key, request) in virtualCalls) {
				val family = checkNotNull(model.virtualMethods.genericFamilyOf(key.template))
				withBindings(request.caller) {
					for (template in family.implementations.values.distinct()) {
						instantiate(ClosedMethodKey(template, key.arguments), request.supplied.span, request.supplied)
					}
				}
			}
		}

		private fun registerInterfaceTargets() {
			// Interface conformance can gain another class during specialization.
			for ((use, request) in interfaceCalls) {
				val (view, key) = use
				val requirement = model.interfaces.requirementOf(key.owner, key.declaration)
				withBindings(request.caller) {
					for ((_, target) in model.interfaces.dispatchTargets(view, requirement)) {
						instantiate(target.specialize(key.arguments), request.supplied.span, request.supplied)
					}
				}
			}
		}

		private fun argumentDepth(type: ValueType): Int {
			var value = type
			while (true) value = when (value) {
				is CArray    -> value.element
				is CNullable -> value.payload
				else         -> return (value as? CMemberType)?.let { model.catalog[it]?.bindings?.argumentDepth } ?: 0
			}
		}

		private fun instantiate(definition: CMemberType, arguments: List<ValueType>, use: TokenSpan, suppliedArguments: TypeArgumentSpans? = null): CMemberType {
			val key = NominalApplicationKey(definition, arguments.toList())
			// Reuse provisional identities before charging depth or instance budgets.
			val instance = model.applications.getOrPut(key) {
				val context = newBindings(definition.declaration, definition.name, key.arguments, use, suppliedArguments)
				val type = definition.application(key.arguments)
				register(type, definition, key.arguments, context)
			}
			// An exact array bound can reuse an application registered by an earlier type use.
			if (suppliedArguments == null) instance.bindings.canonical = true
			if (suppliedArguments != null && names.parametersOf(definition.declaration).any { parameter ->
					parameter.declaration.bounds.any { it is ConstructorBound }
				}) constructorApplications.add(ConstructorApplication(instance, suppliedArguments, bindings))
			return instance.type
		}

		private fun instantiate(
			key: ClosedCallableKey,
			use: TokenSpan,
			suppliedArguments: TypeArgumentSpans? = null,
			inherited: InheritedMethodBounds? = (key as? ClosedMethodKey)?.template?.let(::inheritedMethodBounds),
		) {
			model.callableInstances.getOrPut(key) {
				val declaration = key.declaration
				val context = newBindings(declaration, declaration.name, key.arguments, use, suppliedArguments, key.owner, inherited)
				model.callableCandidates.remove(key)
				work.addLast {
					withBindings(context) {
						val signature = bindSignature(declaration)
						if (key is ClosedFunctionKey) {
							model.functionList.add(signature)
							if (suppliedArguments == null) {
								model.functionPatterns[key.declaration] = signatureKeys.inferencePatterns(declaration)
							}
						}
						if (key !is ClosedStateContractKey || declaration is StateMethodContractDeclaration) bindOwner(declaration)
					}
				}
				context
			}
		}

		private fun prepareCandidate(request: GenericCallableRequest, constructorAccessChecked: Boolean = false) {
			val (key, supplied, caller) = request
			val declaration = key.declaration
			val parameterOwner = if (key is ClosedConstructorKey) key.definition.declaration else declaration
			val inherited = (key as? ClosedMethodKey)?.template?.let(::inheritedMethodBounds)
			val context = scopedBindings(parameterOwner, key.arguments, key.owner, inherited, caller.chain + (supplied.span to declaration.name.lexeme), 0, supplied)
			withBindings(context) {
				val probeApplications = mutableMapOf<NominalApplicationKey, CMemberType>()
				for (parameter in names.parametersOf(parameterOwner)) {
					val argument = context.environment.getValue(parameter)
					val bounds = resolveBounds(boundParameter(parameter), probeApplications)
					if (bounds.isEmpty()) continue
					val check = GenericBoundCheck(parameter, argument, bounds, supplied.arguments[parameter.index])
					val failure = check.failure(model::hasConstructor) { boundInterfaces(it, probeApplications) }
					if (failure != null) {
						model.rejectedCallables[key] = failure
						return@withBindings
					}
					recordConstructorBounds(parameter, bounds)
				}
				// A later application can supply access to the same exact constructor.
				if (!constructorAccessChecked && model.constructorAccessFailure(parameterOwner, context, supplied, caller) != null) {
					model.constructorProbeBindings[key] = context
					return@withBindings
				}
				model.constructorProbeBindings.remove(key)
				model.callableCandidates[key] = GenericCallableCandidate(context, signature(declaration))
			}
		}

		private fun newBindings(
			declaration: Declaration,
			name: Token,
			arguments: List<ValueType>,
			use: TokenSpan,
			suppliedArguments: TypeArgumentSpans?,
			owner: CMemberType? = null,
			inherited: InheritedMethodBounds? = null,
		): GenericBindings {
			val chain = bindings.chain
			val exactArguments = owner?.typeArguments.orEmpty() + arguments
			val open = exactArguments.any { it.hasTypeParameters() }
			val depth = 1 + (exactArguments.maxOfOrNull(::argumentDepth) ?: 0)
			fun limit(code: DiagnosticCode, summary: String): Nothing = throw GenericErrors.limit(code, summary, use, name, chain)
			if (depth > maximumTypeArgumentNesting) limit(DiagnosticCode.GENERIC_SUBSTITUTION_NESTING, "Generic type argument nesting limit exceeded ($maximumTypeArgumentNesting)")
			if (chain.size >= maximumGenericDependencyDepth) limit(if (open) DiagnosticCode.GENERIC_TEMPLATE_DEPENDENCY_LIMIT else DiagnosticCode.GENERIC_DEPENDENCY_LIMIT, if (open) "Generic template analysis dependency limit exceeded ($maximumGenericDependencyDepth)" else "Generic instantiation dependency limit exceeded ($maximumGenericDependencyDepth)")
			if (open && symbolicCount >= maximumGenericInstances) limit(DiagnosticCode.GENERIC_TEMPLATE_INSTANCE_LIMIT, "Too many symbolic generic instances ($maximumGenericInstances)")
			if (!open && closedCount >= maximumGenericInstances) limit(DiagnosticCode.GENERIC_INSTANCE_LIMIT, "Too many distinct generic instances ($maximumGenericInstances)")
			if (open) symbolicCount++ else closedCount++
			return scopedBindings(declaration, arguments, owner, inherited, chain + (use to name.lexeme), depth, suppliedArguments)
		}

		private fun scopedBindings(
			declaration: Declaration,
			arguments: List<ValueType>,
			owner: CMemberType?,
			inherited: InheritedMethodBounds?,
			chain: List<Pair<TokenSpan, String>>,
			depth: Int,
			supplied: TypeArgumentSpans?,
		): GenericBindings {
			val enclosing = owner?.let(model::instanceOf)?.bindings
			val inheritedOwner = inherited?.let { model.instanceOf(it.owner).bindings }
			val inheritedArguments = inherited?.let { names.parametersOf(it.declaration).zip(arguments).toMap() }.orEmpty()
			val environment = inheritedOwner?.environment.orEmpty() + enclosing?.environment.orEmpty() + inheritedArguments + names.parametersOf(declaration).zip(arguments).toMap()
			return GenericBindings(environment, chain, depth, supplied, inheritedBounds = inherited).also {
				for (scope in listOfNotNull(inheritedOwner, enclosing)) {
					it.nominalBounds.putAll(scope.nominalBounds)
					it.constructorBounds.putAll(scope.constructorBounds)
				}
			}
		}

		private fun bindSignature(declaration: CallableSignature): ClosedCallableSignature {
			bindBounds(declaration)
			checkDefaultTypes(declaration)
			return signature(declaration)
		}

		private fun boundParameter(parameter: TypeParameterSymbol): TypeParameterSymbol = bindings.inheritedBounds?.let { names.parametersOf(it.declaration)[parameter.index] } ?: parameter

		private fun bindBounds(declaration: Declaration) {
			val supplied = bindings.suppliedArguments
			for (parameter in names.parametersOf(declaration)) {
				val argument = bindings.environment.getValue(parameter)
				val source = boundParameter(parameter)
				val bounds = resolveBounds(source)
				val constructors = recordConstructorBounds(parameter, bounds)
				if (constructors.isNotEmpty() && source !== parameter) bindings.constructorBounds[source] = constructors
				for (constructor in constructors) bindOwner(constructor.declaration)
				val nominal = bounds.mapNotNull { it.nominalType }.let {
					if (constructors.isNotEmpty() && it.isEmpty()) listOf(model.objectRoot) else it
				}
				// Only the canonical declaration supplies guarantees for a symbolic argument.
				val exact = if (bindings.canonical) argument.parameterPayload() else null
				if (nominal.isNotEmpty()) {
					bindings.nominalBounds[parameter.declaration] = nominal
					if (source !== parameter) bindings.nominalBounds[source.declaration] = nominal
					exact?.nominalBounds = nominal
				}
				if (constructors.isNotEmpty()) exact?.constructorSignatures = constructors.map { it.parameterTypes }
				if (bounds.size > 1 || (supplied != null && bounds.isNotEmpty())) {
					boundChecks.add(GenericBoundCheck(
						parameter, argument, bounds, supplied?.arguments?.get(parameter.index),
						checkWrittenDuplicates = supplied == null && source === parameter,
					))
				}
			}
		}

		private fun recordConstructorBounds(parameter: TypeParameterSymbol, bounds: List<GenericBound>): List<GenericBound.Constructor> = bounds.filterIsInstance<GenericBound.Constructor>().also { if (it.isNotEmpty()) bindings.constructorBounds[parameter] = it }

		private fun checkDefaultTypes(declaration: Declaration) {
			if (!symbolic || !bindings.canonical) return
			for (parameter in names.parametersOf(declaration)) {
				val default = parameter.declaration.defaultType ?: continue
				val type = resolve(default)
				val source = boundParameter(parameter)
				val environment = bindings.environment + (parameter to type) + (source to type)
				withBindings(GenericBindings(environment, bindings.chain, bindings.argumentDepth)) {
					val bounds = resolveBounds(source)
					if (bounds.isNotEmpty()) boundChecks.add(GenericBoundCheck(parameter, type, bounds, default.span))
				}
			}
		}

		private fun arrayBound(reference: TypeReference, probeApplications: MutableMap<NominalApplicationKey, CMemberType>? = null): GenericBound.Array {
			val numeric = names.nameOf(reference) === NumberBoundName
			// The numeric proof uses the suffix shape and checks the actual element independently.
			val shape = if (numeric) resolver().withSuffixes(reference, ValueType.INT) else resolve(reference, probeApplications)
			return GenericBound.Array(shape, numeric, reference)
		}

		private fun resolveBounds(parameter: TypeParameterSymbol, probeApplications: MutableMap<NominalApplicationKey, CMemberType>? = null): List<GenericBound> = parameter.declaration.bounds.map { reference ->
			when (reference) {
				is TypeReference    -> if (reference.arraySuffixes.isNotEmpty()) arrayBound(reference, probeApplications) else {
					val payload = if (names.nameOf(reference) === NumberBoundName) GenericBound.Number(reference.span)
					else {
						val type = resolve(reference, probeApplications)
						GenericBound.Nominal(reference.span, ((type as? CNullable)?.payload ?: type) as CMemberType)
					}
					if (reference.nullable != null) GenericBound.Nullable(payload) else payload
				}

				is ConstructorBound -> GenericBound.Constructor(reference.declaration, reference.declaration.parameters.map {
					resolve(it.type, probeApplications).also { type -> if (type == ValueType.VOID) throw SemanticErrors.voidType(it.type.span) }
				})
			}
		}

		private fun checkBounds() {
			// Parent edges and interface conformance are complete before proofs run.
			for (check in boundChecks) {
				check.failure(model::hasConstructor) { boundInterfaces(it) }?.let { throw it.diagnostic(check.use) }
			}
			boundChecks.clear()
		}

		private fun checkConstructorAccess() {
			for ((instance, supplied, caller) in constructorApplications) {
				model.constructorAccessFailure(instance.definition.declaration, instance.bindings, supplied, caller)?.let { throw it.diagnostic(supplied) }
			}
			constructorApplications.clear()
		}

		private fun boundInterfaces(
			bound: CMemberType,
			probeApplications: MutableMap<NominalApplicationKey, CMemberType>? = null,
		): Sequence<CInterface> = sequence {
			if (names.interfaceSpan == null) return@sequence
			val pending = ArrayDeque<CMemberType>().also { it.add(bound) }
			val seen = mutableSetOf<CMemberType>()
			while (pending.isNotEmpty()) {
				val type = pending.removeFirst()
				if (type === model.objectRoot || !seen.add(type)) continue
				val known = when (type) {
					is CClass     -> model.interfaces.classes[type]?.implementations?.keys
					is CInterface -> {
						yield(type)
						model.interfaces.contracts[type]?.ancestors
					}

					else          -> throw InternalErrors.invariant("Expected a class or interface bound")
				}
				if (known != null) yieldAll(known)
				else pending.addAll(probeBoundParents(type, checkNotNull(probeApplications)))
			}
		}

		private fun probeBoundParents(type: CMemberType, probeApplications: MutableMap<NominalApplicationKey, CMemberType>): List<CMemberType> {
			// An overload can require applications outside the argument's ancestry.
			// Read their parent clauses without publishing candidate-only types.
			val parents = when (type) {
				is CClass     -> type.declaration.parents.map { it.type }
				is CInterface -> type.declaration.parents
				else          -> throw InternalErrors.invariant("Expected a class or interface bound")
			}
			val environment = names.parametersOf(type.declaration).zip(type.typeArguments).toMap()
			return withBindings(GenericBindings(environment)) {
				parents.map { resolve(it, probeApplications) as CMemberType }
			}
		}

		private fun signature(declaration: CallableSignature): ClosedCallableSignature = ClosedCallableSignature(declaration, declaration.parameters.map { pattern(it.type) }, declaration.returnTypes.map(::pattern), (declaration as? MethodDeclaration)?.operator?.selector?.let(::pattern))

		private fun defineClass(owner: CClass) {
			val declaration = owner.declaration
			var base = files.objectRoot
			var clause: ClassParentClause? = null
			for (parent in declaration.parents) when (val type = resolve(parent.type)) {
				is CClass     -> {
					base = type; clause = parent
				}

				is CInterface -> Unit
				else          -> throw SemanticErrors.classMember("'${parent.type.name.lexeme}' is not a class base", parent.type.name.span)
			}
			if (base.declaration.finalKeyword != null) throw SemanticErrors.classMember("Cannot inherit from final class '${base.name.lexeme}'", checkNotNull(clause).type.name.span, base.name)
			if (base === files.objectRoot && clause?.constructorArguments?.isNotEmpty() == true) throw SemanticErrors.classMember("'${base.spelling}' takes no base arguments", clause.span)
			owner.base = base
			val seen = mutableMapOf<String, Token>()
			val fields = mutableListOf<ClassField>()
			fun add(name: Token, annotation: TypeReference, visibility: Token?) {
				seen.putIfAbsent(name.lexeme, name)?.let { throw SemanticErrors.duplicate(name, it) }
				val type = resolve(annotation)
				if (type == ValueType.VOID) throw SemanticErrors.voidType(annotation.span)
				fields.add(ClassField(owner, name, annotation, fields.size, type, visibility))
			}
			for (parameter in declaration.parameters) if (parameter.fieldKeyword != null) add(parameter.parameter.name, parameter.parameter.type, parameter.visibility)
			for (field in declaration.members.filterIsInstance<ClassFieldDeclaration>()) add(field.name, field.type, field.visibility)
			owner.defineFields(fields)
		}

		private fun defineStruct(owner: CStruct) {
			val seen = mutableMapOf<String, Token>()
			owner.defineFields(owner.declaration.fields.mapIndexed { index, field ->
				seen.putIfAbsent(field.name.lexeme, field.name)?.let { throw SemanticErrors.duplicate(field.name, it) }
				val type = resolve(field.type)
				if (type == ValueType.VOID) throw SemanticErrors.voidType(field.type.span)
				StructField(field, index, type)
			})
		}

		private fun validateInheritance(): List<CClass> {
			val complete = linkedSetOf(files.objectRoot)
			for (owner in model.nominalTypes.filterIsInstance<CClass>()) {
				val path = mutableSetOf<CClass>()
				var next: CClass? = owner
				while (next != null && next !in complete) {
					if (!path.add(next)) throw SemanticErrors.classMember("Inheritance cycle involving '${next.name.lexeme}'", checkNotNull(model.baseClauseOf(next)).type.name.span)
					next = next.base
				}
				complete.addAll(path.reversed())
			}
			return complete.drop(1)
		}

		private fun validateStructs() {
			class Frame(val owner: CStruct) {

				val fields = owner.fields.iterator()
				var field: StructField? = null
			}

			val complete = mutableSetOf<CStruct>()
			for (owner in model.nominalTypes.filterIsInstance<CStruct>()) {
				if (owner in complete) continue
				val stack = mutableListOf(Frame(owner))
				val active = mutableMapOf(owner to 0)
				while (stack.isNotEmpty()) {
					val frame = stack.last()
					if (!frame.fields.hasNext()) {
						complete.add(frame.owner); active.remove(frame.owner); stack.removeLast(); continue
					}
					val field = frame.fields.next().also { frame.field = it }
					var type = field.type
					while (type is CArray || type is CNullable) type = if (type is CArray) type.element else (type as CNullable).payload
					if (type !is CStruct || type in complete) continue
					active[type]?.let { index -> throw SemanticErrors.structCycle(type.name, stack.drop(index).mapNotNull { it.field?.declaration }) }
					active[type] = stack.size
					stack.add(Frame(type))
				}
			}
		}
	}
}
