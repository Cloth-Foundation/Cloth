package cloth.data

import cloth.diagnostics.InternalErrors
import cloth.token.Keywords
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.LLVMVoidTypeInContext
import java.math.BigInteger

/** A canonical Cloth type descriptor, shared by semantic analysis and LLVM emission. */
sealed class ValueType(val keyword: Keywords) {

	open val bits: Int get() = 0
	open val storageBits: Int get() = bits
	open val unsigned: Boolean get() = false
	open val integer: Boolean get() = false
	open val floating: Boolean get() = false
	val numeric: Boolean get() = integer || floating
	open val buffered: Boolean get() = false
	open val arrayDimensions: List<BigInteger?> get() = emptyList()

	/** Every source type describes itself without constructing an instance. */
	internal open val providers: Map<CompilerDataProviders, ProviderResult<*>>
		get() = mapOf(CompilerDataProviders.TYPE to ProviderResult.TypeMetadata(this))

	internal fun provider(spelling: String): ProviderResult<*>? = CompilerDataProviders.fromSpelling(spelling)?.let(providers::get)

	/** Metadata text is canonical and always includes numeric widths. Diagnostics keep spelling. */
	val metadataSpelling: String
		get() = when (this) {
			is CArray -> {
				var base: ValueType = this
				while (base is CArray) base = base.element
				base.metadataSpelling + arrayDimensions.joinToString("") { "[${it ?: ""}]" }
			}

			else      -> if (numeric) (if (unsigned) "unsigned " else "") + "$keyword<$bits>" else spelling
		}

	/** LLVM types belong to the supplied context; descriptors never cache native handles. */
	abstract fun llvmValue(context: LLVMContextRef): LLVMTypeRef
	open fun llvmStorage(context: LLVMContextRef): LLVMTypeRef = llvmValue(context)

	enum class FloatingFormat(val bits: Int, val exponentBits: Int) { HALF(16, 5), SINGLE(32, 8), DOUBLE(64, 11)
	}

	/** Unknown extents may be resolved at runtime; conflicting known extents cannot merge. */
	open fun commonArrayShape(other: ValueType): ValueType? = takeIf { this == other }

	/** Already-typed scalar values require an exact match. */
	open fun accepts(actual: ValueType): Boolean = actual == NEVER || this == actual

	/** Scalars have no inferred array extents. */
	open fun inferArraySizes(actual: ValueType): ValueType = this

	open val spelling: String
		get() = buildString {
			if (unsigned) append("${Keywords.UNSIGNED} ")
			append(keyword)
			if (numeric && bits != DEFAULT_BITS) append("<$bits>")
		}

	/** A standalone conditional executes branches without yielding a value. */
	data object Void : ValueType(Keywords.VOID) {

		override fun llvmValue(context: LLVMContextRef): LLVMTypeRef = LLVMVoidTypeInContext(context)
		override fun llvmStorage(context: LLVMContextRef): LLVMTypeRef = throw InternalErrors.invariant("A void statement has no binding storage")
	}

	/** Internal bottom type: evaluation returns from the enclosing function. No source spelling. */
	data object Never : ValueType(Keywords.VOID) {

		override val providers: Map<CompilerDataProviders, ProviderResult<*>> get() = emptyMap()
		override val spelling: String = "<no continuation>"
		override fun llvmValue(context: LLVMContextRef): LLVMTypeRef = throw InternalErrors.invariant("A non-continuing expression has no LLVM value")
		override fun llvmStorage(context: LLVMContextRef): LLVMTypeRef = throw InternalErrors.invariant("A non-continuing expression has no storage")
	}

	companion object {

		const val DEFAULT_BITS = 32
		val INTEGER_WIDTHS: Set<Int> = setOf(8, 16, 32, 64)
		val FLOAT_WIDTHS: Set<Int> = FloatingFormat.entries.mapTo(linkedSetOf()) { it.bits }
		val MAX_INTEGER_BITS: Int get() = INTEGER_WIDTHS.max()

		val INT = CInt()
		val UINT = CInt(unsigned = true)
		val FLOAT = CFloat()
		val HALF = CFloat(FloatingFormat.HALF)
		val SINGLE = CFloat(FloatingFormat.SINGLE)
		val DOUBLE = CFloat(FloatingFormat.DOUBLE)

		// Getters avoid initialization cycles when a singleton descriptor is used first.
		val BOOL get() = CBool
		val CHAR get() = CChar
		val STRING get() = CString
		val TYPEINFO get() = CTypeInfo
		val VOID get() = Void
		internal val NEVER get() = Never

		/** Two floats use the wider format; mixed integer/float expressions use at least float32. */
		fun commonFloating(left: ValueType, right: ValueType): CFloat {
			require(left.floating || right.floating)
			val minimum = if (left.integer || right.integer) DEFAULT_BITS else 0
			return CFloat(maxOf(minimum, if (left.floating) left.bits else 0, if (right.floating) right.bits else 0))
		}
	}
}
