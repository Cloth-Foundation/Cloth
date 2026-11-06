package cloth.semantic.declarations

import cloth.data.CEnum
import cloth.data.CompilerDataProviders
import cloth.data.EnumField
import cloth.data.ValueType
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.MethodDeclaration
import cloth.parser.expression.*
import cloth.parser.statement.ExpressionStatement
import cloth.parser.type.TypeReference
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

/** Payload readers use ordinary checked returns and buffered result transport. */
internal object EnumModel {

	fun defineFields(owner: CEnum, resolver: TypeResolver) {
		val declaration = owner.declaration
		val names = mutableMapOf<String, Token>()
		for ((name, values) in declaration.cases) {
			names.putIfAbsent(name.lexeme, name)?.let { throw SemanticErrors.duplicate(name, it) }
			if (values.size > declaration.fields.size) throw SemanticErrors.enumMember("Too many payload values for '${name.lexeme}'", values[declaration.fields.size].span)
		}
		names.clear()
		owner.defineFields(declaration.fields.mapIndexed { index, field ->
			names.putIfAbsent(field.name.lexeme, field.name)?.let { throw SemanticErrors.duplicate(field.name, it) }
			val type = resolver.resolve(field.type)
			if (type == ValueType.VOID) throw SemanticErrors.voidType(field.type.span)
			field.default?.let(::requireConstant)
			val entries = declaration.cases.map { case ->
				val supplied = case.values.getOrNull(index)
				supplied?.let(::requireConstant)
				supplied ?: field.default ?: throw SemanticErrors.enumMember("Case '${case.name.lexeme}' requires payload field '${field.name.lexeme}'", case.name.span)
			}
			val reader = reader(owner, "<payload.${field.name.lexeme}>", field.type, entries, field.default)
			EnumField(owner, field, index, type, reader)
		})
	}

	fun defaultFormatter(owner: CEnum): MethodDeclaration {
		val type = TypeReference(Token(TokenType.Keyword, owner.name.span, Keywords.STRING.toString()))
		val names = owner.declaration.cases.map { StringLiteral(it.name.lexeme, it.name.span) }
		return reader(owner, "<${CompilerDataProviders.STR}>", type, names)
	}

	private fun reader(owner: CEnum, name: String, type: TypeReference, entries: List<Expression>, default: Expression? = null): MethodDeclaration {
		val span = owner.name.span
		fun keyword(value: Keywords) = Token(TokenType.Keyword, span, value.toString())
		fun operator(value: Operators) = Token(TokenType.Operator, span, value.toString())
		val cases = if (default == null) entries.dropLast(1) else entries
		val body = if (cases.isEmpty()) entries.single() else {
			val branches = cases.mapIndexed { index, value ->
				val ordinal = CastExpression(NameExpression(keyword(Keywords.SELF)), operator(Operators.POINTER_RIGHT), TypeReference(keyword(Keywords.INTEGER)))
				val literal = IntegerLiteral(index.toBigInteger(), Token(TokenType.IntegerLiteral, span, index.toString()))
				val condition = BinaryExpression(ordinal, operator(Operators.EQUAL), literal)
				ConditionalBranch(condition, listOf(ExpressionStatement(value)), value.span)
			}
			val fallback = default ?: entries.last()
			IfExpression(keyword(Keywords.IF), branches, listOf(ExpressionStatement(fallback)), owner.declaration.span, fallback.span)
		}
		return MethodDeclaration(keyword(Keywords.METHOD), Token(TokenType.Identifier, span, name), listOf(type), emptyList(), ExpressionBody(operator(Operators.ASSIGN), body, owner.declaration.span))
	}

	private fun requireConstant(expression: Expression) {
		when (expression) {
			is NumberLiteral, is BooleanLiteral, is CharacterLiteral, is StringLiteral, is NullLiteral -> Unit
			is CastExpression                                                                          -> requireConstant(expression.operand)
			is UnaryExpression                                                                         -> if (expression.operand !is NumberLiteral && expression.operand !is BooleanLiteral) invalidConstant(expression)
			is ArrayLiteral                                                                            -> expression.elements.forEach(::requireConstant)
			is StructLiteral                                                                           -> expression.fields.forEach(::requireConstant)
			else                                                                                       -> invalidConstant(expression)
		}
	}

	private fun invalidConstant(expression: Expression): Nothing = throw SemanticErrors.enumMember("An enum payload requires a constant literal or explicit cast", expression.span)
}
