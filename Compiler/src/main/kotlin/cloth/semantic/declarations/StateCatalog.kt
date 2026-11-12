package cloth.semantic.declarations

import cloth.data.*
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.StateErrors
import cloth.parser.declaration.*
import cloth.parser.type.TypeReference
import cloth.semantic.generics.*
import cloth.semantic.symbols.*
import cloth.semantic.typing.FunctionType
import cloth.semantic.typing.ResolvedStateCall
import cloth.token.Token
import cloth.token.TokenSpan
import java.util.*

internal class StateDefinition(
	val owner: CClass,
	val declaration: StateBodyDeclaration,
	val selection: StateSelectionDeclaration,
	val origin: TopLevelStateDeclaration?,
	val payloadType: ValueType,
	val methods: Map<FunctionSignature, MethodSymbol>,
	val genericMethods: Map<GenericMethodSignature, GenericStateMethod>,
	val specializedMethods: Map<ClosedStateMethodKey, MethodSymbol>,
	val activation: StateActivationSymbol?,
	val initialPayload: StateInitialPayloadSymbol?,
) {

	val name get() = declaration.name
	val initial get() = selection.initial

	fun implements(call: ResolvedStateCall): Boolean {
		val key = call.specialization ?: return call.contract.signature in methods
		return key.template.signature in genericMethods
	}

	fun target(call: ResolvedStateCall): MethodSymbol? {
		val key = call.specialization ?: return methods[call.contract.signature]
		val template = genericMethods[key.template.signature] ?: return null
		return specializedMethods.getValue(ClosedStateMethodKey(template, key.arguments))
	}
}

internal data class StateMethodContract(val callable: CallableContractSymbol, val owner: CClass, val public: Boolean)

internal data class GenericStateContract(
	val owner: CClass,
	val declaration: CallableSignature,
	val signature: GenericMethodSignature,
	val public: Boolean,
	val slots: List<CTypeParameter>,
	val declaringClass: CClass? = owner,
) {

	val patterns = GenericParameterPatterns(signature.valueParameters.parameterTypes.map { checkNotNull(it.exact) }, slots)

	val source: StateMethodContractDeclaration
		get() = when (val member = declaration) {
			is StateMethodContractDeclaration -> member
			is MethodDeclaration              -> StateMethodContractDeclaration(
				member.keyword, member.visibility, member.name, member.returnTypes, member.parameters, member.span, member.typeParameters,
			)

			else                              -> throw InternalErrors.invariant("Invalid generic state contract declaration")
		}
}

internal data class GenericStateMethod(
	val owner: CClass,
	val host: CMemberType,
	val declaration: MethodDeclaration,
	val signature: GenericMethodSignature,
	val declaringClass: CClass?,
)

internal data class ClassStates(
	val supported: Map<String, StateDefinition> = emptyMap(),
	val initial: StateDefinition? = null,
	val contracts: Map<FunctionSignature, StateMethodContract> = emptyMap(),
	val genericContracts: Map<GenericMethodSignature, GenericStateContract> = emptyMap(),
)

/** Inheritance copies the effective sets while retaining the defining state and contract identities. */
internal class StateCatalog(files: FileBindings, owners: Set<CClass>, views: GenericBodyViews?, resolver: TypeResolver) {

	val firstSyntax = files.firstStateSyntax
	private val declaredStates = mutableListOf<StateDefinition>()
	val definitions: List<StateDefinition> get() = declaredStates
	private val explicit = mutableListOf<StateMethodContractSymbol>()
	private val classes = mutableMapOf<CClass, ClassStates>()
	private val payloads = IdentityHashMap<CallableSignature, StatePayloadSymbol>()
	private val bodies: Map<CallableContractSymbol, StateDefinition>
	private val attachedReceivers: Map<ReceiverSymbol, StateDefinition>
	val callables: List<CallableContractSymbol>

	init {
		if (firstSyntax != null) {
			val builder = Builder(files, owners, views, resolver)
			owners.forEach(builder::collect)
		}
		bodies = buildMap {
			for (state in definitions) {
				state.methods.values.forEach { put(it, state) }
				state.specializedMethods.values.forEach { put(it, state) }
				state.activation?.let { put(it, state) }
				state.initialPayload?.let { put(it, state) }
			}
		}
		callables = explicit + views?.stateContracts?.values.orEmpty() + bodies.keys
		attachedReceivers = buildMap {
			for ((body, state) in bodies) if (isAttachedBody(body)) put(checkNotNull(body.receiver), state)
		}
	}

