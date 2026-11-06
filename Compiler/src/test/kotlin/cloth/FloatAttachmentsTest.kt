package cloth

import cloth.backend.IntegerKernelProgram
import cloth.builtin.BuiltinParameter
import cloth.builtin.BuiltinStorageContract
import cloth.builtin.attachments.FloatMethod
import cloth.builtin.attachments.PrimitiveAttachments
import cloth.data.CFloat
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.types.primitives.EmitFloatMember
import cloth.emitter.types.primitives.EmitHalf
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.typing.FunctionAnalyzer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class FloatAttachmentsTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("float-members.co", source.trimIndent())).scanTokens()).parse()
	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(unit(source)) }
		assertEquals(code, error.diagnosticCode, source)
		assertLocated(error)
	}

	@Test
	fun `float registrations retain exact widths and remain separate from integer providers`() {
		for (bits in ValueType.FLOAT_WIDTHS) {
			val type = CFloat(bits)
			val floats = PrimitiveAttachments.floating(type)
			assertEquals(listOf(floats.ABS, floats.CLAMP, floats.SIGNUM), floats.all.toList())
			assertSame(floats.ABS, PrimitiveAttachments[type, "abs", nativeTestLayout])
			assertEquals(type, floats.ABS.overload.returns)
			assertEquals(type, floats.CLAMP.overload.returns)
			assertEquals(List(2) { BuiltinParameter.Exact(type) }, floats.CLAMP.parameters)
			assertEquals(ValueType.INT, floats.SIGNUM.overload.returns)
			assertTrue(floats.all.all { it.overload.storage == BuiltinStorageContract.READ_ONLY_INDEPENDENT_RESULT })
			assertNull(PrimitiveAttachments[type, "leadingZeros", nativeTestLayout])
			assertNull(PrimitiveAttachments[type, "toStringRadix", nativeTestLayout])
			assertNull(PrimitiveAttachments[type, "Abs", nativeTestLayout])
		}
		assertNotSame(PrimitiveAttachments[ValueType.INT, "abs", nativeTestLayout], PrimitiveAttachments[ValueType.FLOAT, "abs", nativeTestLayout])
		assertFailsWith<UnsupportedOperationException> { (PrimitiveAttachments.floating(ValueType.FLOAT).all as MutableCollection).clear() }
	}

	private fun rounded(value: Double, bits: Int): Double = when (bits) {
		16   -> java.lang.Float.float16ToFloat(java.lang.Float.floatToFloat16(value.toFloat())).toDouble()
		32   -> value.toFloat().toDouble()
		else -> value
	}

	private fun raw(value: Double, bits: Int): Long = when (bits) {
		16   -> java.lang.Float.floatToFloat16(value.toFloat()).toLong() and 0xffffL
		32   -> value.toFloat().toRawBits().toLong() and 0xffffffffL
		else -> value.toRawBits()
	}

	private fun decoded(raw: Long, bits: Int): Double = when (bits) {
		16   -> java.lang.Float.float16ToFloat(raw.toShort()).toDouble()
		32   -> Float.fromBits(raw.toInt()).toDouble()
		else -> Double.fromBits(raw)
	}

	@Test
	fun `runtime methods agree with independent IEEE oracles including signed zeros infinities subnormals and NaNs`() = IntegerKernelProgram.scratch { directory ->
		for (abi in IntegerKernelProgram.targets()) IntegerKernelProgram.run(abi, directory, "float-oracle-${abi.architecture}") {
			for (bits in ValueType.FLOAT_WIDTHS) case("float$bits") {
				val type = CFloat(bits)
				val llvmType = type.llvmValue(context, abi.layout)
				val integer = LLVMIntTypeInContext(context, bits)
				val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
				// Compile each implementation once with real parameters. Host oracle
				// inputs pass through calls rather than folding all operations in the test.
				val functions = FloatMethod.entries.associateWith { operation ->
					val count = if (operation == FloatMethod.CLAMP) 3 else 1
					val result = if (operation == FloatMethod.SIGNUM) LLVMInt32TypeInContext(context) else llvmType
					val signature = PointerPointer<LLVMTypeRef>(count.toLong()).use { parameters ->
						repeat(count) { parameters.put(it.toLong(), llvmType) }; LLVMFunctionType(result, parameters, count, 0)
					}
					val function = LLVMAddFunction(module, "test.float.$bits.${operation.name}", signature)
					LLVMSetLinkage(function, LLVMInternalLinkage)
					val body = LLVMCreateBuilderInContext(context)
					try {
						LLVMPositionBuilderAtEnd(body, LLVMAppendBasicBlockInContext(context, function, "entry"))
						LLVMBuildRet(body, EmitFloatMember(context, body, abi, "float-oracle").emit(operation, type, List(count) { LLVMGetParam(function, it) }))
					} finally {
						LLVMDisposeBuilder(body)
					}
					function
				}

				fun value(pattern: Long) = LLVMConstBitCast(LLVMConstInt(integer, pattern, 0), llvmType)
				fun invoke(operation: FloatMethod, vararg patterns: Long): LLVMValueRef {
					val function = functions.getValue(operation)
					return PointerPointer<LLVMValueRef>(patterns.size.toLong()).use { arguments ->
						patterns.forEachIndexed { index, pattern -> arguments.put(index.toLong(), value(pattern)) }
						LLVMBuildCall2(builder, LLVMGlobalGetValueType(function), function, arguments, patterns.size, "member_result")
					}
				}

				fun matches(actual: LLVMValueRef, expected: Double, label: String) {
					if (expected.isNaN()) {
						val comparable = if (bits == 16) EmitHalf(context, builder, abi.layout).toFloat(actual) else actual
						check(LLVMBuildFCmp(builder, LLVMRealUNO, comparable, comparable, "matches_nan"), label)
					} else check(LLVMBuildICmp(builder, LLVMIntEQ, LLVMBuildBitCast(builder, actual, integer, "result_bits"), LLVMConstInt(integer, raw(expected, bits), 0), "matches_bits"), label)
				}

				val random = Random(731 + bits)
				val finite = listOf(0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 2.0, -2.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Math.scalb(1.0, type.minimumSubnormalExponent), -Math.scalb(1.0, type.minimumSubnormalExponent), Math.scalb(1.0, type.minimumNormalExponent), type.maximum.toDouble(), type.minimum.toDouble())
				val exponent = ((1L shl type.exponentBits) - 1) shl type.fractionBits
				val patterns = (finite.map { raw(rounded(it, bits), bits) } + listOf(exponent or 1, exponent or (1L shl (type.fractionBits - 1)), exponent or (1L shl (bits - 1)) or 3) + List(96) {
					when (bits) {
						16 -> random.nextInt(65536).toLong(); 32 -> random.nextInt().toLong() and 0xffffffffL; else -> random.nextLong()
					}
				}).distinct()
				val bounds = listOf(-1.0 to 1.0, Double.NEGATIVE_INFINITY to Double.POSITIVE_INFINITY, -0.0 to 0.0, 0.0 to -0.0, 0.0 to 0.0, -0.0 to -0.0, 2.0 to 2.0, Double.NEGATIVE_INFINITY to Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY to Double.POSITIVE_INFINITY)
				for (pattern in patterns) {
					val number = decoded(pattern, bits)
					matches(invoke(FloatMethod.ABS, pattern), abs(number), "abs $bits $pattern")
					if (!number.isNaN()) status(invoke(FloatMethod.SIGNUM, pattern), if (number == 0.0) 0 else if (number < 0.0) -1 else 1, "signum $bits $pattern")
					for ((low, high) in bounds) {
						val expected = when {
							number < low -> low; number > high -> high; else -> number
						}
						matches(invoke(FloatMethod.CLAMP, pattern, raw(low, bits), raw(high, bits)), expected, "clamp $bits $pattern $low $high")
					}
				}
			}
		}
	}

	@Test
	fun `closed invalid calls retain first intrinsic diagnostics in skipped code and overload probes`() {
		failure("fn main() { (0.0 / 0.0).signum() }", DiagnosticCode.FLOAT_SIGNUM_NAN)
		failure("fn main() { def<float<16>> value = 0.0; value.clamp(1.0, 0.0) }", DiagnosticCode.INVALID_FLOAT_CLAMP_BOUNDS)
		failure("fn main() { 1.0.clamp(0.0 / 0.0, 2.0) }", DiagnosticCode.INVALID_FLOAT_CLAMP_BOUNDS)
		failure("fn main() { 1.0.clamp(0.0, 0.0 / 0.0) }", DiagnosticCode.INVALID_FLOAT_CLAMP_BOUNDS)
		failure("fn main() { false && (0.0 / 0.0).signum() == 0 }", DiagnosticCode.FLOAT_SIGNUM_NAN)
		failure("fn f(int n) {}\nfn f(float n) {}\nfn main() { f((0.0 / 0.0).signum()) }", DiagnosticCode.FLOAT_SIGNUM_NAN)
		failure("fn main() { (0.0 / 0.0).clamp(2.0, 1.0) }", DiagnosticCode.INVALID_FLOAT_CLAMP_BOUNDS)
	}

	@Test
	fun `exact argument identities and method boundaries remain strict`() {
		failure("fn f(float<64> n, float low) { n.clamp(low, 2.0) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn main() { 1.0.clamp(0, 2.0) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn main() { 1.0.abs(0) }", DiagnosticCode.CALL_ARITY)
		failure("fn main() { 1.0.clamp(low = 0.0, high = 2.0) }", DiagnosticCode.INVALID_ARGUMENT_BINDING)
		failure("fn f(float? n) { n.abs() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn f(float<64>? n, float fallback) { (n ?: fallback).abs() }", DiagnosticCode.INVALID_ELVIS)
		failure("fn main() { 1.0.leadingZeros() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn main() { float.abs() }", DiagnosticCode.TYPE_RECEIVER_VALUE)
	}

	@Test
	fun `source calls snapshot operands preserve precision nullable proofs ordered results and signed zeros`(@TempDir directory: Path) {
		val source = """
			fn<float<64>> receiver() { print("R"); return -8.5 }
			fn<float<64>> bound(float<64> n) { print("B"); return n }
			fn<float<64>, int> pair(float<64> n) = n.abs(), n.signum()
			fn<float<16>> half(float<16> n) = n.clamp(-1.0, 1.0)
			fn<int> main() {
			    println(receiver().clamp(bound(-5.0), bound(5.0)))
			    def<float<64>> n = -8.5
			    def<float<64>> change = { n = 100.0; return -5.0 }
			    println(n.clamp(change, 5.0)); println(n)
			    def<float<64>?> maybe = -3.5
			    if (maybe != null) println(maybe.abs())
			    println((maybe ?: 0.0).signum())
			    def magnitude, sign = pair(-2.5)
			    println(magnitude); println(sign)
			    println(half(2.0)); println(half(-0.5))
			    def<float<64>> wide = 16777217.0
			    if (wide.clamp(16777216.5, 16777217.5) != wide) return 1
			    def<float<64>?> absent = null
			    if ((absent ?: 16777217.0).abs() != wide) return 3
			    def<float<16>?> absentHalf = null
			    if ((absentHalf ?: 0.5).abs()::type != float<16>::type) return 4
			    def<float<16>> zero = -0.0
			    if (1.0 / zero.clamp(0.0, 0.0) >= 0.0 || 1.0 / zero.abs() <= 0.0) return 2
			    println(n.abs()::type); println(n.clamp(0.0, 200.0)::type); println(n.signum()::type)
			    def<float<16>[]> values = [-1.0, 2.0]
			    println(values[0].abs()); println(values[1].clamp(0.0, 1.0))
			    return 0
			}
		"""
		val expected = "RBB-5\n-5\n100\n3.5\n-1\n2.5\n-1\n1\n-0.5\nfloat<64>\nfloat<64>\nint<32>\n1\n1\n"
		for (abi in IntegerKernelProgram.targets()) assertEquals(expected + completion(), execute(directory, "source_${abi.architecture}", source, abi))
	}

	@Test
	fun `dynamic NaN and reversed bounds fail after ordered operands with original locations`(@TempDir directory: Path) {
		val programs = listOf("fn main() { def<float<16>> n = 0.0 / 0.0; print(\"before:\"); n.signum(); print(\"BAD\") }" to "signum", "fn main() { def<float> n = 0.0 / 0.0; n.signum(); print(\"BAD\") }" to "signum", "fn main() { def<float<64>> n = 0.0 / 0.0; n.signum(); print(\"BAD\") }" to "signum", "fn<float> bound(float n) { print(\"B\"); return n }\nfn main() { 0.0.clamp(bound(2.0), bound(1.0)); print(\"BAD\") }" to "BBFloat clamp", "fn<float<16>> nan() = 0.0 / 0.0\nfn main() { 0.0.clamp(nan() -> float, 1.0); print(\"BAD\") }" to "Float clamp", "fn<float<64>> nan() = 0.0 / 0.0\nfn main() { nan().clamp(0.0, nan()); print(\"BAD\") }" to "Float clamp")
		for (abi in IntegerKernelProgram.targets()) for ((index, fixture) in programs.withIndex()) {
			val output = execute(directory, "invalid_${abi.architecture}_$index", fixture.first, abi, 1)
			assertContains(output, fixture.second)
			assertContains(output, "float-members.co:")
			assertFalse(output.contains("BAD")); assertFalse(output.contains("PEC:"))
		}
	}

	@Test
	fun `shared names preserve nonreturning receivers argument checks and skipped execution`(@TempDir directory: Path) {
		val source = """
			fn<int> beforeAbs() { (if (true) { return 1 } else { 0.0 }).abs() }
			fn<int> beforeSign() { (if (true) { return 2 } else { 0.0 }).signum() }
			fn<int> beforeClamp() { (if (true) { return 3 } else { 0.0 }).clamp(0.0, 1.0) }
			fn<int> typedBounds(float<64> upper) { (if (true) { return 4 } else { 0.0 }).clamp(0.0, upper) }
			fn<int> wideBounds(int<129> upper) { (if (true) { return 5 } else { 0 }).clamp(0, upper) }
			fn<int> first() { 0.0.clamp(if (true) { return 6 } else { 0.0 }, if (true) { print("BAD"); 1.0 } else { 1.0 }) }
			fn<int> last() { 0.0.clamp(0.0, if (true) { return 7 } else { 1.0 }) }
			fn<int> main() {
			    def nan = 0.0 / 0.0
			    def lower = 2.0
			    false && nan.signum() == 0
			    true || nan.clamp(lower, 1.0) == 0.0
			    println(beforeAbs()); println(beforeSign()); println(beforeClamp()); println(typedBounds(1.0)); println(wideBounds(1)); println(first()); println(last())
			    return 0
			}
		"""
		assertEquals("1\n2\n3\n4\n5\n6\n7\n" + completion(), execute(directory, "early", source, NativeCABI.native()))
		failure("fn<int> f() { (if (true) { return 1 } else { 0.0 }).clamp(true, false) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("fn<int> f() { (if (true) { return 1 } else { 0.0 }).abs(true) }", DiagnosticCode.CALL_ARITY)
		failure("fn<int> f() { (if (true) { return 1 } else { 0.0 }).abs(missing) }", DiagnosticCode.UNKNOWN_NAME)
	}

	@Test
	fun `native examples and primitive loop consumers execute on both host targets`(@TempDir directory: Path) {
		val source = Files.readString(Path.of("examples", "FloatAttachments.co"))
		for (abi in IntegerKernelProgram.targets()) assertEquals("3.5\n-1\n-1\nfloat<16>\nfloat<32>\nfloat<64>\nint<32>\n" + completion(), execute(directory, "example_${abi.architecture}", source, abi))
	}

	@Test
	fun `parameterized half single double calls and maintained example verify on all targets`(@TempDir directory: Path) {
		val ast = unit("""
			fn<float<16>, int> half(float<16> n, float<16> low, float<16> high) = n.abs().clamp(low, high), n.signum()
			fn<float<32>, int> single(float n, float low, float high) = n.abs().clamp(low, high), n.signum()
			fn<float<64>, int> double(float<64> n, float<64> low, float<64> high) = n.abs().clamp(low, high), n.signum()
		""")
		val example = unit(Files.readString(Path.of("examples", "FloatAttachments.co")))
		for ((index, triple) in IntegerKernelProgram.triples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emit(ast)
			assertContains(ir, "float_clamp_bounds_valid")
			assertFalse(ir.contains("fcmp ole half"))
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("float_$index.o"))) > 0)
			assertTrue(Files.size(emitter.compileObject(example, directory.resolve("example_$index.o"))) > 0)
		}
		val program = parseMain("(-3.5).abs()")
		assertEquals(ValueType.FLOAT, FunctionAnalyzer(nativeTestLayout).analyze(program).typeOf(program.mainStatements.single()))
	}

	@Test
	fun `constant diagnostics stop CLI before object and linker output`(@TempDir directory: Path) {
		val source = directory.resolve("invalid.co")
		val objectFile = directory.resolve("invalid.o")
		Files.writeString(source, "fn main() { (0.0 / 0.0).signum(); 0.0.clamp(2.0, 1.0) }")
		val diagnostic = StringBuilder()
		assertEquals(1, runCompiler(arrayOf("--emit", "object", source.toString(), objectFile.toString(), "--linker", "missing-linker"), DiagnosticReporter(diagnostic)))
		assertContains(diagnostic.toString(), "C3075")
		assertFalse(diagnostic.toString().contains("C3076")); assertFalse(Files.exists(objectFile))
	}

	private fun completion() = processCompletion().replace("\r\n", "\n")
	private fun execute(directory: Path, name: String, source: String, abi: NativeCABI, expectedStatus: Int = 0): String {
		val executable = LLVMEmitter(abi).compile(unit(source), directory.resolve(name))
		val log = directory.resolve("$name.log")
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
		try {
			assertTrue(process.waitFor(60, TimeUnit.SECONDS), name)
			val output = Files.readString(log).replace("\r\n", "\n")
			assertEquals(expectedStatus, process.exitValue(), output.take(2000))
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
