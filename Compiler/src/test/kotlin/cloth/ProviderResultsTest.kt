package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.NativeLinker
import cloth.emitter.ToolchainResolver
import cloth.emitter.types.*
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.expression.Expression
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ReturnStatement
import cloth.semantic.FunctionSemanticModel
import cloth.semantic.ProviderAccess
import cloth.semantic.SemanticAnalyzer
import cloth.settings.LinkOptions
import cloth.token.TokenType
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProviderResultsTest {

	@Test
	fun `integer bounds retain exact widths signedness and values`() {
		for (width in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
			val type = CInt(width, unsigned)
			val minimum = assertIs<ProviderResult.IntegerConstant>(type.provider("min"))
			val maximum = assertIs<ProviderResult.IntegerConstant>(type.provider("max"))
			assertEquals(type, minimum.type)
			assertEquals(type, maximum.receiverType)
			assertEquals(if (unsigned) BigInteger.ZERO else BigInteger.ONE.shiftLeft(width - 1).negate(), minimum.value)
			assertEquals(BigInteger.ONE.shiftLeft(if (unsigned) width else width - 1) - BigInteger.ONE, maximum.value)
		}
	}

	@Test
	fun `provider spellings are contextual and unresolved accessors stay unavailable`() {
		for (provider in CompilerDataProviders.entries) {
			assertEquals(provider, CompilerDataProviders.fromSpelling(provider.spelling))
			assertEquals(TokenType.Identifier, Lexer(provider.spelling).scanTokens().first().type)
		}
		assertNull(CompilerDataProviders.fromSpelling("MAX"))
		assertNull(CInt().provider("unknown"))
		assertNull(CInt().provider("addr"))
		assertIs<ProviderResult.TypeMetadata>(CInt().provider("type"))
		assertEquals(setOf(CompilerDataProviders.MIN, CompilerDataProviders.MAX, CompilerDataProviders.SIZE, CompilerDataProviders.STR, CompilerDataProviders.TYPE), CFloat().providers.keys)
		for (type in listOf(CBool, CChar, CString)) assertEquals(setOf(CompilerDataProviders.SIZE, CompilerDataProviders.STR, CompilerDataProviders.TYPE), type.providers.keys)
		assertEquals(setOf(CompilerDataProviders.SIZE, CompilerDataProviders.TYPE), CArray(CInt()).providers.keys)
		assertEquals(setOf(CompilerDataProviders.TYPE), ValueType.VOID.providers.keys)
		assertTrue(ValueType.NEVER.providers.isEmpty())
		val ast = Parser(Lexer("1").scanTokens()).parseSnippet()
		val semantics = SemanticAnalyzer().analyze(ast)
		val expression = (ast.statements.single() as cloth.parser.statement.ExpressionStatement).expression
		assertEquals(CInt(), assertNotNull(semantics.providerAccess(expression, "max")).type)
		assertEquals(CInt(64, true), assertNotNull(semantics.providerAccess(expression, "size")).type)
	}

	@Test
	fun `invalid typed constants and operations fail before LLVM emission`() {
		assertFailsWith<IllegalArgumentException> { ProviderResult.IntegerConstant(CInt(8), BigInteger.valueOf(128)) }
		assertFailsWith<IllegalArgumentException> { ProviderResult.IntegerConstant(CInt(64, true), BigInteger.valueOf(-1)) }
		assertFailsWith<IllegalArgumentException> { ProviderResult.FloatingConstant(CFloat(16), CFloat(16).overflowThreshold.toBigDecimal()) }
		assertFailsWith<IllegalArgumentException> { ProviderResult.ArrayExtent(CArray(CInt()), 1, CInt(64)) }
		assertFailsWith<IllegalArgumentException> { ProviderResult.ArrayExtent(CArray(CInt()), 0, CInt(32)) }
		assertFailsWith<IllegalArgumentException> { ProviderResult.ScalarText(CArray(CString)) }
	}

	@Test
	fun `constant recipes emit exact integer boundaries and floating formats`() {
		withModule("fn<int> sample() = 1") {
			val receiver = receiver()
			for (width in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
				val type = CInt(width, unsigned)
				for (bound in listOf(type.minimum, type.maximum)) {
					val value = assertNotNull(expressions.emitProvider(ProviderAccess(receiver, ProviderResult.IntegerConstant(type, bound, CInt()))))
					assertEquals(width, LLVMGetIntTypeWidth(LLVMTypeOf(value)))
					val actual = if (unsigned) LLVMConstIntGetZExtValue(value).toULong().toString() else LLVMConstIntGetSExtValue(value).toString()
					assertEquals(bound.toString(), actual)
				}
			}
			for (width in ValueType.FLOAT_WIDTHS) {
				val value = assertNotNull(expressions.emitProvider(ProviderAccess(receiver, ProviderResult.FloatingConstant(CFloat(width), BigDecimal("1.5"), CInt()))))
				assertEquals(CFloat(width).llvmValue(context).address(), LLVMTypeOf(value).address())
				val losesInfo = org.bytedeco.javacpp.IntPointer(1L)
				try {
					assertEquals(1.5, LLVMConstRealGetDouble(value, losesInfo))
				} finally {
					losesInfo.close()
				}
			}
		}
	}

	@Test
	fun `a constant access evaluates a captured receiver exactly once per use`(@TempDir directory: Path) {
		native(directory, "constant_effects", """
			fn<int<8>> sample() {
			    def count = 0
			    def<int<8>> next = {
			        count += 1
			        return 7
			    }
			    return next
			}
		""") {
			val expression = receiver()
			val bound = assertNotNull(semantics.types.providerAccess(expression, "max"))
			val declaration = (semantics.declarations.functions.single().declaration.body as BlockBody).statements.first() as BindingDeclaration
			for (count in 1..2) {
				val result = assertNotNull(expressions.emitProvider(bound))
				check(LLVMBuildICmp(builder, LLVMIntEQ, result, LLVMConstInt(CInt(8).llvmValue(context), 127, 0), "bound_valid"))
				check(LLVMBuildICmp(builder, LLVMIntEQ, bindings.read(semantics.declarations.symbolOf(declaration)), int(count), "count_valid"))
			}
		}
	}

	@Test
	fun `array extent operations read runtime metadata and retain known extent effects`(@TempDir directory: Path) {
		native(directory, "array_extents", """
			fn<int[]> make(bool many) = if (many) { [1, 2, 3] } else { [1] }
			fn<int[]> dynamic() = make(true)
			fn<int[2]> known() {
			    def count = 0
			    def<int[2]> next = {
			        count += 1
			        return [7, 8]
			    }
			    return next
			}
		""") {
			val dynamic = receiver("dynamic")
			val dynamicType = semantics.typeOf(dynamic) as CArray
			assertNull(dynamicType.size)
			val dynamicValue = assertNotNull(expressions.emitProvider(ProviderAccess(dynamic, ProviderResult.ArrayExtent(dynamicType, 0, CInt(64, true)))))
			check(LLVMBuildICmp(builder, LLVMIntEQ, dynamicValue, LLVMConstInt(CInt(64).llvmValue(context), 3, 0), "dynamic_valid"))
			val known = receiver("known")
			val knownValue = assertNotNull(expressions.emitProvider(ProviderAccess(known, ProviderResult.ArrayExtent(semantics.typeOf(known) as CArray, 0, CInt(64)))))
			assertEquals(2L, LLVMConstIntGetSExtValue(knownValue))
			val declaration = (semantics.declarations.functions.single { it.name.lexeme == "known" }.declaration.body as BlockBody).statements.first() as BindingDeclaration
			check(LLVMBuildICmp(builder, LLVMIntEQ, bindings.read(semantics.declarations.symbolOf(declaration)), int(1), "known_effect_valid"))
		}
	}

	@Test
	fun `scalar formatting operations preserve current formatting and Unicode bytes`(@TempDir directory: Path) {
		native(directory, "scalar_text", """
			fn<int> integer() = -42
			fn<unsigned int<64>> wide() = 18446744073709551615
			fn<float<16>> half() = 1.5 -> float<16>
			fn<float<64>> floating() = 2.25 -> float<64>
			fn<bool> boolean() = true
			fn<char> character() = '😀'
			fn<string> text() = "é\0中😀"
		""") {
			val results = listOf("integer" to "-42", "wide" to "18446744073709551615", "half" to "1.5", "floating" to "2.25", "boolean" to "true", "character" to "😀", "text" to "é\u0000中😀").map { (name, expected) ->
				val expression = receiver(name)
				val value = assertNotNull(expressions.emitProvider(ProviderAccess(expression, ProviderResult.ScalarText(semantics.typeOf(expression)))))
				value to expected
			}
			for ((value, expected) in results) check(strings.equal(value, strings.literal(expected)))
		}
	}

	@Test
	fun `internal extent reads preserve inner axes and unwitnessed dimension sentinels`(@TempDir directory: Path) {
		native(directory, "inner_extents", """
			fn<int[][]> grid() = [[1, 2], [3, 4]]
			fn<int[][]> dynamic() = grid()
			fn<int[][]> empty() = []
			fn<int[][]> emptyCall() = empty()
		""") {
			for ((name, expected) in listOf("dynamic" to 2L, "emptyCall" to -1L)) {
				val expression = receiver(name)
				val type = semantics.typeOf(expression) as CArray
				val value = assertNotNull(expressions.emitProvider(ProviderAccess(expression, ProviderResult.ArrayExtent(type, 1, CInt(64)))))
				check(LLVMBuildICmp(builder, LLVMIntEQ, value, LLVMConstInt(CInt(64).llvmValue(context), expected, 1), "inner_extent_valid"))
			}
		}
	}

	@Test
	fun `borrowed provider text survives a buffered return and source frame cleanup`(@TempDir directory: Path) {
		native(directory, "buffered_text", """
			fn<string[]> make() = ["é中😀", "Cloth"]
			fn<string> sample() = make()[0]
		""") {
			val wrapperType = PointerPointer<LLVMTypeRef>(1L).use { parameters ->
				parameters.put(0, LLVMPointerTypeInContext(context, 0))
				LLVMFunctionType(CString.llvmValue(context), parameters, 1, 0)
			}
			val wrapper = LLVMAddFunction(module, "provider_wrapper", wrapperType)
			val nestedBuilder = LLVMCreateBuilderInContext(context)
			try {
				val nested = Harness(context, module, nestedBuilder, functions, semantics, wrapper, CString, LLVMGetParam(wrapper, 0))
				val result = assertNotNull(nested.expressions.emitProvider(ProviderAccess(nested.receiver(), ProviderResult.ScalarText(CString))))
				nested.frame.emitReturn(result)
				nested.frame.finish()
			} finally {
				LLVMDisposeBuilder(nestedBuilder)
			}
			val returned = PointerPointer<LLVMValueRef>(1L).use { arguments ->
				arguments.put(0, frame.stringReturnArena())
				LLVMBuildCall2(builder, wrapperType, wrapper, arguments, 1, "returned_provider_text")
			}
			// A subsequent discarded array/string expression releases only its own new buffers.
			expressions.emitDiscarded(receiver())
			check(strings.equal(returned, strings.literal("é中😀")))
		}
	}

	@Test
	fun `provider recipes are reusable across LLVM contexts and reject mismatched receivers`() {
		val recipe = ProviderResult.IntegerConstant(CInt(64, true), CInt(64, true).maximum, CInt())
		repeat(2) {
			withModule("fn<int> sample() = 1") {
				val expression = receiver()
				val result = assertNotNull(expressions.emitProvider(ProviderAccess(expression, recipe)))
				assertEquals(context.address(), LLVMGetTypeContext(LLVMTypeOf(result)).address())
				assertFailsWith<ClothDiagnostic> { expressions.emitProvider(ProviderAccess(expression, ProviderResult.ScalarText(CBool))) }
			}
		}
	}

	@Test
	fun `a terminating receiver emits no provider value`(@TempDir directory: Path) {
		native(directory, "terminating", "fn<int> sample() = if (true) { return 0 } else { return 0 }") {
			val expression = receiver()
			assertEquals(ValueType.NEVER, semantics.typeOf(expression))
			assertNull(expressions.emitProvider(ProviderAccess(expression, ProviderResult.IntegerConstant(CInt(), BigInteger.TEN, ValueType.NEVER))))
			assertTrue(expressions.terminated())
		}
	}

	/** Test-only module entry; no source accessor grammar or implicit printing is added. */
	private fun withModule(source: String, output: Path? = null, action: Harness.() -> Unit) {
		val emitter = LLVMEmitter()
		val ast = Parser(Lexer(source.trimIndent()).scanTokens()).parse()
		emitter.withFunctions(ast) { context, module, functions, semantics ->
			val builder = LLVMCreateBuilderInContext(context)
			try {
				val signature = LLVMFunctionType(CInt().llvmValue(context), null as PointerPointer<*>?, 0, 0)
				val main = LLVMAddFunction(module, "main", signature)
				val harness = Harness(context, module, builder, functions, semantics, main)
				harness.action()
				if (!harness.expressions.terminated()) harness.frame.emitReturn(LLVMBuildSelect(builder, harness.success, harness.int(0), harness.int(1), "test_status"))
				harness.frame.finish()
				emitter.verify(module)
				if (output != null) emitter.emitObject(module, output)
			} finally {
				LLVMDisposeBuilder(builder)
			}
		}
	}

	private fun native(directory: Path, name: String, source: String, action: Harness.() -> Unit) {
		val abi = NativeCABI.native()
		val objectFile = directory.resolve("$name.obj")
		val executable = abi.executableFormat.executablePath(directory.resolve(name))
		withModule(source, objectFile, action)
		try {
			NativeLinker.link(objectFile, executable, ToolchainResolver.native(abi, LinkOptions()))
		} finally {
			Files.deleteIfExists(objectFile)
		}
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "$name timed out")
			val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
			assertEquals(0, process.exitValue(), "$name: $output")
			assertEquals("", output)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	private class Harness(
		val context: LLVMContextRef,
		val module: LLVMModuleRef,
		val builder: LLVMBuilderRef,
		val functions: EmitFunctions,
		val semantics: FunctionSemanticModel,
		function: LLVMValueRef,
		returnType: ValueType = CInt(),
		callerArena: LLVMValueRef? = null,
	) {

		private val abi = NativeCABI.native()
		private val entry = LLVMAppendBasicBlockInContext(context, function, "entry")
		val frame: EmitFunctionFrame
		val bindings: EmitBindings
		val strings: EmitString
		val expressions: EmitExpression
		var success: LLVMValueRef = LLVMConstInt(CBool.llvmValue(context), 1, 0)

		init {
			LLVMPositionBuilderAtEnd(builder, entry)
			frame = EmitFunctionFrame(context, builder, entry, returnType, returnType.llvmValue(context), EmitStringArena(context, module, abi), callerArena)
			bindings = EmitBindings(context, builder, entry)
			strings = EmitString(context, builder, abi)
			val casts = EmitCast(context, builder, semantics.types, abi)
			expressions = EmitExpression(context, builder, bindings, semantics.types, casts, strings, EmitPower(context, builder, abi), abi, functions, semantics, returnType, frame)
		}

		fun receiver(name: String = "sample"): Expression = when (val body = semantics.declarations.functions.single { it.name.lexeme == name }.declaration.body) {
			is ExpressionBody -> body.expression
			is BlockBody      -> {
				expressions.emitStatements(body.statements.dropLast(1))
				checkNotNull((body.statements.last() as ReturnStatement).value)
			}
		}

		fun int(value: Int): LLVMValueRef = LLVMConstInt(CInt().llvmValue(context), value.toLong(), 1)
		fun check(condition: LLVMValueRef) {
			success = LLVMBuildAnd(builder, success, condition, "checks_pass")
		}
	}
}
