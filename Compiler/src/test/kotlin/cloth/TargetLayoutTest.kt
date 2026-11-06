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
import cloth.semantic.declarations.DeclarationAnalyzer
import cloth.semantic.typing.FunctionAnalyzer
import cloth.settings.CompilerSettings
import cloth.target.TargetLayout
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TargetLayoutTest {

	private fun unit(source: String) = Parser(Lexer(source.trimIndent()).scanTokens()).parseDeclarations()
	private fun synthetic32() = TargetLayout("i686-unknown-linux-gnu", pointerBits = 32, cLongBits = 32)

	@Test
	fun `native ABI layout is accepted by explicit semantic analysis`() {
		val native = NativeCABI.native()
		assertSame(native.layout, FunctionAnalyzer(native.layout).analyze(unit("fn test() {}")).target)
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
	fun `driver applies selected target bounds before publishing an object`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("target.co"), "fn<usize> limit() = 4294967296")
		val output = directory.resolve("objects/target.obj")
		fun settings(triple: String) = CompilerSettings(arrayOf(
			source.toString(), output.toString(), "--emit", "object", "--target", triple,
		))

		val error = assertFailsWith<ClothDiagnostic> { compileArtifact(settings("i686-pc-windows-msvc")) }
		assertEquals(DiagnosticCode.INTEGER_RANGE, error.diagnosticCode)
		assertFalse(Files.exists(output.parent))
		val artifact = compileArtifact(settings("x86_64-pc-windows-msvc"))
		assertEquals(output, artifact)
		assertTrue(Files.size(artifact) > 0)
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
			val functions = FunctionAnalyzer(declarations.target).analyze(declarations)
			val program = ProgramAnalyzer(target).analyze(source)
			for (model in listOf(functions, program.functions)) {
				assertSame(target, model.target)
				assertSame(target, model.declarations.target)
				assertSame(target, model.types.target)
				assertSame(target, model.types.typeResolver.target)
				assertSame(target, model.trial().model.target)
				val count = model.declarations.functions.single { it.name.lexeme == "count" }
				assertEquals(CUSize(target.sizeBits), model.typeOf(count).returns)
				assertEquals(CUSize(target.sizeBits), model.types.providers.values.single { it.result.receiverType.arrayDimensions.isNotEmpty() }.result.type)
			}
		}
		assertEquals(32, small.sizeBits)
		assertEquals(64, wide.sizeBits)
	}

	@Test
	fun `fixed integer widths and providers are independent of target size`() {
		val context = LLVMContextCreate()
		try {
			for (target in listOf(synthetic32(), NativeCABI.native().layout)) {
				val types = LLVMTypes(context, target)
				assertSame(target, types.target)
				for (width in ValueType.MACHINE_INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
					val prefix = if (unsigned) "unsigned " else ""
					val ast = parseMain("def<$prefix int<$width>> n = 1\nn::max")
					val semantics = FunctionAnalyzer(target).analyze(ast)
					assertSame(target, semantics.target)
					assertEquals(CInt(width, unsigned), semantics.typeOf(ast.mainStatements.first()))
					assertEquals(CInt(width, unsigned), semantics.typeOf(ast.mainStatements.last()))
					assertEquals(width, LLVMGetIntTypeWidth(types.value(CInt(width, unsigned))))
					assertEquals(width, LLVMGetIntTypeWidth(types.storage(CInt(width, unsigned))))
				}
				val ast = parseMain("int::size")
				assertEquals(CUSize(target.sizeBits), FunctionAnalyzer(target).analyze(ast).typeOf(ast.mainStatements.single()))
			}
		} finally {
			LLVMContextDispose(context)
		}
	}

	@Test
	fun `semantic stages reject mismatched targets and trials inherit their analysis target`() {
		val source = unit("fn<int> test() = 1")
		val small = DeclarationAnalyzer(synthetic32()).analyze(source)
		val native = DeclarationAnalyzer(nativeTestLayout).analyze(source)
		assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(native.target).analyze(small) }
		val smallFacts = FunctionAnalyzer(small.target).analyze(small)
		val trial = smallFacts.trial()
		assertSame(small.target, trial.model.target)
		trial.commit()
		assertSame(small.target, smallFacts.target)
		assertEquals(CInt(), smallFacts.typeOf(small.functions.single()).returns)
	}

	@Test
	fun `supported injected targets install LLVM layout for functions and executables`() {
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
			for (ir in listOf(emitter.emit(source), emitter.emitExecutable(source))) {
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
