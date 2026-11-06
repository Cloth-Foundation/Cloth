package cloth.semantic.flow

import cloth.data.CClass
import cloth.parser.expression.Expression
import cloth.parser.expression.FieldAccessExpression
import cloth.parser.expression.MemberCallExpression
import cloth.parser.expression.NameExpression
import cloth.parser.statement.UpdateStatement
import java.util.Collections
import java.util.IdentityHashMap

/** Written by the pre-typing flow pass and shared by body trials. */
internal class PresenceFacts {

	val names = Collections.newSetFromMap(IdentityHashMap<NameExpression, Boolean>())
	val fields = Collections.newSetFromMap(IdentityHashMap<FieldAccessExpression, Boolean>())
	val updates = Collections.newSetFromMap(IdentityHashMap<UpdateStatement, Boolean>())
	val narrowedNames = IdentityHashMap<NameExpression, CClass>()
	val lostReceiverProofs = IdentityHashMap<MemberCallExpression, List<Expression>>()
}
