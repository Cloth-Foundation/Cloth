package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.*
import cloth.emitter.types.EmitRuntimeError
import cloth.lexer.Lexer
import cloth.parser.Parser
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

class StringFunctionTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("strings.co", source.trimIndent())).scanTokens()).parse()

	@Test
	fun `string boundaries use descriptors and a private caller storage argument`() {
		val ir = LLVMEmitter().emit(parse("fn<string> identity(string value) = value\nfn<bool> test() = identity(\"a\") == \"a\""))
		assertContains(ir, "define internal { ptr, i64 } @cloth.fn.0.identity({ ptr, i64 }")
		assertContains(ir, "@cloth.rt.string.copy(ptr")
		assertContains(ir, "@cloth.rt.string.release(ptr")
		assertContains(ir, "declare ptr @malloc(i64)")
		assertContains(ir, "declare void @free(ptr)")
		assertContains(ir, "Unable to allocate returned string storage")
		// Scalar-only programs require no string allocation runtime or frame slot.
		val scalar = LLVMEmitter().emitExecutable(parse("fn<int> main() = 0"))
		assertFalse(scalar.contains("@malloc"))
		assertFalse(scalar.contains("string_returns"))
	}

	@Test
	fun `empty literals Unicode NULs and raw strings survive calls`(@TempDir directory: Path) {
		native(directory, "literal", """
			fn<string> identity(string value) = value
			fn<string> literal() = "é中😀\0%"
			fn<bool> test() = identity("") == "" && identity(literal()) == "é中😀\0%" && identity("é") != "é"
		""")
		val raw = "\"\"\"line 1\nraw \\n 😀\"\"\""
		native(directory, "raw", "fn<string> raw() = $raw\nfn<bool> test() = raw() == $raw")
	}

	@Test
	fun `returned local concatenations survive later calls and copies survive rebinding`(@TempDir directory: Path) {
		native(directory, "locals", """
			fn<string> build(string value) {
			    def local = "prefix:" + value
			    def saved := local
			    local += "changed"
			    return saved + ":suffix"
			}
			fn<bool> test() {
			    def first = build("first")
			    def saved := first
			    def second = build("second")
			    first = build("third")
			    return saved == "prefix:first:suffix" && second == "prefix:second:suffix" && first == "prefix:third:suffix"
			}
		""")
	}

	@Test
	fun `single interpolation holes copy formatted stack buffers before return`(@TempDir directory: Path) {
		native(directory, "holes", """
			fn<string> integer(int value) = #"{value}"
			fn<string> decimal(float value) { return #"{value}" }
			fn<string> character(char value) = #"{value}"
			fn<string> boolean(bool value) = #"{value}"
			fn<string> all(string value) = #"[{value} {integer(12)} {character('😀')} {boolean(true)}]"
			fn<bool> test() {
			    def number := integer(-123456)
			    def text := character('😀')
			    def zero := character('\0')
			    def floating := decimal(1.25)
			    return number == "-123456" && text == "😀" && zero == "\0" && floating == "1.25" && all("x") == "[x 12 😀 true]"
			}
		""")
	}

	@Test
	fun `string parameters borrow stable values but reassignment stays local`(@TempDir directory: Path) {
		native(directory, "parameters", """
			fn<string> change(string value) {
			    def original := value
			    value += " changed"
			    return original + "/" + value
			}
			fn<string> read(final string value) {
			    if (true) { def value := value + " shadow"
			        return value }
			}
			fn<bool> test() {
			    def original = "caller" + " text"
			    def result = change(original)
			    return original == "caller text" && result == "caller text/caller text changed" && read(original) == "caller text shadow"
			}
		""")
	}

	@Test
	fun `string arguments snapshot once in order before later arguments rebind their source`(@TempDir directory: Path) {
		native(directory, "arguments", """
			fn<string> combine(string a, string b, string c) = a + b + c
			fn<string> echo(string value) = value
			fn<bool> test() {
			    def source = "first"
			    def count = 0
			    def result = combine(echo(if (true) { count += 1
			        source } else { "" }), echo(if (true) { count += 1
			        source = "second"
			        source } else { "" }), echo(if (true) { count += 1
			        source = "third"
			        source } else { "" }))
			    return count == 3 && source == "third" && result == "firstsecondthird"
			}
		""")
	}

	@Test
	fun `direct and mutual recursion preserve strings across frame exits`(@TempDir directory: Path) {
		native(directory, "recursion", """
			fn<string> chain(int n) {
			    def saved = #"{n}:"
			    if (n == 0) { return "end" }
			    def tail := chain(n - 1)
			    return saved + tail
			}
			fn<string> even(int n, string prefix) {
			    if (n == 0) { return prefix }
			    return odd(n - 1, prefix + "e")
			}
			fn<string> odd(int n, string prefix) = even(n - 1, prefix + "o")
			fn<bool> test() = chain(5) == "5:4:3:2:1:end" && even(6, "") == "eoeoeo"
		""")
	}

	@Test
	fun `conditional string results and nested early returns preserve control flow`(@TempDir directory: Path) {
		native(directory, "branches", """
			fn<string> echo(string value) = value
			fn<string> choose(bool a, bool b) {
			    def seed = echo("seed")
			    def result = if (a) {
			        if (b) { return seed + " early" } else { echo("branch") }
			    } else { echo("other") }
			    return result + " end"
			}
			fn<string> expression(bool a) = if (a) { return echo("early") } else { echo("later") }
			fn<bool> test() = choose(true, true) == "seed early" && choose(true, false) == "branch end" && choose(false, true) == "other end" && expression(true) == "early" && expression(false) == "later"
		""")
	}

	@Test
	fun `return in a later argument releases earlier results and skips remaining work`(@TempDir directory: Path) {
		native(directory, "interrupted", """
			fn<string> echo(string value) = value
			fn<string> trap() {
			    def invalid = 1.0 / 0.0
			    invalid -> int
			    return "trap"
			}
			fn<string> combine(string a, string b, string c) = a + b + c
			fn<string> choose(bool flag) {
			    return combine(echo("first"), if (flag) { return echo("early") } else { echo("later") }, trap())
			}
			fn<bool> test() = choose(true) == "early"
		""")
	}

	@Test
	fun `string overloads and scalar boundaries keep existing resolution rules`(@TempDir directory: Path) {
		native(directory, "overloads", """
			fn<string> choose(string value) = value + "!"
			fn<string> choose(int value) = #"int:{value}"
			fn<string> choose(float value) = #"float:{value}"
			fn<bool> same(string a, string b) = a == b
			fn<bool> test() = same(choose("x"), "x!") && same(choose(2), "int:2") && same(choose(1.5), "float:1.5")
		""")
	}

	@Test
	fun `dynamic CRT links string allocation and release`(@TempDir directory: Path) {
		native(directory, "dynamic", "fn<string> make(float<16> value) = #\"half:{value}\"\nfn<bool> test() = make(1.25) == \"half:1.25\"", LinkOptions(runtime = RuntimeLinkage.DYNAMIC))
	}

	@Test
	fun `cloth malloc and free names cannot collide with string storage imports`(@TempDir directory: Path) {
		native(directory, "runtime_names", "fn<string> malloc(string value) = value + \" malloc\"\nfn<string> free(string value) = value + \" free\"\nfn<bool> test() = free(malloc(\"text\")) == \"text malloc free\"")
	}

	@Test
	fun `string array boundaries emit and string type errors remain semantic`(@TempDir directory: Path) {
		val output = directory.resolve("absent/object.o")
		assertTrue(Files.size(LLVMEmitter().compileObject(parse("fn take(string[] args) {}"), output)) > 0)
		for ((source, code) in listOf("fn<string> wrong() = 1" to DiagnosticCode.RETURN_TYPE, "fn<string> wrong(final string value) { value = \"x\"\n return value }" to DiagnosticCode.IMMUTABLE_BINDING)) {
			val failure = assertFailsWith<ClothDiagnostic> { LLVMEmitter().emit(parse(source)) }
			assertEquals(code, failure.diagnosticCode)
			assertLocated(failure)
		}
	}

	@Test
	fun `normal exits release all returned buffers including discarded and interrupted calls`(@TempDir directory: Path) {
		val source = """
			fn<string> echo(string value) = value
			fn<string> nested(int n) {
			    def saved = echo("saved")
			    if (n == 0) { return saved }
			    return nested(n - 1) + saved
			}
			fn discard(bool early) {
			    echo("ignored")
			    echo("explicit") -> void
			    if (early) { return echo("return") -> void }
			}
			fn conditional(bool enter) {
			    if (enter) { echo("branch")
			        return }
			}
			fn before(bool early) {
			    if (early) { return }
			    echo("tail")
			}
			fn<string> combine(string a, string b) = a + b
			fn<int> interrupt(bool flag) {
			    combine(echo("first"), if (flag) { return 7 } else { echo("second") })
			    return 8
			}
			fn<bool> test() {
			    discard(true)
			    discard(false)
			    conditional(false)
			    conditional(true)
			    before(true)
			    before(false)
			    def result = nested(12)
			    def copy := result
			    result = echo("replacement")
			    return interrupt(true) == 7 && interrupt(false) == 8 && copy == "savedsavedsavedsavedsavedsavedsavedsavedsavedsavedsavedsavedsaved" && result == "replacement"
			}
		"""
		instrumented(directory, "balanced", source)
	}

	@Test
	fun `skipped branches and arguments do not allocate return buffers`(@TempDir directory: Path) {
		instrumented(directory, "skipped", """
			fn<string> echo(string value) = value
			fn<bool> test() {
			    if (false) { echo("never") }
			    false && (echo("skipped") == "skipped")
			    return true
			}
		""", expectAllocation = false)
	}

	@Test
	fun `allocation failure exits with a controlled runtime diagnostic`(@TempDir directory: Path) {
		instrumented(directory, "allocation_failure", "fn<string> echo(string value) = value\nfn<bool> test() = echo(\"text\") == \"text\"", failAllocation = true)
	}

	@Test
	fun `array returns deep copy nested buffers and release them on normal and early exits`(@TempDir directory: Path) {
		instrumented(directory, "array_cleanup", """
			fn<string[][]> make(int n) {
			    def local = [[#"value {n}", "Cloth" + "!"]]
			    if (n > 0) { return make(n - 1) }
			    return local
			}
			fn<string[][]> forward(string[][] a) = a
			fn discard(bool early) {
			    make(2)
			    if (early) { return }
			    forward(make(1)) -> void
			}
			fn<bool> test() {
			    def saved = forward(make(4))
			    discard(true)
			    discard(false)
			    return saved[0][0] == "value 0" && saved[0][1] == "Cloth!"
			}
		""")
		instrumented(directory, "array_allocation_failure", "fn<int[]> make() = [1, 2]\nfn<bool> test() = make()[0] == 1", failAllocation = true)
		instrumented(directory, "array_overflow", "fn<int[]> make() = [1, 2]\nfn<bool> test() = make()[0] == 1", oversizedResult = true, oversizedArray = true)
	}

	@Test
	fun `return storage length overflow is rejected before calling the allocator`(@TempDir directory: Path) {
		instrumented(directory, "overflow", "fn<string> echo(string value) = value\nfn<bool> test() = echo(\"text\") == \"text\"", oversizedResult = true)
	}

	@Test
	fun `function blocks retain buffered results until the enclosing function releases them`(@TempDir directory: Path) {
		instrumented(directory, "block_cleanup", """
			fn<string> text(int n) = #"value {n}"
			fn<string[][]> make(int n) {
			    def<string[][]> x = {
			        if (n == 0) { return [[text(n), "Cloth" + "!"]] }
			        return make(n - 1)
			    }
			    return x
			}
			fn discard(bool early) {
			    def<string[][]> x = { return make(2) }
			    x
			    x -> void
			    if (early) { return }
			    x
			}
			fn<bool> test() {
			    def<string[][]> x = { return make(3) }
			    def saved = x
			    def other = x
			    discard(true)
			    discard(false)
			    return saved[0][0] == "value 0" && saved[0][1] == "Cloth!" && other[0][0] == "value 0"
			}
		""")
	}

	@Test
	fun `array writes copies and returned updates release every temporary on all normal exits`(@TempDir directory: Path) {
		instrumented(directory, "array_write_cleanup", """
			fn<string[][]> change(string[][] a, int n) {
			    a[0][0] = #"value {n}"
			    a[1] = ["Cloth" + "!"]
			    if (n > 0) { return change(a, n - 1) }
			    return a
			}
			fn discard(bool early) {
			    def a = [["first"], ["second"]]
			    def b = a
			    b[0][0] = "changed"
			    change(a, 3)
			    if (early) { return }
			    a = change(a, 2)
			}
			fn<bool> test() {
			    def a = [["first"], ["second"]]
			    def b = change(a, 4)
			    discard(true)
			    discard(false)
			    if (a[0][0] != "first" || b[0][0] != "value 0" || b[1][0] != "Cloth!") { return false }
			    a = change(a, 1)
			    return a[0][0] == "value 0" && a[1][0] == "Cloth!"
			}
		""")
	}

	@Test
	fun `initial array shapes survive returned replacements without leaking storage`(@TempDir directory: Path) {
		instrumented(directory, "array_shape_cleanup", """
			fn<string[][]> make(int n) = [[#"value {n}"], ["Cloth" + "!"]]
			fn<string[][]> replace(string[][] a) {
			    a = make(2)
			    a = make(3)
			    return a
			}
			fn discard(bool early) {
			    def a = make(0)
			    a = replace(a)
			    if (early) { return }
			    a = make(4)
			}
			fn<bool> test() {
			    def a = make(0)
			    def b = a
			    a = replace(a)
			    a = make(5)
			    discard(true)
			    discard(false)
			    return a[0][0] == "value 5" && b[0][0] == "value 0" && a[1][0] == "Cloth!"
			}
		""")
	}

	/** Replace only the module's native allocation imports with counted test wrappers.
	 * Observe the heap after test() returns, independently of the generated cleanup shape.
	 */
	private fun instrumented(directory: Path, name: String, source: String, expectAllocation: Boolean = true, failAllocation: Boolean = false, oversizedResult: Boolean = false, oversizedArray: Boolean = false) {
		val emitter = LLVMEmitter()
		val abi = NativeCABI.native()
		val configuration = ToolchainResolver.native(abi, LinkOptions())
		val executable = abi.executableFormat.executablePath(directory.resolve(name))
		val objectFile = directory.resolve("$name.o")
		emitter.withFunctions(parse(source)) { context, module, functions, semantics ->
			val builder = LLVMCreateBuilderInContext(context)
			try {
				val pointer = LLVMPointerTypeInContext(context, 0)
				val integer = LLVMTypes(context).value(abi.intType)
				val size = LLVMTypes(context).value(abi.sizeType)
				if (oversizedResult) {
					// Exercise the internal size guard with an unrepresentable byte-buffer length.
					val invalid = PointerPointer<LLVMValueRef>(2L).use {
						it.put(0, LLVMConstNull(pointer)); it.put(1, LLVMConstInt(size, -1, 0))
						LLVMConstStructInContext(context, it, 2, 0)
					}
					LLVMReplaceAllUsesWith(LLVMGetParam(LLVMGetNamedFunction(module, if (oversizedArray) "cloth.rt.array.copy.0" else "cloth.rt.string.copy"), 1), invalid)
				}
				val zero = LLVMConstInt(integer, 0, 0)
				val one = LLVMConstInt(integer, 1, 0)
				fun counter(label: String): LLVMValueRef = LLVMAddGlobal(module, integer, label).also {
					LLVMSetInitializer(it, zero); LLVMSetLinkage(it, LLVMPrivateLinkage)
				}

				val outstanding = counter("outstanding")
				val allocated = counter("allocations")
				fun update(slot: LLVMValueRef, increment: Boolean) {
					val current = LLVMBuildLoad2(builder, integer, slot, "count")
					LLVMBuildStore(builder, if (increment) LLVMBuildAdd(builder, current, one, "increment") else LLVMBuildSub(builder, current, one, "decrement"), slot)
				}

				fun signature(result: LLVMTypeRef, parameter: LLVMTypeRef): LLVMTypeRef = PointerPointer<LLVMTypeRef>(1L).use {
					it.put(0, parameter); LLVMFunctionType(result, it, 1, 0)
				}

				fun forward(type: LLVMTypeRef, function: LLVMValueRef, argument: LLVMValueRef, label: String): LLVMValueRef = PointerPointer<LLVMValueRef>(1L).use {
					it.put(0, argument); LLVMBuildCall2(builder, type, function, it, 1, label)
				}

				val malloc = LLVMGetNamedFunction(module, "malloc")
				LLVMSetValueName2(malloc, "test.allocate", 13)
				LLVMSetLinkage(malloc, LLVMInternalLinkage)
				val mallocType = signature(pointer, size)
				val realMalloc = LLVMAddFunction(module, "malloc", mallocType)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, malloc, "entry"))
				update(allocated, true)
				update(outstanding, true)
				val header = (abi.pointerBits / 8).toLong()
				fun offset(address: LLVMValueRef, amount: Long): LLVMValueRef = PointerPointer<LLVMValueRef>(1L).use {
					it.put(0, LLVMConstInt(size, amount, 1)); LLVMBuildInBoundsGEP2(builder, LLVMInt8TypeInContext(context), address, it, 1, "offset")
				}
				if (oversizedResult) EmitRuntimeError(context, builder, abi).emit("Unexpected allocation for oversized string")
				else if (failAllocation) LLVMBuildRet(builder, LLVMConstNull(pointer)) else {
					val capacity = LLVMGetParam(malloc, 0)
					val allocation = forward(mallocType, realMalloc, LLVMBuildAdd(builder, capacity, LLVMConstInt(size, header, 0), "tracked_capacity"), "allocation")
					LLVMBuildStore(builder, capacity, allocation)
					LLVMBuildRet(builder, offset(allocation, header))
				}

				val free = LLVMGetNamedFunction(module, "free")
				if (free != null && !free.isNull) {
					LLVMSetValueName2(free, "test.release", 12)
					LLVMSetLinkage(free, LLVMInternalLinkage)
					val freeType = signature(LLVMVoidTypeInContext(context), pointer)
					val realFree = LLVMAddFunction(module, "free", freeType)
					LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, free, "entry"))
					update(outstanding, false)
					val released = LLVMGetParam(free, 0)
					val allocation = offset(released, -header)
					val capacity = LLVMBuildLoad2(builder, size, allocation, "released_capacity")
					// Poison before freeing, making an accidentally returned alias observably wrong.
					LLVMBuildMemSet(builder, released, LLVMConstInt(LLVMInt8TypeInContext(context), 0xa5, 0), capacity, 1)
					forward(freeType, realFree, allocation, "")
					LLVMBuildRetVoid(builder)
				}

				val mainType = LLVMFunctionType(integer, null as PointerPointer<*>?, 0, 0)
				val main = LLVMAddFunction(module, "main", mainType)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, main, "entry"))
				val entry = semantics.declarations.functions.single { it.name.lexeme == "test" }
				val result = LLVMBuildCall2(builder, functions.signature(entry), functions.reference(entry), null as PointerPointer<LLVMValueRef>?, 0, "result")
				val balanced = LLVMBuildICmp(builder, LLVMIntEQ, LLVMBuildLoad2(builder, integer, outstanding, "remaining"), zero, "balanced")
				val used = LLVMBuildICmp(builder, if (expectAllocation) LLVMIntUGT else LLVMIntEQ, LLVMBuildLoad2(builder, integer, allocated, "total"), zero, "used")
				val success = LLVMBuildAnd(builder, result, LLVMBuildAnd(builder, balanced, used, "storage_ok"), "success")
				LLVMBuildRet(builder, LLVMBuildSelect(builder, success, zero, one, "status"))
				emitter.verify(module)
				emitter.emitObject(module, objectFile)
			} finally {
				LLVMDisposeBuilder(builder)
			}
		}
		try {
			NativeLinker.link(objectFile, executable, configuration)
		} finally {
			Files.deleteIfExists(objectFile)
		}
		if (failAllocation || oversizedResult) assertProgram(executable, 1, "Unable to allocate returned ${if (oversizedArray) "array" else "string"} storage" + System.lineSeparator()) else assertProgram(executable)
	}

	private fun native(directory: Path, name: String, source: String, options: LinkOptions = LinkOptions()) {
		val program = source + "\nfn<int> main() = if (test()) { 0 } else { 1 }"
		assertProgram(LLVMEmitter(options).compile(parse(program), directory.resolve(name)))
	}

	private fun assertProgram(executable: Path, status: Int = 0, output: String = "") {
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Executable timed out")
			val actual = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), actual)
			assertEquals(output, actual)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
