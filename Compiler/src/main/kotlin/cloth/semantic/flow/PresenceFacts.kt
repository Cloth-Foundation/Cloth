package cloth.semantic.flow

import cloth.data.*
import cloth.parser.expression.CallSite
import cloth.parser.expression.Expression
import cloth.parser.expression.FieldAccessExpression
import cloth.parser.expression.MemberCallExpression
import cloth.parser.expression.NameExpression
import cloth.parser.statement.UpdateStatement
import java.util.*

/** Written by the pre-typing flow pass and shared by body trials. */
internal class PresenceFacts {

	val names = Collections.newSetFromMap(IdentityHashMap<NameExpression, Boolean>())
	val fields = Collections.newSetFromMap(IdentityHashMap<FieldAccessExpression, Boolean>())
	val updates = Collections.newSetFromMap(IdentityHashMap<UpdateStatement, Boolean>())
	val narrowedNames = IdentityHashMap<NameExpression, List<CMemberType>>()
	val lostReceiverProofs = IdentityHashMap<MemberCallExpression, List<Expression>>()
	val inferredResultsUsed = Collections.newSetFromMap(IdentityHashMap<CallSite, Boolean>())

	fun narrowedType(name: NameExpression, source: ValueType?): CMemberType? {
		val proofs = narrowedNames[name] ?: return null
		val payload = (source as? CNullable)?.payload ?: source ?: return null
		val declared = payload.classBackedType() ?: return null
		val candidates = proofs.filter { target ->
			// A class-to-interface test opens the interface's call contract.
			val opensInterface = declared is CClass && target is CInterface
			declared.canNarrowTo(target) && (opensInterface || !target.accepts(payload))
		}
		val interfaces = candidates.filterIsInstance<CInterface>()
		return candidates.filterIsInstance<CClass>().maxByOrNull { it.ancestry().count() }
			?: interfaces.singleOrNull { candidate -> interfaces.all { it.accepts(candidate) } }
	}
}
