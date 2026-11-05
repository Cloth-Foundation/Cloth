package cloth.emitter.types

import cloth.data.*
import cloth.diagnostics.BackendErrors
import cloth.semantic.FunctionSemanticModel
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*
import java.math.BigInteger

/** Validate inline sizes with exact arithmetic before asking LLVM to lay out a
 * potentially oversized aggregate. Array fields occupy descriptor bytes only.
 */
internal class StructStorageLayout(private val context: LLVMContextRef, module: LLVMModuleRef, private val semantics: FunctionSemanticModel) {

	private data class Shape(val bytes: BigInteger, val alignment: Int)

	private val layout = LLVMGetModuleDataLayout(module)
	private val maximum = BigInteger.ONE.shiftLeft(minOf(semantics.target.sizeBits, semantics.target.pointerBits - 1)) - BigInteger.ONE
	private val memo = mutableMapOf<ValueType, Shape>()
	private val byte = LLVMInt8TypeInContext(context)
	private val aggregateAlignment = PointerPointer<LLVMTypeRef>(1L).use { members ->
		members.put(0, byte)
		LLVMABIAlignmentOfType(layout, LLVMStructTypeInContext(context, members, 1, 0))
	}

	private fun aligned(bytes: BigInteger, alignment: Int): BigInteger {
		val unit = alignment.toBigInteger()
		return (bytes + unit - BigInteger.ONE) / unit * unit
	}

	private fun aggregate(fields: List<Shape>): Shape {
		val alignment = fields.fold(aggregateAlignment) { current, field -> maxOf(current, field.alignment) }
		val end = fields.fold(BigInteger.ZERO) { offset, field -> aligned(offset, field.alignment) + field.bytes }
		return Shape(aligned(end, alignment), alignment)
	}

	private fun shape(type: ValueType): Shape = memo.getOrPut(type) {
		val result = when {
			type is CStruct                              -> aggregate(if (type.fields.isEmpty()) listOf(Shape(BigInteger.ONE, 1)) else type.fields.map { shape(it.type) })
			type is CNullable && type.payload is CStruct -> aggregate(listOf(Shape(BigInteger.ONE, 1), shape(type.payload)))
			else                                         -> {
				val storage = type.llvmStorage(context, semantics.target)
				Shape(LLVMABISizeOfType(layout, storage).toBigInteger(), LLVMABIAlignmentOfType(layout, storage))
			}
		}
		if (result.bytes > maximum && type.isStructValue) {
			val struct = ((type as? CNullable)?.payload ?: type) as CStruct
			throw BackendErrors.structStorage(type, struct.declaration.name.span)
		}
		result
	}

	fun validate() {
		semantics.declarations.structs.values.forEach { shape(it) }
		fun check(type: ValueType) {
			when {
				type.isStructValue -> shape(type)
				type is CArray     -> check(type.element)
				type is CNullable  -> check(type.payload)
			}
		}
		semantics.bindings.values.forEach(::check)
		semantics.types.expressions.values.forEach(::check)
	}
}
