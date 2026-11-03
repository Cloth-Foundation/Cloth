package cloth.backend

import cloth.diagnostics.ClothDiagnostic
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.NativeLinker
import cloth.emitter.ToolchainResolver
import cloth.settings.LinkOptions
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.LLVM.LLVMTargetRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM.*
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in experiments: LLVM fatal errors must never terminate Gradle's test worker. */
internal object IntegerBackendProbe {

	val columns = listOf("target", "bits", "group", "status", "phase", "object_bytes", "codegen_ms", "store_bytes", "alloc_bytes", "alignment", "nullable_bytes", "jvm_peak_heap_bytes", "helpers", "detail")
	private fun clean(value: Any?) = value.toString().replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').take(1000)

	fun worker(triple: String, bits: Int, group: String, directory: Path, assembly: Boolean) {
		val facts = mutableMapOf<String, Any>("target" to triple, "bits" to bits, "group" to group)
		fun phase(name: String) {
			facts["phase"] = name; Files.writeString(directory.resolve("phase.txt"), name)
		}
		try {
			phase("initialize")
			val abi = NativeCABI.forTarget(triple)
			LLVMEmitter(abi) // Initialize exactly the architectures available to the production emitter.
			val major = IntArray(1);
			val minor = IntArray(1);
			val patch = IntArray(1)
			LLVMGetVersion(major, minor, patch)
			Files.writeString(directory.resolve("version.txt"), "${major[0]}.${minor[0]}.${patch[0]}\n")
			val context = LLVMContextCreate()
			try {
				val target = LLVMTargetRef()
				BytePointer(triple).use { spelling -> checked { LLVMGetTargetFromTriple(spelling, target, it) } }
				val machine = LLVMCreateTargetMachine(target, triple, "generic", abi.targetFeatures, LLVMCodeGenLevelDefault, LLVMRelocPIC, LLVMCodeModelDefault)
				check(machine != null && !machine.isNull) { "Unable to create target machine" }
				try {
					val layout = LLVMCreateTargetDataLayout(machine)
					try {
						val integer = if (group == "limbs") LLVMArrayType2(LLVMIntTypeInContext(context, abi.pointerBits), ((bits + abi.pointerBits - 1) / abi.pointerBits).toLong()) else LLVMIntTypeInContext(context, bits)
						facts["store_bytes"] = LLVMStoreSizeOfType(layout, integer)
						facts["alloc_bytes"] = LLVMABISizeOfType(layout, integer)
						facts["alignment"] = LLVMABIAlignmentOfType(layout, integer)
						PointerPointer<LLVMTypeRef>(2L).use { fields ->
							fields.put(0, LLVMInt8TypeInContext(context)); fields.put(1, integer)
							facts["nullable_bytes"] = LLVMABISizeOfType(layout, LLVMStructTypeInContext(context, fields, 2, 0))
						}
						phase("parse")
						Files.writeString(directory.resolve("layout.tsv"), columns.joinToString("\t") { clean(facts[it] ?: "") } + "\n")
						val ir = IntegerProbeIR.source(bits, group, abi.pointerBits)
						Files.writeString(directory.resolve("input.ll"), ir)
						val module = LLVMModuleRef()
						// ParseIRInContext consumes this memory buffer (including on a parse error).
						val buffer = LLVMCreateMemoryBufferWithMemoryRangeCopy(ir, ir.toByteArray(Charsets.UTF_8).size.toLong(), "integer-probe")
						checked { LLVMParseIRInContext(context, buffer, module, it) }
						try {
							LLVMSetTarget(module, triple)
							LLVMSetModuleDataLayout(module, layout)
							checked { LLVMVerifyModule(module, LLVMReturnStatusAction, it) }
							phase("object")
							val objectFile = directory.resolve("probe.o")
							val started = System.nanoTime()
							checked { LLVMTargetMachineEmitToFile(machine, module, objectFile.toString(), LLVMObjectFile, it) }
							facts["codegen_ms"] = (System.nanoTime() - started) / 1_000_000
							facts["object_bytes"] = Files.size(objectFile)
							phase("symbols")
							val symbols = symbols(objectFile)
							Files.writeString(directory.resolve("symbols.txt"), symbols.joinToString("\n"))
							// Symbol iterators include definitions and constant pools, not just undefined references.
							facts["helpers"] = symbols.filter { (it.startsWith("__") && !it.startsWith("__xmm@") && !it.startsWith("__real@")) || it.contains("chkstk") }.joinToString(",")
							if (assembly) {
								phase("assembly")
								// Object codegen mutates its input; use a fresh parsed module for assembly.
								val copy = LLVMModuleRef()
								checked { LLVMParseIRInContext(context, LLVMCreateMemoryBufferWithMemoryRangeCopy(ir, ir.toByteArray().size.toLong(), "assembly-probe"), copy, it) }
								try {
									LLVMSetTarget(copy, triple); LLVMSetModuleDataLayout(copy, layout)
									checked { LLVMTargetMachineEmitToFile(machine, copy, directory.resolve("probe.s").toString(), LLVMAssemblyFile, it) }
								} finally {
									LLVMDisposeModule(copy)
								}
							}
							facts["status"] = "OBJECT_PASS"
							val host = NativeCABI.native()
							val runnable = triple == host.targetTriple || host.windows && host.architecture == NativeCABI.Architecture.X86_64 && abi.windows && abi.architecture == NativeCABI.Architecture.X86
							if (runnable) {
								phase("link")
								val executable = abi.executableFormat.executablePath(directory.resolve("probe"))
								NativeLinker.link(objectFile, executable, ToolchainResolver.native(abi, LinkOptions()))
								phase("run")
								val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("native.log").toFile()).start()
								try {
									check(process.waitFor(10, TimeUnit.SECONDS)) { "Native program timed out" }
									check(process.exitValue() == 0) { "Native oracle mismatch/status ${process.exitValue()}" }
								} finally {
									if (process.isAlive) process.destroyForcibly()
								}
								facts["status"] = "NATIVE_PASS"
							}
						} finally {
							LLVMDisposeModule(module)
						}
					} finally {
						LLVMDisposeTargetData(layout)
					}
				} finally {
					LLVMDisposeTargetMachine(machine)
				}
			} finally {
				LLVMContextDispose(context)
			}
		} catch (failure: Exception) {
			facts["status"] = "FAIL"
			facts["detail"] = failure.message ?: failure.javaClass.name
			if (failure is ClothDiagnostic) failure.guidance?.let { Files.writeString(directory.resolve("diagnostic.txt"), it); System.err.println(it) }
			failure.printStackTrace()
		} finally {
			facts["jvm_peak_heap_bytes"] = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }.sumOf { it.peakUsage.used }
			Files.writeString(directory.resolve("result.tsv"), columns.joinToString("\t") { clean(facts[it] ?: "") } + "\n")
		}
	}

	private fun checked(action: (BytePointer) -> Int) {
		val error = BytePointer(null as Pointer?)
		try {
			check(action(error) == 0) { if (error.isNull) "LLVM operation failed" else error.string }
		} finally {
			if (!error.isNull) LLVMDisposeMessage(error)
		}
	}

	fun symbols(path: Path): List<String> {
		val bytes = Files.readAllBytes(path)
		val buffer = BytePointer(*bytes).use { LLVMCreateMemoryBufferWithMemoryRangeCopy(it, bytes.size.toLong(), BytePointer("probe.o")) }
		val objectFile = LLVMCreateObjectFile(buffer)
		check(objectFile != null && !objectFile.isNull)
		try {
			val iterator = LLVMGetSymbols(objectFile)
			try {
				return buildList {
					while (LLVMIsSymbolIteratorAtEnd(objectFile, iterator) == 0) {
						add(LLVMGetSymbolName(iterator).string)
						LLVMMoveToNextSymbol(iterator)
					}
				}.distinct().sorted()
			} finally {
				LLVMDisposeSymbolIterator(iterator)
			}
		} finally {
			LLVMDisposeObjectFile(objectFile)
		}
	}

	/** The supervisor never loads native LLVM, so a failed case cannot kill other cases. */
	fun launch(triple: String, bits: Int, group: String, directory: Path, timeout: Long, assembly: Boolean, classpath: String, workerClass: String = "cloth.backend.IntegerBackendProbeKt"): String {
		Files.createDirectories(directory)
		listOf("result.tsv", "phase.txt", "layout.tsv").forEach { Files.deleteIfExists(directory.resolve(it)) }
		val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
		fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
		val arguments = listOf("--enable-native-access=ALL-UNNAMED", "-Xmx512m", "-XX:ErrorFile=${directory.resolve("hs_err_pid%p.log")}", "-cp", classpath, workerClass, "--child", triple, bits.toString(), group, directory.toString(), assembly.toString())
		val argumentFile = directory.resolve("java.args")
		Files.writeString(argumentFile, arguments.joinToString("\n", transform = ::quoted))
		val process = ProcessBuilder(java.toString(), "@$argumentFile").directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("worker.log").toFile()).start()
		val completed = try {
			process.waitFor(timeout, TimeUnit.SECONDS)
		} finally {
			if (process.isAlive) {
				process.descendants().use { descendants -> descendants.toList().asReversed().forEach { it.destroyForcibly() } }
				process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
			}
		}
		val result = directory.resolve("result.tsv")
		if (completed && process.exitValue() == 0 && Files.isRegularFile(result)) return Files.readString(result).trimEnd('\r', '\n')
		val phase = directory.resolve("phase.txt").let { if (Files.exists(it)) Files.readString(it) else "startup" }
		val status = if (!completed) "TIMEOUT" else "CRASH"
		val layout = directory.resolve("layout.tsv")
		val row = if (Files.isRegularFile(layout)) Files.readString(layout).trimEnd('\r', '\n').split('\t').toMutableList() else MutableList(columns.size) { "" }
		row[0] = triple; row[1] = bits.toString(); row[2] = group; row[3] = status; row[4] = phase
		row[13] = if (completed) "exit ${process.exitValue()}" else "${timeout}s"
		return row.joinToString("\t")
	}
}

