package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.api.SourceModuleRole
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeLinker
import cloth.emitter.ToolchainResolver
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
import cloth.settings.LinkOptions
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import cloth.toolchain.StandardLibrary
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileIOTest {

	@Test
	fun `read only files own one cursor and return independent counted bytes`(@TempDir directory: Path) {
		val input = Files.write(directory.resolve("二进制 bytes.bin"), byteArrayOf(0, 13, 10, 26, 0xFF.toByte()))
		val missing = directory.resolve("missing.bin")
		val source = Files.writeString(directory.resolve("FileIO.co"), """
			using cloth.io.File: File, FileError
			using cloth.io.Path: pathOf
			using cloth.text.Utf8: decodeUtf8

			fn<isize> main(string[] args) {
			    def path = pathOf(args[0])
			    def file = File::new(path)
			    path.spelling = args[1]
			    def<unsigned int<8>[]?, FileError?> _, unopened = file.read(0)
			    if (unopened != FileError.NotOpen) return 1
			    if (file.open() != null) return 2
			    if (file.open() != FileError.AlreadyOpen) return 3
			    def<unsigned int<8>[]?, FileError?> empty, zeroError = file.read(0)
			    if (empty == null) return 4
			    if (zeroError != null || empty::size != 0) return 5
			    def<unsigned int<8>[]?, FileError?> first, firstError = file.read(2)
			    if (first == null) return 6
			    if (firstError != null || first::size != 2) return 7
			    def alias = file
			    def<unsigned int<8>[]?, FileError?> rest, restError = alias.read(32)
			    if (rest == null) return 8
			    if (restError != null || rest::size != 3) return 9
			    def<unsigned int<8>[]?, FileError?> eof, eofError = file.read(1)
			    if (eof != null || eofError != null) return 10
			    file::delete
			    if (first[0] != 0 || first[1] != 13) return 11
			    if (rest[0] != 10 || rest[1] != 26 || rest[2] != 255) return 12
			    if (decodeUtf8(rest) != null) return 13
			    if (decodeUtf8(empty) != "") return 14
			    def<unsigned int<8>[]> withNul = [65, 0, 66]
			    if (decodeUtf8(withNul) != "A\0B") return 15

			    def another = File::new(args[0])
			    if (another.open() != null) return 16
			    def<unsigned int<8>[]?, FileError?> head, headError = another.read(1)
			    another::delete
			    if (head == null) return 17
			    if (headError != null || head[0] != 0) return 18
			    def absent = File::new(args[1])
			    if (absent.open() != FileError.NotFound || absent.open() != FileError.NotFound) return 19
			    absent::delete
			    def invalid = File::new(args[0] + "\0ignored")
			    if (invalid.open() != FileError.InvalidPath) return 20
			    invalid::delete
			    return 0
			}
		""".trimIndent())
		for (abi in structRunners()) {
			val request = StandardLibrary.default().attachTo(CompilationRequest(
				source, directory.resolve("file_${abi.layout.sizeBits}"), targetTriple = abi.targetTriple,
			))
			val executable = ClothCompiler.compile(request).artifact
			val process = ProcessBuilder(executable.toString(), input.toString(), missing.toString()).redirectErrorStream(true).start()
			try {
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), abi.targetTriple)
				val actual = process.inputStream.readBytes().decodeToString()
				assertEquals(0, process.exitValue(), actual)
				assertEquals(processCompletion(), actual)
			} finally {
				if (process.isAlive) process.destroyForcibly()
			}
		}
		assertFalse(Files.exists(missing))
	}

	@Test
	fun `partial read errors latch and deletion closes the opened stream once`(@TempDir directory: Path) {
		val input = Files.write(directory.resolve("input.bin"), byteArrayOf(1, 2))
		val source = Files.writeString(directory.resolve("ReadFailure.co"), """
			using cloth.io.File: File, FileError

			fn<isize> main(string[] args) {
			    def file = File::new(args[0])
			    if (file.open() != null) return 1
			    def<unsigned int<8>[]?, FileError?> data, error = file.read(8)
			    if (data != null || error != FileError.ReadFailed) return 2
			    data, error = file.read(8)
			    if (data != null || error != FileError.ReadFailed) return 3
			    def<Object> alias = file
			    alias::delete
			    def unopened = File::new(args[0])
			    unopened::delete
			    return 0
			}
		""".trimIndent())
		for (abi in structRunners()) {
			val request = StandardLibrary.default().attachTo(CompilationRequest(
				source, directory.resolve("failure_${abi.layout.sizeBits}"), targetTriple = abi.targetTriple,
			))
			val graph = SourceGraphLoader.load(request)
			val program = ProgramAnalyzer(abi.layout).analyze(FileBindings.graph(graph))
			val emitter = LLVMEmitter(abi)
			val objectFile = directory.resolve("failure_${abi.layout.sizeBits}.o")
			emitExecutableObject(emitter, emitter.emitExecutable(program), objectFile, ::injectReadFailure)
			val executable = abi.executableFormat.executablePath(request.outputFile)
			val configuration = ToolchainResolver.native(abi, LinkOptions()).let { if (abi.windows) it.withWideEntryPoint() else it }
			NativeLinker.link(objectFile, executable, configuration)
			val process = ProcessBuilder(executable.toString(), input.toString()).redirectErrorStream(true).start()
			try {
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), abi.targetTriple)
				val actual = process.inputStream.readBytes().decodeToString().replace("\r\n", "\n")
				assertEquals(0, process.exitValue(), actual)
				assertEquals("probe read\nprobe close\n" + processCompletion().replace("\r\n", "\n"), actual)
			} finally {
				if (process.isAlive) process.destroyForcibly()
			}
		}
	}

	private fun injectReadFailure(context: LLVMContextRef, module: LLVMModuleRef) {
		val builder = LLVMCreateBuilderInContext(context)
		try {
			val integer = LLVMInt32TypeInContext(context)
			val pointer = LLVMPointerTypeInContext(context, 0)
			val failed = LLVMAddGlobal(module, integer, "cloth.test.read_failed")
			LLVMSetLinkage(failed, LLVMInternalLinkage)
			LLVMSetInitializer(failed, LLVMConstNull(integer))

			val putsType = PointerPointer<LLVMTypeRef>(1L).use { parameters ->
				parameters.put(0, pointer)
				LLVMFunctionType(integer, parameters, 1, 0)
			}
			val existing = LLVMGetNamedFunction(module, "puts")
			val puts = if (existing == null || existing.isNull) LLVMAddFunction(module, "puts", putsType) else existing
			fun marker(message: String) = PointerPointer<LLVMValueRef>(1L).use { argument ->
				argument.put(0, LLVMBuildGlobalStringPtr(builder, message, "probe_message"))
				LLVMBuildCall2(builder, putsType, puts, argument, 1, "")
			}

			fun define(name: String): LLVMValueRef {
				val function = LLVMGetNamedFunction(module, name)
				val probeName = "cloth.test.$name"
				LLVMSetValueName2(function, probeName, probeName.length.toLong())
				LLVMSetLinkage(function, LLVMInternalLinkage)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, function, "probe"))
				return function
			}

			val read = define("fread")
			marker("probe read")
			LLVMBuildStore(builder, LLVMConstInt(LLVMInt8TypeInContext(context), 65, 0), LLVMGetParam(read, 0))
			LLVMBuildStore(builder, LLVMConstInt(integer, 1, 0), failed)
			LLVMBuildRet(builder, LLVMConstInt(LLVMGetReturnType(LLVMGlobalGetValueType(read)), 1, 0))
			define("ferror")
			LLVMBuildRet(builder, LLVMBuildLoad2(builder, integer, failed, "probe_error"))

			val close = define("fclose")
			val closeType = LLVMGlobalGetValueType(close)
			val nativeClose = LLVMAddFunction(module, "fclose", closeType)
			marker("probe close")
			PointerPointer<LLVMValueRef>(1L).use { argument ->
				argument.put(0, LLVMGetParam(close, 0))
				LLVMBuildRet(builder, LLVMBuildCall2(builder, closeType, nativeClose, argument, 1, "close_status"))
			}
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}

	@Test
	fun `compiler bodies require a registered declaration origin and exact signature`(@TempDir directory: Path) {
		val main = Files.writeString(directory.resolve("Main.co"), "using cloth.text.Utf8: decodeUtf8\nfn<int> intrinsic(int n) = n\nfn<int> ordinary() = intrinsic(1)")
		val root = Files.createDirectory(directory.resolve("library"))
		val declaration = Files.createDirectories(root.resolve("cloth/text")).resolve("Utf8.co")
		val valid = """pub fn<string?> decodeUtf8(unsigned int<8>[] bytes) = intrinsic "text.utf8.decode""""
		fun request(role: SourceModuleRole, name: String) = CompilationRequest(
			main, directory.resolve("$name.o"), listOf(SourceModule("Library", root, role)), outputKind = OutputKind.OBJECT,
		)
		for ((name, code, role) in listOf(
			Triple("regular", valid, SourceModuleRole.REGULAR),
			Triple("unknown", valid.replace("text.utf8.decode", "unknown.operation"), SourceModuleRole.STANDARD_LIBRARY),
			Triple("signature", valid.replace("unsigned int<8>[]", "int"), SourceModuleRole.STANDARD_LIBRARY),
			Triple("duplicate", valid + "\n" + valid.replace("decodeUtf8", "other"), SourceModuleRole.STANDARD_LIBRARY),
		)) {
			Files.writeString(declaration, code)
			val request = request(role, name)
			val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request) }
			assertEquals(DiagnosticCode.INVALID_INTRINSIC_BINDING, failure.diagnosticCode, name)
			assertEquals(if (name == "duplicate") 2 else 1, failure.sourceLabels.size, name)
			assertTrue(failure.sourceLabels.all { it.span.source?.name == declaration.toString() }, name)
			assertFalse(Files.exists(request.outputFile))
		}
		Files.writeString(declaration, valid)
		assertTrue(Files.size(ClothCompiler.compile(request(SourceModuleRole.STANDARD_LIBRARY, "selected")).artifact) > 0)
		val misplaced = Files.writeString(root.resolve("cloth/text/Misplaced.co"), valid)
		Files.writeString(main, "using cloth.text.Misplaced: decodeUtf8\nfn<int> ordinary() = 1")
		val wrongOrigin = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request(SourceModuleRole.STANDARD_LIBRARY, "misplaced")) }
		assertEquals(DiagnosticCode.INVALID_INTRINSIC_BINDING, wrongOrigin.diagnosticCode)
		assertEquals(misplaced.toString(), wrongOrigin.sourceLabels.single().span.source?.name)
	}
}
