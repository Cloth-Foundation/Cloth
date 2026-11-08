package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.NativeLinker
import cloth.emitter.ToolchainResolver
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
import cloth.settings.LinkOptions
import cloth.settings.StandardLibrarySelection
import cloth.source.SourceGraphLoader
import cloth.toolchain.StandardLibrary
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.*

/** Verify the first library allocation consumer through existing typed class operations. */
class StandardLibraryRuntimeTest {
	@TempDir lateinit var temporary: Path
	private val fixture = Path.of("Compiler/src/test/resources/standard-library-runtime").toAbsolutePath()

	private fun request(name: String, abi: NativeCABI, source: String? = null, selected: Boolean = true): CompilationRequest {
		val primary = if (source == null) fixture.resolve("Main.co") else Files.writeString(temporary.resolve("$name.co"), source.trimIndent())
		val request = CompilationRequest(primary, temporary.resolve(name),
			modules = if (selected) emptyList() else listOf(SourceModule("RuntimeFixture", fixture)), targetTriple = abi.targetTriple)
		return if (selected) StandardLibrary.select(StandardLibrarySelection.Explicit(fixture), temporary)!!.attachTo(request) else request
	}

	private fun executableIR(request: CompilationRequest, abi: NativeCABI): String {
		val program = ProgramAnalyzer(abi.layout).analyze(FileBindings.graph(SourceGraphLoader.load(request)))
		return LLVMEmitter(abi).emitExecutable(program)
	}

	@Test fun `ordinary selected and regular roots use the same class allocation on native profiles`() {
		for (abi in structRunners()) for (selected in listOf(true, false)) {
			val request = request("owned_${abi.layout.sizeBits}_$selected", abi, selected = selected)
			assertEquals(processCompletion(), runExecutable(ClothCompiler.compile(request).artifact))
		}
	}

	@Test fun `library allocation emits target sized private helpers and startup objects for every profile`() {
		for ((index, triple) in structTargetTriples.withIndex()) {
			val abi = NativeCABI.forTarget(triple)
			val emitter = LLVMEmitter(abi)
			val ir = executableIR(request("target_$index", abi), abi)
			assertContains(ir, "target triple = \"$triple\"")
			assertContains(ir, "declare ptr @malloc(i${abi.layout.sizeBits})")
			assertContains(ir, "declare ptr @realloc(ptr, i${abi.layout.sizeBits})")
			assertContains(ir, "define internal i64 @cloth.objects.allocate(i${abi.layout.sizeBits}")
			val objectFile = temporary.resolve("target_$index.o")
			withModule(ir) { _, module ->
				for (name in listOf("allocate", "lookup", "release")) {
					assertEquals(LLVMInternalLinkage, LLVMGetLinkage(LLVMGetNamedFunction(module, "cloth.objects.$name")))
				}
				for (name in listOf("malloc", "realloc", "free")) assertEquals(LLVMCCallConv, LLVMGetFunctionCallConv(LLVMGetNamedFunction(module, name)))
				emitter.verify(module)
				emitter.emitObject(module, objectFile)
			}
			assertTrue(Files.size(objectFile) > 0)
			if (triple == "x86_64-unknown-linux-gnu") System.getProperty("cloth.test.standardLibraryRuntimeArtifacts")?.let {
				val evidence = Files.createDirectories(Path.of(it))
				Files.writeString(evidence.resolve("owned-chain-linux-x64.ll"), ir)
				Files.copy(objectFile, evidence.resolve("owned-chain-linux-x64.o"), StandardCopyOption.REPLACE_EXISTING)
			}
		}
	}

	@Test fun `deletion releases node payloads and field buffers across poisoned native frees`() {
		for (abi in structRunners()) {
			val request = request("poison_${abi.layout.sizeBits}", abi)
			val artifact = instrumented(request, abi) { context, module -> trackAllocations(context, module, abi) }
			val output = runExecutable(artifact).replace("\r\n", "\n")
			assertTrue(output.startsWith(processCompletion().replace("\r\n", "\n")), output)
			val metrics = assertNotNull(Regex("probe mallocs=(\\d+) frees=(\\d+) reallocs=(\\d+)\\n$").find(output), output).groupValues
			assertEquals(metrics[1], metrics[2], output)
			assertTrue(metrics[1].toLong() > 1_000, output)
			assertEquals(4, metrics[3].toInt(), output) // 16 -> 32 -> 64 -> 128 slots, then reuse.
		}
	}

