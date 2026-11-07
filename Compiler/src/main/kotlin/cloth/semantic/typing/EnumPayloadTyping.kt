package cloth.semantic.typing

import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.Expression
import cloth.semantic.ResultShape
import cloth.token.Token

/** Check source payloads before generated reader branches can choose their diagnostics. */
internal fun checkEnumPayloads(model: FunctionSemanticModel) {
	val constants = EnumConstantExpressions(model.types)
	for (owner in model.declarations.enumTypes) {
		val fields = owner.fields
		val checkers = fields.map { BodyTyping(model, it.reader, ResultShape.of(it.type)) }
		fun check(index: Int, expression: Expression, caseName: Token? = null) {
			val field = fields[index]
			val actual = checkers[index].typeOf(expression, field.type)
			constants.firstInvalidCast(expression)?.let { cast ->
				throw SemanticErrors.enumPayloadCast(field, model.types.typeOf(cast.operand), model.types.typeOf(cast), cast.span, caseName)
			}
			if (!field.type.accepts(actual)) throw SemanticErrors.enumPayloadType(field, actual, expression.span, caseName)
		}
		fields.forEachIndexed { index, field -> field.declaration.default?.let { check(index, it) } }
		for (case in owner.declaration.cases) {
			case.values.forEachIndexed { index, expression -> check(index, expression, case.name) }
		}
	}
}
