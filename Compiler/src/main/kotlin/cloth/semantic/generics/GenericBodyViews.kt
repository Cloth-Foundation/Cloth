package cloth.semantic.generics

import cloth.data.*
import cloth.diagnostics.InternalErrors
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.parser.type.TypeReference
import cloth.semantic.symbols.FunctionSymbol
import cloth.semantic.symbols.SignatureType
import cloth.semantic.typing.FunctionSemanticModel
import java.util.IdentityHashMap

/** Source-linked semantic views. Nominal identity and field layout remain in the type catalog. */
class GenericBodyViews internal constructor(val types: ClosedTypeModel, val templates: FunctionSemanticModel? = null) {
	internal val resolvedTypes = IdentityHashMap<TypeReference, ValueType>()
	internal val receivers = IdentityHashMap<NameExpression, ValueType>()
	private val owners = IdentityHashMap<CMemberType, Declaration>()
	private val copies = IdentityHashMap<CMemberType, IdentityHashMap<Any, Any>>()
	private val originals = IdentityHashMap<Any, Any>()

	init {
		for ((reference, pattern) in types.rootBindings.facts) if (pattern is ClosedTypePattern.Exact) resolvedTypes[reference] = pattern.type
		receivers.putAll(types.rootBindings.receivers)
		for (owner in types.nominalTypes) {
			val view = view(owner)
			owners[owner] = when (owner) {
				is CClass -> view.owner(owner.declaration)
				is CStruct -> view.owner(owner.declaration)
				is CEnum -> owner.declaration
				is CInterface -> view.owner(owner.declaration)
			}
		}
	}

	internal val functions = types.functionInstances.mapValues { (key, bindings) ->
		FunctionSymbol(View(bindings).function(key.template.declaration)).also { it.signatureResolver = ::signatureType }
	}

	internal fun specialize(template: FunctionSymbol, arguments: List<ValueType>): FunctionSymbol =
		functions[ClosedFunctionKey(template, arguments)] ?: throw InternalErrors.invariant("Missing specialization for '${template.name.lexeme}'")

	internal fun declarationOf(owner: CMemberType) = owners.getValue(owner)
	internal fun stateView(owner: CClass, source: TopLevelStateDeclaration) = view(owner).state(source)
	fun originalOf(node: Any): Any = originals[node] ?: node
	fun viewOf(node: Any, owner: CMemberType): Any = copies.getValue(owner).getValue(node)
	internal fun signatureType(reference: TypeReference): SignatureType = resolvedTypes[reference]?.let(SignatureType::resolved) ?: SignatureType.of(reference)

	private fun view(owner: CMemberType) = View(types.instanceOf(owner).bindings, copies.getOrPut(owner) { IdentityHashMap() })

