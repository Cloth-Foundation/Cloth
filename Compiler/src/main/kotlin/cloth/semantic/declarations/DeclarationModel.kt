package cloth.semantic.declarations

import cloth.data.*
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.parser.type.TypeReference
import cloth.semantic.symbols.*
import cloth.semantic.generics.GenericBodyViews
import cloth.semantic.generics.GenericMethodSignature
import cloth.semantic.generics.GenericMethodTemplate
import cloth.semantic.interfaces.InterfaceModel
import cloth.semantic.interfaces.InterfaceRequirement
import cloth.target.TargetLayout
import cloth.token.Operators
import cloth.token.Token
import java.util.*

/** Declaration identities and name resolutions, before expression/call type checking. */
class DeclarationModel internal constructor(
	internal val files: FileBindings,
	val functions: List<FunctionSymbol>,
	val target: TargetLayout,
	internal val allStructs: List<CStruct>,
	val methods: List<MethodSymbol>,
	internal val classDefinitions: Map<CClass, ClassDefinition>,
	internal val objectRoot: CClass,
	internal val enumTypes: List<CEnum>,
	private val enumNames: Map<CEnum, MethodDeclaration>,
	val genericViews: GenericBodyViews? = null,
) {

	/** Compatibility views contain only the primary file's visible names. Global storage is identity keyed. */
	val fileScope get() = files.primary.scope
	val structs = files.primary.types.filterValues { it is CStruct && it.declaration.typeParameters == null }.mapValues { it.value as CStruct }
	internal val classes = files.primary.types.mapNotNull { (name, type) -> (type as? CClass)?.let { classDefinitions[it]?.let { definition -> name to definition } } }.toMap()
	internal val enums = files.primary.types.filterValues { it is CEnum }.mapValues { it.value as CEnum }
	internal val callables: List<CallableSymbol> = functions + methods + classDefinitions.values.flatMap { it.constructors + listOfNotNull(it.destructor) }
	internal val widthTemplates = methods.filter { it.declaration.operator?.selector?.capturesWidth == true }.toSet()
	internal val concreteCallables get() = callables.filterNot { it in widthTemplates }
	internal val interfaces = genericViews?.types?.interfaces ?: InterfaceModel.Empty
	internal val interfaceMethods = buildMap<InterfaceRequirement, InterfaceMethodSymbol> {
		if (genericViews != null) for ((owner, contract) in interfaces.contracts) {
			for (method in contract.methods.values) if (method.owner === owner) {
				put(method, InterfaceMethodSymbol(method, genericViews.viewOf(method.declaration, owner) as InterfaceMethodDeclaration))
			}
		}
	}
	internal val states = StateCatalog(files, classDefinitions.keys, genericViews, typeResolver())
	internal val callableContracts: List<CallableContractSymbol> = callables + interfaceMethods.values + states.callables
	internal val concreteContracts: List<CallableContractSymbol> get() = concreteCallables + interfaceMethods.values + states.callables
	private val interfaceGroups = interfaces.contracts.mapValues { (owner, contract) ->
		contract.methods.values.map(interfaceMethods::getValue).groupBy { it.name.lexeme }
			.mapValues { (_, overloads) -> InterfaceMethodGroupSymbol(owner, overloads) }
	}
	internal fun namedType(name: String): CMemberType? = if (name == objectRoot.spelling) objectRoot else files.primary.types[name]
	internal fun namedType(name: Token): CMemberType? = files.nominal(name)
	internal fun fileScopeOf(name: Token): LexicalScope = files.context(name).scope
	internal fun signatureType(type: cloth.parser.type.TypeReference) = genericViews?.signatureType(type) ?: SignatureType.of(type, namedType(type.name))
	internal fun typeResolver(operatorWidth: Int? = null) = TypeResolver(target, operatorWidth, contextualNamedType = ::namedType, resolvedType = genericViews?.resolvedTypes?.let { it::get })
	internal fun unboundStateName(target: TypeReference): Token? = target.bareIdentifier?.takeIf {
		states.firstSyntax != null && namedType(it) == null && genericViews?.resolvedTypes?.containsKey(target) != true
	}

	internal fun initializationExpressions(declaration: CallableSignature): List<Expression> = when (declaration) {
		is PrimaryConstructorDeclaration -> {
			val definition = classDefinitions.getValue(classOwners.getValue(declaration))
			definition.baseCall?.arguments.orEmpty() + definition.fieldInitializers.map { it.expression }
		}
		is ConstructorDeclaration        -> declaration.delegation.arguments
		is StateInitialPayloadDeclaration -> listOf(declaration.expression)
		else                             -> emptyList()
	}

	internal val enumCases = IdentityHashMap<FieldAccessExpression, EnumCase>()
	private val valueTargets = IdentityHashMap<TypeCheckExpression, BinaryExpression>()
	internal fun valueTargetOf(expression: TypeCheckExpression): BinaryExpression? = valueTargets[expression]
	internal fun recordValueTarget(expression: TypeCheckExpression, application: BinaryExpression) {
		valueTargets[expression] = application
	}

	internal val classOwners = IdentityHashMap<CallableSignature, CClass>().apply {
		for (callable in callableContracts) {
			if (!states.isAttachedBody(callable)) (callable.receiver?.owner as? CClass)?.let { put(callable.declaration, it) }
		}
	}
	internal val enumOwners = IdentityHashMap<CallableSignature, CEnum>().apply {
		for (callable in callables) (callable.receiver?.owner as? CEnum)?.let { put(callable.declaration, it) }
	}
	private val operatorGroups = methods.filter { it.declaration.operator != null }.groupBy { it.name.lexeme }
	private val methodGroups = buildMap {
		val ordinary = methods.filter { it.declaration.operator == null && it.declaration.typeParameters == null }.groupBy { it.owner }
		val generic = genericViews?.types?.methodTemplates.orEmpty()
		for (owner in ordinary.keys + generic.keys) {
			val overloads = ordinary[owner].orEmpty().groupBy { it.name.lexeme }
			val templates = generic[owner].orEmpty()
			put(owner, (overloads.keys + templates.keys).associateWith { name ->
				MethodGroupSymbol(overloads[name].orEmpty(), templates[name].orEmpty())
			})
		}
	}

	internal fun hasOperator(name: String) = name in operatorGroups
	internal fun operators(owner: CMemberType, name: String, right: Boolean, arity: Int): List<MethodSymbol> {
		val declarations = operatorGroups[name].orEmpty().filter {
			it.declaration.operator?.selector == null && it.declaration.operator?.rightReceiver == right && it.parameters.size == arity
		}
		val owners = if (owner is CClass) owner.ancestry() else sequenceOf(owner)
		return owners.flatMap { ancestor -> declarations.filter { it.owner == ancestor } }.distinctBy { it.signature }.toList()
	}

	internal fun contextOperators(owner: CMemberType, name: String): List<MethodSymbol> {
		val candidates = operatorGroups[name].orEmpty().filter { it.declaration.operator?.selector != null }
		val owners = if (owner is CClass) owner.ancestry() else sequenceOf(owner)
		return owners.flatMap { ancestor -> candidates.filter { it.owner == ancestor } }.distinctBy {
			val selector = checkNotNull(it.declaration.operator?.selector)
			signatureType(selector) to selector.capturesWidth
		}.toList()
	}

	internal fun equalityOverride(owner: CClass, operation: Operators): MethodSymbol? = operators(owner, operation.toString(), right = false, arity = 1).firstOrNull() ?: if (operation == Operators.NOT_EQUAL) operators(owner, Operators.EQUAL.toString(), right = false, arity = 1).firstOrNull() else null

	internal fun formatterOverride(owner: CMemberType): MethodSymbol? = operators(owner, CompilerDataProviders.STR.toString(), right = false, arity = 0).firstOrNull()

	internal fun enumNameReader(owner: CEnum): MethodSymbol = symbolOf(enumNames.getValue(owner))

	internal fun enumFormatter(owner: CEnum): MethodSymbol = formatterOverride(owner) ?: enumNameReader(owner)

	internal fun formattingMayCallUserCode(type: ValueType): Boolean = when (type) {
		is CNullable -> formattingMayCallUserCode(type.payload)
		is CArray    -> formattingMayCallUserCode(type.element)
		is CTypeParameter -> type.nominalBound?.let(::formattingMayCallUserCode) == true
		is CClass, is CInterface -> classDefinitions.values.any { type.accepts(it.owner) && formatterOverride(it.owner) != null }
		is CEnum     -> formatterOverride(type) != null
		else         -> false
	}

	internal val namedTypeReceivers = IdentityHashMap<Expression, ValueType>()
	internal val classCreations = IdentityHashMap<ClassCreationExpression, ClassDefinition>()
	internal val fieldRoots = IdentityHashMap<FieldWriteStatement, BindingSymbol>()
	internal val functionSymbols = IdentityHashMap<FunctionDeclaration, FunctionSymbol>()
	internal val methodSymbols = IdentityHashMap<MethodDeclaration, MethodSymbol>()
	internal val parameterSymbols = IdentityHashMap<ParameterDeclaration, ParameterSymbol>()
	internal val localSymbols = IdentityHashMap<BindingDeclaration, LocalSymbol>()
	internal val groupSymbols = IdentityHashMap<BindingGroupDeclaration, List<GroupLocalSymbol?>>()
	internal val loopSymbols = IdentityHashMap<ForStatement, LoopBindingSymbol>()
	internal val loopScopes = IdentityHashMap<ForStatement, LexicalScope>()
	internal val loopBodyScopes = IdentityHashMap<LoopStatement, LexicalScope>()
	internal val loopSteppers = IdentityHashMap<ForStatement, cloth.parser.expression.Expression?>()
	internal val loopJumps = IdentityHashMap<LoopJumpStatement, LoopTarget>()
	internal val names = IdentityHashMap<NameExpression, Symbol>()
	internal val calls = IdentityHashMap<CallExpression, CallableGroupSymbol>()
	internal val receiverFields = IdentityHashMap<FieldAccessExpression, ReceiverFieldSymbol>()
	internal val receiverCalls = IdentityHashMap<MemberCallExpression, CallableGroupSymbol>()
	internal val implicitReceivers = IdentityHashMap<CallExpression, ReceiverSymbol>()
	internal val readOnlyDefaultReceivers = Collections.newSetFromMap(IdentityHashMap<MemberCallExpression, Boolean>())
	internal val readOnlyValueReceivers = Collections.newSetFromMap(IdentityHashMap<Expression, Boolean>())
	internal val fieldInitializerCalls = Collections.newSetFromMap(IdentityHashMap<MemberCallExpression, Boolean>())
	internal val assignments = IdentityHashMap<AssignmentStatement, BindingSymbol>()
	internal val multiAssignments = IdentityHashMap<MultiAssignmentStatement, List<BindingSymbol?>>()
	internal val updates = IdentityHashMap<UpdateStatement, BindingSymbol>()
	internal val callableScopes = IdentityHashMap<CallableSignature, LexicalScope>()
	internal val blockScopes = IdentityHashMap<BlockBody, LexicalScope>()
	internal val branchScopes = IdentityHashMap<ConditionalBranch, LexicalScope>()
	internal val elseScopes = IdentityHashMap<IfExpression, LexicalScope>()

	fun symbolOf(declaration: FunctionDeclaration): FunctionSymbol = functionSymbols.getValue(declaration)
	fun symbolOf(declaration: MethodDeclaration): MethodSymbol = methodSymbols.getValue(declaration)
	internal fun symbolOf(body: VirtualMethodBody): MethodSymbol =
		symbolOf(checkNotNull(genericViews).viewOf(body.declaration, body.owner) as MethodDeclaration)

	internal fun virtualFamilyOf(method: MethodSymbol): VirtualMethodFamily? {
		if (method.declaration.virtualPrefix == null && method.declaration.overridePrefix == null) return null
		val owner = method.owner as? CClass ?: return null
		val views = genericViews ?: return null
		return views.types.virtualMethods.familyOf(owner, views.originalOf(method.declaration) as MethodDeclaration)
	}
	fun symbolOf(declaration: ParameterDeclaration): ParameterSymbol = parameterSymbols.getValue(declaration)
	fun symbolOf(declaration: BindingDeclaration): LocalSymbol = localSymbols.getValue(declaration)
	fun symbolsOf(declaration: BindingGroupDeclaration): List<GroupLocalSymbol?> = groupSymbols.getValue(declaration)
	fun symbolOf(statement: ForStatement): LoopBindingSymbol = loopSymbols.getValue(statement)
	fun scopeOf(statement: ForStatement): LexicalScope = loopScopes.getValue(statement)
	fun bodyScopeOf(statement: LoopStatement): LexicalScope = loopBodyScopes.getValue(statement)
	fun symbolOf(expression: NameExpression): Symbol = names.getValue(expression)
	/** An immediate cast of self still accesses the receiver. A stored alias only has the identity. */
	internal fun directReceiverOf(expression: Expression): ReceiverSymbol? {
		val original = generateSequence(expression) { (it as? CastExpression)?.operand }.last()
		return (original as? NameExpression)?.let { names[it] as? ReceiverSymbol }
	}
	fun candidatesOf(call: CallExpression): CallableGroupSymbol = calls.getValue(call)
	internal fun methodGroup(owner: CMemberType, name: String): MethodGroupSymbol? {
		if (owner !is CClass) return methodGroups[owner]?.get(name)
		val methods = owner.ancestry().flatMap { methodGroups[it]?.get(name)?.overloads.orEmpty() }
			.distinctBy { it.signature }
			.map { method -> virtualFamilyOf(method)?.let { symbolOf(it.base) } ?: method }
			.toList()
		val templates = methodTemplates(owner, name)
		return if (methods.isEmpty() && templates.isEmpty()) null else MethodGroupSymbol(methods, templates)
	}

	internal fun methodTemplate(owner: CMemberType, source: MethodDeclaration): GenericMethodTemplate? =
		genericViews?.types?.methodTemplates?.get(owner)?.get(source.name.lexeme)?.firstOrNull { it.declaration === source }

	private fun methodTemplates(owner: CClass, name: String): List<GenericMethodTemplate> {
		if (genericViews?.types?.methodTemplates.isNullOrEmpty()) return emptyList()
		return buildList {
			val hidden = mutableSetOf<GenericMethodSignature>()
			for (ancestor in owner.ancestry()) {
				val own = methodGroups[ancestor]?.get(name)?.templates.orEmpty()
				addAll(own.filter { it.signature !in hidden })
				own.mapTo(hidden) { it.signature }
			}
		}
	}
	internal fun interfaceGroup(owner: CInterface, name: String): InterfaceMethodGroupSymbol? = interfaceGroups[owner]?.get(name)
	internal fun memberGroup(owner: CMemberType, name: String): CallableGroupSymbol? =
		if (owner is CInterface) interfaceGroup(owner, name) else methodGroup(owner, name)

	fun targetOf(statement: AssignmentStatement): BindingSymbol = assignments.getValue(statement)
	fun targetsOf(statement: MultiAssignmentStatement): List<BindingSymbol?> = multiAssignments.getValue(statement)
	fun targetOf(statement: UpdateStatement): BindingSymbol = updates.getValue(statement)
	fun scopeOf(function: FunctionDeclaration): LexicalScope = callableScopes.getValue(function)
	internal fun scopeOf(callable: CallableSignature): LexicalScope = callableScopes.getValue(callable)
	fun scopeOf(body: BlockBody): LexicalScope = blockScopes.getValue(body)
	fun scopeOf(branch: ConditionalBranch): LexicalScope = branchScopes.getValue(branch)
	fun otherwiseScopeOf(expression: IfExpression): LexicalScope? = elseScopes[expression]
}
