package cloth

import cloth.diagnostics.SourceFile
import cloth.emitter.*
import cloth.emitter.types.EmitEntryPoint
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.ProgramKind
import cloth.semantic.ProgramModel
import cloth.settings.*
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class UnicodeArgumentsTest {
	private val native = NativeCABI.native()
	private val windows = native.profile == NativeCABI.Profile.WINDOWS_LLP64
	private val unixProfiles = listOf("x86_64-unknown-linux-gnu", "x86_64-apple-darwin").map(NativeCABI::forTarget)
	private fun parse(source: String) = Parser(Lexer(SourceFile("unicode-args.co", source.trimIndent())).scanTokens()).parse()
	private fun literal(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
	private fun units(value: String, wide: Boolean) = if (wide) value.map { it.code }.toIntArray() else value.toByteArray(Charsets.UTF_8).map { it.toInt() and 255 }.toIntArray()
	private fun sourceFor(values: List<String>) = "fn<int> main(string[] args) {\n" + values.mapIndexed { index, text -> "if (args[$index] != ${literal(text)}) { return ${index + 10} }" }.joinToString("\n") + "\nreturn 0\n}"
	private fun run(executable: Path, arguments: List<String> = emptyList(), status: Int = 0, error: String? = null) {
		// The JVM's legacy Windows launcher leaves embedded quotes unescaped.
		// Use its strict quoting mode so the child actually receives the test data.
		val property = "jdk.lang.Process.allowAmbiguousCommands"
		val previous = if (windows) System.setProperty(property, "false") else null
		val process = try { ProcessBuilder(listOf(executable.toString()) + arguments).redirectErrorStream(true).start() }
		finally { if (windows) { if (previous == null) System.clearProperty(property) else System.setProperty(property, previous) } }
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), executable.toString())
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			if (error == null) assertEquals("", output) else assertEquals(error + System.lineSeparator(), output)
		} finally { if (process.isAlive) process.destroyForcibly() }
	}

	@Test fun `native startup preserves Unicode empty arguments quoting and argument order`(@TempDir directory: Path) {
		val values = listOf("Cloth", "", "é中😀", "é", "é", "two words", "a\"b", "trailing\\", "%s%llu", "\t\n", "�", "\uFEFF")
		val executable = LLVMEmitter().compile(parse(sourceFor(values)), directory.resolve("Unicode é中😀/arguments"))
		run(executable, values)
	}

	@Test fun `startup preserves main return behavior with and without argument parameters`(@TempDir directory: Path) {
		for ((index, source) in listOf("fn main() {}", "fn main() { return }", "fn<int> main() = 7", "fn main(string[] args) {}", "fn<int> main(string[] args) = 7").withIndex()) {
			val executable = LLVMEmitter().compile(parse(source), directory.resolve("empty_$index"))
			run(executable, status = if (source.contains("= 7")) 7 else 0)
			run(executable, listOf("é中😀", ""), status = if (source.contains("= 7")) 7 else 0)
		}
	}

	@Test fun `Windows Unicode startup supports static dynamic GUI and MSVC linker choices`(@TempDir directory: Path) {
		if (!windows) return
		val values = listOf("é中😀", "", "with spaces", "a\"b\\")
		for ((index, options) in listOf(
			LinkOptions(runtime = RuntimeLinkage.STATIC),
			LinkOptions(runtime = RuntimeLinkage.DYNAMIC),
			LinkOptions(subsystem = ApplicationSubsystem.WINDOWS),
			LinkOptions(runtime = RuntimeLinkage.DYNAMIC, subsystem = ApplicationSubsystem.WINDOWS),
			LinkOptions(driver = LinkerDriver.MSVC),
			LinkOptions(driver = LinkerDriver.MSVC, subsystem = ApplicationSubsystem.WINDOWS)
		).withIndex()) run(LLVMEmitter(options).compile(parse(sourceFor(values)), directory.resolve("options_$index")), values)
	}

	@Test fun `every supported platform emits the appropriate verified native adapter`() {
		for (triple in listOf("x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "arm64-apple-darwin")) {
			val profile = NativeCABI.forTarget(triple)
			for (parameters in listOf("", "string[] args")) {
				val emitter = LLVMEmitter()
				emitter.withFunctions(parse("fn main($parameters) {}")) { context, module, functions, semantics ->
					LLVMSetTarget(module, profile.targetTriple)
					EmitEntryPoint(context, module, profile).emit(ProgramModel(ProgramKind.EXECUTABLE, semantics, semantics.declarations.functions.single()), functions)
					emitter.verify(module)
					val printed = LLVMPrintModuleToString(module)
					val ir = try { printed.string } finally { LLVMDisposeMessage(printed) }
					val wide = profile.profile == NativeCABI.Profile.WINDOWS_LLP64
					assertContains(ir, "define i32 @${if (wide) "wmain" else "main"}(i32")
					assertContains(ir, "Invalid Unicode in command-line argument")
					if (wide) {
						assertContains(ir, "@WideCharToMultiByte(i32 65001, i32 128")
						assertFalse(ir.contains("@cloth.rt.argument.utf8"))
					} else {
						assertContains(ir, "@cloth.rt.argument.utf8(ptr")
						assertFalse(ir.contains("@WideCharToMultiByte"))
					}
				}
			}
		}
	}

	@Test fun `Unix adapters preserve all UTF-8 boundaries without normalization or replacement`(@TempDir directory: Path) {
		val values = listOf("", "\u0001\u007f", "\u0080\u07ff", "\u0800\ud7ff", "\ue000\uffff", String(Character.toChars(0x10000)), String(Character.toChars(0x10ffff)), "é中😀", "é", "�", "\ufeff")
		for ((index, profile) in unixProfiles.withIndex()) {
			val executable = synthetic(directory, "utf8_valid_$index", sourceFor(values), profile, listOf(values.map { units(it, false) }))
			run(executable)
		}
	}

	private val invalidUtf8 = listOf(
		intArrayOf(0x80), intArrayOf(0xbf), intArrayOf(0xc0, 0x80), intArrayOf(0xc1, 0xbf),
		intArrayOf(0xc2), intArrayOf(0xc2, 0x7f), intArrayOf(0xdf, 0xc0),
		intArrayOf(0xe0, 0x9f, 0xbf), intArrayOf(0xe0, 0xa0), intArrayOf(0xe1, 0x80),
		intArrayOf(0xed, 0xa0, 0x80), intArrayOf(0xed, 0xbf, 0xbf), intArrayOf(0xef, 0xbf),
		intArrayOf(0xf0, 0x8f, 0xbf, 0xbf), intArrayOf(0xf0, 0x90, 0x80),
		intArrayOf(0xf1, 0x80, 0x7f, 0x80), intArrayOf(0xf4, 0x90, 0x80, 0x80),
		intArrayOf(0xf5, 0x80, 0x80, 0x80), intArrayOf(0xf8), intArrayOf(0xff),
		intArrayOf(0x41, 0xf0, 0x90, 0x80, 0x41), intArrayOf(0xc2, 0x80, 0x80)
	)

	@Test fun `Unix adapters reject malformed UTF-8 before both forms of main and stop before later arguments`(@TempDir directory: Path) {
		for ((index, profile) in unixProfiles.withIndex()) for ((form, parameters) in listOf("", "string[] args").withIndex()) {
			// A null later argument would crash if the adapter kept validating after its first error.
			val cases = invalidUtf8.map { listOf(units("valid é中😀", false), it, null) }
			val executable = synthetic(directory, "utf8_invalid_${index}_$form", "fn<int> main($parameters) = 77", profile, cases)
			for (case in cases.indices) run(executable, listOf(case.toString()), 1, "Invalid Unicode in command-line argument 2 (expected UTF-8)")
		}
	}

	@Test fun `Windows rejects unpaired surrogates before both forms of main and stops at first invalid argument`(@TempDir directory: Path) {
		if (!windows) return
		val malformed = listOf(intArrayOf(0xd800), intArrayOf(0xdbff), intArrayOf(0xdc00), intArrayOf(0xdfff), intArrayOf(0xd800, 0x41), intArrayOf(0xd800, 0xd800), intArrayOf(0xdc00, 0xd800), intArrayOf(0xd800, 0xdc00, 0xdc00))
		for ((index, parameters) in listOf("", "string[] args").withIndex()) {
			val cases = malformed.map { listOf(units("valid é中😀", true), it, null) }
			val executable = synthetic(directory, "utf16_invalid_$index", "fn<int> main($parameters) = 77", native, cases)
			for (case in cases.indices) run(executable, listOf(case.toString()), 1, "Invalid Unicode in command-line argument 2 (expected UTF-16)")
		}
	}

	@Test fun `valid scalar boundaries survive Windows UTF-16 conversion`(@TempDir directory: Path) {
		if (!windows) return
		val values = listOf("", "\ud7ff\ue000\uffff", String(Character.toChars(0x10000)), String(Character.toChars(0x10ffff)), "é中😀", "é", "�", "\ufeff", "a\"b\\")
		run(synthetic(directory, "utf16_valid", sourceFor(values), native, listOf(values.map { units(it, true) })))
	}

	@Test fun `converted arguments and copied returned values release all startup storage`(@TempDir directory: Path) {
		val source = """
			fn<string[]> identity(string[] a) = a
			fn<int> main(string[] args) {
			    def copy = identity(args)
			    args[0] = "replacement"
			    if (copy[0] != "é中😀" || args[0] != "replacement" || copy[1] != "") { return 19 }
			    return 0
			}
		"""
		run(synthetic(directory, "cleanup", source, native, listOf(listOf(units("é中😀", windows), intArrayOf())), checkBalance = true))
	}

	@Test fun `Unix startup emits objects for separate native platform verification`() {
		// Retain runnable fixtures for native Linux/macOS checks outside the host
		// JVM. Object emission alone does not establish execution on either OS.
		val architecture = if (native.architecture == NativeCABI.Architecture.X86_64) "x86_64" else "aarch64"
		val directory = Files.createDirectories(Path.of("build", "unicode-startup"))
		val expected = listOf("é中😀", "", "two words", "a\"b\\", "é", "é")
		for ((platform, triple) in listOf("linux" to "$architecture-unknown-linux-gnu", "macos" to "$architecture-apple-darwin")) {
			val profile = NativeCABI.forTarget(triple)
			for ((name, source) in listOf("arguments" to sourceFor(expected), "noargs" to "fn<int> main() = 77", "trap" to "fn<int> main(string[] args) = 77")) {
				val emitter = LLVMEmitter()
				emitter.withFunctions(parse(source)) { context, module, functions, semantics ->
					LLVMSetTarget(module, profile.targetTriple)
					val main = semantics.declarations.functions.single { it.name.lexeme == "main" }
					EmitEntryPoint(context, module, profile).emit(ProgramModel(ProgramKind.EXECUTABLE, semantics, main), functions)
					emitter.verify(module)
					emitter.emitObject(module, directory.resolve("$platform-$name.o"), profile)
				}
			}
		}
	}

	/** Execute real generated startup against exact native code units, bypassing
	 * the JVM's encoding/argument sanitization. Unix profile logic also runs on
	 * Windows (all supported profiles have the same scalar widths here); that is
	 * shared-adapter coverage, not a claim of native execution on another OS.
	 */
	private fun synthetic(directory: Path, name: String, source: String, profile: NativeCABI, cases: List<List<IntArray?>>, checkBalance: Boolean = false): Path {
		val emitter = LLVMEmitter()
		val configuration = ToolchainResolver.native(native, LinkOptions())
		val executable = native.executableFormat.executablePath(directory.resolve(name))
		val objectFile = directory.resolve("$name.o")
		emitter.withFunctions(parse(source)) { context, module, functions, semantics ->
			val selected = semantics.declarations.functions.single { it.name.lexeme == "main" }
			EmitEntryPoint(context, module, profile).emit(ProgramModel(ProgramKind.EXECUTABLE, semantics, selected), functions)
			val startup = LLVMGetNamedFunction(module, if (profile.profile == NativeCABI.Profile.WINDOWS_LLP64) "wmain" else "main")
			LLVMSetValueName2(startup, "test.startup", 12)
			LLVMSetLinkage(startup, LLVMInternalLinkage)
			val builder = LLVMCreateBuilderInContext(context)
			try {
				val pointer = LLVMPointerTypeInContext(context, 0)
				val integer = LLVMInt32TypeInContext(context)
				fun int(value: Int) = LLVMConstInt(integer, value.toLong(), 0)
				fun signature(result: LLVMTypeRef, vararg params: LLVMTypeRef) = PointerPointer<LLVMTypeRef>(params.size.toLong()).use { values ->
					params.forEachIndexed { index, value -> values.put(index.toLong(), value) }; LLVMFunctionType(result, values, params.size, 0)
				}
				fun call(type: LLVMTypeRef, function: LLVMValueRef, vararg params: LLVMValueRef) = PointerPointer<LLVMValueRef>(params.size.toLong()).use { values ->
					params.forEachIndexed { index, value -> values.put(index.toLong(), value) }; LLVMBuildCall2(builder, type, function, values, params.size, "")
				}
				var outstanding: LLVMValueRef? = null
				if (checkBalance) {
					outstanding = LLVMAddGlobal(module, integer, "test.outstanding")
					LLVMSetInitializer(outstanding, int(0))
					for ((name, allocate) in listOf("malloc" to true, "free" to false)) {
						val original = LLVMGetNamedFunction(module, name)
						assertFalse(original == null || original.isNull)
						LLVMSetValueName2(original, "test.$name", (name.length + 5).toLong())
						LLVMSetLinkage(original, LLVMInternalLinkage)
						val type = signature(if (allocate) pointer else LLVMVoidTypeInContext(context), if (allocate) LLVMInt64TypeInContext(context) else pointer)
						val real = LLVMAddFunction(module, name, type)
						LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, original, "entry"))
						val previous = LLVMBuildLoad2(builder, integer, outstanding, "previous")
						LLVMBuildStore(builder, LLVMBuildAdd(builder, previous, int(if (allocate) 1 else -1), "balance"), outstanding)
						val result = call(type, real, LLVMGetParam(original, 0))
						if (allocate) LLVMBuildRet(builder, result) else LLVMBuildRetVoid(builder)
					}
				}
				val mainType = signature(integer, integer, pointer)
				val main = LLVMAddFunction(module, "main", mainType)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, main, "entry"))
				val targets = cases.indices.map { LLVMAppendBasicBlockInContext(context, main, "case_$it") }
				if (cases.size == 1) LLVMBuildBr(builder, targets.single()) else {
					val argument = PointerPointer<LLVMValueRef>(1L).use { values ->
						values.put(0, int(1)); LLVMBuildInBoundsGEP2(builder, pointer, LLVMGetParam(main, 1), values, 1, "case_slot")
					}
					val atoiType = signature(integer, pointer)
					val atoi = LLVMAddFunction(module, "atoi", atoiType)
					val index = call(atoiType, atoi, LLVMBuildLoad2(builder, pointer, argument, "case_text"))
					val switch = LLVMBuildSwitch(builder, index, targets.first(), cases.size)
					targets.forEachIndexed { index, block -> LLVMAddCase(switch, int(index), block) }
				}
				for ((caseIndex, values) in cases.withIndex()) {
					LLVMPositionBuilderAtEnd(builder, targets[caseIndex])
					val element = if (profile.profile == NativeCABI.Profile.WINDOWS_LLP64) LLVMInt16TypeInContext(context) else LLVMInt8TypeInContext(context)
					val argv = LLVMBuildArrayAlloca(builder, pointer, int(values.size + 1), "synthetic_argv")
					val all = listOf<IntArray?>(null) + values // argv[0] is deliberately unreadable and must be excluded.
					for ((index, units) in all.withIndex()) {
						val text = if (units == null) LLVMConstNull(pointer) else {
							val sequence = units + intArrayOf(0)
							val initializer = PointerPointer<LLVMValueRef>(sequence.size.toLong()).use { items ->
								sequence.forEachIndexed { at, value -> items.put(at.toLong(), LLVMConstInt(element, value.toLong(), 0)) }
								LLVMConstArray(element, items, sequence.size)
							}
							LLVMAddGlobal(module, LLVMTypeOf(initializer), "test.units").also { LLVMSetInitializer(it, initializer); LLVMSetGlobalConstant(it, 1); LLVMSetLinkage(it, LLVMPrivateLinkage) }
						}
						val slot = PointerPointer<LLVMValueRef>(1L).use { indices -> indices.put(0, int(index)); LLVMBuildInBoundsGEP2(builder, pointer, argv, indices, 1, "slot") }
						LLVMBuildStore(builder, text, slot)
					}
					val status = call(mainType, startup, int(all.size), argv)
					val result = outstanding?.let { LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, LLVMBuildLoad2(builder, integer, it, "remaining"), int(0), "balanced"), status, int(99), "checked_status") } ?: status
					LLVMBuildRet(builder, result)
				}
				emitter.verify(module)
				emitter.emitObject(module, objectFile)
			} finally { LLVMDisposeBuilder(builder) }
		}
		try { NativeLinker.link(objectFile, executable, configuration) } finally { Files.deleteIfExists(objectFile) }
		return executable
	}
}
