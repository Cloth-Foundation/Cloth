package cloth.semantic

import cloth.builtin.BoundBuiltinCall
import cloth.data.CNullable
import cloth.data.ValueType
import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.target.TargetLayout
import java.util.*

/** Identity keys distinguish equal-looking syntax used in different type contexts. */
class SemanticModel(val target: TargetLayout = TargetLayout.native()) {

	internal val typeResolver = TypeResolver(target)

	internal val expressions = IdentityHashMap<Expression, ValueType>()
	internal val expressionResults = IdentityHashMap<Expression, ResultShape>()
	internal val statementResults = IdentityHashMap<Statement, ResultShape>()
	internal val resultMetadata = IdentityHashMap<Expression, List<ValueType?>>()
	internal val statements = IdentityHashMap<Statement, ValueType>()
	internal val receptions = IdentityHashMap<Statement, ReceptionPlan>()
	internal val typeChecks = IdentityHashMap<TypeCheckExpression, ValueType>()
	internal val providers = IdentityHashMap<ProviderAccessExpression, ProviderAccess>()
	internal val memberCalls = IdentityHashMap<MemberCallExpression, BoundBuiltinCall>()
	internal val payloadReads = IdentityHashMap<Expression, CNullable>()
	internal val nullableComparisons = IdentityHashMap<BinaryExpression, ValueType>()
	internal val updatePayloads = IdentityHashMap<UpdateStatement, CNullable>()
	internal val loops = IdentityHashMap<ForStatement, ForLoopPlan>()
	internal val loopJumps = IdentityHashMap<LoopJumpStatement, LoopTarget>()
	fun targetOf(statement: LoopJumpStatement): LoopTarget = loopJumps.getValue(statement)
	fun loopOf(statement: ForStatement): ForLoopPlan = loops.getValue(statement)

	/** Definite represented types, separate from the expression's common typeinfo value type. */
	internal val metadata = IdentityHashMap<Expression, ValueType>()
	fun typeOf(expression: Expression): ValueType = expressions[expression] ?: throw SemanticErrors.scalarResults(resultOf(expression), expression.span)
	fun resultOf(expression: Expression): ResultShape = expressionResults[expression] ?: ResultShape.of(expressions.getValue(expression))
	fun resultOf(statement: Statement): ResultShape = statementResults[statement] ?: ResultShape.of(statements.getValue(statement))
	fun representedResultsOf(expression: Expression): List<ValueType?> = resultMetadata[expression] ?: resultOf(expression).values.map { null }
	internal fun recordResult(expression: Expression, result: ResultShape) {
		expressionResults[expression] = result
		val scalar = result.scalarOrNull()
		if (scalar == null) expressions.remove(expression) else expressions[expression] = scalar
	}

	internal fun recordResult(statement: Statement, result: ResultShape) {
		statementResults[statement] = result
		val scalar = result.scalarOrNull()
		if (scalar == null) statements.remove(statement) else statements[statement] = scalar
	}

	fun typeOf(statement: Statement): ValueType = statements[statement] ?: throw SemanticErrors.scalarResults(resultOf(statement), statement.span)
	fun receptionOf(statement: Statement): ReceptionPlan = receptions.getValue(statement)
	fun targetTypeOf(expression: TypeCheckExpression): ValueType = typeChecks.getValue(expression)
	internal fun providerAccess(receiver: Expression, spelling: String): ProviderAccess? = typeOf(receiver).provider(spelling, target)?.let { ProviderAccess(receiver, it) }
	internal fun providerAccess(expression: ProviderAccessExpression): ProviderAccess = providers.getValue(expression)
}
