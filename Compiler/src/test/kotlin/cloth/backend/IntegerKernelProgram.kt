package cloth.backend

import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.NativeLinker
import cloth.emitter.ToolchainResolver
import cloth.emitter.types.EmitWideInteger
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.settings.LinkOptions
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests production operation helpers without prematurely enabling Stage 4 source carriers. */
internal class IntegerKernelProgram(val abi: NativeCABI, private val context: LLVMContextRef, private val module: LLVMModuleRef) {

	private val signature = LLVMFunctionType(LLVMInt32TypeInContext(context), null as org.bytedeco.javacpp.PointerPointer<*>?, 0, 0)
	private val cases = mutableListOf<LLVMValueRef>()
	private val labels = mutableMapOf<Int, String>()
	private var serial = 0

	class Checks(val context: LLVMContextRef, val builder: LLVMBuilderRef, val integers: EmitWideInteger, private val record: (LLVMValueRef, String) -> Unit) {

		fun int(value: Int) = LLVMConstInt(LLVMInt32TypeInContext(context), value.toLong(), 1)
		fun check(condition: LLVMValueRef, label: String) = record(condition, label)
		fun integer(actual: LLVMValueRef, expected: java.math.BigInteger, bits: Int, label: String) {
			check(LLVMBuildICmp(builder, LLVMIntEQ, integers.comparison(actual, integers.constant(expected, bits), bits, false), int(0), "matches_integer"), label)
		}

		fun status(actual: LLVMValueRef, expected: Int, label: String) = check(LLVMBuildICmp(builder, LLVMIntEQ, actual, int(expected), "matches_status"), label)
		fun flag(actual: LLVMValueRef, expected: Boolean, label: String) = check(if (expected) actual else LLVMBuildNot(builder, actual, "matches_flag"), label)
	}

	fun case(name: String, action: Checks.() -> Unit) {
		val function = LLVMAddFunction(module, "integer.case.$name", signature)
		LLVMSetLinkage(function, LLVMInternalLinkage)
		val builder = LLVMCreateBuilderInContext(context)
		try {
			LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, function, "entry"))
			val type = LLVMInt32TypeInContext(context)
			var status = LLVMConstInt(type, 0, 0)
			Checks(context, builder, EmitWideInteger(context, builder, abi)) { condition, label ->
				val id = ++serial
				labels[id] = "$name: $label"
				val first = LLVMBuildICmp(builder, LLVMIntEQ, status, LLVMConstInt(type, 0, 0), "no_failure")
				val failed = LLVMBuildAnd(builder, first, LLVMBuildNot(builder, condition, "failed"), "first_failure")
				status = LLVMBuildSelect(builder, failed, LLVMConstInt(type, id.toLong(), 0), status, "status")
			}.action()
			LLVMBuildRet(builder, status)
			cases.add(function)
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}

	private fun finish() {
		val main = LLVMAddFunction(module, "main", signature)
		val builder = LLVMCreateBuilderInContext(context)
		try {
			LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, main, "entry"))
			val type = LLVMInt32TypeInContext(context)
			var status = LLVMConstInt(type, 0, 0)
			for (function in cases) {
				val next = LLVMBuildCall2(builder, signature, function, null as org.bytedeco.javacpp.PointerPointer<*>?, 0, "case_status")
				status = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, status, LLVMConstInt(type, 0, 0), "no_previous_failure"), next, status, "status")
			}
			LLVMBuildRet(builder, status)
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}

	companion object {

		val triples = IntegerProbeIR.triples
		fun targets(): List<NativeCABI> {
			val host = NativeCABI.native()
			return if (host.windows && host.architecture == NativeCABI.Architecture.X86_64) listOf(host, NativeCABI.forTarget("i686-pc-windows-msvc")) else listOf(host)
		}

		fun run(abi: NativeCABI, directory: Path, name: String, objectsOnly: Boolean = false, expectedFailure: String? = null, action: IntegerKernelProgram.() -> Unit): String {
			Files.createDirectories(directory)
			val emitter = LLVMEmitter(abi)
			val ast = Parser(Lexer("fn fixture() {}").scanTokens()).parse()
			return emitter.withFunctions(ast) { context, module, _, _ ->
				val program = IntegerKernelProgram(abi, context, module)
				program.action()
				program.finish()
				emitter.verify(module)
				val printed = LLVMPrintModuleToString(module)
				val ir = try {
					printed.string
				} finally {
					LLVMDisposeMessage(printed)
				}
				Files.writeString(directory.resolve("$name.ll"), ir)
				val objectFile = directory.resolve("$name.o")
				emitter.emitObject(module, objectFile, abi)
				assertTrue(Files.size(objectFile) > 0)
				if (!objectsOnly) {
					val executable = abi.executableFormat.executablePath(directory.resolve(name))
					NativeLinker.link(objectFile, executable, ToolchainResolver.native(abi, LinkOptions()))
					val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("$name.log").toFile()).start()
					try {
						assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Integer helper program timed out")
						val status = process.exitValue()
						val output = Files.readString(directory.resolve("$name.log"))
						assertEquals(if (expectedFailure == null) 0 else 1, status, "Integer assertion $status: ${program.labels[status]}\n$output")
						if (expectedFailure != null) {
							assertContains(output, expectedFailure)
							assertContains(output, "wide-guards.co:3:7")
						}
					} finally {
						if (process.isAlive) process.destroyForcibly()
					}
				}
				ir
			}
		}

		fun scratch(action: (Path) -> Unit) {
			val root = Path.of("tmp").toAbsolutePath().normalize()
			Files.createDirectories(root)
			val directory = Files.createTempDirectory(root, "integer-stage3-")
			try {
				action(directory)
			} finally {
				check(directory.startsWith(root) && directory != root)
				Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
			}
		}
	}
}
