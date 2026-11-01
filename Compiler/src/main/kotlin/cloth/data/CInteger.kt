package cloth.data

import cloth.target.TargetLayout
import cloth.token.Keywords
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.LLVMIntTypeInContext
import java.math.BigInteger

/** Shared integer behavior. Concrete descriptors retain their own equality and
 * canonical metadata spelling; equal machine widths do not establish type identity.
 */
abstract class CInteger(keyword: Keywords) : CObject(keyword) {

	abstract val width: Int
	abstract override val unsigned: Boolean
	final override val bits: Int get() = width
	final override val integer: Boolean get() = true
	val minimum: BigInteger get() = if (unsigned) BigInteger.ZERO else -BigInteger.ONE.shiftLeft(width - 1)
	val maximum: BigInteger get() = BigInteger.ONE.shiftLeft(width - if (unsigned) 0 else 1) - BigInteger.ONE

	fun acceptsLiteral(value: BigInteger): Boolean = value in minimum..maximum

	/** Low bits interpreted with this type's signedness, for existing constant checks. */
	internal fun wrap(value: BigInteger): BigInteger {
		val modulus = BigInteger.ONE.shiftLeft(width)
		val low = value.mod(modulus)
		return if (!unsigned && low.testBit(width - 1)) low - modulus else low
	}

	/** Bounds preserve the concrete receiver identity; storage size follows the target. */
	override fun providers(target: TargetLayout): Map<CompilerDataProviders, ProviderResult<*>> = super.providers(target) + mapOf(
		CompilerDataProviders.MIN to ProviderResult.IntegerConstant(this, minimum),
		CompilerDataProviders.MAX to ProviderResult.IntegerConstant(this, maximum),
		CompilerDataProviders.SIZE to storageSize(this, target),
		CompilerDataProviders.STR to ProviderResult.ScalarText(this),
	)

	final override fun llvmValue(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = LLVMIntTypeInContext(context, width)
}
