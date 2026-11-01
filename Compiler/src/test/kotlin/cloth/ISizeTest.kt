package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.emitter.types.EmitCast
import cloth.emitter.types.EmitString
import cloth.emitter.types.ProviderEmissionContext
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.CallExpression
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticAnalyzer
import cloth.semantic.SemanticModel
import cloth.target.TargetLayout
import cloth.token.TokenType
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.test.*

class ISizeTest {

	private val targets = listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc").map { NativeCABI.forTarget(it).layout }
	private fun unit(source: String) = Parser(Lexer(SourceFile("isize.co", source.trimIndent())).scanTokens()).parseDeclarations()
	private fun snippet(source: String) = Parser(Lexer(SourceFile("isize.co", source.trimIndent())).scanTokens()).parseSnippet()
	private fun failure(source: String, code: DiagnosticCode, target: TargetLayout) {
		val diagnostic = assertFailsWith<ClothDiagnostic>(source) { FunctionAnalyzer(target).analyze(unit(source)) }
		assertEquals(code, diagnostic.diagnosticCode, source)
		assertLocated(diagnostic)
	}

	@Test
	fun `one parsed source resolves isize independently for each target`() {
		assertEquals(TokenType.Keyword, Lexer("isize").scanTokens().first().type)
		val ast = snippet("def<isize> value = -7\ndef copied = value\ndef<int> defaultWidth = 0\ndef<int<64>> fixed = 0\nvalue::min")
		val functions = unit("fn<isize> identity(isize value) = value\nfn<isize[]> make(isize value) = [value, -1]")
		val context = LLVMContextCreate()
		try {
			for (target in targets + targets.first()) {
				val type = CISize(target.sizeBits)
				val semantics = SemanticAnalyzer(target).analyze(ast)
				assertEquals(listOf(type, type, CInt(), CInt(64), type), ast.statements.map { semantics.typeOf(it) })
				val model = FunctionAnalyzer(target).analyze(functions)
				val identity = model.typeOf(model.declarations.functions.first())
				assertEquals(listOf(type), identity.parameters)
				assertEquals(type, identity.returns)
				assertEquals(CArray(type), model.typeOf(model.declarations.functions.last()).returns)
				val types = LLVMTypes(context, target)
				assertEquals(target.sizeBits, LLVMGetIntTypeWidth(types.value(type)))
				assertEquals(types.value(CInt(target.sizeBits)).address(), types.value(type).address())
				assertEquals(types.value(type).address(), types.storage(type).address())
				assertNotEquals<ValueType>(CInt(target.sizeBits), type)
				assertNotEquals<ValueType>(CUSize(target.sizeBits), type)
			}
		} finally {
			LLVMContextDispose(context)
		}
	}

	@Test
	fun `signed literal bounds and wrapping use the target width`() {
		for (target in targets) {
			val type = CISize(target.sizeBits)
			val signed = CInt(target.sizeBits)
			assertEquals(signed.minimum, type.minimum)
			assertEquals(signed.maximum, type.maximum)
			assertFalse(type.unsigned)
			assertTrue(type.integer)
			for (bound in listOf(type.minimum, type.maximum)) {
				FunctionAnalyzer(target).analyze(unit("fn<isize> bound() = $bound"))
				assertTrue(type.acceptsLiteral(bound))
			}
			for (outside in listOf(type.minimum - BigInteger.ONE, type.maximum + BigInteger.ONE)) {
				assertFalse(type.acceptsLiteral(outside))
				failure("fn<isize> bad() = $outside", DiagnosticCode.INTEGER_RANGE, target)
			}
			assertEquals(type.minimum, type.wrap(type.maximum + BigInteger.ONE))
			assertEquals(type.maximum, type.wrap(type.minimum - BigInteger.ONE))
			assertEquals(-BigInteger.ONE, type.wrap(CUSize(target.sizeBits).maximum))
			for (operator in listOf("/", "%")) {
				failure("fn<isize> bad() = isize::min $operator -1", DiagnosticCode.INTEGER_DIVISION_OVERFLOW, target)
				failure("fn<isize> bad(isize value) = value $operator 0", DiagnosticCode.INTEGER_ZERO_DIVISOR, target)
			}
		}
	}

