package cloth.data

import cloth.target.TargetLayout
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*

/** Shared native buffer layout. Inner extents keep knownness outside the count. */
internal object BufferLayout {

	fun count(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = LLVMIntTypeInContext(context, target.sizeBits)
	fun extent(context: LLVMContextRef, target: TargetLayout): LLVMTypeRef = PointerPointer<LLVMTypeRef>(2L).use { fields ->
		fields.put(0, count(context, target))
		fields.put(1, LLVMInt1TypeInContext(context))
		LLVMStructTypeInContext(context, fields, 2, 0)
	}

	fun descriptor(context: LLVMContextRef, target: TargetLayout, dimensions: Int, sharedShape: Boolean = false): LLVMTypeRef = PointerPointer<LLVMTypeRef>((dimensions + 1 + if (sharedShape) 1 else 0).toLong()).use { fields ->
		fields.put(0, LLVMPointerTypeInContext(context, 0))
		fields.put(1, count(context, target))
		for (index in 2..dimensions) fields.put(index.toLong(), extent(context, target))
		if (sharedShape) fields.put((dimensions + 1).toLong(), LLVMPointerTypeInContext(context, 0))
		LLVMStructTypeInContext(context, fields, dimensions + 1 + if (sharedShape) 1 else 0, 0)
	}

	/** Array-only storage facts follow the shape fields. Strings keep their two-field ABI. */
	fun arrayDescriptor(context: LLVMContextRef, target: TargetLayout, dimensions: Int, sharedShape: Boolean): LLVMTypeRef {
		val capacity = dimensions + 1 + if (sharedShape) 1 else 0
		return PointerPointer<LLVMTypeRef>((capacity + 2).toLong()).use { fields ->
			fields.put(0, LLVMPointerTypeInContext(context, 0))
			fields.put(1, count(context, target))
			for (index in 2..dimensions) fields.put(index.toLong(), extent(context, target))
			if (sharedShape) fields.put((dimensions + 1).toLong(), LLVMPointerTypeInContext(context, 0))
			fields.put(capacity.toLong(), count(context, target))
			fields.put((capacity + 1).toLong(), LLVMPointerTypeInContext(context, 0))
			LLVMStructTypeInContext(context, fields, capacity + 2, 0)
		}
	}
}
