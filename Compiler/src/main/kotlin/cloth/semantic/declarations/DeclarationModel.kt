package cloth.semantic.declarations

import cloth.data.*
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.symbols.*
import cloth.semantic.generics.GenericBodyViews
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
	internal val callableContracts: List<CallableContractSymbol> = callables + interfaceMethods.values
	internal val concreteContracts: List<CallableContractSymbol> get() = concreteCallables + interfaceMethods.values
	private val interfaceGroups = interfaces.contracts.mapValues { (owner, contract) ->
		contract.methods.values.map(interfaceMethods::getValue).groupBy { it.name.lexeme }
			.mapValues { (_, overloads) -> InterfaceMethodGroupSymbol(owner, overloads) }
	}
	internal fun namedType(name: String): CMemberType? = if (name == objectRoot.spelling) objectRoot else files.primary.types[name]
	internal fun namedType(name: Token): CMemberType? = files.nominal(name)
	internal fun fileScopeOf(name: Token): LexicalScope = files.context(name).scope
	internal fun signatureType(type: cloth.parser.type.TypeReference) = genericViews?.signatureType(type) ?: SignatureType.of(type, namedType(type.name))
	internal fun typeResolver(operatorWidth: Int? = null) = TypeResolver(target, operatorWidth, contextualNamedType = ::namedType, resolvedType = genericViews?.resolvedTypes?.let { it::get })

	internal fun initializationExpressions(declaration: CallableSignature): List<Expression> = when (declaration) {
		is PrimaryConstructorDeclaration -> {
			val definition = classDefinitions.getValue(classOwners.getValue(declaration))
			definition.baseCall?.arguments.orEmpty() + definition.fieldInitializers.map { it.expression }
		}
		is ConstructorDeclaration        -> declaration.delegation.arguments
		else                             -> emptyList()
	}

	internal val enumCases = IdentityHashMap<FieldAccessExpression, EnumCase>()
	private val valueTargets = IdentityHashMap<TypeCheckExpression, BinaryExpression>()
	internal fun valueTargetOf(expression: TypeCheckExpression): BinaryExpression? = valueTargets[expression]
	internal fun recordValueTarget(expression: TypeCheckExpression, application: BinaryExpression) {
		valueTargets[expression] = application
	}

	internal val classOwners = IdentityHashMap<CallableSignature, CClass>().apply {
		for (callable in callables) (callable.receiver?.owner as? CClass)?.let { put(callable.declaration, it) }
	}
	internal val enumOwners = IdentityHashMap<CallableSignature, CEnum>().apply {
		for (callable in callables) (callable.receiver?.owner as? CEnum)?.let { put(callable.declaration, it) }
	}
	private val operatorGroups = methods.filter { it.declaration.operator != null }.groupBy { it.name.lexeme }
	private val methodGroups = methods.filter { it.declaration.operator == null }.groupBy { it.owner }.mapValues { (_, members) ->
		members.groupBy { it.name.lexeme }.mapValues { (_, overloads) -> MethodGroupSymbol(overloads) }
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
		is CClass, is CInterface -> classDefinitions.values.any { type.accepts(it.owner) && formatterOverride(it.owner) != null }
		is CEnum     -> formatterOverride(type) != null
		else         -> false
	}

	internal val namedTypeReceivers = IdentityHashMap<NameExpression, ValueType>()
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
		val methods = owner.ancestry().flatMap { methodGroups[it]?.get(name)?.overloads.orEmpty() }.distinctBy { it.signature }.toList()
		return methods.takeIf { it.isNotEmpty() }?.let(::MethodGroupSymbol)
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
