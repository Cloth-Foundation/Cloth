package cloth.semantic

import cloth.data.*
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.symbols.*
import cloth.target.TargetLayout
import cloth.token.Operators
import java.util.*

/** Declaration identities and name resolutions, before expression/call type checking. */
class DeclarationModel internal constructor(
	val fileScope: LexicalScope,
	val functions: List<FunctionSymbol>,
	val target: TargetLayout,
	val structs: Map<String, CStruct>,
	val methods: List<MethodSymbol>,
	internal val classes: Map<String, ClassDefinition>,
	internal val objectRoot: CClass,
	internal val enums: Map<String, CEnum>,
	private val enumDefaults: Map<CEnum, MethodDeclaration>,
) {

	internal val callables: List<CallableSymbol> = functions + methods + classes.values.flatMap { it.constructors + listOfNotNull(it.destructor) }
	internal val widthTemplates = methods.filter { it.declaration.operator?.selector?.capturesWidth == true }.toSet()
	internal val concreteCallables get() = callables.filterNot { it in widthTemplates }
	internal fun namedType(name: String): CMemberType? = if (name == objectRoot.spelling) objectRoot else classes[name]?.owner ?: structs[name] ?: enums[name]
	internal val enumCases = IdentityHashMap<FieldAccessExpression, EnumCase>()
	internal val valueChecks = IdentityHashMap<TypeCheckExpression, BinaryExpression>()
	internal val classOwners = IdentityHashMap<CallableDeclaration, CClass>().apply {
		for (callable in callables) (callable.receiver?.owner as? CClass)?.let { put(callable.declaration, it) }
	}
	internal val enumOwners = IdentityHashMap<CallableDeclaration, CEnum>().apply {
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
			SignatureType.of(selector) to selector.capturesWidth
		}.toList()
	}

	internal fun equalityOverride(owner: CClass, operation: Operators): MethodSymbol? = operators(owner, operation.toString(), right = false, arity = 1).firstOrNull() ?: if (operation == Operators.NOT_EQUAL) operators(owner, Operators.EQUAL.toString(), right = false, arity = 1).firstOrNull() else null

	internal fun classFormatter(owner: CClass): MethodSymbol? = operators(owner, CompilerDataProviders.STR.toString(), right = false, arity = 0).firstOrNull()

	internal fun enumFormatter(owner: CEnum): MethodSymbol = operators(owner, CompilerDataProviders.STR.toString(), right = false, arity = 0).firstOrNull() ?: symbolOf(enumDefaults.getValue(owner))

	internal fun formattingMayCallUserCode(type: ValueType): Boolean = when (type) {
		is CNullable -> formattingMayCallUserCode(type.payload)
		is CArray    -> formattingMayCallUserCode(type.element)
		is CClass    -> classes.values.any { type.accepts(it.owner) && classFormatter(it.owner) != null }
		is CEnum     -> type !in enumDefaults
		else         -> false
	}

	internal val namedTypeReceivers = IdentityHashMap<NameExpression, CMemberType>()
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
	internal val receiverCalls = IdentityHashMap<MemberCallExpression, MethodGroupSymbol>()
	internal val implicitReceivers = IdentityHashMap<CallExpression, ReceiverSymbol>()
	internal val readOnlyDefaultReceivers = Collections.newSetFromMap(IdentityHashMap<MemberCallExpression, Boolean>())
	internal val readOnlyValueReceivers = Collections.newSetFromMap(IdentityHashMap<Expression, Boolean>())
	internal val fieldInitializerCalls = Collections.newSetFromMap(IdentityHashMap<MemberCallExpression, Boolean>())
	internal val assignments = IdentityHashMap<AssignmentStatement, BindingSymbol>()
	internal val multiAssignments = IdentityHashMap<MultiAssignmentStatement, List<BindingSymbol?>>()
	internal val updates = IdentityHashMap<UpdateStatement, BindingSymbol>()
	internal val callableScopes = IdentityHashMap<CallableDeclaration, LexicalScope>()
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
	fun candidatesOf(call: CallExpression): CallableGroupSymbol = calls.getValue(call)
	internal fun methodGroup(owner: CMemberType, name: String): MethodGroupSymbol? {
		if (owner !is CClass) return methodGroups[owner]?.get(name)
		val methods = owner.ancestry().flatMap { methodGroups[it]?.get(name)?.overloads.orEmpty() }.distinctBy { it.signature }.toList()
		return methods.takeIf { it.isNotEmpty() }?.let(::MethodGroupSymbol)
	}

	fun targetOf(statement: AssignmentStatement): BindingSymbol = assignments.getValue(statement)
	fun targetsOf(statement: MultiAssignmentStatement): List<BindingSymbol?> = multiAssignments.getValue(statement)
	fun targetOf(statement: UpdateStatement): BindingSymbol = updates.getValue(statement)
	fun scopeOf(function: FunctionDeclaration): LexicalScope = callableScopes.getValue(function)
	internal fun scopeOf(callable: CallableDeclaration): LexicalScope = callableScopes.getValue(callable)
	fun scopeOf(body: BlockBody): LexicalScope = blockScopes.getValue(body)
	fun scopeOf(branch: ConditionalBranch): LexicalScope = branchScopes.getValue(branch)
	fun otherwiseScopeOf(expression: IfExpression): LexicalScope? = elseScopes[expression]
}
