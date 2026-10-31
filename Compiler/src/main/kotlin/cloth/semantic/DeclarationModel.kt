package cloth.semantic

import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.declaration.ParameterDeclaration
import cloth.parser.expression.CallExpression
import cloth.parser.expression.ConditionalBranch
import cloth.parser.expression.IfExpression
import cloth.parser.expression.NameExpression
import cloth.parser.statement.AssignmentStatement
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.UpdateStatement
import cloth.semantic.symbols.*
import java.util.*

/** Declaration identities and name resolutions, before expression/call type checking. */
class DeclarationModel internal constructor(val fileScope: LexicalScope, val functions: List<FunctionSymbol>) {

	internal val functionSymbols = IdentityHashMap<FunctionDeclaration, FunctionSymbol>()
	internal val parameterSymbols = IdentityHashMap<ParameterDeclaration, ParameterSymbol>()
	internal val localSymbols = IdentityHashMap<BindingDeclaration, LocalSymbol>()
	internal val names = IdentityHashMap<NameExpression, Symbol>()
	internal val calls = IdentityHashMap<CallExpression, CallableGroupSymbol>()
	internal val assignments = IdentityHashMap<AssignmentStatement, BindingSymbol>()
	internal val updates = IdentityHashMap<UpdateStatement, BindingSymbol>()
	internal val functionScopes = IdentityHashMap<FunctionDeclaration, LexicalScope>()
	internal val blockScopes = IdentityHashMap<BlockBody, LexicalScope>()
	internal val branchScopes = IdentityHashMap<ConditionalBranch, LexicalScope>()
	internal val elseScopes = IdentityHashMap<IfExpression, LexicalScope>()

	fun symbolOf(declaration: FunctionDeclaration): FunctionSymbol = functionSymbols.getValue(declaration)
	fun symbolOf(declaration: ParameterDeclaration): ParameterSymbol = parameterSymbols.getValue(declaration)
	fun symbolOf(declaration: BindingDeclaration): LocalSymbol = localSymbols.getValue(declaration)
	fun symbolOf(expression: NameExpression): Symbol = names.getValue(expression)
	fun candidatesOf(call: CallExpression): CallableGroupSymbol = calls.getValue(call)
	fun targetOf(statement: AssignmentStatement): BindingSymbol = assignments.getValue(statement)
	fun targetOf(statement: UpdateStatement): BindingSymbol = updates.getValue(statement)
	fun scopeOf(function: FunctionDeclaration): LexicalScope = functionScopes.getValue(function)
	fun scopeOf(body: BlockBody): LexicalScope = blockScopes.getValue(body)
	fun scopeOf(branch: ConditionalBranch): LexicalScope = branchScopes.getValue(branch)
	fun otherwiseScopeOf(expression: IfExpression): LexicalScope? = elseScopes[expression]
}
