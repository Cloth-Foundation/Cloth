package cloth.semantic

import cloth.data.ValueType
import cloth.parser.expression.Expression
import cloth.parser.expression.ProviderAccessExpression
import cloth.parser.expression.TypeCheckExpression
import cloth.parser.statement.Statement
import java.util.*

/** Identity keys distinguish equal-looking syntax used in different type contexts. */
class SemanticModel {

	internal val expressions = IdentityHashMap<Expression, ValueType>()
	internal val statements = IdentityHashMap<Statement, ValueType>()
	internal val typeChecks = IdentityHashMap<TypeCheckExpression, ValueType>()
	internal val providers = IdentityHashMap<ProviderAccessExpression, ProviderAccess>()

	/** Definite represented types, separate from the expression's common typeinfo value type. */
	internal val metadata = IdentityHashMap<Expression, ValueType>()
	fun typeOf(expression: Expression): ValueType = expressions.getValue(expression)
	fun typeOf(statement: Statement): ValueType = statements.getValue(statement)
	fun targetTypeOf(expression: TypeCheckExpression): ValueType = typeChecks.getValue(expression)
	internal fun providerAccess(receiver: Expression, spelling: String): ProviderAccess? = typeOf(receiver).provider(spelling)?.let { ProviderAccess(receiver, it) }
	internal fun providerAccess(expression: ProviderAccessExpression): ProviderAccess = providers.getValue(expression)
}
