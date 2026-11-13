package cloth.semantic

import cloth.data.*
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.MethodDeclaration
import cloth.parser.type.TypeReference
import cloth.token.Keywords
import cloth.token.Operators

internal object OperatorRules {

	private val unary = setOf(Operators.PLUS, Operators.MINUS, Operators.NOT)
	private val ordering = setOf(Operators.LESS, Operators.LESS_EQUAL, Operators.GREATER, Operators.GREATER_EQUAL)
	private val binary = ordering + setOf(Operators.PLUS, Operators.MINUS, Operators.MULTIPLY, Operators.DIVIDE, Operators.MODULO, Operators.MUL_MUL)
	fun supportsContext(operator: Operators?) = operator in binary || operator in equality
	fun supportsInferredWidth(type: TypeReference) = !type.unsigned && (type.name.lexeme == Keywords.INTEGER.toString() || type.name.lexeme == Keywords.FLOAT.toString())

	fun inferredSelectorWidth(selector: TypeReference?, peer: ValueType): Int? {
		if (selector?.capturesWidth != true || selector.name.lexeme != peer.keyword.toString()) return null
		return when (peer) {
			is CInt   -> peer.bits.takeUnless { peer.unsigned }
			is CFloat -> peer.bits
			else      -> null
		}
	}

	val equality = setOf(Operators.EQUAL, Operators.NOT_EQUAL)
	val classComparisons = equality + Operators.IDENTITY
	private val indexing = setOf(Operators.INDEX_READ, Operators.INDEX_VALIDATE, Operators.INDEX_WRITE)
	fun isIndexValidator(member: MethodDeclaration) = member.operator != null && member.name.lexeme == Operators.INDEX_VALIDATE.toString()
	fun isIndexSetter(member: MethodDeclaration) = member.operator != null && member.name.lexeme == Operators.INDEX_WRITE.toString()
	fun isForeach(member: MethodDeclaration) = member.operator != null && member.name.lexeme == Operators.FOREACH.toString()

	fun fixedResult(member: MethodDeclaration): ValueType? {
		if (member.operator?.selector != null) return null
		if (member.name.lexeme == Keywords.IS.toString()) return ValueType.BOOL
		if (member.operator?.provider == true && member.name.lexeme == CompilerDataProviders.STR.toString()) return ValueType.STRING
		val operator = Operators.fromCharacter(member.name.lexeme)
		if (operator == Operators.INDEX_WRITE) return ValueType.VOID
		return if (operator == Operators.NOT || operator == Operators.INDEX_VALIDATE || operator in ordering || operator in classComparisons) ValueType.BOOL else null
	}

	fun validate(member: MethodDeclaration, owner: CMemberType) {
		val form = member.operator ?: return
		val operator = Operators.fromCharacter(member.name.lexeme)
		val parameters = member.parameters
		fun invalid(message: String): Nothing = throw SemanticErrors.operatorDeclaration(message, member.span)
		if (parameters.any { it.optional }) invalid("Operator operands require explicit parameters")
		if (operator == Operators.FOREACH) {
			if (owner !is CClass && owner !is CStruct) invalid("Operator 'foreach' requires a class or struct receiver")
			if (form.provider || form.selector != null || form.rightReceiver || parameters.isNotEmpty() || member.returnTypes.size != 1) {
				invalid("Operator 'foreach' requires an empty parameter list and a 'Snapshot<T>' result type")
			}
			return
		}
		if (operator in indexing) {
			if (owner !is CClass) invalid("Operator '${member.name.lexeme}' requires a class receiver")
			val setter = operator == Operators.INDEX_WRITE
			if (form.selector != null || form.rightReceiver || parameters.size != (if (setter) 2 else 1)) {
				val required = if (setter) "an index and a value parameter" else "one index parameter"
				invalid("Operator '${member.name.lexeme}' requires $required with its receiver on the left")
			}
			if (operator == Operators.INDEX_READ && member.body !is ExpressionBody && member.returnTypes.isEmpty()) invalid("A block-bodied index operator requires a result type after '${Operators.POINTER_RIGHT}'")
			return
		}
		if ((form.provider || form.selector != null) && (form.rightReceiver || parameters.isNotEmpty())) invalid("This operator form requires an empty parameter list")
		if (form.rightReceiver && parameters.size != 1 || parameters.size > 1) invalid("A binary operator declares one operand in addition to its receiver")
		if (form.selector != null) {
			if (form.provider || !supportsContext(operator)) invalid("This operator family does not have an operand context contract")
			if (form.selector.capturesWidth && (!supportsInferredWidth(form.selector) || form.selector.hasNullability || form.selector.arraySuffixes.isNotEmpty())) {
				invalid("An inferred selector width requires scalar '${Keywords.INTEGER}${Operators.LESS}${Keywords.AUTO}${Operators.GREATER}' or '${Keywords.FLOAT}${Operators.LESS}${Keywords.AUTO}${Operators.GREATER}'")
			}
			if (member.body !is ExpressionBody && member.returnTypes.isEmpty()) invalid("A block-bodied operand context requires a result type")
			return
		}
		if (!form.provider && member.name.lexeme == Keywords.IS.toString()) {
			if (owner !is CClass || parameters.size != 1) invalid("Value-target '${Keywords.IS}' requires one class operand and a class receiver")
			return
		}
		if (form.provider && (owner is CClass || owner is CEnum) && member.name.lexeme == CompilerDataProviders.STR.toString()) return
		if (!form.provider && operator in equality && (owner is CStruct || owner is CEnum)) {
			if (parameters.size != 1) invalid("Operator '${member.name.lexeme}' requires one '${owner.spelling}' operand")
			return
		}
		if (!form.provider && operator in classComparisons && owner is CClass) {
			if (parameters.size != 1) invalid("Operator '${member.name.lexeme}' requires one class operand")
			if (form.rightReceiver && operator != Operators.IDENTITY) invalid("Operator '${member.name.lexeme}' requires '${Keywords.SELF}' on the left")
			return
		}
		if (form.provider || operator !in unary && operator !in binary) invalid("Operator '${member.name.lexeme}' declarations await this operator's resolution rules")
		if (parameters.isEmpty() && operator !in unary || parameters.isNotEmpty() && operator !in binary) invalid("Operator '${member.name.lexeme}' does not accept this operand count")
		if (member.body !is ExpressionBody && member.returnTypes.isEmpty() && fixedResult(member) == null) invalid("A block-bodied operator requires a result type after '${Operators.POINTER_RIGHT}'")
	}
}
