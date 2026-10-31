package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.emitter.LLVMTypes
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.SemanticAnalyzer
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PrimitiveTypesTest {

	@Test
	fun `semantic analysis produces the primitive descriptors used by emission`() {
		val source = """
			def integer = 1
			def<unsigned int<64>> wideUnsigned = 18446744073709551615
			def half = 1 -> float<16>
			def boolean = true
			def character = '😀'
			def text = "Cloth"
			def array = [1, 2]
			def matrix = [[true], [false]]
		""".trimIndent()
		val ast = Parser(Lexer(source).scanTokens()).parseSnippet()
		val semantics = SemanticAnalyzer().analyze(ast)
		assertEquals(listOf(CInt(), CInt(64, true), CFloat(16), CBool, CChar, CString, CArray(CInt(), 2), CArray(CArray(CBool, 1), 2)), ast.statements.map { semantics.typeOf(it) })
	}

	@Test
	fun `descriptors preserve scalar and aggregate LLVM representations`() {
		val context = LLVMContextCreate()
		try {
			val types = LLVMTypes(context)
			val cases = buildList {
				for (width in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) add(CInt(width, unsigned) to "i$width")
				add(CFloat(16) to "half")
				add(CFloat(32) to "float")
				add(CFloat(64) to "double")
				add(CBool to "i1")
				add(CChar to "i32")
				add(CString to "{ ptr, i64 }")
				add(ValueType.TYPEINFO to "ptr")
				add(CArray(CInt(), 0) to "{ ptr, i64 }")
				add(CArray(CArray(CArray(CInt()), 0), 2) to "{ ptr, i64, i65, i65 }")
				add(ValueType.VOID to "void")
			}
			for ((type, expected) in cases) {
				assertEquals(expected, spelling(types.value(type)), type.spelling)
				if (type != ValueType.VOID) {
					assertEquals(if (type == CBool) "i8" else expected, spelling(types.storage(type)), type.spelling)
				}
			}
			assertFailsWith<ClothDiagnostic> { types.storage(ValueType.VOID) }
			assertFailsWith<ClothDiagnostic> { types.value(ValueType.NEVER) }
			assertFailsWith<ClothDiagnostic> { types.storage(ValueType.NEVER) }
		} finally {
			LLVMContextDispose(context)
		}
	}

	@Test
	fun `a descriptor can be reused across independent LLVM contexts`() {
		val first = LLVMContextCreate()
		val second = LLVMContextCreate()
		try {
			for (type in listOf(CInt(64, true), CFloat(16), CBool, CChar, CString, CArray(CArray(CInt())))) {
				val firstType = type.llvmValue(first)
				val secondType = type.llvmValue(second)
				assertEquals(first.address(), LLVMGetTypeContext(firstType).address(), type.spelling)
				assertEquals(second.address(), LLVMGetTypeContext(secondType).address(), type.spelling)
				assertEquals(spelling(firstType), spelling(secondType), type.spelling)
				assertNotEquals(firstType.address(), secondType.address(), type.spelling)
			}
		} finally {
			LLVMContextDispose(second)
			LLVMContextDispose(first)
		}
	}

	private fun spelling(type: LLVMTypeRef): String {
		val text = LLVMPrintTypeToString(type)
		return try {
			text.string
		} finally {
			LLVMDisposeMessage(text)
		}
	}
}
