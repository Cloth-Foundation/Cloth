package cloth.semantic

import cloth.parser.expression.Expression
import cloth.parser.statement.Statement
import java.util.IdentityHashMap

/** Identity keys distinguish equal-looking syntax used in different type contexts. */
class SemanticModel {
	internal val expressions = IdentityHashMap<Expression, ValueType>()
	internal val statements = IdentityHashMap<Statement, ValueType>()
	fun typeOf(expression: Expression): ValueType = expressions.getValue(expression)
	fun typeOf(statement: Statement): ValueType = statements.getValue(statement)
}
