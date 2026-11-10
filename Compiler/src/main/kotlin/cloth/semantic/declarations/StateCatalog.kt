package cloth.semantic.declarations

import cloth.data.CClass
import cloth.data.CInterface
import cloth.data.CMemberType
import cloth.data.ValueType
import cloth.diagnostics.StateErrors
import cloth.parser.declaration.*
import cloth.parser.type.TypeReference
import cloth.semantic.generics.GenericBodyViews
import cloth.semantic.symbols.*
import cloth.semantic.typing.FunctionType
import cloth.token.Token
import cloth.token.TokenSpan
import java.util.IdentityHashMap

internal class StateDefinition(
	val owner: CClass,
	val declaration: StateBodyDeclaration,
	val selection: StateSelectionDeclaration,
	val origin: TopLevelStateDeclaration?,
	val payloadType: ValueType,
	val methods: Map<FunctionSignature, MethodSymbol>,
	val activation: StateActivationSymbol?,
	val initialPayload: StateInitialPayloadSymbol?,
) {
	val name get() = declaration.name
	val initial get() = selection.initial
}

internal data class StateMethodContract(val callable: CallableContractSymbol, val owner: CClass, val public: Boolean)

internal data class ClassStates(
	val supported: Map<String, StateDefinition> = emptyMap(),
	val initial: StateDefinition? = null,
	val contracts: Map<FunctionSignature, StateMethodContract> = emptyMap(),
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
				state.activation?.let { put(it, state) }
				state.initialPayload?.let { put(it, state) }
			}
		}
		callables = explicit + bodies.keys
		attachedReceivers = buildMap {
			for ((body, state) in bodies) if (isAttachedBody(body)) put(checkNotNull(body.receiver), state)
		}
	}

	operator fun get(owner: CClass): ClassStates? = classes[owner]
	fun payloadOf(declaration: CallableSignature): StatePayloadSymbol? = payloads[declaration]
	fun stateOf(body: CallableContractSymbol): StateDefinition? = bodies[body]
	fun isAttachedBody(body: CallableContractSymbol): Boolean = body !is StateInitialPayloadSymbol && bodies[body]?.origin != null
	fun attachedStateOf(receiver: ReceiverSymbol): StateDefinition? = attachedReceivers[receiver]

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

	private data class Members(val initializer: StateInitializerDeclaration?, val methods: Map<FunctionSignature, MethodDeclaration>)

	private inner class Builder(private val files: FileBindings, private val owners: Set<CClass>, private val views: GenericBodyViews?, private val resolver: TypeResolver) {
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
					is NestedStateDeclaration -> define(owner, selection, selection, owner)
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
			for (source in contracts) {
				val signature = FunctionSignature.of(source, resolved = ::signatureType)
				shared[signature]?.let { prior ->
					throw StateErrors.contract("State-method contract '${signature.spelling}' is already declared", source.name, prior.callable.name)
				}
				val symbol = StateMethodContractSymbol(owner, source, signature)
				explicit.add(symbol)
				shared[signature] = StateMethodContract(symbol, owner, source.visibility != null)
			}
			for (source in states) {
				for ((signature, method) in supported.getValue(source.name.lexeme).methods) {
					val public = method.declaration.visibility != null
					val prior = shared.putIfAbsent(signature, StateMethodContract(method, owner, public)) ?: continue
					if (prior.callable is MethodSymbol) {
						val labels = method.parameters.map { it.name.lexeme }
						if (labels != prior.callable.parameters.map { it.name.lexeme } || public != prior.public) {
							throw StateErrors.contract("State methods for '${signature.spelling}' must agree on labels and visibility to infer a shared contract", method.name, prior.callable.name)
						}
					}
				}
			}
			ClassStates(supported, initial ?: inherited.initial, shared)
		}

		private fun signatureType(type: TypeReference) = SignatureType.resolved(resolver.resolve(type))

		private fun attach(owner: CClass, selection: StateAttachmentDeclaration): StateDefinition {
			val origin = files.state(selection.name)
				?: throw StateErrors.attachment("Unknown top-level state '${selection.name.lexeme}'", selection.name)
			val host = hosts.getValue(origin)
			if (!host.accepts(owner)) {
				throw StateErrors.attachment("Class '${owner.spelling}' does not satisfy '${host.spelling}' for state '${origin.name.lexeme}'", selection.name, origin.host.name)
			}
			val source = checkNotNull(views).stateView(owner, origin)
			return define(owner, selection, source, host, origin)
		}

		private fun members(source: StateBodyDeclaration): Members {
			var initializer: StateInitializerDeclaration? = null
			val methods = linkedMapOf<FunctionSignature, MethodDeclaration>()
			for (member in source.members) when (member) {
				is StateInitializerDeclaration -> {
					initializer?.let { throw StateErrors.declaration("A state can declare only one entry initializer", member.keyword, it.keyword) }
					initializer = member
				}
				is MethodDeclaration -> {
					member.operator?.let { throw StateErrors.contract("A state method requires a name", member.name) }
					member.parameters.firstOrNull { it.optional }?.let {
						throw StateErrors.contract("Declare state-method defaults on the host class contract", it.name)
					}
					val signature = FunctionSignature.of(member, resolved = ::signatureType)
					methods.putIfAbsent(signature, member)?.let {
						throw StateErrors.contract("State '${source.name.lexeme}' already implements '${signature.spelling}'", member.name, it.name)
					}
				}
			}
			return Members(initializer, methods)
		}

		private fun define(owner: CClass, selection: StateSelectionDeclaration, source: StateBodyDeclaration, host: CMemberType, origin: TopLevelStateDeclaration? = null): StateDefinition {
			val type = resolver.resolve(source.payloadType)
			val payload = if (type == ValueType.VOID) null else StatePayloadSymbol(source, type)
			val prefix = selection.initial
			prefix?.let { checkStatePayloadPresence(source.name, type, it.payload != null, it.span) }
			val (initializer, declarations) = members(source)
			val methods = declarations.mapValues { (_, member) ->
				if (payload != null) payloads[member] = payload
				MethodSymbol(member, host).also { it.signatureResolver = ::signatureType }
			}
			val activation = initializer?.let {
				val declaration = StateActivationDeclaration(source.name, it)
				if (payload != null) payloads[declaration] = payload
				StateActivationSymbol(host, declaration)
			}
			val initialPayload = if (prefix?.payload == null) null else StateInitialPayloadSymbol(owner, StateInitialPayloadDeclaration(selection.name, prefix))
			return StateDefinition(owner, source, selection, origin, type, methods, activation, initialPayload)
		}
	}
}

internal fun checkStatePayloadPresence(state: Token, type: ValueType, supplied: Boolean, span: TokenSpan) {
	if (supplied == (type == ValueType.VOID)) {
		val reason = if (supplied) "takes no payload" else "requires a payload of type '${type.spelling}'"
		throw StateErrors.payload("State '${state.lexeme}' $reason", span, state)
	}
}
