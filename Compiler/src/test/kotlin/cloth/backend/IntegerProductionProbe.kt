package cloth.backend

import cloth.data.CInt
import cloth.data.CNullable
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.NativeLinker
import cloth.emitter.ToolchainResolver
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.settings.LinkOptions
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.global.LLVM.*
import java.lang.management.ManagementFactory
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Stage 6 measures the integrated compiler in the same isolated JVM harness as
 * Stage 1. Operands cross source function boundaries and retain full nonzero bits.
 */
internal object IntegerProductionProbe {

	fun source(bits: Int): String {
		require(bits in setOf(65535, 65536))
		val signed = CInt(bits).spelling
		val unsigned = CInt(bits, true).spelling
		return """
			fn<$unsigned> mix($unsigned a, $unsigned b) = a * b + a
			fn<$unsigned, $unsigned> divide($unsigned a, $unsigned b) = a / b, a % b
			fn<$unsigned> power($unsigned a, $unsigned b) = a ** b
			fn<$signed> negative($signed a) = a / 3
			fn<string> describe($unsigned?[] values) = values::str
			fn<int> main() {
			    def<$unsigned> input = $unsigned::max - 17
			    def<$unsigned> divisor = input / 65537
			    def product = mix(input, divisor)
			    def quotient, remainder = divide(product, divisor + 65539)
			    def raised = power(input, 3)
			    def<$unsigned?[]> values = [product, null]
			    def visits = 0
			    for (def<$signed> i <- $signed::max..=$signed::max; i *= 2) visits++
			    if ((1.75f -> $signed) != 1) return 1
			    println(product)
			    println(quotient)
			    println(remainder)
			    println(negative($signed::min))
			    println(raised)
			    println(describe(values))
			    println((product -> float<64>) > float<64>::max)
			    println(visits)
			    return 0
			}
		""".trimIndent()
	}

	fun expected(bits: Int): String {
		val modulus = BigInteger.ONE.shiftLeft(bits)
		val input = modulus - 18.toBigInteger()
		val divisor = input / 65537.toBigInteger()
		val product = (input * divisor + input).mod(modulus)
		check(product.bitLength() > bits - 32)
		val denominator = divisor + 65539.toBigInteger()
		check((product % denominator).bitLength() > bits - 32)
		val values = listOf(product.toString(), (product / denominator).toString(), (product % denominator).toString(), (-BigInteger.ONE.shiftLeft(bits - 1) / 3.toBigInteger()).toString(), input.modPow(3.toBigInteger(), modulus).toString(), "[$product, null]", product.toDouble().isInfinite().toString(), "1")
		return values.joinToString("\n", postfix = "\n\nPEC: 0\n")
	}

