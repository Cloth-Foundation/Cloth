package cloth.token

import cloth.diagnostics.SyntaxErrors
import java.math.BigInteger

/** Decode validated numeric tokens without changing their original source spelling. */
fun Token.integerLiteralValue(): BigInteger {
	if (type != TokenType.IntegerLiteral) throw SyntaxErrors.number(this, floating = false)
	val text = lexeme.replace(Operators.UNDERSCORE.toString(), "")
	val radix = when {
		text.startsWith("0x") -> 16
		text.startsWith("0b") -> 2
		text.startsWith("0o") -> 8
		else                  -> 10
	}
	val digits = if (radix == 10) text.removeSuffix("i").removeSuffix("I") else text.drop(2)
	return digits.toBigIntegerOrNull(radix) ?: throw SyntaxErrors.number(this, floating = false)
}

/** Keep exact decimal text until the selected target floating format rounds it. */
fun Token.floatingLiteralText(): String {
	if (type != TokenType.FloatingPointLiteral) throw SyntaxErrors.number(this, floating = true)
	var text = lexeme.replace(Operators.UNDERSCORE.toString(), "").removeSuffix("f").removeSuffix("F")
	// Decimal scanning retains its existing Unicode digit support. LLVM expects ASCII.
	text = text.map { if (it.isDigit()) '0' + it.digitToInt() else it }.joinToString("")
	if (text.endsWith(Operators.DOT.toString())) text += "0"
	if (!text.contains(Operators.DOT.toString())) text += "${Operators.DOT}0"
	if (text.toBigDecimalOrNull() == null) throw SyntaxErrors.number(this, floating = true)
	return text
}