	@Test
	fun `isize requires exact typed values and explicit numeric conversions`() {
		for (target in targets) {
			for (other in listOf("int<${target.sizeBits}>", "usize")) {
				failure("fn bad(isize value) { def<$other> copy = value }", DiagnosticCode.TYPE_MISMATCH, target)
				failure("fn bad($other value) { def<isize> copy = value }", DiagnosticCode.TYPE_MISMATCH, target)
				failure("fn<isize> bad($other value) = value", DiagnosticCode.RETURN_TYPE, target)
				failure("fn<$other> bad(isize value) = value", DiagnosticCode.RETURN_TYPE, target)
				failure("fn take($other value) {}\nfn bad(isize value) { take(value) }", DiagnosticCode.NO_MATCHING_CALL, target)
				failure("fn bad(isize value, $other other) { [value, other] }", DiagnosticCode.INVALID_ARRAY, target)
				for (operator in listOf("+", "-", "*", "/", "%", "==", "!=", "<", ">", "<=", ">=")) {
					failure("fn bad(isize value, $other other) { value $operator other }", DiagnosticCode.MIXED_INTEGERS, target)
				}
				FunctionAnalyzer(target).analyze(unit("fn<isize> convert($other value) = value -> isize\nfn<$other> reverse(isize value) = value -> $other"))
			}
			failure("fn bad() { def<isize<32>> value = 0 }", DiagnosticCode.UNSUPPORTED_TYPE, target)
			failure("fn bad() { def<unsigned isize> value = 0 }", DiagnosticCode.INVALID_UNSIGNED, target)
			failure("fn bad() { def<isize> value = 1.0 }", DiagnosticCode.TYPE_MISMATCH, target)
			failure("fn bad() { true -> isize }", DiagnosticCode.INVALID_CAST, target)
			failure("fn bad(isize value) { value -> bool }", DiagnosticCode.INVALID_CAST, target)
			FunctionAnalyzer(target).analyze(unit("fn<isize> fromFloat(float<64> value) = value -> isize\nfn<float<64>> toFloat(isize value) = value -> float<64>"))
		}
	}

	@Test
	fun `overloads distinguish signed fixed integers and both target sized primitives`() {
		for (target in targets) {
			val fixed = "int<${target.sizeBits}>"
			val source = unit("""
				fn choose(isize value) {}
				fn choose($fixed value) {}
				fn choose(usize value) {}
				fn test(isize signed, $fixed fixed, usize count) {
				    choose(signed)
				    choose(fixed)
				    choose(count)
				}
			""")
			val model = FunctionAnalyzer(target).analyze(source)
			val body = model.declarations.functions.last().declaration.body as BlockBody
			val calls = body.statements.map { (it as ExpressionStatement).expression as CallExpression }
			assertEquals(listOf(CISize(target.sizeBits), CInt(target.sizeBits), CUSize(target.sizeBits)), calls.map { model.typeOf(model.selectedFunction(it)).parameters.single() })
			FunctionAnalyzer(target).analyze(unit("fn take(isize value) {}\nfn test() { take(-7) }"))
			failure("fn choose(isize value) {}\nfn choose(usize value) {}\nfn bad() { choose(7) }", DiagnosticCode.AMBIGUOUS_CALL, target)
		}
	}

