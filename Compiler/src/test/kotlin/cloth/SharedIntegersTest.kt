package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.text.EmitString
import cloth.emitter.text.ProviderEmissionContext
import cloth.emitter.types.EmitCast
import cloth.emitter.types.EmitPower
import cloth.emitter.types.primitives.EmitIntegerDivision
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.expression.CastExpression
import cloth.parser.expression.Expression
import cloth.parser.expression.NameExpression
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.Statement
import cloth.semantic.typing.ExpressionTyping
import cloth.semantic.typing.SemanticModel
import cloth.token.Keywords
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.test.*

class SharedIntegersTest {

	/** Test-only identity exercises the contract without adding any source type. */
	private data class TestInteger(override val width: Int, override val unsigned: Boolean) : CInteger(Keywords.INTEGER) {

		override val spelling get() = (if (unsigned) "unsigned " else "") + "testint<$width>"
		override val metadataSpelling get() = spelling
	}

	private fun expression(source: String): Expression = (parseMain(source).mainStatements.single() as ExpressionStatement).expression

	private fun check(source: String, integer: CInteger, expected: ValueType? = null): Pair<Expression, SemanticModel> {
		val model = SemanticModel(nativeTestLayout)
		val typing = object : ExpressionTyping(model) {
			override fun nameType(expression: NameExpression): ValueType {
				check(expression.name.lexeme == "n")
				return integer
			}

			override fun statement(statement: Statement): Unit = error("No statements in this expression fixture")
			override fun withScope(action: () -> Unit) = action()
		}
		val expression = expression(source)
		typing.typeOf(expression, expected)
		return expression to model
	}

	@Test
	fun `shared bounds providers and representation preserve distinct integer identities`() {
		val context = LLVMContextCreate()
		try {
			for (width in ValueType.MACHINE_INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
				val fixed = CInt(width, unsigned)
				val distinct = TestInteger(width, unsigned)
				assertNotEquals<ValueType>(fixed, distinct)
				assertFalse(fixed.accepts(distinct))
				assertFalse(distinct.accepts(fixed))
				assertNotEquals(fixed.metadataSpelling, distinct.metadataSpelling)
				assertNotEquals(CArray(fixed, 2).metadataSpelling, CArray(distinct, 2).metadataSpelling)
				assertEquals(fixed.llvmValue(context, nativeTestLayout).address(), distinct.llvmValue(context, nativeTestLayout).address())
				assertEquals(fixed.llvmStorage(context, nativeTestLayout).address(), distinct.llvmStorage(context, nativeTestLayout).address())
				for (type in listOf<CInteger>(fixed, distinct)) {
					assertEquals(fixed.minimum, type.minimum)
					assertEquals(fixed.maximum, type.maximum)
					for (name in listOf("min", "max")) {
						val bound = assertIs<ProviderResult.IntegerConstant>(type.provider(name, nativeTestLayout))
						assertSame(type, bound.type)
						assertEquals(if (name == "min") type.minimum else type.maximum, bound.value)
					}
					assertTrue(type.acceptsLiteral(type.minimum))
					assertTrue(type.acceptsLiteral(type.maximum))
					assertFalse(type.acceptsLiteral(type.minimum - BigInteger.ONE))
					assertFalse(type.acceptsLiteral(type.maximum + BigInteger.ONE))
					assertEquals(type.minimum, type.wrap(type.maximum + BigInteger.ONE))
					assertFailsWith<IllegalArgumentException> { ProviderResult.IntegerConstant(type, type.maximum + BigInteger.ONE) }
				}
			}
		} finally {
			LLVMContextDispose(context)
		}
	}