	private inner class View(private val bindings: GenericBindings, private val cache: IdentityHashMap<Any, Any> = IdentityHashMap()) {
		@Suppress("UNCHECKED_CAST")
		private fun <T : Any> copy(source: T, create: () -> T): T = cache.getOrPut(source) { create().also { originals[it] = source } } as T

		private fun type(source: TypeReference): TypeReference = copy(source) {
			source.copy(application = source.application?.copy(arguments = source.typeArguments.map(::type))).also { view ->
				val pattern = bindings.facts[source] ?: types.rootBindings.facts[source]
				if (pattern is ClosedTypePattern.Exact) resolvedTypes[view] = pattern.type
			}
		}
		private fun parameter(source: ParameterDeclaration) = copy(source) { source.copy(type = type(source.type), defaultValue = source.defaultValue?.let(::expression)) }
		private fun body(source: FunctionBody): FunctionBody = copy(source) { when (source) {
			is BlockBody -> source.copy(statements = source.statements.map(::statement))
			is ExpressionBody -> source.copy(expression = expression(source.expression))
		} }
		private fun arguments(source: List<CallArgument>) = source.map { it.copy(expression = expression(it.expression)) }
		private fun delegation(source: ConstructorDelegation) = copy(source) { source.copy(suppliedArguments = arguments(source.suppliedArguments), type = type(source.type)) }
		private fun method(source: MethodDeclaration) = copy(source) { source.copy(returnTypes = source.returnTypes.map(::type), parameters = source.parameters.map(::parameter), body = body(source.body), operator = source.operator?.let { it.copy(selector = it.selector?.let(::type)) }) }
		fun function(source: FunctionDeclaration) = copy(source) { source.copy(returnTypes = source.returnTypes.map(::type), parameters = source.parameters.map(::parameter), body = body(source.body)) }
		private fun stateMember(source: StateMember): StateMember = when (source) {
			is MethodDeclaration -> method(source)
			is StateInitializerDeclaration -> copy(source) { source.copy(body = body(source.body) as BlockBody) }
		}
		fun state(source: TopLevelStateDeclaration): TopLevelStateDeclaration = copy(source) {
			source.copy(payloadType = type(source.payloadType), host = type(source.host), members = source.members.map(::stateMember))
		}

		fun owner(source: ClassDeclaration): ClassDeclaration = copy(source) { source.copy(
			parameters = source.parameters.map { it.copy(parameter = parameter(it.parameter)) },
			parents = source.parents.map { it.copy(type = type(it.type), constructorArguments = it.constructorArguments?.let(::arguments)) },
			members = source.members.map { member -> when (member) {
				is NestedStateDeclaration -> copy(member) { member.copy(
					payloadType = type(member.payloadType),
					initial = member.initial?.let { it.copy(payload = it.payload?.let(::expression)) },
					members = member.members.map(::stateMember),
				) }
				is StateAttachmentDeclaration -> copy(member) { member.copy(initial = member.initial?.let { it.copy(payload = it.payload?.let(::expression)) }) }
				is StateMethodContractDeclaration -> copy(member) { member.copy(returnTypes = member.returnTypes.map(::type), parameters = member.parameters.map(::parameter)) }
				is ClassFieldDeclaration -> copy(member) { member.copy(type = type(member.type), initializer = expression(member.initializer)) }
				is MethodDeclaration -> method(member)
				is ConstructorDeclaration -> copy(member) { member.copy(parameters = member.parameters.map(::parameter), delegation = delegation(member.delegation), body = body(member.body) as BlockBody) }
				is DestructorDeclaration -> copy(member) { member.copy(body = body(member.body) as BlockBody) }
			} },
		) }
		fun owner(source: StructDeclaration): StructDeclaration = copy(source) { source.copy(fields = source.fields.map { it.copy(type = type(it.type)) }, methods = source.methods.map(::method)) }
		fun owner(source: InterfaceDeclaration): InterfaceDeclaration = copy(source) { source.copy(
			parents = source.parents.map(::type),
			methods = source.methods.map { member -> copy(member) { member.copy(
				returnTypes = member.returnTypes.map(::type),
				parameters = member.parameters.map(::parameter),
				fallback = member.fallback?.let { it.copy(body = body(it.body)) },
			) } },
		) }
		private fun loopBody(source: LoopBody) = copy(source) { source.copy(statements = source.statements.map(::statement)) }

		private fun statement(source: Statement): Statement = copy(source) { when (source) {
			is BindingDeclaration -> source.copy(type = source.type?.let(::type), initializer = expression(source.initializer))
			is BindingGroupDeclaration -> source.copy(types = source.types.map(::type), initializer = expression(source.initializer))
			is ExpressionStatement -> source.copy(expression = expression(source.expression))
			is AssignmentStatement -> source.copy(value = expression(source.value))
			is MultiAssignmentStatement -> source.copy(value = expression(source.value))
			is ElementAssignmentStatement -> source.copy(target = expression(source.target) as IndexExpression, value = expression(source.value))
			is FieldWriteStatement -> source.copy(target = expression(source.target), value = source.value?.let(::expression))
			is UpdateStatement -> source.copy()
			is ReturnStatement -> source.copy(value = source.value?.let(::expression))
			is LoopJumpStatement -> source.copy()
			is WhileStatement -> source.copy(condition = expression(source.condition), body = loopBody(source.body))
			is DoWhileStatement -> source.copy(condition = expression(source.condition), body = loopBody(source.body))
			is ForStatement -> source.copy(binding = source.binding.copy(type = source.binding.type?.let(::type)), source = when (val input = source.source) {
				is ForSource.Elements -> input.copy(expression = expression(input.expression))
				is ForSource.Range -> input.copy(start = expression(input.start), end = expression(input.end))
			}, stepper = source.stepper?.let { it.copy(value = it.value?.let(::expression)) }, body = loopBody(source.body))
			else -> throw InternalErrors.invariant("Unknown statement view: ${source.javaClass.name}")
		} }

		private fun expression(source: Expression): Expression = copy(source) { when (source) {
			is NameExpression -> source.copy().also { view ->
				(bindings.receivers[source] ?: types.rootBindings.receivers[source])?.let { receivers[view] = it }
			}
			is IntegerLiteral -> source.copy()
			is FloatingPointLiteral -> source.copy()
			is BooleanLiteral -> source.copy()
			is CharacterLiteral -> source.copy()
			is StringLiteral -> source.copy()
			is NullLiteral -> source.copy()
			is InterpolatedString -> source.copy(parts = source.parts.map(::expression))
			is ArrayLiteral -> source.copy(elements = source.elements.map(::expression))
			is StructLiteral -> source.copy(fields = source.fields.map(::expression))
			is ResultListExpression -> source.copy(values = source.values.map(::expression))
			is TypeReceiverExpression -> source.copy(annotation = type(source.annotation))
			is CastExpression -> source.copy(operand = expression(source.operand), target = type(source.target))
			is StateTransitionExpression -> source.copy(receiver = expression(source.receiver), payload = source.payload?.let(::expression))
			is TypeCheckExpression -> source.copy(operand = expression(source.operand), target = type(source.target))
			is UnaryExpression -> source.copy(operand = expression(source.operand))
			is BinaryExpression -> source.copy(left = expression(source.left), right = expression(source.right))
			is ElvisExpression -> source.copy(left = expression(source.left), fallback = expression(source.fallback))
			is GuardedAccessExpression -> source.copy(payload = expression(source.payload) as GuardedReceiver, continuation = expression(source.continuation))
			is GuardedReceiver -> GuardedReceiver(expression(source.source))
			is IndexExpression -> source.copy(array = expression(source.array), index = expression(source.index))
			is FieldAccessExpression -> source.copy(receiver = expression(source.receiver))
			is ProviderAccessExpression -> source.copy(receiver = expression(source.receiver))
			is CallExpression -> source.copy(
				suppliedArguments = arguments(source.suppliedArguments),
				typeArguments = source.typeArguments?.let { it.copy(arguments = it.arguments.map(::type)) },
			)
			is MemberCallExpression -> source.copy(receiver = expression(source.receiver), suppliedArguments = arguments(source.suppliedArguments))
			is ClassCreationExpression -> source.copy(receiver = expression(source.receiver), suppliedArguments = arguments(source.suppliedArguments))
			is ClassDeletionExpression -> source.copy(receiver = expression(source.receiver))
			is StateCallExpression -> source.copy(receiver = expression(source.receiver), suppliedArguments = arguments(source.suppliedArguments))
			is ConstructorDelegation -> delegation(source)
			is FunctionBlockExpression -> source.copy(declaration = function(source.declaration))
			is IfExpression -> source.copy(branches = source.branches.map { it.copy(condition = expression(it.condition), statements = it.statements.map(::statement)) }, otherwise = source.otherwise?.map(::statement))
			else -> throw InternalErrors.invariant("Unknown expression view: ${source.javaClass.name}")
		} }
	}
}
