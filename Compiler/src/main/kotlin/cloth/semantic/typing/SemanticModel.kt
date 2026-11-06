package cloth.semantic.typing

import cloth.builtin.BoundBuiltinCall
import cloth.data.*
import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.ClassComparisonPlan
import cloth.semantic.FieldWritePlan
import cloth.semantic.ForLoopPlan
import cloth.semantic.InstancePlace
import cloth.semantic.ProviderAccess
import cloth.semantic.ReceptionPlan
import cloth.semantic.ResultShape
import cloth.semantic.StoredPlace
import cloth.semantic.declarations.TypeResolver
import cloth.target.TargetLayout

/** Identity keys distinguish equal-looking syntax used in different type contexts. */
class SemanticModel internal constructor(internal val typeResolver: TypeResolver) {
	constructor(target: TargetLayout, namedTypes: Map<String, CMemberType> = emptyMap(), operatorWidth: Int? = null) :
		this(TypeResolver(target, operatorWidth, namedTypes::get))

	val target get() = typeResolver.target

	private val facts = FactCopies<SemanticModel>()
	internal val structLiterals: MutableMap<StructLiteral, CStruct> = facts.identityMap { it.structLiterals }
	internal val fields: MutableMap<FieldAccessExpression, InstanceField> = facts.identityMap { it.fields }
	internal val fieldWrites: MutableMap<FieldWriteStatement, FieldWritePlan> = facts.identityMap { it.fieldWrites }
	internal val instanceWrites: MutableMap<Statement, InstancePlace> = facts.identityMap { it.instanceWrites }
	internal val receiverElementWrites: MutableMap<ElementAssignmentStatement, StoredPlace> = facts.identityMap { it.receiverElementWrites }

	private val expressionResults: MutableMap<Expression, ResultShape> = facts.identityMap { it.expressionResults }
	private val statementResults: MutableMap<Statement, ResultShape> = facts.identityMap { it.statementResults }
	internal val resultMetadata: MutableMap<Expression, List<ValueType?>> = facts.identityMap { it.resultMetadata }
	internal val receptions: MutableMap<Statement, ReceptionPlan> = facts.identityMap { it.receptions }
	internal val typeChecks: MutableMap<TypeCheckExpression, ValueType> = facts.identityMap { it.typeChecks }
	private val valueChecks: MutableMap<TypeCheckExpression, BoundValueCheck> = facts.identityMap { it.valueChecks }
	internal val providers: MutableMap<ProviderAccessExpression, ProviderAccess> = facts.identityMap { it.providers }
	private val interpolationText: MutableMap<Expression, ProviderResult<*>> = facts.identityMap { it.interpolationText }
	internal val memberCalls: MutableMap<MemberCallExpression, BoundBuiltinCall> = facts.identityMap { it.memberCalls }
	internal val operatorCalls: MutableMap<Expression, ResolvedOperator> = facts.identityMap { it.operatorCalls }
	internal val projectedOperands: MutableMap<NameExpression, ProjectedOperand> = facts.identityMap { it.projectedOperands }
	internal val operatorContexts: MutableMap<Expression, ResolvedOperatorContext> = facts.identityMap { it.operatorContexts }
	internal val classComparisons: MutableMap<BinaryExpression, ClassComparisonPlan> = facts.identityMap { it.classComparisons }
	internal val valueComparisons: MutableMap<BinaryExpression, ValueComparisonPlan> = facts.identityMap { it.valueComparisons }
	internal val payloadReads: MutableMap<Expression, CNullable> = facts.identityMap { it.payloadReads }
	internal val nullableComparisons: MutableMap<BinaryExpression, ValueType> = facts.identityMap { it.nullableComparisons }
	internal val updatePayloads: MutableMap<UpdateStatement, CNullable> = facts.identityMap { it.updatePayloads }
	internal val loops: MutableMap<ForStatement, ForLoopPlan> = facts.identityMap { it.loops }
	internal val loopJumps: MutableMap<LoopJumpStatement, LoopTarget> = facts.identityMap { it.loopJumps }
	fun targetOf(statement: LoopJumpStatement): LoopTarget = loopJumps.getValue(statement)
	fun loopOf(statement: ForStatement): ForLoopPlan = loops.getValue(statement)

	/** Definite represented types, separate from the expression's common typeinfo value type. */
	internal val metadata: MutableMap<Expression, ValueType> = facts.identityMap { it.metadata }
	fun typeOf(expression: Expression): ValueType = resultOf(expression).scalarOrNull() ?: throw SemanticErrors.scalarResults(resultOf(expression), expression.span)
	fun typeOf(statement: Statement): ValueType = resultOf(statement).scalarOrNull() ?: throw SemanticErrors.scalarResults(resultOf(statement), statement.span)
	fun resultOf(expression: Expression): ResultShape = expressionResults.getValue(expression)
	fun resultOf(statement: Statement): ResultShape = statementResults.getValue(statement)
	fun representedResultsOf(expression: Expression): List<ValueType?> = resultMetadata[expression] ?: resultOf(expression).values.map { null }
	internal fun scalarTypeOrNull(expression: Expression): ValueType? = expressionResults[expression]?.scalarOrNull()
	internal fun expressionValueTypes(): Sequence<ValueType> = expressionResults.values.asSequence().flatMap { it.values }
	internal fun recordType(expression: Expression, type: ValueType) = recordResult(expression, ResultShape.of(type))
	internal fun recordType(statement: Statement, type: ValueType) = recordResult(statement, ResultShape.of(type))

	internal fun recordResult(expression: Expression, result: ResultShape) {
		expressionResults[expression] = result
	}

	internal fun recordResult(statement: Statement, result: ResultShape) {
		statementResults[statement] = result
	}

	internal fun copyFrom(source: SemanticModel) = facts.copyFrom(source)
	fun receptionOf(statement: Statement): ReceptionPlan = receptions.getValue(statement)
	fun targetTypeOf(expression: TypeCheckExpression): ValueType = typeChecks.getValue(expression)
	internal fun operatorOf(expression: Expression): ResolvedOperator? = operatorCalls[expression]
	internal fun operatorContextOf(expression: Expression): ResolvedOperatorContext? = operatorContexts[expression]
	internal fun projectedOperandOf(expression: NameExpression): ProjectedOperand? = projectedOperands[expression]
	internal fun valueCheckOf(expression: TypeCheckExpression): BoundValueCheck? = valueChecks[expression]
	internal fun recordValueCheck(expression: TypeCheckExpression, application: BinaryExpression) {
		valueChecks[expression] = BoundValueCheck(application, operatorCalls.getValue(application))
	}
	internal fun providerAccess(receiver: Expression, spelling: String): ProviderAccess? = typeOf(receiver).provider(spelling, target)?.let { ProviderAccess(receiver, it) }
	internal fun providerAccess(expression: ProviderAccessExpression): ProviderAccess = providers.getValue(expression)
	internal fun interpolationTextOf(part: Expression): ProviderResult<*>? = interpolationText[part]
	internal fun recordInterpolationText(part: Expression, recipe: ProviderResult<*>) {
		interpolationText[part] = recipe
	}
}