	@Test
	fun `metadata and bounds preserve isize while all measurements remain usize`() {
		for (target in targets) {
			val type = CISize(target.sizeBits)
			val count = CUSize(target.sizeBits)
			assertEquals("isize", type.spelling)
			assertEquals("isize", type.metadataSpelling)
			assertEquals("isize[2][3]", CArray(CArray(type, 3), 2).metadataSpelling)
			for ((name, expected) in listOf("min" to type.minimum, "max" to type.maximum)) {
				val provider = assertIs<ProviderResult.IntegerConstant>(type.provider(name, target))
				assertSame(type, provider.type)
				assertEquals(expected, provider.value)
			}
			val size = assertIs<ProviderResult.IntegerConstant>(type.provider("size", target))
			assertEquals(count, size.type)
			assertEquals(BigInteger.valueOf((target.sizeBits / 8).toLong()), size.value)
			val ast = snippet("isize::min\nisize::max\nisize::size\nisize::type\nisize::type::str\nisize::type::width\nisize::type::isunsigned\nisize::type::isnumeric\nisize[3]::size\nisize[3]::type::rank\nisize[3]::type::extent")
			val semantics = SemanticAnalyzer(target).analyze(ast)
			assertEquals(listOf(type, type, count, CTypeInfo, CString, count, CBool, CBool, count, count, count), ast.statements.map { semantics.typeOf(it) })
			failure("fn bad() { isize::str }", DiagnosticCode.INVALID_PROVIDER_RECEIVER, target)
			failure("fn bad() { isize::max = 0 }", DiagnosticCode.READ_ONLY_PROVIDER, target)
		}
	}

	@Test
	fun `emitted metadata records canonical identity target width and signed flags`() {
		for (target in targets) {
			val abi = NativeCABI.forTarget(target.targetTriple)
			val emitter = LLVMEmitter(abi)
			emitter.withFunctions(unit("fn<isize> identity(isize value) = value\nfn<typeinfo> describe() = isize::type\nfn<typeinfo> array() = isize[3]::type")) { context, module, _, _ ->
				val global = LLVMGetNamedGlobal(module, "cloth_type_6973697a65")
				assertFalse(global.isNull)
				val descriptor = LLVMGetInitializer(global)
				assertEquals(target.sizeBits.toLong(), LLVMConstIntGetZExtValue(LLVMGetOperand(descriptor, 4)))
				assertEquals(0L, LLVMConstIntGetZExtValue(LLVMGetOperand(descriptor, 7)))
				assertEquals(1L, LLVMConstIntGetZExtValue(LLVMGetOperand(descriptor, 8)))
				val identity = LLVMGetNamedFunction(module, "cloth.fn.0.identity")
				assertEquals(target.sizeBits, LLVMGetIntTypeWidth(LLVMGetReturnType(LLVMGlobalGetValueType(identity))))
				assertEquals(target.sizeBits, LLVMGetIntTypeWidth(LLVMTypeOf(LLVMGetParam(identity, 0))))
				assertEquals(target.sizeBits, LLVMGetIntTypeWidth(CISize(target.sizeBits).llvmValue(context, target)))
				emitter.verify(module)
			}
		}
	}

	@Test
	fun `isize provider constants reject facts from a different target before emission`() {
		val abi = NativeCABI.forTarget(targets.first().targetTriple)
		val context = LLVMContextCreate()
		val builder = LLVMCreateBuilderInContext(context)
		try {
			val strings = EmitString(context, builder, abi)
			val services = ProviderEmissionContext(context, builder, strings, EmitCast(context, builder, SemanticModel(abi.layout), abi), "isize.co")
			val wrong = CISize(targets.last().sizeBits)
			val recipe = assertIs<ProviderResult.IntegerConstant>(wrong.provider("min", targets.last()))
			val diagnostic = assertFailsWith<ClothDiagnostic> { recipe.emit(services, ProviderReceiver.Type(wrong)) }
			assertContains(diagnostic.message.orEmpty(), "Provider isize result belongs to a different target layout")
			val type = CISize(abi.layout.sizeBits)
			for (bound in listOf(type.minimum, type.maximum)) {
				val value = ProviderResult.IntegerConstant(type, bound).emit(services, ProviderReceiver.Type(type))
				assertEquals(bound.toLong(), LLVMConstIntGetSExtValue(value))
			}
		} finally {
			LLVMDisposeBuilder(builder)
			LLVMContextDispose(context)
		}
	}
}
