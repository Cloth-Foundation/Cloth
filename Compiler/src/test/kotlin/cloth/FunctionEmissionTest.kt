package cloth

import cloth.diagnostics.*
import cloth.emitter.*
import cloth.emitter.types.EmitCast
import cloth.emitter.types.EmitString
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.ValueType
import cloth.settings.LinkOptions
import cloth.settings.RuntimeLinkage
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FunctionEmissionTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("functions.co", source.trimIndent())).scanTokens()).parseDeclarations()
	private fun ir(source: String) = LLVMEmitter().emit(parse(source))

	@Test
	fun `declaration modules contain mangled functions and no synthesized entry point`() {
		val module = ir("fn<int> add(int a, int b) = a + b\nfn main() {}")
		assertContains(module, "define internal i32 @cloth.fn.0.add(i32")
		assertContains(module, "define internal void @cloth.fn.1.main()")
		assertFalse(module.contains("@main("))
		assertFalse(module.contains("@printf"))
		ir("")
	}

	@Test
	fun `forward direct and mutually recursive functions have verified call targets`() {
		val module = ir("fn<int> first(int n) = second(n)\nfn<int> second(int n) = first(n)\nfn<int> recurse(int n) = recurse(n)")
		assertContains(module, "call i32 @cloth.fn.1.second")
		assertContains(module, "call i32 @cloth.fn.0.first")
		assertContains(module, "call i32 @cloth.fn.2.recurse")
	}

	@Test
	fun `overloaded calls target distinct resolved symbols`(@TempDir directory: Path) {
		native(directory, "overloads", """
			fn<int> choose(int value) = 1
			fn<int> choose(int<64> value) = 2
			fn<int> choose(float value) = 3
			fn<int> choose(float<64> value) = 4
			fn<int> test() = choose(10) * 1000 + choose(10 -> int<64>) * 100 + choose(1.5) * 10 + choose(1.5 -> float<64>)
		""", "1234")
	}

	@Test
	fun `ordinary forward calls and explicit and expression returns execute`(@TempDir directory: Path) {
		native(directory, "forward", "fn<int> test() = add(2, 3) ** 2\nfn<int> add(int a, int b) { return a + b }", "25")
	}

	@Test
	fun `recursive calls preserve each invocation's parameters and locals`(@TempDir directory: Path) {
		native(directory, "factorial", """
			fn<int<64>> factorial(int n) {
			    def snapshot = n
			    if (n <= 1) { return 1 }
			    def result = factorial(n - 1)
			    return (snapshot -> int<64>) * result
			}
			fn<int<64>> test() = factorial(12)
		""", "479001600")
		native(directory, "fibonacci", "fn<int> fib(int n) {\n if (n < 2) { return n }\n return fib(n - 1) + fib(n - 2)\n}\nfn<int> test() = fib(12)", "144")
	}

	@Test
	fun `mutual recursion runs through later declarations`(@TempDir directory: Path) {
		native(directory, "mutual", """
			fn<bool> even(int n) {
			    if (n == 0) { return true }
			    return odd(n - 1)
			}
			fn<bool> odd(int n) {
			    if (n == 0) { return false }
			    return even(n - 1)
			}
			fn<bool> test() = even(24) && odd(25) && !even(25)
		""", "true")
	}

	@Test
	fun `parameter reassignment mutates only callee storage and final parameters remain readable`(@TempDir directory: Path) {
		native(directory, "parameters", "fn<int> change(int value) {\n value += 5\n return value\n}\nfn<int> read(final int value) = value\nfn<bool> test() {\n def x = 10\n def result = change(x)\n return x == 10 && result == 15 && read(x) == 10\n}", "true")
	}

	@Test
	fun `arguments evaluate once left to right and snapshot values before later arguments`(@TempDir directory: Path) {
		native(directory, "ordered", """
			fn<int> combine(int a, int b, int c) = a * 100 + b * 10 + c
			fn<bool> test() {
			    def x = 1
			    def result = combine(if (true) {
			        x += 1
			        x
			    } else { 0 }, if (true) {
			        x += 1
			        x
			    } else { 0 }, if (true) {
			        x += 1
			        x
			    } else { 0 })
			    return result == 234 && x == 4
			}
		""", "true")
	}

	@Test
	fun `every integer width and signedness survives argument storage and return`(@TempDir directory: Path) {
		for (bits in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
			val type = ValueType.Integer(bits, unsigned)
			for ((index, value) in listOf(type.minimum, type.maximum).withIndex()) {
				native(directory, "integer_${bits}_${unsigned}_$index", "fn<${type.spelling}> identity(${type.spelling} value) = value\nfn<${type.spelling}> test() = identity($value)", value.toString())
			}
		}
	}

	@Test
	fun `boolean and unicode character calls retain independent representations`(@TempDir directory: Path) {
		native(directory, "bool", "fn<bool> toggle(bool value) {\n value = !value\n return value\n}\nfn<bool> test() = toggle(false) && !toggle(true)", "true")
		native(directory, "char", "fn<char> identity(char value) = value\nfn<char> test() = identity('😀')", "😀")
	}

	@Test
	fun `floating calls retain contextual precision and half calls use raw bits internally`(@TempDir directory: Path) {
		for (bits in ValueType.FLOAT_WIDTHS) native(directory, "float_$bits", "fn<float<$bits>> add(float<$bits> a, float<$bits> b) = a + b\nfn<float<$bits>> test() = add(1.5, 2.25)", "3.75")
		native(directory, "double_precision", "fn<float<64>> identity(float<64> value) = value\nfn<float<64>> test() = identity(1.0000000000000002)", "1.0000000000000002")
		native(directory, "half_rounding", "fn<float<16>> identity(float<16> value) = value\nfn<float<16>> test() = identity(1.00048828125)", "1")
		assertContains(ir("fn<float<16>> identity(float<16> value) = value"), "define internal i16 @cloth.fn.0.identity(i16")
	}

	@Test
	fun `parameter shadowing is symbol based and branch locals allocate in entry`() {
		val module = ir("fn<int> choose(bool flag, int value) {\n if (flag) {\n def value = value + 1\n return value\n }\n return value\n}")
		var block = ""
		var allocations = 0
		for (line in module.lines()) {
			Regex("^([A-Za-z_][A-Za-z_0-9.]*):").find(line)?.let { block = it.groupValues[1] }
			if ("alloca " in line) { allocations++; assertEquals("entry", block, line) }
		}
		assertEquals(3, allocations)
	}

	@Test
	fun `nested branch shadowing restores enclosing values at runtime`(@TempDir directory: Path) {
		native(directory, "shadow", "fn<int> choose(bool flag, int value) {\n if (flag) {\n def value = value + 1\n return value\n }\n return value\n}\nfn<bool> test() = choose(true, 10) == 11 && choose(false, 10) == 10", "true")
	}

	@Test
	fun `returning conditional branches contribute no phi input or extra branch after return`(@TempDir directory: Path) {
		native(directory, "mixed_return", "fn<int> choose(bool flag) {\n def value = if (flag) { return 10 } else { 20 }\n return value\n}\nfn<bool> test() = choose(true) == 10 && choose(false) == 20", "true")
		native(directory, "all_return", "fn<int> choose(bool flag) { def value = if (flag) { return 10 } else { return 20 } }\nfn<bool> test() = choose(true) == 10 && choose(false) == 20", "true")
	}

	@Test
	fun `nested conditionals and early returns compose with implicit expression returns`(@TempDir directory: Path) {
		native(directory, "nested", "fn<int> choose(bool a, bool b) = if (a) { if (b) { return 1 } else { 2 } } else { 3 }\nfn<int> test() = choose(true, true) * 100 + choose(true, false) * 10 + choose(false, false)", "123")
	}

	@Test
	fun `return in a call argument prevents later arguments and the callee from running`(@TempDir directory: Path) {
		native(directory, "interrupted_call", """
			fn<int> trap() {
			    def value = 1.0 / 0.0
			    return value -> int
			}
			fn<int> combine(int a, int b) = a + b
			fn<int> choose(bool flag) {
			    combine(if (flag) { return 7 } else { return 8 }, trap())
			}
			fn<bool> test() = choose(true) == 7 && choose(false) == 8
		""", "true")
		native(directory, "partly_interrupted", "fn<int> combine(int a, int b) = a + b\nfn<int> choose(bool flag) { return combine(if (flag) { return 7 } else { 3 }, 5) }\nfn<bool> test() = choose(true) == 7 && choose(false) == 8", "true")
	}

	@Test
	fun `noncontinuing eager operands do not emit remaining arithmetic or guards`(@TempDir directory: Path) {
		native(directory, "eager_return", "fn<int> trap() {\n def value = 1.0 / 0.0\n return value -> int\n}\nfn<int> choose(bool flag) { (if (flag) { return 7 } else { return 8 }) + trap() }\nfn<bool> test() = choose(true) == 7 && choose(false) == 8", "true")
	}

	@Test
	fun `unknown short circuits keep only continuing phi predecessors`(@TempDir directory: Path) {
		for ((index, pair) in listOf("&&" to "13", "||" to "32").withIndex()) {
			val (operator, expected) = pair
			native(directory, "logical_$index", "fn<int> choose(bool flag) {\n flag $operator (if (flag) { return 1 } else { return 2 })\n return 3\n}\nfn<int> test() = choose(true) * 10 + choose(false)", expected)
		}
	}

	@Test
	fun `known boolean flow is honored without skipping condition evaluation`(@TempDir directory: Path) {
		native(directory, "literal_flow", "fn<int> test() { if (true) { return 42 } }", "42")
		native(directory, "logical_flow", "fn<bool> ready() = false\nfn<int> test() { if (ready() || true) { return 42 } }", "42")
		native(directory, "returning_condition", "fn<int> choose(bool flag) { if (if (flag) { return 1 } else { return 2 }) {} }\nfn<int> test() = choose(true) * 10 + choose(false)", "12")
	}

	@Test
	fun `void returns discard results without fake values or automatic output`(@TempDir directory: Path) {
		native(directory, "void", "fn<int> calculate() = 3\nfn work() = calculate() -> void\nfn cleanup() { return work() }\nfn test() {\n calculate()\n cleanup()\n return calculate() -> void\n}", null)
		native(directory, "void_conditional", "fn cleanup() {}\nfn test() = if (true) { cleanup() } else { cleanup() }", null)
	}

	@Test
	fun `unreachable statements are checked earlier but are not emitted after returns`(@TempDir directory: Path) {
		native(directory, "unreachable", "fn trap() {\n def value = 1.0 / 0.0\n value -> int\n}\nfn test() {\n return\n trap()\n}", null)
	}

	@Test
	fun `runtime cast guards use the actual nested call and conditional predecessors`(@TempDir directory: Path) {
		native(directory, "guards", "fn<int> convert(float value) = value -> int\nfn<int> choose(bool flag, float value) = if (flag) { return convert(value) } else { convert(value + 1.0) }\nfn<int> test() = choose(true, 3.75) * 10 + choose(false, 3.75)", "34")
	}

	@Test
	fun `local string helpers remain safe within recursive frames`(@TempDir directory: Path) {
		native(directory, "local_strings", """
			fn<bool> check(int n) {
			    def saved = #"Value: {n}"
			    if (n > 0) {
			        if (!check(n - 1)) { return false }
			    }
			    return saved == #"Value: {n}"
			}
			fn<bool> test() = check(6)
		""", "true")
	}

	@Test
	fun `cloth function names cannot collide with imported native runtime names`(@TempDir directory: Path) {
		native(directory, "names", "fn<int> printf(int n) = n + 1\nfn<int> pow(int n) = n + 2\nfn<int> test() = printf(1) + pow(1)", "5")
	}

	@Test
	fun `object emission works without main with array boundaries and validates return types`(@TempDir directory: Path) {
		val output = LLVMEmitter().compileObject(parse("fn<int> identity(int value) = value"), directory.resolve("functions.obj"))
		assertTrue(Files.size(output) > 0)
		assertTrue(Files.size(LLVMEmitter().compileObject(parse("fn<string> value() = \"text\"\nfn take(string value) {}"), directory.resolve("strings.obj"))) > 0)
		for ((index, source) in listOf("fn take(string[] args) {}", "fn<int[]> value(int[] values) = values").withIndex()) {
			val target = directory.resolve("arrays_$index/out.obj")
			assertTrue(Files.size(LLVMEmitter().compileObject(parse(source), target)) > 0)
		}
		val semantic = assertFailsWith<ClothDiagnostic> { LLVMEmitter().emit(parse("fn<int> wrong() = true")) }
		assertEquals(DiagnosticCode.RETURN_TYPE, semantic.diagnosticCode)
	}

	@Test
	fun `function calls link against the explicit dynamic runtime`(@TempDir directory: Path) {
		native(directory, "dynamic", "fn<float> power(float value) = value ** 2.0\nfn<float> test() = power(1.5)", "2.25", LinkOptions(runtime = RuntimeLinkage.DYNAMIC))
	}

	/** Test instrumentation, not Cloth startup or implicit output semantics. */
	private fun native(directory: Path, name: String, source: String, expected: String?, options: LinkOptions = LinkOptions()) {
		val emitter = LLVMEmitter(options)
		val abi = NativeCABI.native()
		val configuration = ToolchainResolver.native(abi, options)
		val executable = abi.executableFormat.executablePath(directory.resolve(name))
		val objectFile = directory.resolve("$name.obj")
		emitter.withFunctions(parse(source)) { context, module, functions, semantics ->
			val builder = LLVMCreateBuilderInContext(context)
			try {
				val types = LLVMTypes(context)
				val mainType = LLVMFunctionType(types.value(abi.intType), null as PointerPointer<*>?, 0, 0)
				val main = LLVMAddFunction(module, "main", mainType)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, main, "entry"))
				val entry = semantics.declarations.functions.single { it.name.lexeme == "test" && it.parameters.isEmpty() }
				val type = semantics.typeOf(entry).returns
				val call = LLVMBuildCall2(builder, functions.signature(entry), functions.reference(entry), null as PointerPointer<LLVMValueRef>?, 0, if (type == ValueType.VOID) "" else "test_result")
				if (type != ValueType.VOID) emitter.emitPrint(context, module, builder, functions.decode(builder, call, type), type, EmitCast(context, builder, semantics.types, abi), EmitString(context, builder, abi))
				LLVMBuildRet(builder, LLVMConstInt(types.value(abi.intType), 0, 0))
				emitter.verify(module)
				emitter.emitObject(module, objectFile)
			} finally { LLVMDisposeBuilder(builder) }
		}
		try { NativeLinker.link(objectFile, executable, configuration) } finally { Files.deleteIfExists(objectFile) }
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "$name timed out")
			val bytes = process.inputStream.readBytes()
			assertEquals(0, process.exitValue(), bytes.toString(Charsets.UTF_8))
			assertContentEquals((expected?.let { it + System.lineSeparator() } ?: "").toByteArray(Charsets.UTF_8), bytes, name)
		} finally { if (process.isAlive) process.destroyForcibly() }
	}
}
