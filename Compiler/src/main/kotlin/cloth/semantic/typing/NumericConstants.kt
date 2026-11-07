package cloth.semantic.typing

import cloth.data.CFloat
import cloth.data.CInteger
import cloth.data.CNullable
import cloth.data.ValueType
import cloth.parser.expression.*
import cloth.token.Operators
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Closed numeric constants only: never execute calls or infer mutable binding values.
 * Round every operation as the emitter does, including software half arithmetic.
 */
internal class NumericConstants(private val model: SemanticModel) {

	private fun payloadType(expression: Expression): ValueType = model.typeOf(expression).let { (it as? CNullable)?.payload ?: it }

	/** BigDecimal(Double) retains the exact binary value, including large integer bits. */
	private fun floatingInteger(value: Double, target: CInteger): BigInteger? = if (!value.isFinite()) null
	else BigDecimal(value).toBigInteger().takeIf(target::acceptsLiteral)

	/** Unknown values and known absence do not execute a payload conversion here. */
	fun isInvalidCast(expression: CastExpression): Boolean {
		if (payloadType(expression.operand) !is CFloat) return false
		val target = payloadType(expression) as? CInteger ?: return false
		val value = floating(expression.operand) ?: return false
		return floatingInteger(value, target) == null
	}

	private fun integerFloat(value: BigInteger, type: CFloat): Double = if (type.bits == 64) value.toDouble() else value.toBigDecimal().toFloat().toDouble()

	private fun literal(value: BigDecimal, type: CFloat): Double = when (type.bits) {
		64   -> value.toDouble()
		32   -> value.toFloat().toDouble()
		else -> {
			val unit = Math.scalb(1.0, maxOf(-24, Math.getExponent(kotlin.math.abs(value.toDouble())) - 10))
			val exactUnit = BigDecimal(unit)
			value.divide(exactUnit, 0, RoundingMode.HALF_EVEN).multiply(exactUnit).toDouble()
		}
	}

	fun integer(expression: Expression): BigInteger? {
		val type = payloadType(expression) as? CInteger ?: return null
		val value = when (expression) {
			is IntegerLiteral           -> expression.value
			is ProviderAccessExpression -> model.providers[expression]?.result?.integerConstant ?: return null
			is UnaryExpression          -> {
				val operand = integer(expression.operand) ?: return null
				when (Operators.fromCharacter(expression.operator.lexeme)) {
					Operators.PLUS  -> operand
					Operators.MINUS -> -operand
					else            -> return null
				}
			}

			is CastExpression           -> if (payloadType(expression.operand) is CFloat) floatingInteger(floating(expression.operand) ?: return null, type) ?: return null
			else integer(expression.operand) ?: return null

			is BinaryExpression         -> {
				val a = integer(expression.left) ?: return null
				val b = integer(expression.right) ?: return null
				val operator = Operators.fromCharacter(expression.operator.lexeme)
				if (operator in setOf(Operators.DIVIDE, Operators.MODULO) && !type.unsigned && a == type.minimum && b == -BigInteger.ONE) return null
				when (operator) {
					Operators.PLUS     -> a + b
					Operators.MINUS    -> a - b
					Operators.MULTIPLY -> a * b
					Operators.DIVIDE   -> if (b != BigInteger.ZERO) a / b else return null
					Operators.MODULO   -> if (b != BigInteger.ZERO) a % b else return null
					else               -> return null
				}
			}

			else                        -> return null
		}
		return type.wrap(value)
	}

	fun floating(expression: Expression): Double? {
		val type = payloadType(expression) as? CFloat ?: return null
		val value = when (expression) {
			is FloatingPointLiteral     -> literal(expression.value.toBigDecimal(), type)
			is ProviderAccessExpression -> literal(model.providers[expression]?.result?.floatingConstant ?: return null, type)
			is UnaryExpression          -> {
				val operand = floating(expression.operand) ?: return null
				when (Operators.fromCharacter(expression.operator.lexeme)) {
					Operators.PLUS  -> operand
					Operators.MINUS -> -operand
					else            -> return null
				}
			}

			is CastExpression           -> floating(expression.operand) ?: integer(expression.operand)?.let { integerFloat(it, type) } ?: return null
			is BinaryExpression         -> {
				val left = floating(expression.left) ?: integer(expression.left)?.let { integerFloat(it, type) } ?: return null
				val right = floating(expression.right) ?: integer(expression.right)?.let { integerFloat(it, type) } ?: return null
				val operator = Operators.fromCharacter(expression.operator.lexeme)
				if (type.bits <= 32) {
					val a = left.toFloat()
					val b = right.toFloat()
					when (operator) {
						Operators.PLUS     -> a + b
						Operators.MINUS    -> a - b
						Operators.MULTIPLY -> a * b
						Operators.DIVIDE   -> a / b
						Operators.MODULO   -> a % b
						else               -> return null
					}.toDouble()
				} else when (operator) {
					Operators.PLUS     -> left + right
					Operators.MINUS    -> left - right
					Operators.MULTIPLY -> left * right
					Operators.DIVIDE   -> left / right
					Operators.MODULO   -> left % right
					else               -> return null
				}
			}

			else                        -> return null
		}
		return when (type.bits) {
			64   -> value
			32   -> value.toFloat().toDouble()
			else -> {
				if (!value.isFinite() || value == 0.0) value
				else if (kotlin.math.abs(value) >= 65520.0) Math.copySign(Double.POSITIVE_INFINITY, value)
				else {
					val unit = Math.scalb(1.0, maxOf(-24, Math.getExponent(kotlin.math.abs(value)) - 10))
					Math.rint(value / unit) * unit
				}
			}
		}
	}
}
