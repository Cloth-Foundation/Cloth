package cloth.semantic

import cloth.parser.expression.Expression
import cloth.parser.expression.TypeCheckExpression
import cloth.parser.statement.Statement
import java.util.*

/** Identity keys distinguish equal-looking syntax used in different type contexts. */
class SemanticModel {

	internal val expressions = IdentityHashMap<Expression, ValueType>()
	internal val statements = IdentityHashMap<Statement, ValueType>()
	internal val typeChecks = IdentityHashMap<TypeCheckExpression, ValueType>()
	fun typeOf(expression: Expression): ValueType = expressions.getValue(expression)
	fun typeOf(statement: Statement): ValueType = statements.getValue(statement)
	fun targetTypeOf(expression: TypeCheckExpression): ValueType = typeChecks.getValue(expression)
}