	operator fun get(owner: CClass): ClassStates? = classes[owner]
	fun payloadOf(declaration: CallableSignature): StatePayloadSymbol? = payloads[declaration]
	fun stateOf(body: CallableContractSymbol): StateDefinition? = bodies[body]
	fun isAttachedBody(body: CallableContractSymbol): Boolean = body !is StateInitialPayloadSymbol && bodies[body]?.origin != null
	fun attachedStateOf(receiver: ReceiverSymbol): StateDefinition? = attachedReceivers[receiver]

	fun dispatchStates(owner: CClass): Sequence<StateDefinition> = definitions.asSequence().filter { owner.accepts(it.owner) || it.owner.accepts(owner) }

	fun genericContracts(): Set<GenericStateContract> = classes.values.flatMap { it.genericContracts.values }.toSet()

	fun inheritedBounds(method: GenericStateMethod): InheritedMethodBounds? {
		val contract = classes.getValue(method.owner).genericContracts.getValue(method.signature)
		return (contract.declaration as? StateMethodContractDeclaration)?.let { InheritedMethodBounds(contract.owner, it) }
	}

	fun checkResults(signatures: Map<CallableContractSymbol, FunctionType>) {
		for (state in definitions) {
			val contracts = classes.getValue(state.owner).contracts
			for ((key, method) in state.methods) {
				val contract = contracts.getValue(key).callable
				val actual = signatures.getValue(method).results
				val required = signatures.getValue(contract).results
				if (actual != required) {
					throw StateErrors.contract("State method '${key.spelling}' returns '${actual.spelling}', but its shared contract returns '${required.spelling}'", method.name, contract.name)
				}
			}
		}
	}

	private data class Members(
		val initializer: StateInitializerDeclaration?,
		val methods: Map<FunctionSignature, MethodDeclaration>,
		val genericMethods: Map<GenericMethodSignature, MethodDeclaration>,
	)

