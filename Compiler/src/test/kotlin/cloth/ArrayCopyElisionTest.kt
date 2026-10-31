package cloth

import cloth.diagnostics.SourceFile
import cloth.emitter.*
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.settings.LinkOptions
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ArrayCopyElisionTest {
	private fun parse(source: String) = Parser(Lexer(SourceFile("array-elision.co", source.trimIndent())).scanTokens()).parse()
	private val make = "fn<int[]> make(int n) = [n, n]"

	@Test fun `the audited identity example eliminates the extra result binding copy`(@TempDir directory: Path) {
		counted(directory, "identity", """
			fn<int[]> identity(int[] a) = a
			fn<int> sample() {
			    def a = [1, 2, 3]
			    def b = identity(a)
			    b[0] = 9
			    if (a[0] != 1) { return 1 }
			    return b[0] - 9
			}
		""", 3)
	}

	@Test fun `fresh results pass through bindings and chained arguments without extra snapshots`(@TempDir directory: Path) {
		counted(directory, "chain", """
			$make
			fn<int[]> change(int[] a) { a[0] = 9
			    return a }
			fn<int> sample() {
			    def a = change(change(make(1)))
			    return a[0] + a[1] - 10
			}
		""", 3)
	}

	@Test fun `stored and final results lose freshness and retain independent copy semantics`(@TempDir directory: Path) {
		counted(directory, "stored", """
			$make
			fn<int[]> change(final int[] a) { a[0] = 9
			    return a }
			fn<int> sample() {
			    def a := make(1)
			    def b = a
			    b[0] = 5
			    change(a)
			    return a[0] + b[0] - 6
			}
		""", 4)
	}

	@Test fun `whole binding replacement adopts a fresh result and preserves its shape`(@TempDir directory: Path) {
		counted(directory, "replace", "$make\nfn<int> sample() {\n def a = make(1)\n a = make(2)\n return a[1] - 2\n}", 2)
	}

	@Test fun `fresh rows transfer on replacement while extracted bound rows still copy`(@TempDir directory: Path) {
		counted(directory, "rows", """
			$make
			fn<int> sample() {
			    def a = [[1, 2]]
			    a[0] = make(3)
			    def row = a[0]
			    row[0] = 9
			    return a[0][0] + row[0] - 12
			}
		""", 5)
	}

	@Test fun `indexing a fresh temporary can transfer its independent subarray`(@TempDir directory: Path) {
		counted(directory, "temporary_row", "fn<int[][]> matrix() = [[1, 2], [3, 4]]\nfn<int> sample() {\n def row = matrix()[1]\n row[0] = 9\n return row[1] - 4\n}", 5)
		counted(directory, "bound_row", "fn<int[][]> matrix() = [[1, 2], [3, 4]]\nfn<int> sample() {\n def a = matrix()\n def row = a[1]\n row[0] = 9\n return a[1][0] - 3\n}", 6)
	}

	@Test fun `dynamic conditional merges preserve fresh ownership on either path`(@TempDir directory: Path) {
		for ((index, condition) in listOf("true", "false").withIndex()) {
			counted(directory, "conditional_$index", "$make\nfn<bool> flag() = $condition\nfn<int> sample() {\n def a = if (flag()) { make(1) } else { make(2) }\n return a[0] - ${index + 1}\n}", 1)
		}
	}

	@Test fun `conditional assignment results cannot reuse storage already held by a binding`(@TempDir directory: Path) {
		for ((index, condition) in listOf("true", "false").withIndex()) {
			counted(directory, "conditional_alias_$index", """
				$make
				fn<bool> flag() = $condition
				fn<int> sample() {
				    def a = make(1)
				    def b = if (flag()) { a = make(2)
				        a } else { a = make(3)
				        a }
				    b[0] = 9
				    return a[0] - ${index + 2}
				}
			""", 3)
		}
	}

	@Test fun `conditional indexed assignment results also take independent snapshots`(@TempDir directory: Path) {
		counted(directory, "stored_row_result", """
			$make
			fn<int> sample() {
			    def a = [[1, 2]]
			    def b = if (true) { a[0] = make(3)
			        a[0] } else { a[0] }
			    b[0] = 9
			    return a[0][0] - 3
			}
		""", 5)
	}

	@Test fun `each function block read produces a separately transferable fresh result`(@TempDir directory: Path) {
		counted(directory, "block_fresh", """
			$make
			fn<int> sample() {
			    def<int[]> compute = { return make(1) }
			    def a = compute
			    def b = compute
			    a[0] = 9
			    return b[0] - 1
			}
		""", 2)
		counted(directory, "block_captured", """
			$make
			fn<int> sample() {
			    def source = make(1)
			    def<int[]> compute = { return source }
			    def a = compute
			    source[0] = 5
			    def b = compute
			    return a[0] + b[0] - 6
			}
		""", 3)
	}

	@Test fun `multiple block return paths merge fresh and borrowed values safely`(@TempDir directory: Path) {
		for ((index, condition) in listOf("true", "false").withIndex()) {
			counted(directory, "block_paths_$index", """
				$make
				fn<bool> flag() = $condition
				fn<int> sample() {
				    def source = make(1)
				    def<int[]> compute = {
				        if (flag()) { return make(2) }
				        return source
				    }
				    def a = compute
				    a[0] = 9
				    return source[0] - 1
				}
			""", 2)
		}
	}

	@Test fun `argument snapshots precede later captured mutations and evaluate once`(@TempDir directory: Path) {
		counted(directory, "argument_order", """
			$make
			fn<int> read(int[] a, int ignored) = a[0]
			fn<int> sample() {
			    def source = make(1)
			    def count = 0
			    def<int> mutate = { count += 1
			        source[0] = 9
			        return 0 }
			    def first = read(source, mutate)
			    def second = read(make(2), mutate)
			    if (first != 1 || second != 2 || source[0] != 9 || count != 2) { return 1 }
			    return 0
			}
		""", 3)
	}

	@Test fun `captured array producers and mixed conditional aliases preserve argument snapshots`(@TempDir directory: Path) {
		counted(directory, "mixed_argument", """
			$make
			fn<bool> flag() = true
			fn<int> read(int[] a, int ignored) = a[0]
			fn<int> sample() {
			    def source = make(1)
			    def<int> mutate = { source[0] = 9
			        return 0 }
			    def value = read(if (flag()) { source = make(2)
			        source } else { source }, mutate)
			    return value - 2
			}
		""", 3)
	}

	@Test fun `early returns stop further argument evaluation and release adopted temporaries`(@TempDir directory: Path) {
		counted(directory, "early", "$make\nfn<int[]> unused(int[] a, int n) = a\nfn<int> sample() {\n unused(make(1), if (true) { return 0 } else { [1][5] })\n return 1\n}", 1)
	}

	@Test fun `nested string results survive recursion replacement and poisoned callee cleanup`(@TempDir directory: Path) {
		counted(directory, "nested_lifetime", """
			fn<string[][]> make(int n) {
			    if (n > 0) { return make(n - 1) }
			    return [[#"value {n}", "Cloth" + "!"]]
			}
			fn<string[]> row() = ["new", "row"]
			fn<string[][]> change(string[][] a) { a[0] = row()
			    return a }
			fn<int> sample() {
			    def a = make(4)
			    def b = change(make(2))
			    def c = a
			    a[0] = row()
			    if (c[0][0] != "value 0" || c[0][1] != "Cloth!" || b[0][1] != "row" || a[0][0] != "new") { return 1 }
			    return 0
			}
		""")
	}

	@Test fun `fresh adoption retains known and inferred shape checks including empty dimensions`(@TempDir directory: Path) {
		val cases = listOf(
			"fn<int[]> make() = [1]\nfn main() { def<int[2]> a = make() }" to "Array size mismatch: expected 2 elements",
			"fn<int[]> make() = [1]\nfn<int> trap() = [1][5]\nfn take(int[2] a, int n) {}\nfn main() { take(make(), trap()) }" to "Array size mismatch: expected 2 elements",
			"fn<int[]> make() = [1]\nfn main() {\n def<int[2]> compute = { return make() }\n compute\n}" to "Array size mismatch: expected 2 elements",
			"fn<int[][]> make() = [[1, 2], [3, 4]]\nfn main() {\n def a = make()\n a = if (true) { [[1], [2]] } else { [[3], [4]] }\n}" to "assignment must preserve the binding's initial shape",
			"fn<int[][]> make() {\n def<int[0][3]> a = []\n return a\n}\nfn main() { def<int[0][4]> a = make() }" to "Array size mismatch: expected 4 elements",
			"fn<int[]> make() = [1]\nfn main() {\n def a = [[1, 2]]\n a[0] = make()\n}" to "Array shape mismatch"
		)
		for ((index, case) in cases.withIndex()) {
			val executable = LLVMEmitter().compile(parse(case.first), directory.resolve("shape_$index"))
			val (status, output) = execute(executable)
			assertNotEquals(0, status, output)
			assertContains(output, case.second)
			assertFalse(output.contains("Array index out of bounds"), output)
		}
	}

	@Test fun `index targets remain resolved before rhs replacement of their root binding`(@TempDir directory: Path) {
		counted(directory, "target_order", """
			fn<int[][]> make() = [[1, 2]]
			fn<int[]> row() = [7, 8]
			fn<int> sample() {
			    def a = make()
			    def<int[]> rhs = { a = make()
			        return row() }
			    a[0] = rhs
			    return a[0][0] - 1
			}
		""")
	}

	private fun execute(executable: Path): Pair<Int, String> {
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), executable.toString())
			return process.exitValue() to process.inputStream.readBytes().toString(Charsets.UTF_8)
		} finally { if (process.isAlive) process.destroyForcibly() }
	}

	/** Count actual heap allocations independently of emitter structure. Poison
	 * released bytes and check balance after the entire Cloth invocation exits.
	 */
	private fun counted(directory: Path, name: String, source: String, expected: Int? = null) {
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
				fun int(value: Long) = LLVMConstInt(integer, value, 0)
				fun count(value: Long) = LLVMConstInt(size, value, 0)
				fun signature(result: LLVMTypeRef, vararg params: LLVMTypeRef) = PointerPointer<LLVMTypeRef>(params.size.toLong()).use { values ->
					params.forEachIndexed { index, value -> values.put(index.toLong(), value) }; LLVMFunctionType(result, values, params.size, 0)
				}
				fun call(type: LLVMTypeRef, function: LLVMValueRef, vararg params: LLVMValueRef) = PointerPointer<LLVMValueRef>(params.size.toLong()).use { values ->
					params.forEachIndexed { index, value -> values.put(index.toLong(), value) }; LLVMBuildCall2(builder, type, function, values, params.size, "")
				}
				fun global(name: String) = LLVMAddGlobal(module, integer, name).also { LLVMSetInitializer(it, int(0)); LLVMSetLinkage(it, LLVMPrivateLinkage) }
				val allocated = global("test.allocations")
				val outstanding = global("test.outstanding")
				fun increment(slot: LLVMValueRef, amount: Long) = LLVMBuildStore(builder, LLVMBuildAdd(builder, LLVMBuildLoad2(builder, integer, slot, "count"), int(amount), "updated"), slot)
				fun offset(address: LLVMValueRef, amount: Long) = PointerPointer<LLVMValueRef>(1L).use { values -> values.put(0, count(amount)); LLVMBuildInBoundsGEP2(builder, LLVMInt8TypeInContext(context), address, values, 1, "offset") }
				val header = (abi.pointerBits / 8).toLong()
				val malloc = LLVMGetNamedFunction(module, "malloc")
				assertFalse(malloc == null || malloc.isNull)
				LLVMSetValueName2(malloc, "test.malloc", 11)
				LLVMSetLinkage(malloc, LLVMInternalLinkage)
				val mallocType = signature(pointer, size)
				val realMalloc = LLVMAddFunction(module, "malloc", mallocType)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, malloc, "entry"))
				increment(allocated, 1)
				increment(outstanding, 1)
				val capacity = LLVMGetParam(malloc, 0)
				val allocation = call(mallocType, realMalloc, LLVMBuildAdd(builder, capacity, count(header), "tracked_capacity"))
				LLVMBuildStore(builder, capacity, allocation)
				LLVMBuildRet(builder, offset(allocation, header))
				val free = LLVMGetNamedFunction(module, "free")
				assertFalse(free == null || free.isNull)
				LLVMSetValueName2(free, "test.free", 9)
				LLVMSetLinkage(free, LLVMInternalLinkage)
				val freeType = signature(LLVMVoidTypeInContext(context), pointer)
				val realFree = LLVMAddFunction(module, "free", freeType)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, free, "entry"))
				increment(outstanding, -1)
				val released = LLVMGetParam(free, 0)
				val original = offset(released, -header)
				val bytes = LLVMBuildLoad2(builder, size, original, "released_bytes")
				LLVMBuildMemSet(builder, released, LLVMConstInt(LLVMInt8TypeInContext(context), 0xa5, 0), bytes, 1)
				call(freeType, realFree, original)
				LLVMBuildRetVoid(builder)
				val mainType = signature(integer)
				val main = LLVMAddFunction(module, "main", mainType)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, main, "entry"))
				val sample = semantics.declarations.functions.single { it.name.lexeme == "sample" }
				val result = call(functions.signature(sample), functions.reference(sample))
				val printfType = PointerPointer<LLVMTypeRef>(1L).use { values -> values.put(0, pointer); LLVMFunctionType(integer, values, 1, 1) }
				val existing = LLVMGetNamedFunction(module, "printf")
				val printf = if (existing == null || existing.isNull) LLVMAddFunction(module, "printf", printfType) else existing
				call(printfType, printf, LLVMBuildGlobalStringPtr(builder, "allocations=%d outstanding=%d result=%d\n", "metrics"),
					LLVMBuildLoad2(builder, integer, allocated, "allocations"), LLVMBuildLoad2(builder, integer, outstanding, "remaining"), result)
				LLVMBuildRet(builder, result)
				emitter.verify(module)
				emitter.emitObject(module, objectFile)
			} finally { LLVMDisposeBuilder(builder) }
		}
		try { NativeLinker.link(objectFile, executable, configuration) } finally { Files.deleteIfExists(objectFile) }
		val (status, output) = execute(executable)
		assertEquals(0, status, output)
		val metrics = assertNotNull(Regex("allocations=(\\d+) outstanding=(-?\\d+) result=(-?\\d+)").matchEntire(output.trim()), output).groupValues
		assertEquals("0", metrics[2], output)
		assertEquals("0", metrics[3], output)
		if (expected != null) assertEquals(expected, metrics[1].toInt(), output)
	}
}
