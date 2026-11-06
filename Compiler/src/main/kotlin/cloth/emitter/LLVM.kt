package cloth.emitter

import cloth.ast.SourceUnit
import cloth.diagnostics.BackendErrors
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.InternalErrors
import cloth.emitter.callables.EmitEntryPoint
import cloth.emitter.callables.EmitFunctions
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.ProgramKind
import cloth.semantic.ProgramModel
import cloth.semantic.typing.FunctionAnalyzer
import cloth.semantic.typing.FunctionSemanticModel
import cloth.settings.LinkOptions
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import java.nio.file.Files
import java.nio.file.Path

class LLVMEmitter internal constructor(private val abi: NativeCABI, private val linkOptions: LinkOptions = LinkOptions()) {

	constructor(linkOptions: LinkOptions = LinkOptions()) : this(NativeCABI.native(), linkOptions)

	private val linker by lazy { ToolchainResolver.native(abi, linkOptions) }

	fun compile(unit: SourceUnit, output: Path): Path = compile(ProgramAnalyzer(abi.layout).analyze(unit), output)

	internal fun compile(program: ProgramModel, output: Path): Path {
		if (program.entryPoint == null) throw InternalErrors.invariant("Executable emission requires a validated entry point")
		validate(program.functions)
		val configuration = if (abi.windows) linker.withWideEntryPoint() else linker
		val executable = abi.executableFormat.executablePath(output).toAbsolutePath().normalize()
		Files.createDirectories(executable.parent)
		val objectFile = Files.createTempFile("cloth-", ".o")
		try {
			withFunctions(program.functions) { context, module, functions, _ ->
				EmitEntryPoint(context, module, abi).emit(program, functions)
				verify(module)
				emitObject(module, objectFile)
			}
			NativeLinker.link(objectFile, executable, configuration)
			return executable
		} finally {
			Files.deleteIfExists(objectFile)
		}
	}

	fun emitExecutable(unit: SourceUnit): String {
		val program = ProgramAnalyzer(abi.layout).analyze(unit)
		return withFunctions(program.functions) { context, module, functions, _ ->
			EmitEntryPoint(context, module, abi).emit(program, functions)
			verify(module)
			val ir = LLVMPrintModuleToString(module)
			try {
				ir.string
			} finally {
				LLVMDisposeMessage(ir)
			}
		}
	}

	/** Declaration module only; use emitExecutable to include program startup. */
	fun emit(unit: SourceUnit): String = withFunctions(unit) { _, module, _, _ ->
		val ir = LLVMPrintModuleToString(module)
		try {
			ir.string
		} finally {
			LLVMDisposeMessage(ir)
		}
	}

	fun compileObject(unit: SourceUnit, output: Path): Path = compileObject(ProgramAnalyzer(abi.layout).analyze(unit, ProgramKind.LIBRARY).functions, output)

	internal fun compileObject(semantics: FunctionSemanticModel, output: Path): Path = withFunctions(semantics) { _, module, _, _ ->
		val target = output.toAbsolutePath().normalize()
		Files.createDirectories(target.parent)
		emitObject(module, target)
		target
	}

	/** Module composition hook used by native tests and entry-point emission. */
	internal fun <T> withFunctions(unit: SourceUnit, action: (LLVMContextRef, LLVMModuleRef, EmitFunctions, FunctionSemanticModel) -> T): T = withFunctions(FunctionAnalyzer(abi.layout).analyze(unit), action)

	private fun <T> withFunctions(semantics: FunctionSemanticModel, action: (LLVMContextRef, LLVMModuleRef, EmitFunctions, FunctionSemanticModel) -> T): T {
		validate(semantics)
		val targetABI = abi
		val context = LLVMContextCreate()
		val module = LLVMModuleCreateWithNameInContext("cloth", context)
		try {
			configureModule(module, targetABI)
			val functions = EmitFunctions(context, module, targetABI, semantics)
			functions.emitAll()
			verify(module)
			return action(context, module, functions, semantics)
		} finally {
			LLVMDisposeModule(module)
			LLVMContextDispose(context)
		}
	}

	private fun validate(semantics: FunctionSemanticModel) {
		if (semantics.target != abi.layout) throw InternalErrors.invariant("Semantic and emission targets differ")
		EmitFunctions.validate(semantics)
	}

