package cloth.semantic

import cloth.data.ProviderReceiverForm
import cloth.data.ProviderResult
import cloth.data.ValueType
import cloth.parser.expression.Expression
import cloth.parser.expression.TypeReceiverExpression
import cloth.token.TokenSpan

/** Resolved access, with an explicit distinction between type and executable receivers. */
internal data class ProviderAccess(val receiver: Expression, val result: ProviderResult<*>, val span: TokenSpan = receiver.span) {

	val type: ValueType get() = result.type
	val receiverForm: ProviderReceiverForm get() = if (receiver is TypeReceiverExpression) ProviderReceiverForm.TYPE else ProviderReceiverForm.VALUE
}