	@Test fun `allocation and table growth failures stop before user continuation or completion`() {
		val source = """
			using cloth.collections.OwnedTextChain : OwnedTextChain
			fn main() {
			    def chain = OwnedTextChain::new
			    println("must not run")
			    chain::delete
			}
		"""
		for (abi in structRunners()) for ((name, message) in listOf("malloc" to "Unable to allocate class instance", "realloc" to "Unable to grow class instance table")) {
			val request = request("fail_${name}_${abi.layout.sizeBits}", abi, source)
			val artifact = instrumented(request, abi) { context, module ->
				val function = LLVMGetNamedFunction(module, name)
				assertEquals(0, LLVMCountBasicBlocks(function))
				val builder = LLVMCreateBuilderInContext(context)
				try {
					LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, function, "fail"))
					LLVMBuildRet(builder, LLVMConstNull(LLVMPointerTypeInContext(context, 0)))
				} finally { LLVMDisposeBuilder(builder) }
			}
			assertEquals("$message\n", runExecutable(artifact, 1).replace("\r\n", "\n"))
		}
	}

	@Test fun `private collection storage and provably deleted aliases retain source diagnostics`() {
		val abi = NativeCABI.native()
		for ((name, source, code) in listOf(
			Triple("private_node", "using cloth.collections.OwnedTextChain : TextNode\nfn main() {}", DiagnosticCode.IMPORT_PRIVATE_NAME),
			Triple("deleted_alias", "using cloth.collections.OwnedTextChain : OwnedTextChain\nfn main() { def chain = OwnedTextChain::new; def alias := chain; chain::delete; alias.size() }", DiagnosticCode.DELETED_CLASS_ACCESS),
		)) {
			val request = request(name, abi, source)
			val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request) }
			assertEquals(code, failure.diagnosticCode)
			assertTrue(failure.sourceLabels.any { it.span.source?.name == request.sourceFile.toString() })
			assertFalse(Files.exists(abi.executableFormat.executablePath(request.outputFile)))
		}
	}

	@Test fun `unknown aliases retain fatal liveness checks across library destruction`() {
		val source = """
			using cloth.collections.OwnedTextChain : OwnedTextChain
			fn<OwnedTextChain> throughCall(OwnedTextChain value) = value
			fn main() {
			    def chain = OwnedTextChain::new
			    chain.prepend("owned")
			    def alias = throughCall(chain)
			    chain::delete
			    println(alias.size())
			    println("must not run")
			}
		"""
		for (abi in structRunners()) {
			val request = request("stale_${abi.layout.sizeBits}", abi, source)
			assertEquals("Class object is no longer live\n", runExecutable(ClothCompiler.compile(request).artifact, 1).replace("\r\n", "\n"))
		}
	}

	/** Instrument verified graph IR, retaining original per-file semantic identities. */
	private fun instrumented(request: CompilationRequest, abi: NativeCABI, instrument: (LLVMContextRef, LLVMModuleRef) -> Unit): Path {
		val emitter = LLVMEmitter(abi)
		val objectFile = request.outputFile.resolveSibling(request.outputFile.fileName.toString() + ".o")
		withModule(executableIR(request, abi)) { context, module ->
			instrument(context, module)
			emitter.verify(module)
			emitter.emitObject(module, objectFile)
		}
		val configuration = ToolchainResolver.native(abi, LinkOptions()).let { if (abi.windows) it.withWideEntryPoint() else it }
		val executable = abi.executableFormat.executablePath(request.outputFile)
		NativeLinker.link(objectFile, executable, configuration)
		return executable
	}

	private fun withModule(ir: String, action: (LLVMContextRef, LLVMModuleRef) -> Unit) {
		val context = LLVMContextCreate()
		val module = LLVMModuleRef()
		val failure = BytePointer(null as Pointer?)
		try {
			val buffer = LLVMCreateMemoryBufferWithMemoryRangeCopy(ir, ir.toByteArray(Charsets.UTF_8).size.toLong(), "library-runtime")
			assertEquals(0, LLVMParseIRInContext(context, buffer, module, failure), if (failure.isNull) "Invalid IR" else failure.string)
			action(context, module)
		} finally {
			if (!module.isNull) LLVMDisposeModule(module)
			if (!failure.isNull) LLVMDisposeMessage(failure)
			LLVMContextDispose(context)
		}
	}

	/** Observe program allocations only; the persistent object table uses unpoisoned realloc. */
	private fun trackAllocations(context: LLVMContextRef, module: LLVMModuleRef, abi: NativeCABI) {
		val builder = LLVMCreateBuilderInContext(context)
		try {
			val pointer = LLVMPointerTypeInContext(context, 0)
			val size = LLVMIntTypeInContext(context, abi.layout.sizeBits)
			val counter = LLVMInt64TypeInContext(context)
			fun constant(type: LLVMTypeRef, value: Long) = LLVMConstInt(type, value, 0)
			fun global(name: String) = LLVMAddGlobal(module, counter, "probe.$name").also {
				LLVMSetLinkage(it, LLVMInternalLinkage); LLVMSetInitializer(it, constant(counter, 0))
			}
			val mallocs = global("mallocs")
			val frees = global("frees")
			val reallocs = global("reallocs")
			fun increment(value: LLVMValueRef) = LLVMBuildStore(builder, LLVMBuildAdd(builder, LLVMBuildLoad2(builder, counter, value, "count"), constant(counter, 1), "incremented"), value)
			fun call(function: LLVMValueRef, vararg arguments: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(arguments.size.toLong()).use { values ->
				arguments.forEachIndexed { index, value -> values.put(index.toLong(), value) }
				LLVMBuildCall2(builder, LLVMGlobalGetValueType(function), function, values, arguments.size, "")
			}
			fun offset(value: LLVMValueRef, bytes: Long): LLVMValueRef = PointerPointer<LLVMValueRef>(1L).use { values ->
				values.put(0, constant(size, bytes)); LLVMBuildGEP2(builder, LLVMInt8TypeInContext(context), value, values, 1, "payload_offset")
			}
			fun wrap(name: String, emit: (LLVMValueRef, LLVMValueRef) -> Unit) {
				val wrapper = LLVMGetNamedFunction(module, name)
				val probeName = "probe.$name"
				LLVMSetValueName2(wrapper, probeName, probeName.length.toLong())
				LLVMSetLinkage(wrapper, LLVMInternalLinkage)
				val original = LLVMAddFunction(module, name, LLVMGlobalGetValueType(wrapper))
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, wrapper, "observe"))
				emit(wrapper, original)
			}
			wrap("malloc") { wrapper, original ->
				increment(mallocs)
				val bytes = LLVMGetParam(wrapper, 0)
				val allocation = call(original, LLVMBuildAdd(builder, bytes, constant(size, 16), "with_header"))
				LLVMBuildStore(builder, bytes, allocation)
				LLVMBuildRet(builder, offset(allocation, 16))
			}
			wrap("free") { wrapper, original ->
				increment(frees)
				val payload = LLVMGetParam(wrapper, 0)
				val allocation = offset(payload, -16)
				val bytes = LLVMBuildLoad2(builder, size, allocation, "payload_bytes")
				LLVMBuildMemSet(builder, payload, constant(LLVMInt8TypeInContext(context), 0xa5), bytes, 1)
				call(original, allocation)
				LLVMBuildRetVoid(builder)
			}
			wrap("realloc") { wrapper, original ->
				increment(reallocs)
				LLVMBuildRet(builder, call(original, LLVMGetParam(wrapper, 0), LLVMGetParam(wrapper, 1)))
			}
			val entryName = if (abi.windows) "wmain" else "main"
			val original = LLVMGetNamedFunction(module, entryName)
			val originalName = "probe.original.entry"
			LLVMSetValueName2(original, originalName, originalName.length.toLong())
			LLVMSetLinkage(original, LLVMInternalLinkage)
			val entry = LLVMAddFunction(module, entryName, LLVMGlobalGetValueType(original))
			LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, entry, "entry"))
			val arguments = (0 until LLVMCountParams(entry)).map { LLVMGetParam(entry, it) }.toTypedArray()
			val status = call(original, *arguments)
			call(LLVMGetNamedFunction(module, "printf"), LLVMBuildGlobalStringPtr(builder, "probe mallocs=%llu frees=%llu reallocs=%llu\n", "metrics"),
				LLVMBuildLoad2(builder, counter, mallocs, "mallocs"), LLVMBuildLoad2(builder, counter, frees, "frees"), LLVMBuildLoad2(builder, counter, reallocs, "reallocs"))
			LLVMBuildRet(builder, status)
		} finally { LLVMDisposeBuilder(builder) }
	}
}
