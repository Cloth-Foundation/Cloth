package cloth.semantic.generics

import cloth.data.*
import cloth.diagnostics.InternalErrors
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.parser.type.TypeArgumentList
import cloth.parser.type.TypeParameterDeclaration
import cloth.parser.type.TypeReference
import cloth.semantic.ResultShape
import cloth.semantic.declarations.ClassDefinition
import cloth.semantic.interfaces.InterfaceImplementation
import cloth.semantic.interfaces.InterfaceRequirement
import cloth.semantic.interfaces.specialize
import cloth.semantic.symbols.*
import cloth.semantic.typing.FunctionSemanticModel
import cloth.semantic.typing.FunctionType
import java.util.*

/** Source-linked semantic views. Nominal identity and field layout remain in the type catalog. */
class GenericBodyViews internal constructor(
	val types: ClosedTypeModel,
	val templates: FunctionSemanticModel? = null,
	private var worklist: ClosedTypeAnalyzer.Worklist? = null,
) {

	private class CanonicalBodies {

		val expressions = IdentityHashMap<Expression, Expression>()
		val callables = IdentityHashMap<CallableSignature, CallableSignature>()
	}

	internal val resolvedTypes = IdentityHashMap<TypeReference, ValueType>()
	internal val receivers = IdentityHashMap<NameExpression, ValueType>()
	private val owners = IdentityHashMap<CMemberType, Declaration>()
	private val copies = IdentityHashMap<CMemberType, IdentityHashMap<Any, Any>>()
	private val originals = IdentityHashMap<Any, Any>()
	private val boundContexts = IdentityHashMap<Expression, GenericBindings>()
	private val callContexts = IdentityHashMap<CallSite, GenericBindings>()
	private val canonicalBodies = CanonicalBodies()
	private val canonicalStates = IdentityHashMap<ClassDeclaration, CanonicalBodies>()
	private val stateScopes = IdentityHashMap<Any, ClassDeclaration>()
	private val callableKeys = IdentityHashMap<CallableContractSymbol, ClosedCallableKey>()
	private val candidateSymbols = mutableMapOf<ClosedCallableKey, CallableContractSymbol>()
	private val candidateContracts = IdentityHashMap<CallableContractSymbol, FunctionType>()

	init {
		for ((reference, pattern) in types.rootBindings.facts) if (pattern is ClosedTypePattern.Exact) resolvedTypes[reference] = pattern.type
		receivers.putAll(types.rootBindings.receivers)
		for (owner in types.nominalTypes) {
			val view = view(owner)
			owners[owner] = when (owner) {
				is CClass     -> view.owner(owner.declaration)
				is CStruct    -> view.owner(owner.declaration)
				is CEnum      -> owner.declaration
				is CInterface -> view.owner(owner.declaration)
			}
		}
	}

	internal val functions = buildMap {
		for ((key, bindings) in types.callableInstances) if (key is ClosedFunctionKey) {
			val function = FunctionSymbol(View(bindings).function(key.declaration)).also { it.signatureResolver = ::signatureType }
			callableKeys[function] = key
			put(key, function)
		}
	}

	internal val methods = buildMap {
		for ((key, bindings) in types.callableInstances) if (key is ClosedMethodKey) {
			val method = MethodSymbol(View(bindings).method(key.declaration), key.owner).also { it.signatureResolver = ::signatureType }
			callableKeys[method] = key
			put(key, method)
		}
	}

	internal val interfaceContracts = buildMap {
		for ((key, bindings) in types.callableInstances) if (key is ClosedInterfaceMethodKey) {
			val method = interfaceSymbol(key, View(bindings).interfaceMethod(key.declaration))
			callableKeys[method] = key
			put(key, method)
		}
	}

	internal val stateContracts = buildMap {
		for ((key, bindings) in types.callableInstances) if (key is ClosedStateContractKey) {
			val contract = stateSymbol(key, View(bindings, stateScope = key.owner.declaration).stateContract(key.template.source))
			callableKeys[contract] = key
			put(key, contract)
		}
	}

	internal val stateMethods = buildMap {
		for ((key, bindings) in types.callableInstances) if (key is ClosedStateMethodKey) {
			val view = View(bindings, stateScope = key.owner.declaration).method(key.declaration)
			val method = MethodSymbol(view, key.template.host).also { it.signatureResolver = ::signatureType }
			put(key, method)
		}
	}

	private val guaranteeSymbols = IdentityHashMap<GenericBound.Constructor, ConstructorGuaranteeSymbol>()
	internal val constructorGuarantees = buildList {
		fun addGuarantees(owner: Declaration, bindings: GenericBindings, declaringClass: CClass?, stateScope: ClassDeclaration? = null) {
			if (bindings.constructorBounds.isEmpty()) return
			val inherited = bindings.inheritedBounds
			// Inherited default expressions retain their source contract's canonical body.
			val view = View(bindings, recordsCanonical = inherited == null, stateScope = stateScope)
			for (parameter in types.names.parametersOf(owner)) {
				for (bound in bindings.constructorBounds[parameter].orEmpty()) {
					val symbol = ConstructorGuaranteeSymbol(
						view.constructorGuarantee(bound.declaration), bound.parameterTypes, bindings.environment.getValue(parameter),
						(inherited?.owner ?: declaringClass) as? CClass,
					)
					guaranteeSymbols[bound] = symbol
					add(symbol)
				}
			}
		}
		for (instance in types.catalog.values) addGuarantees(instance.definition.declaration, instance.bindings, instance.type as? CClass)
		for ((key, bindings) in types.callableInstances) {
			val declaringClass = when (key) {
				is ClosedStateContractKey -> key.template.declaringClass
				is ClosedStateMethodKey   -> key.template.declaringClass
				else                      -> key.owner as? CClass
			}
			val stateScope = when (key) {
				is ClosedStateContractKey -> key.owner.declaration
				is ClosedStateMethodKey   -> ((bindings.inheritedBounds?.owner as? CClass) ?: key.owner).declaration
				else                      -> null
			}
			addGuarantees(key.declaration, bindings, declaringClass, stateScope)
		}
	}

	internal fun constructorGuaranteesFor(call: ClassCreationExpression): List<ConstructorGuaranteeSymbol> {
		val receiver = (originalOf(call) as ClassCreationExpression).receiver as? NameExpression ?: return emptyList()
		val parameter = types.names.receiverName(receiver) as? TypeParameterSymbol ?: return emptyList()
		val bindings = callContexts[call] ?: types.rootBindings
		return bindings.constructorBounds[parameter].orEmpty().map(guaranteeSymbols::getValue)
	}

	internal fun finishDiscovery() {
		worklist = null
	}

	internal fun candidateType(symbol: CallableContractSymbol): FunctionType? = candidateContracts[symbol]

	private fun interfaceSymbol(key: ClosedInterfaceMethodKey, view: InterfaceMethodDeclaration): InterfaceMethodSymbol = InterfaceMethodSymbol(
		types.interfaces.requirementOf(key.owner, key.declaration), view,
		FunctionSignature.of(view, resolved = ::signatureType),
	)

	private fun stateSymbol(key: ClosedStateContractKey, view: StateMethodContractDeclaration): StateMethodContractSymbol = StateMethodContractSymbol(key.owner, view, FunctionSignature.of(view, resolved = ::signatureType))

	private fun candidate(key: ClosedCallableKey): CallableContractSymbol = candidateSymbols.getOrPut(key) {
		val candidate = types.callableCandidates[key] ?: throw InternalErrors.invariant("Missing generic call candidate for '${key.declaration.name.lexeme}'")
		val view = View(candidate.bindings)
		val symbol = when (key) {
			is ClosedFunctionKey        -> FunctionSymbol(view.functionSignature(key.declaration)).also { it.signatureResolver = ::signatureType }
			is ClosedMethodKey          -> MethodSymbol(view.methodSignature(key.declaration), key.owner).also { it.signatureResolver = ::signatureType }
			is ClosedInterfaceMethodKey -> interfaceSymbol(key, view.interfaceMethodSignature(key.declaration))
			is ClosedStateContractKey   -> stateSymbol(key, view.stateContract(key.template.source, includeDefaults = false))
			is ClosedStateMethodKey     -> throw InternalErrors.invariant("A state implementation is selected at dispatch")
			is ClosedConstructorKey     -> ConstructorSymbol(key.declaration, key.definition).also { constructor ->
				constructor.signatureResolver = { reference ->
					SignatureType.resolved((candidate.bindings.facts.getValue(reference) as ClosedTypePattern.Exact).type)
				}
			}
		}
		val signature = candidate.signature
		candidateContracts[symbol] = FunctionType(signature.parameters.map { (it as ClosedTypePattern.Exact).type }, signature.fixedResults())
		callableKeys[symbol] = key
		symbol
	}

	internal fun functionCandidates(call: CallExpression, templates: List<FunctionSymbol>, arguments: List<ValueType>, supplied: TypeArgumentList): List<FunctionSymbol> {
		val keys = templates.map { ClosedFunctionKey(it, arguments) }
		val caller = callContexts[call] ?: types.rootBindings
		val spans = supplied.sourceSpans()
		val viable = keys.filter { types.candidateFailure(it, spans, caller) == null }.map { functions[it] ?: candidate(it) as FunctionSymbol }
		if (viable.isNotEmpty()) return viable
		throw checkNotNull(types.candidateFailure(keys.first(), spans, caller)).diagnostic(spans)
	}

	internal fun functionPatterns(template: FunctionSymbol): GenericParameterPatterns = (templates?.declarations?.genericViews?.types ?: types).functionPatterns.getValue(template.declaration)

	internal fun inferredCreation(call: ClassCreationExpression): CClass? = types.names.inferredCreations[originalOf(call) as ClassCreationExpression]

	internal fun constructorTemplates(definition: CClass): List<GenericConstructorTemplate> = (templates?.declarations?.genericViews?.types ?: types).constructorTemplates.getValue(definition.declaration)

	internal fun completeTypeArguments(
		call: CallSite,
		declaration: Declaration,
		owner: CMemberType?,
		arguments: List<ValueType?>,
	): List<ValueType> {
		val parameters = types.names.parametersOf(declaration)
		if (parameters.none { arguments.getOrNull(it.index) == null && it.declaration.defaultType != null }) {
			return arguments.takeWhile { it != null }.filterNotNull()
		}
		val cache = types.completedTypeArguments.getOrPut(declaration) { mutableMapOf() }
		return cache.getOrPut(owner to arguments) {
			val caller = callContexts[call] ?: types.rootBindings
			checkNotNull(worklist).completeCallTypeArguments(declaration, owner, arguments, caller, call.span)
		}
	}

	internal fun inferredCandidates(
		call: CallSite,
		inferred: List<Pair<ClosedCallableKey, TypeArgumentSpans>>,
		classes: Map<CClass, ClassDefinition>,
	): Map<CallableContractSymbol, TypeArgumentSpans> {
		val caller = callContexts[call] ?: types.rootBindings
		fun instance(key: ClosedCallableKey): CallableContractSymbol? = when (key) {
			is ClosedConstructorKey -> (types.applications[key.application]?.type as? CClass)?.let {
				classes[it]?.constructors?.get(key.index)
			}

			else                    -> callableInstance(key)
		}
		worklist?.prepareCallables(inferred.filter { (key, _) -> instance(key) == null }.map { (key, spans) ->
			GenericCallableRequest(key, spans, caller)
		})
		val viable = buildMap {
			for ((key, spans) in inferred) if (types.candidateFailure(key, spans, caller) == null) {
				put(instance(key) ?: candidate(key), spans)
			}
		}
		if (viable.isNotEmpty()) return viable
		val (key, spans) = inferred.first()
		throw checkNotNull(types.candidateFailure(key, spans, caller)).diagnostic(spans)
	}

	private fun callableInstance(key: ClosedCallableKey): CallableContractSymbol? = when (key) {
		is ClosedFunctionKey        -> functions[key]
		is ClosedMethodKey          -> methods[key]
		is ClosedInterfaceMethodKey -> interfaceContracts[key]
		is ClosedStateContractKey   -> stateContracts[key]
		is ClosedStateMethodKey     -> stateMethods[key]
		is ClosedConstructorKey     -> null
	}

	/** A signature-only constructor becomes a real call after its winning owner is interned. */
	internal fun requireConstructorOwner(call: ClassCreationExpression, selected: CallableSymbol, supplied: TypeArgumentSpans) {
		val key = callableKeys[selected] as? ClosedConstructorKey ?: return
		val caller = callContexts[call] ?: types.rootBindings
		checkNotNull(worklist).prepareApplication(key.application, caller, supplied)
	}

	internal fun methodCandidates(
		templates: List<GenericMethodTemplate>,
		arguments: List<ValueType>,
		spans: TypeArgumentSpans,
		call: CallSite,
		report: Boolean,
		constructorAccessChecked: Boolean = false,
	): List<MethodSymbol> {
		val keys = templates.map { ClosedMethodKey(it, arguments) }
		val caller = callContexts[call] ?: types.rootBindings
		worklist?.prepareCallables(keys.map { GenericCallableRequest(it, spans, caller) }, constructorAccessChecked)
		fun failure(key: ClosedMethodKey) = if (constructorAccessChecked) types.rejectedCallables[key] else types.candidateFailure(key, spans, caller)
		val viable = keys.filter { failure(it) == null }.map { methods[it] ?: candidate(it) as MethodSymbol }
		if (viable.isEmpty() && report) throw checkNotNull(failure(keys.first())).diagnostic(spans)
		return viable
	}

	/** Rebind the canonical selection in the caller's catalog without inferring again from its closed arguments. */
	internal fun inferredBoundMethod(
		call: MemberCallExpression,
		template: GenericMethodTemplate,
		selected: MethodSymbol,
		canonical: GenericBodyViews,
		report: Boolean,
	): MethodSymbol? {
		val key = canonical.callableKeys.getValue(selected) as ClosedMethodKey
		val arguments = rebindArguments(call, key.arguments, canonical)
		return methodCandidates(listOf(template), arguments, call.typeArgumentSpans(arguments.size), call, report, constructorAccessChecked = true).singleOrNull()
	}

	internal fun inferredBoundInterfaceMethod(
		call: MemberCallExpression,
		requirement: InterfaceRequirement,
		selected: InterfaceMethodSymbol,
		canonical: GenericBodyViews,
		report: Boolean,
	): InterfaceMethodSymbol? {
		val key = canonical.callableKeys.getValue(selected) as ClosedInterfaceMethodKey
		val arguments = rebindArguments(call, key.arguments, canonical)
		val specialized = ClosedInterfaceMethodKey(requirement.owner, requirement.declaration, arguments)
		val spans = call.typeArgumentSpans(arguments.size)
		val caller = callContexts.getValue(call)
		worklist?.prepareCallables(listOf(GenericCallableRequest(specialized, spans, caller)), constructorAccessChecked = true)
		types.rejectedCallables[specialized]?.let {
			if (report) throw it.diagnostic(spans)
			return null
		}
		return interfaceContracts[specialized] ?: candidate(specialized) as InterfaceMethodSymbol
	}

	private fun rebindArguments(call: MemberCallExpression, arguments: List<ValueType>, canonical: GenericBodyViews): List<ValueType> {
		val caller = callContexts.getValue(call)
		val rebound = IdentityHashMap<ValueType, ValueType>()
		fun rebind(type: ValueType): ValueType = rebound.getOrPut(type) {
			when (type) {
				is CTypeParameter -> {
					val actual = caller.environment.getValue(types.names.parametersOf(type.owner)[type.index])
					type.project(actual)
				}

				is CArray         -> type.copy(element = rebind(type.element))
				is CNullable      -> rebind(type.payload).let { it as? CNullable ?: CNullable(it) }
				is CMemberType    -> {
					val definition = if (type === canonical.types.objectRoot) types.objectRoot else types.definitions.getValue(type.declaration)
					if (type.typeArguments.isEmpty()) definition else {
						val application = NominalApplicationKey(definition, type.typeArguments.map(::rebind))
						types.applications[application]?.type ?: checkNotNull(worklist).prepareApplication(application, caller, call.typeArgumentSpans(application.arguments.size))
					}
				}

				else              -> type
			}
		}

		return arguments.map(::rebind)
	}

	private fun CallSite.typeArgumentSpans(count: Int) = TypeArgumentSpans(span, List(count) { span })

	internal fun callableRequest(call: CallSite, supplied: TypeArgumentSpans?, selected: CallableContractSymbol, dispatchView: CMemberType?): GenericCallableRequest? {
		val key = callableKeys[selected] ?: return null
		if (key is ClosedConstructorKey || key in types.callableInstances && (types.symbolic || dispatchView == null)) return null
		return GenericCallableRequest(key, supplied ?: call.typeArgumentSpans(key.arguments.size), callContexts[call] ?: types.rootBindings, dispatchView)
	}

	internal fun stateSpecialization(contract: CallableContractSymbol): ClosedStateContractKey? = callableKeys[contract] as? ClosedStateContractKey

	internal fun interfaceTarget(contract: InterfaceMethodSymbol, implementation: InterfaceImplementation): CallableContractSymbol? {
		val key = callableKeys[contract] as? ClosedInterfaceMethodKey ?: return null
		return checkNotNull(callableInstance(implementation.specialize(key.arguments)))
	}

	internal fun virtualTargets(method: MethodSymbol): Map<CClass, MethodSymbol>? {
		val key = callableKeys[method] as? ClosedMethodKey ?: return null
		val family = types.virtualMethods.genericFamilyOf(key.template) ?: return null
		if (family.base !== key.template) throw InternalErrors.invariant("Generic virtual call requires the base contract")
		return family.implementations.mapValues { (_, template) -> methods.getValue(ClosedMethodKey(template, key.arguments)) }
	}

	internal fun inferredResult(call: CallSite): ResultShape? = (callContexts[call] ?: types.rootBindings).inferredResults[originalOf(call) as CallSite]

	internal fun publishInferredResults(results: Map<CallSite, ResultShape>) {
		if (worklist == null || results.isEmpty()) return
		var changed = false
		for ((call, result) in results) {
			val context = callContexts[call] ?: types.rootBindings
			if (context.inferredResults.put(originalOf(call) as CallSite, result) != result) changed = true
		}
		if (changed) throw GenericAnalysisRestart()
	}

	internal fun declarationOf(owner: CMemberType) = owners.getValue(owner)
	internal fun stateView(owner: CClass, source: TopLevelStateDeclaration) = view(owner).state(source)
	fun originalOf(node: Any): Any = originals[node] ?: node
	fun viewOf(node: Any, owner: CMemberType): Any = copies.getValue(owner).getValue(node)
	internal fun signatureType(reference: TypeReference): SignatureType = resolvedTypes[reference]?.let(SignatureType::resolved) ?: SignatureType.of(reference)
	private fun canonicalFor(scope: ClassDeclaration?): CanonicalBodies? = if (scope == null) canonicalBodies else canonicalStates[scope]

	internal fun isCanonicalBoundBody(callable: CallableContractSymbol): Boolean {
		if (!types.symbolic) return false
		val body = callable.declaration
		if (canonicalFor(stateScopes[body])?.callables?.get(originalOf(body) as CallableSignature) === body) return true
		if ((callable.declaration as? MethodDeclaration)?.typeParameters != null) return false
		if ((callable.declaration as? InterfaceMethodDeclaration)?.typeParameters != null) return false
		if ((callable.declaration as? StateMethodContractDeclaration)?.typeParameters != null) return false
		val owner = callable.receiver?.owner ?: return false
		return types.catalog[owner]?.bindings?.let { it.canonical && it.nominalBounds.isNotEmpty() } == true
	}

	internal fun checkedExpression(view: Expression): Expression? {
		if (view !in boundContexts) return null
		val declarations = templates?.declarations?.genericViews ?: this
		return declarations.canonicalFor(stateScopes[view])?.expressions?.get(originalOf(view) as Expression)?.takeUnless { it === view }
	}

	internal fun specializedBounds(view: Expression, parameter: TypeParameterDeclaration): List<CMemberType>? = boundContexts[view]?.nominalBounds?.get(parameter)

	internal fun canonicalBoundMethod(declaration: MethodDeclaration): MethodDeclaration? = (canonicalFor(stateScopes[declaration])?.callables?.get(originalOf(declaration) as MethodDeclaration) as? MethodDeclaration)?.takeUnless { it === declaration }

	private fun view(owner: CMemberType) = View(types.instanceOf(owner).bindings, copies.getOrPut(owner) { IdentityHashMap() })

	private inner class View(
		private val bindings: GenericBindings,
		private val cache: IdentityHashMap<Any, Any> = IdentityHashMap(),
		recordsCanonical: Boolean = true,
		private val stateScope: ClassDeclaration? = null,
	) {

		private val checkedBodies = if (recordsCanonical && types.symbolic && bindings.canonical && bindings.nominalBounds.isNotEmpty()) {
			if (stateScope == null) canonicalBodies else canonicalStates.getOrPut(stateScope, ::CanonicalBodies)
		} else null

		@Suppress("UNCHECKED_CAST")
		private fun <T : Any> copy(source: T, create: () -> T): T = cache.getOrPut(source) {
			create().also { view ->
				originals[view] = source
				if (stateScope != null && (view is CallableSignature || view is Expression)) stateScopes[view] = stateScope
				if (source is CallableSignature && view is CallableSignature) checkedBodies?.callables?.put(source, view)
				if (source is Expression && view is Expression && bindings.nominalBounds.isNotEmpty()) {
					boundContexts[view] = bindings
					checkedBodies?.expressions?.put(source, view)
				}
			}
		} as T

		private fun type(source: TypeReference): TypeReference = copy(source) {
			source.copy(application = source.application?.copy(arguments = source.typeArguments.map(::type))).also { view ->
				val pattern = bindings.facts[source] ?: types.rootBindings.facts[source]
				if (pattern is ClosedTypePattern.Exact) resolvedTypes[view] = pattern.type
			}
		}

		private fun parameter(source: ParameterDeclaration) = copy(source) { source.copy(type = type(source.type), defaultValue = source.defaultValue?.let(::expression)) }
		fun constructorGuarantee(source: ConstructorGuaranteeDeclaration) = copy(source) { source.copy(parameters = source.parameters.map(::parameter)) }
		private fun body(source: FunctionBody): FunctionBody = copy(source) {
			when (source) {
				is BlockBody      -> source.copy(statements = source.statements.map(::statement))
				is ExpressionBody -> source.copy(expression = expression(source.expression))
				is IntrinsicBody  -> source
			}
		}

		private fun arguments(source: List<CallArgument>) = source.map { it.copy(expression = expression(it.expression)) }
		private fun delegation(source: ConstructorDelegation) = copy(source) { source.copy(suppliedArguments = arguments(source.suppliedArguments), type = type(source.type)) }
		fun method(source: MethodDeclaration) = copy(source) { source.copy(returnTypes = source.returnTypes.map(::type), parameters = source.parameters.map(::parameter), body = body(source.body), operator = source.operator?.let { it.copy(selector = it.selector?.let(::type)) }) }
		fun methodSignature(source: MethodDeclaration) = copy(source) {
			source.copy(
				returnTypes = source.returnTypes.map(::type),
				parameters = source.parameters.map { it.copy(type = type(it.type)) },
			)
		}

		fun function(source: FunctionDeclaration) = copy(source) { source.copy(returnTypes = source.returnTypes.map(::type), parameters = source.parameters.map(::parameter), body = body(source.body)) }
		fun functionSignature(source: FunctionDeclaration) = source.copy(
			returnTypes = source.returnTypes.map(::type),
			parameters = source.parameters.map { it.copy(type = type(it.type)) },
		)

		private fun stateMember(source: StateMember): StateMember = when (source) {
			is MethodDeclaration           -> if (source.typeParameters == null) method(source) else source
			is StateInitializerDeclaration -> copy(source) { source.copy(body = body(source.body) as BlockBody) }
		}

		fun stateContract(source: StateMethodContractDeclaration, includeDefaults: Boolean = true): StateMethodContractDeclaration = copy(source) {
			source.copy(
				returnTypes = source.returnTypes.map(::type),
				parameters = source.parameters.map { if (includeDefaults) parameter(it) else it.copy(type = type(it.type)) },
			)
		}

		fun state(source: TopLevelStateDeclaration): TopLevelStateDeclaration = copy(source) {
			source.copy(payloadType = type(source.payloadType), host = type(source.host), members = source.members.map(::stateMember))
		}

		fun owner(source: ClassDeclaration): ClassDeclaration = copy(source) {
			source.copy(
				parameters = source.parameters.map { it.copy(parameter = parameter(it.parameter)) },
				parents = source.parents.map { it.copy(type = type(it.type), constructorArguments = it.constructorArguments?.let(::arguments)) },
				members = source.members.map { member ->
					when (member) {
						is NestedStateDeclaration         -> copy(member) {
							member.copy(
								payloadType = type(member.payloadType),
								initial = member.initial?.let { it.copy(payload = it.payload?.let(::expression)) },
								members = member.members.map(::stateMember),
							)
						}

						is StateAttachmentDeclaration     -> copy(member) { member.copy(initial = member.initial?.let { it.copy(payload = it.payload?.let(::expression)) }) }
						is StateMethodContractDeclaration -> if (member.typeParameters == null) stateContract(member) else member
						is ClassFieldDeclaration          -> copy(member) { member.copy(type = type(member.type), initializer = expression(member.initializer)) }
						is MethodDeclaration              -> if (member.typeParameters == null) method(member) else member
						is ConstructorDeclaration         -> copy(member) { member.copy(parameters = member.parameters.map(::parameter), delegation = delegation(member.delegation), body = body(member.body) as BlockBody) }
						is DestructorDeclaration          -> copy(member) { member.copy(body = body(member.body) as BlockBody) }
					}
				},
			)
		}

		fun owner(source: StructDeclaration): StructDeclaration = copy(source) {
			source.copy(
				fields = source.fields.map { it.copy(type = type(it.type)) },
				methods = source.methods.map { if (it.typeParameters == null) method(it) else it },
			)
		}

		fun owner(source: InterfaceDeclaration): InterfaceDeclaration = copy(source) {
			source.copy(
				parents = source.parents.map(::type),
				methods = source.methods.map { if (it.typeParameters == null) interfaceMethod(it) else it },
			)
		}

		fun interfaceMethod(source: InterfaceMethodDeclaration): InterfaceMethodDeclaration = copy(source) {
			source.copy(
				returnTypes = source.returnTypes.map(::type),
				parameters = source.parameters.map(::parameter),
				fallback = source.fallback?.let { it.copy(body = body(it.body)) },
			)
		}

		fun interfaceMethodSignature(source: InterfaceMethodDeclaration): InterfaceMethodDeclaration = copy(source) {
			source.copy(
				returnTypes = source.returnTypes.map(::type),
				parameters = source.parameters.map { it.copy(type = type(it.type)) },
			)
		}

		private fun loopBody(source: LoopBody) = copy(source) { source.copy(statements = source.statements.map(::statement)) }

		private fun statement(source: Statement): Statement = copy(source) {
			when (source) {
				is BindingDeclaration         -> source.copy(type = source.type?.let(::type), initializer = expression(source.initializer))
				is BindingGroupDeclaration    -> source.copy(types = source.types.map(::type), initializer = expression(source.initializer))
				is ExpressionStatement        -> source.copy(expression = expression(source.expression))
				is AssignmentStatement        -> source.copy(value = expression(source.value))
				is MultiAssignmentStatement   -> source.copy(value = expression(source.value))
				is ElementAssignmentStatement -> source.copy(target = expression(source.target) as IndexExpression, value = expression(source.value))
				is FieldWriteStatement        -> source.copy(target = expression(source.target), value = source.value?.let(::expression))
				is UpdateStatement            -> source.copy()
				is ReturnStatement            -> source.copy(value = source.value?.let(::expression))
				is LoopJumpStatement          -> source.copy()
				is WhileStatement             -> source.copy(condition = expression(source.condition), body = loopBody(source.body))
				is DoWhileStatement           -> source.copy(condition = expression(source.condition), body = loopBody(source.body))
				is ForStatement               -> source.copy(binding = source.binding.copy(type = source.binding.type?.let(::type)), source = when (val input = source.source) {
					is ForSource.Elements -> input.copy(expression = expression(input.expression))
					is ForSource.Range    -> input.copy(start = expression(input.start), end = expression(input.end))
				}, stepper = source.stepper?.let { it.copy(value = it.value?.let(::expression)) }, body = loopBody(source.body))

				else                          -> throw InternalErrors.invariant("Unknown statement view: ${source.javaClass.name}")
			}
		}

		private fun expression(source: Expression): Expression = copy(source) {
			when (source) {
				is NameExpression            -> source.copy().also { view ->
					(bindings.receivers[source] ?: types.rootBindings.receivers[source])?.let { receivers[view] = it }
				}

				is IntegerLiteral            -> source.copy()
				is FloatingPointLiteral      -> source.copy()
				is BooleanLiteral            -> source.copy()
				is CharacterLiteral          -> source.copy()
				is StringLiteral             -> source.copy()
				is NullLiteral               -> source.copy()
				is InterpolatedString        -> source.copy(parts = source.parts.map(::expression))
				is ArrayLiteral              -> source.copy(elements = source.elements.map(::expression))
				is StructLiteral             -> source.copy(fields = source.fields.map(::expression))
				is ResultListExpression      -> source.copy(values = source.values.map(::expression))
				is TypeReceiverExpression    -> source.copy(annotation = type(source.annotation))
				is CastExpression            -> source.copy(operand = expression(source.operand), target = type(source.target))
				is StateTransitionExpression -> source.copy(receiver = expression(source.receiver), payload = source.payload?.let(::expression))
				is TypeCheckExpression       -> source.copy(operand = expression(source.operand), target = type(source.target), valueCandidate = source.valueCandidate?.let { expression(it) as FieldAccessExpression })
				is UnaryExpression           -> source.copy(operand = expression(source.operand))
				is BinaryExpression          -> source.copy(left = expression(source.left), right = expression(source.right))
				is ElvisExpression           -> source.copy(left = expression(source.left), fallback = expression(source.fallback))
				is GuardedAccessExpression   -> source.copy(payload = expression(source.payload) as GuardedReceiver, continuation = expression(source.continuation))
				is GuardedReceiver           -> GuardedReceiver(expression(source.source))
				is IndexExpression           -> source.copy(array = expression(source.array), index = expression(source.index))
				is FieldAccessExpression     -> source.copy(receiver = expression(source.receiver))
				is ProviderAccessExpression  -> source.copy(receiver = expression(source.receiver))
				is CallExpression            -> source.copy(
					suppliedArguments = arguments(source.suppliedArguments),
					typeArguments = source.typeArguments?.let { it.copy(arguments = it.arguments.map(::type)) },
				).also { callContexts[it] = bindings }

				is MemberCallExpression      -> source.copy(
					receiver = expression(source.receiver),
					suppliedArguments = arguments(source.suppliedArguments),
					typeArguments = source.typeArguments?.let { it.copy(arguments = it.arguments.map(::type)) },
				).also { callContexts[it] = bindings }

				is ClassCreationExpression   -> source.copy(receiver = expression(source.receiver), suppliedArguments = arguments(source.suppliedArguments)).also { callContexts[it] = bindings }
				is ClassDeletionExpression   -> source.copy(receiver = expression(source.receiver))
				is StateCallExpression       -> source.copy(
					receiver = expression(source.receiver),
					suppliedArguments = arguments(source.suppliedArguments),
					typeArguments = source.typeArguments?.let { it.copy(arguments = it.arguments.map(::type)) },
				).also { callContexts[it] = bindings }

				is ConstructorDelegation     -> delegation(source)
				is FunctionBlockExpression   -> source.copy(declaration = function(source.declaration))
				is IfExpression              -> source.copy(branches = source.branches.map { it.copy(condition = expression(it.condition), statements = it.statements.map(::statement)) }, otherwise = source.otherwise?.map(::statement))
				else                         -> throw InternalErrors.invariant("Unknown expression view: ${source.javaClass.name}")
			}
		}
	}
}