	internal fun verify(module: LLVMModuleRef) = checkLLVM(BackendErrors::invalidIR) { LLVMVerifyModule(module, LLVMReturnStatusAction, it) }

	/** A module's layout is installed before functions or runtime helpers are emitted. */
	private fun configureModule(module: LLVMModuleRef, targetABI: NativeCABI) = withTargetMachine(targetABI) { machine ->
		configureModule(module, targetABI, machine)
	}

	private fun configureModule(module: LLVMModuleRef, targetABI: NativeCABI, machine: LLVMTargetMachineRef) {
		val selected = targetABI.layout
		val existingTarget = LLVMGetTarget(module).string
		if (existingTarget.isNotEmpty() && existingTarget != selected.targetTriple) {
			throw InternalErrors.invariant("Cannot change an emitted module's compilation target")
		}
		val layout = LLVMCreateTargetDataLayout(machine)
		try {
			val pointer = LLVMPointerTypeInContext(LLVMGetModuleContext(module), 0)
			if (LLVMSizeOfTypeInBits(layout, pointer) != selected.pointerBits.toLong() || LLVMABIAlignmentOfType(layout, pointer) * 8 != selected.pointerAlignmentBits || (LLVMByteOrder(layout) == LLVMLittleEndian) != selected.littleEndian) {
				throw BackendErrors.layout(targetABI.profile.name, selected.targetTriple)
			}
			val existingLayout = LLVMGetDataLayoutStr(module).string
			val expectedLayout = LLVMCopyStringRepOfTargetData(layout)
			try {
				if (existingLayout.isNotEmpty() && existingLayout != expectedLayout.string) {
					throw InternalErrors.invariant("Cannot change an emitted module's data layout")
				}
			} finally {
				LLVMDisposeMessage(expectedLayout)
			}
			LLVMSetTarget(module, selected.targetTriple)
			LLVMSetModuleDataLayout(module, layout)
		} finally {
			LLVMDisposeTargetData(layout)
		}
	}

	internal fun emitObject(module: LLVMModuleRef, output: Path, targetABI: NativeCABI = abi) = withTargetMachine(targetABI) { machine ->
		configureModule(module, targetABI, machine)
		checkLLVM(BackendErrors::objectCode) { error ->
			LLVMTargetMachineEmitToFile(machine, module, output.toString(), LLVMObjectFile, error)
		}
	}

	/** Uses the same target-machine configuration as object emission for inspection. */
	internal fun emitAssembly(module: LLVMModuleRef, output: Path) = withTargetMachine(abi) { machine ->
		configureModule(module, abi, machine)
		checkLLVM(BackendErrors::objectCode) { error ->
			LLVMTargetMachineEmitToFile(machine, module, output.toString(), LLVMAssemblyFile, error)
		}
	}

	private fun <T> withTargetMachine(targetABI: NativeCABI, action: (LLVMTargetMachineRef) -> T): T {
		val target = LLVMTargetRef()
		BytePointer(targetABI.targetTriple).use { triple ->
			checkLLVM(BackendErrors::target) { error ->
				LLVMGetTargetFromTriple(triple, target, error)
			}
		}
		val machine = LLVMCreateTargetMachine(target, targetABI.targetTriple, "generic", targetABI.targetFeatures, LLVMCodeGenLevelDefault, LLVMRelocPIC, LLVMCodeModelDefault)
		if (machine == null || machine.isNull) throw BackendErrors.machine()

		return try {
			action(machine)
		} finally {
			LLVMDisposeTargetMachine(machine)
		}
	}

	private fun checkLLVM(diagnostic: (String) -> ClothDiagnostic, action: (BytePointer) -> Int) {
		val error = BytePointer(null as Pointer?)
		try {
			if (action(error) != 0) throw diagnostic(if (error.isNull) "Unknown LLVM error" else error.string)
		} finally {
			if (!error.isNull) LLVMDisposeMessage(error)
		}
	}

	companion object { init {
		// Both supported architectures need layout queries even when emitting IR on another host.
		LLVMInitializeX86TargetInfo()
		LLVMInitializeX86Target()
		LLVMInitializeX86TargetMC()
		LLVMInitializeX86AsmPrinter()
		LLVMInitializeAArch64TargetInfo()
		LLVMInitializeAArch64Target()
		LLVMInitializeAArch64TargetMC()
		LLVMInitializeAArch64AsmPrinter()
	}
	}
}