	fun worker(triple: String, bits: Int, directory: Path, assembly: Boolean) {
		val facts = mutableMapOf<String, Any>("target" to triple, "bits" to bits, "group" to "production")
		val details = linkedMapOf<String, Any>()
		fun phase(name: String) {
			facts["phase"] = name; Files.writeString(directory.resolve("phase.txt"), name)
		}
		try {
			phase("source")
			val abi = NativeCABI.forTarget(triple)
			val emitter = LLVMEmitter(abi)
			val started = System.nanoTime()
			val ast = Parser(Lexer(SourceFile("integer-production.co", source(bits))).scanTokens()).parse()
			val ir = emitter.emitExecutable(ast)
			details["source_ms"] = (System.nanoTime() - started) / 1_000_000
			details["ir_bytes"] = ir.toByteArray(Charsets.UTF_8).size
			check(ir.length < 2_000_000) { "Unexpectedly large production IR" }
			check(!Regex("(?:^|[\\s\\[({,])i(?:6[5-9]|[7-9][0-9]|[0-9]{3,})\\b").containsMatchIn(ir)) { "A wide LLVM scalar escaped production lowering" }
			check(ir.contains("cloth.integer.multiply") && ir.contains("cloth.integer.divrem") && ir.contains("cloth.integer.checked") && ir.contains("cloth.integer.decimal"))
			Files.writeString(directory.resolve("production.co"), source(bits))
			Files.writeString(directory.resolve("production.ll"), ir)
			val context = LLVMContextCreate()
			try {
				fun parse(): LLVMModuleRef {
					val module = LLVMModuleRef()
					val error = BytePointer(null as Pointer?)
					try {
						check(LLVMParseIRInContext(context, LLVMCreateMemoryBufferWithMemoryRangeCopy(ir, ir.toByteArray(Charsets.UTF_8).size.toLong(), "production"), module, error) == 0) { error.string }
					} finally {
						if (!error.isNull) LLVMDisposeMessage(error)
					}
					emitter.verify(module)
					return module
				}

				val module = parse()
				try {
					val storage = CInt(bits).llvmStorage(context, abi.layout)
					val layout = LLVMGetModuleDataLayout(module)
					facts["store_bytes"] = LLVMStoreSizeOfType(layout, storage)
					facts["alloc_bytes"] = LLVMABISizeOfType(layout, storage)
					facts["alignment"] = LLVMABIAlignmentOfType(layout, storage)
					facts["nullable_bytes"] = LLVMABISizeOfType(layout, CNullable(CInt(bits)).llvmStorage(context, abi.layout))
					phase("object")
					val objectFile = directory.resolve("production.o")
					val codegen = System.nanoTime()
					emitter.emitObject(module, objectFile)
					facts["codegen_ms"] = (System.nanoTime() - codegen) / 1_000_000
					facts["object_bytes"] = Files.size(objectFile)
					check(Files.size(objectFile) < 200_000) { "Unexpectedly large production object" }
					phase("symbols")
					val symbols = IntegerBackendProbe.symbols(objectFile)
					Files.writeString(directory.resolve("symbols.txt"), symbols.joinToString("\n"))
					facts["helpers"] = symbols.filter { it.startsWith("__") && !it.startsWith("__xmm@") && !it.startsWith("__real@") || it.contains("chkstk") }.joinToString(",")
					check(symbols.none { it in setOf("__multi3", "__divti3", "__udivti3", "__modti3", "__umodti3", "__floattidf", "__floatuntidf") }) { "Missing-runtime wide integer dependency" }
					if (assembly) {
						phase("assembly")
						val copy = parse()
						try {
							emitter.emitAssembly(copy, directory.resolve("production.s"))
						} finally {
							LLVMDisposeModule(copy)
						}
					}
					facts["status"] = "OBJECT_PASS"
					val expected = expected(bits)
					details["expected_output_bytes"] = expected.toByteArray(Charsets.UTF_8).size
					details["expected_sha256"] = MessageDigest.getInstance("SHA-256").digest(expected.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
					val host = NativeCABI.native()
					if (triple == host.targetTriple || host.windows && host.architecture == NativeCABI.Architecture.X86_64 && abi.windows && abi.architecture == NativeCABI.Architecture.X86) {
						phase("link")
						val executable = abi.executableFormat.executablePath(directory.resolve("production"))
						NativeLinker.link(objectFile, executable, ToolchainResolver.native(abi, LinkOptions()))
						phase("run")
						val run = System.nanoTime()
						val log = directory.resolve("native.log")
						val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
						try {
							check(process.waitFor(30, TimeUnit.SECONDS)) { "Production program timed out" }
							check(process.exitValue() == 0) { "Native status ${process.exitValue()}" }
							check(Files.readString(log).replace("\r\n", "\n") == expected) { "Full-width BigInteger output mismatch" }
						} finally {
							if (process.isAlive) process.destroyForcibly()
						}
						details["run_ms"] = (System.nanoTime() - run) / 1_000_000
						facts["status"] = "NATIVE_PASS"
					}
				} finally {
					LLVMDisposeModule(module)
				}
			} finally {
				LLVMContextDispose(context)
			}
		} catch (failure: Exception) {
			facts["status"] = "FAIL"
			details["failure"] = failure.message ?: failure.javaClass.name
			if (failure is ClothDiagnostic) failure.guidance?.let { Files.writeString(directory.resolve("diagnostic.txt"), it) }
			failure.printStackTrace()
		} finally {
			facts["jvm_peak_heap_bytes"] = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }.sumOf { it.peakUsage.used }
			facts["detail"] = details.entries.joinToString(";") { "${it.key}=${it.value}" }
			Files.writeString(directory.resolve("result.tsv"), IntegerBackendProbe.columns.joinToString("\t") { facts[it]?.toString()?.replace('\t', ' ')?.replace('\r', ' ')?.replace('\n', ' ') ?: "" } + "\n")
		}
	}

	fun launch(triple: String, bits: Int, directory: Path, timeout: Long, assembly: Boolean, classpath: String) = IntegerBackendProbe.launch(triple, bits, "production", directory, timeout, assembly, classpath, "cloth.backend.IntegerProductionProbeKt")
}

fun main(args: Array<String>) {
	if (args.firstOrNull() == "--child") {
		IntegerProductionProbe.worker(args[1], args[2].toInt(), Path.of(args[4]), args[5].toBooleanStrict())
		return
	}
	val options = args.associate { it.removePrefix("--").split('=', limit = 2).let { parts -> require(parts.size == 2); parts[0] to parts[1] } }
	require(options.keys.all { it in setOf("widths", "targets", "output", "timeout", "workers", "assembly") })
	val widths = options["widths"]?.split(',')?.map(String::toInt) ?: listOf(65535, 65536)
	val triples = options["targets"]?.split(',') ?: IntegerProbeIR.triples
	require(widths.all { it in setOf(65535, 65536) } && triples.all { it in IntegerProbeIR.triples })
	val root = Path.of("tmp").toAbsolutePath().normalize()
	val directory = Path.of(options["output"] ?: "tmp/integer-production-stage6").toAbsolutePath().normalize()
	require(directory.startsWith(root) && directory != root) { "Probe output must stay in workspace tmp/" }
	Files.createDirectories(directory)
	val report = directory.resolve("results.tsv")
	Files.writeString(report, IntegerBackendProbe.columns.joinToString("\t") + "\n")
	val timeout = options["timeout"]?.toLong() ?: 60L
	val workers = options["workers"]?.toInt() ?: 2
	require(timeout in 1..120 && workers in 1..4)
	val executor = Executors.newFixedThreadPool(workers)
	try {
		val futures = triples.flatMap { triple ->
			widths.map { bits ->
				executor.submit<String> {
					val row = IntegerProductionProbe.launch(triple, bits, directory.resolve("$triple/$bits"), timeout, options["assembly"]?.toBooleanStrict() ?: true, System.getProperty("java.class.path"))
					synchronized(IntegerProductionProbe) {
						Files.writeString(report, row + "\n", java.nio.file.StandardOpenOption.APPEND)
						println(row.split('\t').take(5).joinToString(" "))
					}
					row
				}
			}
		}
		val rows = futures.map { it.get() }
		check(rows.all { it.split('\t')[3] in setOf("NATIVE_PASS", "OBJECT_PASS") }) { "Production probe failed; inspect $report" }
		println("Report: $report (${rows.size} production cases)")
	} finally {
		executor.shutdownNow()
	}
}
