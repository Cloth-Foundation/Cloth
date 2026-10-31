package cloth

import cloth.diagnostics.SourceFile
import cloth.emitter.*
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.settings.LinkOptions
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArrayTemporaryCleanupTest {

	private val make = "fn<int[]> make(int n) = [n, n]"
	private val read = "fn<int> read(int[] a) = a[0]"
	private val texts = "fn<string[]> texts() = [\"é中😀\", \"Cloth\"]"

	@Test
	fun `discarded results release all bytes before the next statement and bound peak memory`(@TempDir directory: Path) {
		val source = "fn<int[]> make() = [${(0 until 64).joinToString(", ")}]\nfn<int> sample() {\n" + List(8) { "make()\nif (liveBytes() != 0) { return 1 }" }.joinToString("\n") + "\nreturn 0\n}"
		probe(directory, "discarded", source, allocations = 8, peak = 264)
	}

	@Test
	fun `scalar consumers release argument snapshots while preserving existing bindings`(@TempDir directory: Path) {
		probe(directory, "scalar", """
			$make
			$read
			fn<int> sample() {
			    def a = make(1)
			    def baseline = liveBytes()
			    def first = read(a)
			    if (first != 1 || liveBytes() != baseline) { return 1 }
			    def second = read(make(2))
			    if (second != 2 || liveBytes() != baseline || a[0] != 1) { return 2 }
			    return 0
			}
		""", allocations = 3, peak = 32)
	}

	@Test
	fun `void consumers release mutable parameter snapshots after the call`(@TempDir directory: Path) {
		probe(directory, "void", """
			$make
			fn consume(final int[] a) { a[0] = 9 }
			fn<int> sample() {
			    def a := make(1)
			    def baseline = liveBytes()
			    consume(a)
			    if (liveBytes() != baseline || a[0] != 1) { return 1 }
			    consume(make(2))
			    if (liveBytes() != baseline) { return 2 }
			    return 0
			}
		""", allocations = 3, peak = 32)
	}

	@Test
	fun `primitive indexing frees its producer after loading the scalar value`(@TempDir directory: Path) {
		probe(directory, "index", "$make\nfn<int> sample() {\n def n = make(7)[1]\n if (liveBytes() != 0) { return 1 }\n return n - 7\n}", allocations = 1, peak = 16)
	}

	@Test
	fun `nested literal temporaries release copied rows after scalar indexing`(@TempDir directory: Path) {
		probe(directory, "literal_rows", "fn<int> sample() {\n def n = [[1, 2], [3, 4]][0][1]\n if (liveBytes() != 0) { return 1 }\n return n - 2\n}", allocations = 2, peak = 32)
	}

	@Test
	fun `discarded nested string arrays release complete allocation segments`(@TempDir directory: Path) {
		probe(directory, "nested", """
			$texts
			fn<string[][]> matrix() = [texts(), texts()]
			fn<int> sample() {
			    matrix()
			    if (liveBytes() != 0) { return 1 }
			    matrix() -> void
			    if (liveBytes() != 0) { return 2 }
			    return 0
			}
		""")
	}

	@Test
	fun `borrowed string and adopted row results retain their dependency buffers`(@TempDir directory: Path) {
		probe(directory, "views", """
			$make
			$texts
			fn<int[][]> matrix() = [make(1), make(2)]
			fn<int> sample() {
			    def text = texts()[0]
			    def row = matrix()[1]
			    def baseline = liveBytes()
			    texts()
			    matrix()
			    if (liveBytes() != baseline || baseline == 0) { return 1 }
			    row[0] = 9
			    if (text != "é中😀" || row[1] != 2) { return 2 }
			    return 0
			}
		""")
	}

	@Test
	fun `captured array writes force frame lifetime while safe nested regions preserve them`(@TempDir directory: Path) {
		probe(directory, "capture_array", """
			$make
			fn<int> read(int[] a, int ignored) = a[0]
			fn<int> sample() {
			    def saved = make(1)
			    def<int> publish = { saved = make(9)
			        return 0 }
			    def n = read(make(3), publish)
			    def baseline = liveBytes()
			    make(4)
			    if (liveBytes() != baseline || saved[0] != 9 || n != 3) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `captured string writes prevent reclaiming a borrowed array element`(@TempDir directory: Path) {
		probe(directory, "capture_string", """
			$make
			$texts
			fn<int> read(int[] a, int ignored) = a[0]
			fn<int> sample() {
			    def saved = ""
			    def<int> publish = { saved = texts()[0]
			        return make(2)[0] }
			    def n = read(make(3), publish)
			    def baseline = liveBytes()
			    texts()
			    if (liveBytes() != baseline || saved != "é中😀" || n != 3) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `stored overwritten and scoped arrays conservatively retain frame lifetime`(@TempDir directory: Path) {
		probe(directory, "fallback", """
			$make
			fn<int> sample() {
			    def a = make(1)
			    def first = liveBytes()
			    a = make(2)
			    if (liveBytes() <= first || a[0] != 2) { return 1 }
			    def beforeScope = liveBytes()
			    if (true) { def local = make(3) }
			    if (liveBytes() <= beforeScope) { return 2 }
			    return 0
			}
		""", allocations = 3, peak = 48)
	}

	@Test
	fun `pure repeated function block reads reclaim only discarded or scalar results`(@TempDir directory: Path) {
		probe(directory, "blocks", """
			$make
			fn<int> sample() {
			    def<int[]> compute = { return make(7) }
			    compute
			    if (liveBytes() != 0) { return 1 }
			    def n = compute[0]
			    if (liveBytes() != 0 || n != 7) { return 2 }
			    def a = compute
			    if (liveBytes() == 0 || a[0] != 7) { return 3 }
			    return 0
			}
		""", allocations = 3, peak = 16)
	}

	@Test
	fun `dynamic conditional and short circuit cleanup executes only on evaluated paths`(@TempDir directory: Path) {
		for ((index, flag) in listOf("true", "false").withIndex()) {
			probe(directory, "conditional_$index", """
				$make
				$read
				fn<bool> flag() = $flag
				fn<int> sample() {
				    if (flag()) { make(1) } else { make(2) }
				    if (liveBytes() != 0) { return 1 }
				    def n = (if (flag()) { make(3) } else { make(4) })[0]
				    if (liveBytes() != 0 || n != ${index + 3}) { return 2 }
				    false && (read(make(5)) == 5)
				    true || (read(make(6)) == 6)
				    if (liveBytes() != 0) { return 3 }
				    return 0
				}
			""", allocations = 2, peak = 16)
		}
	}

	@Test
	fun `early returns fall back to frame cleanup without freeing a returned array`(@TempDir directory: Path) {
		probe(directory, "early", """
			$make
			fn<int> read(int[] a, int n) = a[0]
			fn<int[]> early() {
			    read(make(1), if (true) { return make(2) } else { 0 })
			    return make(3)
			}
			fn<int> sample() {
			    def a = early()
			    if (a[0] != 2) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `discarded expression body calls and scalar return paths clean up normally`(@TempDir directory: Path) {
		probe(directory, "return_paths", """
			$make
			fn discard() = make(1) -> void
			fn<int> index() = make(2)[0]
			fn<int> sample() {
			    discard()
			    if (liveBytes() != 0) { return 1 }
			    def n = index()
			    if (liveBytes() != 0 || n != 2) { return 2 }
			    return 0
			}
		""", allocations = 2, peak = 16)
	}

	/** Observe actual live/peak requested bytes during Cloth execution, not just
	 * final balance. Test-only observation functions replace ordinary zero-return
	 * bodies, and freed buffers are poisoned before returning them to the C runtime.
	 */
	private fun probe(directory: Path, name: String, source: String, allocations: Int? = null, peak: Long? = null) {
		val emitter = LLVMEmitter()
		val abi = NativeCABI.native()
		val configuration = ToolchainResolver.native(abi, LinkOptions())
		val executable = abi.executableFormat.executablePath(directory.resolve(name))
		val objectFile = directory.resolve("$name.o")
		val unit = Parser(Lexer(SourceFile("cleanup.co", "fn<int<64>> liveBytes() = 0\n" + source.trimIndent())).scanTokens()).parse()
		emitter.withFunctions(unit) { context, module, functions, semantics ->
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

				fun global(name: String, type: LLVMTypeRef) = LLVMAddGlobal(module, type, name).also { LLVMSetInitializer(it, LLVMConstNull(type)); LLVMSetLinkage(it, LLVMPrivateLinkage) }
				val allocated = global("test.allocations", integer)
				val outstanding = global("test.outstanding", integer)
				val live = global("test.live", size)
				val maximum = global("test.peak", size)
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
				val current = LLVMBuildAdd(builder, LLVMBuildLoad2(builder, size, live, "live"), capacity, "new_live")
				LLVMBuildStore(builder, current, live)
				val previousPeak = LLVMBuildLoad2(builder, size, maximum, "peak")
				LLVMBuildStore(builder, LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntUGT, current, previousPeak, "new_peak"), current, previousPeak, "peak_bytes"), maximum)
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
				LLVMBuildStore(builder, LLVMBuildSub(builder, LLVMBuildLoad2(builder, size, live, "live"), bytes, "remaining_bytes"), live)
				LLVMBuildMemSet(builder, released, LLVMConstInt(LLVMInt8TypeInContext(context), 0xa5, 0), bytes, 1)
				call(freeType, realFree, original)
				LLVMBuildRetVoid(builder)
				val observer = semantics.declarations.functions.single { it.name.lexeme == "liveBytes" }
				val observe = LLVMAddFunction(module, "test.liveBytes", functions.signature(observer))
				LLVMSetLinkage(observe, LLVMInternalLinkage)
				LLVMReplaceAllUsesWith(functions.reference(observer), observe)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, observe, "entry"))
				LLVMBuildRet(builder, LLVMBuildLoad2(builder, size, live, "current_bytes"))
				val mainType = signature(integer)
				val main = LLVMAddFunction(module, "main", mainType)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, main, "entry"))
				val sample = semantics.declarations.functions.single { it.name.lexeme == "sample" }
				val result = call(functions.signature(sample), functions.reference(sample))
				val printfType = PointerPointer<LLVMTypeRef>(1L).use { values -> values.put(0, pointer); LLVMFunctionType(integer, values, 1, 1) }
				val existing = LLVMGetNamedFunction(module, "printf")
				val printf = if (existing == null || existing.isNull) LLVMAddFunction(module, "printf", printfType) else existing
				call(printfType, printf, LLVMBuildGlobalStringPtr(builder, "allocations=%d outstanding=%d bytes=%llu peak=%llu result=%d\n", "metrics"), LLVMBuildLoad2(builder, integer, allocated, "allocations"), LLVMBuildLoad2(builder, integer, outstanding, "remaining"), LLVMBuildLoad2(builder, size, live, "remaining_bytes"), LLVMBuildLoad2(builder, size, maximum, "peak_bytes"), result)
				LLVMBuildRet(builder, result)
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
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), name)
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(0, process.exitValue(), output)
			val metrics = assertNotNull(Regex("allocations=(\\d+) outstanding=(-?\\d+) bytes=(\\d+) peak=(\\d+) result=(-?\\d+)").matchEntire(output.trim()), output).groupValues
			assertEquals("0", metrics[2], output)
			assertEquals("0", metrics[3], output)
			assertEquals("0", metrics[5], output)
			if (allocations != null) assertEquals(allocations, metrics[1].toInt(), output)
			if (peak != null) assertEquals(peak, metrics[4].toLong(), output)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