fun main(args: Array<String>) {
	if (args.firstOrNull() == "--child") {
		IntegerBackendProbe.worker(args[1], args[2].toInt(), args[3], Path.of(args[4]), args[5].toBooleanStrict())
		return
	}
	val options = args.associate { it.removePrefix("--").split('=', limit = 2).let { parts -> require(parts.size == 2); parts[0] to parts[1] } }
	require(options.keys.all { it in setOf("widths", "targets", "groups", "output", "timeout", "workers", "assembly") })
	val widths = options["widths"]?.split(',')?.map(String::toInt) ?: IntegerProbeIR.widths
	val triples = options["targets"]?.split(',') ?: IntegerProbeIR.triples
	val groups = options["groups"]?.split(',') ?: IntegerProbeIR.groups.filter { it != "fp-mixed" }
	require(widths.all { it in 1..131072 } && triples.all { it in IntegerProbeIR.triples } && groups.all { it in IntegerProbeIR.groups })
	val root = Path.of("tmp").toAbsolutePath().normalize()
	val directory = Path.of(options["output"] ?: "tmp/integer-backend-stage1").toAbsolutePath().normalize()
	require(directory.startsWith(root) && directory != root) { "Probe output must be inside workspace tmp/" }
	Files.createDirectories(directory)
	Files.writeString(directory.resolve("settings.txt"), "java=${System.getProperty("java.version")}\nwidths=${widths.joinToString(",")}\ntargets=${triples.joinToString(",")}\ngroups=${groups.joinToString(",")}\n")
	val timeout = options["timeout"]?.toLong() ?: 15L
	val workers = options["workers"]?.toInt() ?: 2
	require(timeout in 1..120 && workers in 1..4)
	val assembly = options["assembly"]?.toBooleanStrict() ?: false
	val report = directory.resolve("results.tsv")
	Files.writeString(report, IntegerBackendProbe.columns.joinToString("\t") + "\n")
	val executor = Executors.newFixedThreadPool(workers)
	try {
		val cases = triples.flatMap { triple -> widths.flatMap { bits -> groups.map { group -> Triple(triple, bits, group) } } }
		val futures = cases.map { (triple, bits, group) ->
			executor.submit<String> {
				val row = IntegerBackendProbe.launch(triple, bits, group, directory.resolve("$triple/$bits/$group"), timeout, assembly, System.getProperty("java.class.path"))
				synchronized(IntegerBackendProbe) {
					Files.writeString(report, row + "\n", java.nio.file.StandardOpenOption.APPEND)
					println(row.split('\t').take(5).joinToString(" "))
				}
				row
			}
		}
		futures.forEach { it.get() }
		println("Report: $report (${cases.size} isolated cases)")
	} finally {
		executor.shutdownNow()
	}
}