	@Test
	fun `contextual literals and constant division checks accept the shared contract`() {
		for (width in ValueType.MACHINE_INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
			val type = TestInteger(width, unsigned)
			for (source in listOf("n + 1", "1 + n", "n / 1", "n % 1", "n ** 2", "n::max")) {
				val (expression, model) = check(source, type)
				assertSame(type, model.typeOf(expression), source)
			}
			for (value in listOf(type.minimum, type.maximum)) {
				val (expression, model) = check(value.toString(), type, type)
				assertSame(type, model.typeOf(expression))
			}
			for (value in listOf(type.minimum - BigInteger.ONE, type.maximum + BigInteger.ONE)) {
				// Larger raw literals fail in the parser; descriptor bounds are checked above.
				if (value.abs().bitLength() > ValueType.MAX_INTEGER_BITS) continue
				val failure = assertFailsWith<ClothDiagnostic> { check(value.toString(), type, type) }
				assertEquals(DiagnosticCode.INTEGER_RANGE, failure.diagnosticCode)
				assertContains(failure.message.orEmpty(), type.spelling)
			}
			for (operator in listOf("/", "%")) {
				assertEquals(DiagnosticCode.INTEGER_ZERO_DIVISOR, assertFailsWith<ClothDiagnostic> { check("n $operator 0", type) }.diagnosticCode)
				if (!unsigned) {
					assertEquals(DiagnosticCode.INTEGER_DIVISION_OVERFLOW, assertFailsWith<ClothDiagnostic> { check("n::min $operator -1", type) }.diagnosticCode)
				}
			}
			assertFailsWith<ClothDiagnostic> { check("n + (1 -> ${CInt(width, unsigned).spelling})", type) }
			val (array, model) = check("[n, 1]", type)
			assertEquals(CArray(type, 2), model.typeOf(array))
		}
	}

	@Test
	fun `shared integer descriptors pass casts division power formatting and metadata emission`() {
		val abi = NativeCABI.native()
		val emitter = LLVMEmitter(abi)
		val source = Parser(Lexer("fn existing() {}").scanTokens()).parseDeclarations()
		emitter.withFunctions(source) { context, module, _, _ ->
			for (width in ValueType.MACHINE_INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
				val type = TestInteger(width, unsigned)
				val nativeType = type.llvmValue(context, abi.layout)
				assertEquals(abi.printArgument(CInt(width, unsigned)), abi.printArgument(type))
				assertEquals(abi.printSpecifier(CInt(width, unsigned)), abi.printSpecifier(type))
				val signature = PointerPointer<LLVMTypeRef>(2L).use { parameters ->
					parameters.put(0, nativeType)
					parameters.put(1, ValueType.FLOAT.llvmValue(context, abi.layout))
					LLVMFunctionType(nativeType, parameters, 2, 0)
				}
				val function = LLVMAddFunction(module, "shared_${width}_$unsigned", signature)
				val builder = LLVMCreateBuilderInContext(context)
				try {
					LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, function, "entry"))
					// Supply backend facts directly; no test-only type is added to source resolution.
					val cast = expression("1.5 -> int<$width>") as CastExpression
					val semantics = SemanticModel(abi.layout)
					semantics.recordType(cast.operand, ValueType.FLOAT)
					semantics.recordType(cast, type)
					val casts = EmitCast(context, builder, semantics, abi)
					val converted = casts.emit(cast, LLVMGetParam(function, 1))
					val quotient = EmitIntegerDivision(context, builder, abi).emit(LLVMGetParam(function, 0), converted, type, false, "shared-test")
					val power = EmitPower(context, builder, abi).emit(quotient, LLVMConstInt(nativeType, 2, 0), type, "shared-test")
					val strings = EmitString(context, builder, abi)
					val services = ProviderEmissionContext(context, builder, strings, casts, "shared-test")
					assertNotNull(type.provider("str", abi.layout)).emit(services, ProviderReceiver.Value(type, power))
					val metadata = strings.metadata.constant(type)
					assertNotEquals(metadata.address(), strings.metadata.constant(CInt(width, unsigned)).address())
					LLVMBuildRet(builder, power)
				} finally {
					LLVMDisposeBuilder(builder)
				}
			}
			emitter.verify(module)
			val printed = LLVMPrintModuleToString(module)
			try {
				val ir = printed.string
				for (operation in listOf("udiv", "sdiv", "fptoui", "fptosi")) assertContains(ir, operation)
				assertContains(ir, "testint<32>")
			} finally {
				LLVMDisposeMessage(printed)
			}
		}
	}
}
