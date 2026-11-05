package cloth.semantic

import cloth.data.CClass
import cloth.data.CMemberType
import cloth.data.CStruct
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.symbols.*
import cloth.target.TargetLayout
import java.util.*

/** Declaration identities and name resolutions, before expression/call type checking. */
class DeclarationModel internal constructor(
	val fileScope: LexicalScope,
	val functions: List<FunctionSymbol>,
	val target: TargetLayout,
	val structs: Map<String, CStruct>,
	val methods: List<MethodSymbol>,
	internal val classes: Map<String, ClassDefinition>,
) {

	internal val callables: List<CallableSymbol> = functions + methods + classes.values.flatMap { it.constructors + listOfNotNull(it.destructor) }
	internal fun namedType(name: String): CMemberType? = classes[name]?.owner ?: structs[name]
	internal val classOwners = IdentityHashMap<CallableDeclaration, CClass>().apply {
		for (callable in callables) (callable.receiver?.owner as? CClass)?.let { put(callable.declaration, it) }
	}
	private val methodGroups = methods.groupBy { it.owner }.mapValues { (_, members) ->
		members.groupBy { it.name.lexeme }.mapValues { (_, overloads) -> MethodGroupSymbol(overloads) }
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
	internal fun methodGroup(owner: CMemberType, name: String): MethodGroupSymbol? = methodGroups[owner]?.get(name)
	fun targetOf(statement: AssignmentStatement): BindingSymbol = assignments.getValue(statement)
	fun targetsOf(statement: MultiAssignmentStatement): List<BindingSymbol?> = multiAssignments.getValue(statement)
	fun targetOf(statement: UpdateStatement): BindingSymbol = updates.getValue(statement)
	fun scopeOf(function: FunctionDeclaration): LexicalScope = callableScopes.getValue(function)
	internal fun scopeOf(callable: CallableDeclaration): LexicalScope = callableScopes.getValue(callable)
	fun scopeOf(body: BlockBody): LexicalScope = blockScopes.getValue(body)
	fun scopeOf(branch: ConditionalBranch): LexicalScope = branchScopes.getValue(branch)
	fun otherwiseScopeOf(expression: IfExpression): LexicalScope? = elseScopes[expression]
}
