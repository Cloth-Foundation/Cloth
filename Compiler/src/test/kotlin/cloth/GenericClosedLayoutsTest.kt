package cloth

import cloth.data.CClass
import cloth.emitter.NativeCABI
import cloth.semantic.generics.ClosedTypeAnalyzer
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Native LLVM type-shape checks only: no generic program or generic object is emitted. */
class GenericClosedLayoutsTest {

	@Test
	fun `closed fields form independent concrete LLVM storage on all nine ABI profiles`() {
		val ast = genericUnit("""
			typedef<struct> Slot<T> { T value }
			typedef<class> Base<T> (pub def<T> item)
			typedef<class> Derived<T> : Base<T> { def<Slot<T>[]> values = [] }
			fn take(Slot<int<129>?> a, Slot<string> b, Slot<usize> c, Derived<int[]> d) {}
		""")
		for (triple in structTargetTriples) {
			val target = NativeCABI.forTarget(triple).layout
			val model = ClosedTypeAnalyzer(target).analyze(ast)
			val (wide, text, count, derived) = model.parameterTypes()
			val context = LLVMContextCreate()
			try {
				val wideStorage = wide.llvmStorage(context, target)
				val textStorage = text.llvmStorage(context, target)
				val countStorage = count.llvmStorage(context, target)
				assertEquals(LLVMStructTypeKind, LLVMGetTypeKind(wideStorage))
				assertEquals(2, LLVMCountStructElementTypes(LLVMStructGetTypeAtIndex(wideStorage, 0)))
				assertEquals(target.sizeBits, LLVMGetIntTypeWidth(LLVMStructGetTypeAtIndex(LLVMStructGetTypeAtIndex(textStorage, 0), 1)))
				assertEquals(target.sizeBits, LLVMGetIntTypeWidth(LLVMStructGetTypeAtIndex(countStorage, 0)))
				assertEquals(64, LLVMGetIntTypeWidth(derived.llvmValue(context, target)))
				val objectStorage = (derived as CClass).instanceStorage(context, target)
				assertEquals(3, LLVMCountStructElementTypes(objectStorage)) // Base storage, array value, array arena.
				assertNotEquals(LLVMPrintTypeToString(wideStorage).string, LLVMPrintTypeToString(textStorage).string)
			} finally {
				LLVMContextDispose(context)
			}
		}
	}
}