	private inner class Builder(private val files: FileBindings, private val owners: Set<CClass>, private val views: GenericBodyViews?, private val resolver: TypeResolver) {

		private val specialized = views?.stateMethods.orEmpty().entries.groupBy { it.key.template }
		private val keys get() = checkNotNull(views).types.signatureKeys
		private fun environment(owner: CClass?) = owner?.let { checkNotNull(views).types.instanceOf(it).bindings.environment }.orEmpty()

		private val hosts = IdentityHashMap<TopLevelStateDeclaration, CMemberType>().apply {
			for (state in files.topLevelStates) {
				val host = resolver.resolve(state.host)
				if (host !is CClass && host !is CInterface) {
					throw StateErrors.attachment("State '${state.name.lexeme}' requires a class or interface host contract", state.host.name)
				}
				put(state, host)
				resolver.resolve(state.payloadType)
				members(state)
			}
		}

		fun collect(owner: CClass): ClassStates = classes.getOrPut(owner) {
			val inherited = owner.base?.takeIf { it in owners }?.let(::collect) ?: ClassStates()
			val declaration = (views?.declarationOf(owner) as? ClassDeclaration) ?: owner.declaration
			val states = declaration.members.filterIsInstance<StateSelectionDeclaration>()
			val contracts = declaration.members.filterIsInstance<StateMethodContractDeclaration>()
			if (states.isEmpty() && contracts.isEmpty()) return@getOrPut inherited
			val supported = inherited.supported.toMutableMap()
			var initial: StateDefinition? = null
			for (selection in states) {
				supported[selection.name.lexeme]?.let { prior ->
					val reason = if (prior.owner == owner) "is already declared" else "is inherited from '${prior.owner.spelling}'"
					throw StateErrors.declaration("State '${selection.name.lexeme}' $reason", selection.name, prior.selection.name)
				}
				val prefix = selection.initial
				if (prefix != null) initial?.let { prior ->
					throw StateErrors.declaration("Class '${owner.spelling}' can select only one default state", prefix.keyword, checkNotNull(prior.initial).keyword)
				}
				val state = when (selection) {
					is NestedStateDeclaration     -> define(owner, selection, selection, owner)
					is StateAttachmentDeclaration -> attach(owner, selection)
				}
				if (prefix != null) initial = state
				supported[selection.name.lexeme] = state
				declaredStates.add(state)
			}
			if (states.isNotEmpty() && initial == null) {
				throw StateErrors.declaration("Class '${owner.spelling}' must select a default state", declaration.name)
			}

			val shared = inherited.contracts.toMutableMap()
			val generic = inherited.genericContracts.toMutableMap()
			for (source in contracts) {
				if (source.typeParameters != null) {
					val signature = genericSignature(source, owner)
					val contract = GenericStateContract(owner, source, signature, source.visibility != null, keys.parameterSlots(source))
					generic.putIfAbsent(signature, contract)?.let { prior ->
						throw StateErrors.contract("State-method contract '${signature.valueParameters.spelling}' is already declared", source.name, prior.declaration.name)
					}
					continue
				}
				val signature = FunctionSignature.of(source, resolved = ::signatureType)
				shared[signature]?.let { prior ->
					throw StateErrors.contract("State-method contract '${signature.spelling}' is already declared", source.name, prior.callable.name)
				}
				val symbol = StateMethodContractSymbol(owner, source, signature)
				explicit.add(symbol)
				shared[signature] = StateMethodContract(symbol, owner, source.visibility != null)
			}
			for (source in states) {
				val state = supported.getValue(source.name.lexeme)
				for ((signature, method) in state.methods) {
					val public = method.declaration.visibility != null
					val prior = shared.putIfAbsent(signature, StateMethodContract(method, owner, public)) ?: continue
					if (prior.callable is MethodSymbol) {
						val labels = method.parameters.map { it.name.lexeme }
						if (labels != prior.callable.parameters.map { it.name.lexeme } || public != prior.public) {
							throw StateErrors.contract("State methods for '${signature.spelling}' must agree on labels and visibility to infer a shared contract", method.name, prior.callable.name)
						}
					}
				}
				for ((signature, method) in state.genericMethods) {
					val declaration = method.declaration
					val contract = GenericStateContract(owner, declaration, signature, declaration.visibility != null, keys.parameterSlots(declaration), method.declaringClass)
					val prior = generic.putIfAbsent(signature, contract) ?: continue
					checkGenericContract(method, prior)
				}
			}
			ClassStates(supported, initial ?: inherited.initial, shared, generic)
		}

		private fun genericSignature(declaration: CallableSignature, owner: CClass?): GenericMethodSignature = GenericMethodSignature(checkNotNull(views).types.names.parametersOf(declaration).size, keys.signature(declaration, environment(owner)))

		private fun checkGenericContract(method: GenericStateMethod, contract: GenericStateContract) {
			val member = method.declaration
			val source = contract.declaration
			val signature = contract.signature.valueParameters.spelling
			val actual = environment(method.owner)
			val expected = environment(contract.owner)
			if (source is StateMethodContractDeclaration) {
				val bound = checkNotNull(member.typeParameters).parameters.firstOrNull { it.bounds.isNotEmpty() }
				if (bound != null) throw StateErrors.contract("State methods inherit bounds from the host class contract", bound.name, source.name)
			} else {
				if (member.parameters.map { it.name.lexeme } != source.parameters.map { it.name.lexeme } || (member.visibility != null) != contract.public) {
					throw StateErrors.contract("State methods for '$signature' must agree on labels and visibility to infer a shared contract", member.name, source.name)
				}
				if (keys.bounds(member, actual) != keys.bounds(source, expected) || keys.constructorContracts(member, actual) != keys.constructorContracts(source, expected)) {
					throw StateErrors.contract("State methods for '$signature' must agree on type parameter bounds to infer a shared contract", member.name, source.name)
				}
			}
			val results = keys.results(member, actual)
			val required = keys.results(source, expected)
			if (results != required) {
				throw StateErrors.contract("State method '$signature' returns '${results.spelling}', but its shared contract returns '${required.spelling}'", member.name, source.name)
			}
		}

		private fun signatureType(type: TypeReference) = SignatureType.resolved(resolver.resolve(type))

		private fun attach(owner: CClass, selection: StateAttachmentDeclaration): StateDefinition {
			val origin = files.state(selection.name) ?: throw StateErrors.attachment("Unknown top-level state '${selection.name.lexeme}'", selection.name)
			val host = hosts.getValue(origin)
			if (!host.accepts(owner)) {
				throw StateErrors.attachment("Class '${owner.spelling}' does not satisfy '${host.spelling}' for state '${origin.name.lexeme}'", selection.name, origin.host.name)
			}
			val source = checkNotNull(views).stateView(owner, origin)
			return define(owner, selection, source, host, origin)
		}

		private fun members(source: StateBodyDeclaration, owner: CClass? = null): Members {
			var initializer: StateInitializerDeclaration? = null
			val methods = linkedMapOf<FunctionSignature, MethodDeclaration>()
			val generic = linkedMapOf<GenericMethodSignature, MethodDeclaration>()
			for (member in source.members) when (member) {
				is StateInitializerDeclaration -> {
					initializer?.let { throw StateErrors.declaration("A state can declare only one entry initializer", member.keyword, it.keyword) }
					initializer = member
				}

				is MethodDeclaration           -> {
					member.operator?.let { throw StateErrors.contract("A state method requires a name", member.name) }
					member.parameters.firstOrNull { it.optional }?.let {
						throw StateErrors.contract("Declare state-method defaults on the host class contract", it.name)
					}
					if (member.typeParameters != null) {
						member.typeParameters.parameters.firstOrNull { it.defaultType != null }?.let {
							throw StateErrors.contract("Declare state-method type defaults on the host class contract", it.name)
						}
						val signature = genericSignature(member, owner)
						generic.putIfAbsent(signature, member)?.let {
							throw StateErrors.contract("State '${source.name.lexeme}' already implements '${signature.valueParameters.spelling}'", member.name, it.name)
						}
						continue
					}
					val signature = FunctionSignature.of(member, resolved = ::signatureType)
					methods.putIfAbsent(signature, member)?.let {
						throw StateErrors.contract("State '${source.name.lexeme}' already implements '${signature.spelling}'", member.name, it.name)
					}
				}
			}
			return Members(initializer, methods, generic)
		}

		private fun define(owner: CClass, selection: StateSelectionDeclaration, source: StateBodyDeclaration, host: CMemberType, origin: TopLevelStateDeclaration? = null): StateDefinition {
			val type = resolver.resolve(source.payloadType)
			val payload = if (type == ValueType.VOID) null else StatePayloadSymbol(source, type)
			val prefix = selection.initial
			prefix?.let { checkStatePayloadPresence(source.name, type, it.payload != null, it.span) }
			val (initializer, declarations, generic) = members(source, owner)
			val methods = declarations.mapValues { (_, member) ->
				if (payload != null) payloads[member] = payload
				MethodSymbol(member, host).also { it.signatureResolver = ::signatureType }
			}
			val activation = initializer?.let {
				val declaration = StateActivationDeclaration(source.name, it)
				if (payload != null) payloads[declaration] = payload
				StateActivationSymbol(host, declaration)
			}
			val templates = generic.mapValues { (signature, member) -> GenericStateMethod(owner, host, member, signature, owner.takeIf { origin == null }) }
			val instances = buildMap {
				for (template in templates.values) for ((key, method) in specialized[template].orEmpty()) {
					if (payload != null) payloads[method.declaration] = payload
					put(key, method)
				}
			}
			val initialPayload = if (prefix?.payload == null) null else StateInitialPayloadSymbol(owner, StateInitialPayloadDeclaration(selection.name, prefix))
			return StateDefinition(owner, source, selection, origin, type, methods, templates, instances, activation, initialPayload)
		}
	}
}

internal fun checkStatePayloadPresence(state: Token, type: ValueType, supplied: Boolean, span: TokenSpan) {
	if (supplied == (type == ValueType.VOID)) {
		val reason = if (supplied) "takes no payload" else "requires a payload of type '${type.spelling}'"
		throw StateErrors.payload("State '${state.lexeme}' $reason", span, state)
	}
}
