package cloth

import cloth.data.CInt
import cloth.data.CUSize
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.*
import cloth.target.TargetLayout
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TargetLayoutTest {

	private fun unit(source: String) = Parser(Lexer(source.trimIndent()).scanTokens()).parseDeclarations()
	private fun synthetic32(): TargetLayout = NativeCABI.native().layout.copy(
		targetTriple = "i686-unknown-linux-gnu", pointerBits = 32, sizeBits = 32,
		pointerAlignmentBits = 32, cLongBits = 32,
	)

	@Test
	fun `native ABI facts and native semantic defaults agree`() {
		val native = NativeCABI.native()
		assertEquals(native.layout, SemanticAnalyzer().analyze(Parser(Lexer("1").scanTokens()).parseSnippet()).target)
		assertEquals(native.layout, FunctionAnalyzer().analyze(unit("fn test() {}")).target)
		for (triple in listOf("x86_64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-apple-darwin")) {
			val abi = NativeCABI.forTarget(triple)
			assertEquals(abi.targetTriple, abi.layout.targetTriple)
			assertEquals(abi.pointerBits, abi.layout.pointerBits)
			assertEquals(abi.sizeType.bits, abi.layout.sizeBits)
			assertEquals(abi.intType.bits, abi.layout.cIntBits)
			assertEquals(abi.longType.bits, abi.layout.cLongBits)
			assertEquals(abi.longLongType.bits, abi.layout.cLongLongBits)
			assertEquals(abi.doubleType.format, abi.layout.cDoubleFormat)
		}
	}

	@Test
	fun `interleaved layouts stay local through declarations overloads blocks and providers`() {
		val small = synthetic32()
		val wide = NativeCABI.native().layout
		val source = unit("""
			fn<int<64>> choose(int<64> value) = value
			fn<string> choose(string value) = value
			fn<usize> count() = int[3]::size
			fn<int> main() {
			    def<int> deferred = { return int::max }
			    def<usize> measured = count()
			    def result = choose(7)
			    println(measured)
			    return deferred
			}
		""")
		for (target in listOf(small, wide, small)) {
			val declarations = DeclarationAnalyzer(target).analyze(source)
			val calls = CallAnalyzer().analyze(declarations)
			val functions = FunctionAnalyzer().analyze(declarations)
			val program = ProgramAnalyzer(target).analyze(source)
			for (model in listOf(calls, functions, program.functions)) {
				assertSame(target, model.target)
				assertSame(target, model.declarations.target)
				assertSame(target, model.types.target)
				assertSame(target, model.types.typeResolver.target)
				assertSame(target, model.fork().target)
				val count = model.declarations.functions.single { it.name.lexeme == "count" }
				assertEquals(CUSize(target.sizeBits), model.typeOf(count).returns)
				assertEquals(CUSize(target.sizeBits), model.types.providers.values.single { it.result.receiverType.arrayDimensions.isNotEmpty() }.result.type)
			}
		}
		assertEquals(32, small.sizeBits)
		assertEquals(64, wide.sizeBits)
	}

	@Test
	fun `fixed integer widths and snippet providers are independent of target size`() {
		val context = LLVMContextCreate()
		try {
			for (target in listOf(synthetic32(), NativeCABI.native().layout)) {
				val types = LLVMTypes(context, target)
				assertSame(target, types.target)
				for (width in ValueType.MACHINE_INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
					val prefix = if (unsigned) "unsigned " else ""
					val ast = Parser(Lexer("def<$prefix int<$width>> n = 1\nn::max").scanTokens()).parseSnippet()
					val semantics = SemanticAnalyzer(target).analyze(ast)
					assertSame(target, semantics.target)
					assertEquals(CInt(width, unsigned), semantics.typeOf(ast.statements.first()))
					assertEquals(CInt(width, unsigned), semantics.typeOf(ast.statements.last()))
					assertEquals(width, LLVMGetIntTypeWidth(types.value(CInt(width, unsigned))))
					assertEquals(width, LLVMGetIntTypeWidth(types.storage(CInt(width, unsigned))))
				}
				val ast = Parser(Lexer("int::size").scanTokens()).parseSnippet()
				assertEquals(CUSize(target.sizeBits), SemanticAnalyzer(target).analyze(ast).typeOf(ast.statements.single()))
			}
		} finally {
			LLVMContextDispose(context)
		}
	}

	@Test
	fun `semantic stages reject mixing explicit targets and cross-target fact commits`() {
		val source = unit("fn<int> test() = 1")
		val small = DeclarationAnalyzer(synthetic32()).analyze(source)
		val native = DeclarationAnalyzer().analyze(source)
		assertFailsWith<ClothDiagnostic> { CallAnalyzer(native.target).analyze(small) }
		assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(native.target).analyze(small) }
		val smallFacts = FunctionAnalyzer().analyze(small)
		val nativeFacts = FunctionAnalyzer().analyze(native)
		assertFailsWith<ClothDiagnostic> { smallFacts.commit(nativeFacts) }
		assertSame(small.target, smallFacts.target)
		assertEquals(CInt(), smallFacts.typeOf(small.functions.single()).returns)
	}

	@Test
	fun `supported injected targets install LLVM layout for functions snippets and executables`() {
		val source = unit("fn<int> main() {\n def a = [1, 2]\n println(a::size)\n return a[0]\n}")
		val triples = listOf("x86_64", "aarch64").flatMap { architecture -> listOf("pc-windows-msvc", "unknown-linux-gnu", "apple-darwin").map { platform -> "$architecture-$platform" } } + "i686-pc-windows-msvc"
		for (triple in triples) {
			val abi = NativeCABI.forTarget(triple)
			val emitter = LLVMEmitter(abi)
			emitter.withFunctions(source) { context, module, _, semantics ->
				assertSame(abi.layout, semantics.target)
				assertEquals(abi.targetTriple, LLVMGetTarget(module).string)
				assertTrue(LLVMGetDataLayoutStr(module).string.isNotEmpty())
				val layout = LLVMGetModuleDataLayout(module)
				val pointer = LLVMPointerTypeInContext(context, 0)
				assertEquals(abi.layout.pointerBits.toLong(), LLVMSizeOfTypeInBits(layout, pointer))
				assertEquals(abi.layout.pointerAlignmentBits, LLVMABIAlignmentOfType(layout, pointer) * 8)
				emitter.verify(module)
			}
			val snippets = Parser(Lexer("def<int<64>> value = 7\nvalue").scanTokens()).parseSnippet()
			for (ir in listOf(emitter.emit(source), emitter.emitExecutable(source), emitter.emitSnippet(snippets))) {
				assertContains(ir, "target triple = \"${abi.targetTriple}\"")
				assertContains(ir, "target datalayout = \"")
			}
		}
	}

	@Test
	fun `an emitted module cannot be silently retargeted and synthetic layouts enable no native ABI`(@TempDir directory: Path) {
		val windows = NativeCABI.forTarget("x86_64-pc-windows-msvc")
		val linux = NativeCABI.forTarget("x86_64-unknown-linux-gnu")
		val output = directory.resolve("wrong-target.o")
		LLVMEmitter(windows).withFunctions(unit("fn test() {}")) { _, module, _, _ ->
			assertFailsWith<ClothDiagnostic> { LLVMEmitter(linux).emitObject(module, output) }
		}
		assertFalse(Files.exists(output))
		val failure = assertFailsWith<ClothDiagnostic> { NativeCABI.forTarget(synthetic32().targetTriple) }
		assertEquals(DiagnosticCode.UNSUPPORTED_TARGET, failure.diagnosticCode)
	}
}
