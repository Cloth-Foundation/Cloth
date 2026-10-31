package cloth.semantic

import cloth.data.ValueType
import cloth.parser.expression.CallExpression
import cloth.parser.expression.Expression
import cloth.parser.statement.Statement
import cloth.semantic.symbols.BindingSymbol
import cloth.semantic.symbols.FunctionSymbol
import java.util.*

data class FunctionType(val parameters: List<ValueType>, val returns: ValueType)

/** Committed call choices and expression types; rejected candidate facts never escape. */
class FunctionSemanticModel internal constructor(val declarations: DeclarationModel, internal val signatures: Map<FunctionSymbol, FunctionType>, val controlFlow: ControlFlowModel = ControlFlowModel()) {

	val types = SemanticModel()
	internal val bindings = IdentityHashMap<BindingSymbol, ValueType>()
	internal val calls = IdentityHashMap<CallExpression, ResolvedCall>()
	fun typeOf(expression: Expression) = types.typeOf(expression)
	fun typeOf(statement: Statement) = types.typeOf(statement)
	fun typeOf(binding: BindingSymbol): ValueType = bindings.getValue(binding)
	fun typeOf(function: FunctionSymbol): FunctionType = signatures.getValue(function)
	fun selectedFunction(call: CallExpression): FunctionSymbol = (selectedCall(call) as ResolvedCall.Source).function
	internal fun selectedCall(call: CallExpression): ResolvedCall = calls.getValue(call)

	internal fun fork() = FunctionSemanticModel(declarations, signatures, controlFlow).also { it.commit(this) }
	internal fun commit(other: FunctionSemanticModel) {
		bindings.clear(); bindings.putAll(other.bindings)
		calls.clear(); calls.putAll(other.calls)
		types.expressions.clear(); types.expressions.putAll(other.types.expressions)
		types.statements.clear(); types.statements.putAll(other.types.statements)
		types.typeChecks.clear(); types.typeChecks.putAll(other.types.typeChecks)
		types.providers.clear(); types.providers.putAll(other.types.providers)
		types.metadata.clear(); types.metadata.putAll(other.types.metadata)
	}
}
