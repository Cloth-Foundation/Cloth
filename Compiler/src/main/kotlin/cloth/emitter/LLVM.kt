package cloth.emitter

import cloth.ast.ASTStructure
import cloth.ast.SourceUnit
import cloth.data.CFloat
import cloth.data.ValueType
import cloth.diagnostics.BackendErrors
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.InternalErrors
import cloth.emitter.types.*
import cloth.semantic.*
import cloth.settings.LinkOptions
import cloth.token.Keywords
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import java.nio.file.Files
import java.nio.file.Path

class LLVMEmitter internal constructor(private val abi: NativeCABI, private val linkOptions: LinkOptions = LinkOptions()) {

	constructor(linkOptions: LinkOptions = LinkOptions()) : this(NativeCABI.native(), linkOptions)

	private val linker by lazy { ToolchainResolver.native(abi, linkOptions) }

	fun compile(unit: SourceUnit, output: Path): Path {
		val program = ProgramAnalyzer(abi.layout).analyze(unit)
		EmitFunctions.validate(program.functions)
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

	fun compileObject(unit: SourceUnit, output: Path): Path = withFunctions(ProgramAnalyzer(abi.layout).analyze(unit, ProgramKind.LIBRARY).functions) { _, module, _, _ ->
		val target = output.toAbsolutePath().normalize()
		Files.createDirectories(target.parent)
		emitObject(module, target)
		target
	}

	/** Module composition hook used by native tests and entry-point emission. */
	internal fun <T> withFunctions(unit: SourceUnit, action: (LLVMContextRef, LLVMModuleRef, EmitFunctions, FunctionSemanticModel) -> T): T = withFunctions(FunctionAnalyzer(abi.layout).analyze(unit), action)

	private fun <T> withFunctions(semantics: FunctionSemanticModel, action: (LLVMContextRef, LLVMModuleRef, EmitFunctions, FunctionSemanticModel) -> T): T {
		if (semantics.target != abi.layout) throw InternalErrors.invariant("Semantic and emission targets differ")
		EmitFunctions.validate(semantics)
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

	internal fun verify(module: LLVMModuleRef) = checkLLVM(BackendErrors::invalidIR) { LLVMVerifyModule(module, LLVMReturnStatusAction, it) }

	/** Test instrumentation for isolated body fragments, not language program startup. */
	internal fun compileSnippet(ast: ASTStructure, output: Path, forcePrint: Boolean = true): Path {
		val semantics = SemanticAnalyzer(abi.layout).analyze(ast)
		semantics.requireExecutableLoops()
		val configuration = linker // Validate the toolchain before creating output files or generating code.
		val executable = abi.executableFormat.executablePath(output).toAbsolutePath().normalize()
		Files.createDirectories(executable.parent)
		val objectFile = Files.createTempFile("cloth-", ".o")

		try {
			withModule(ast, forcePrint, semantics) { module ->
				emitObject(module, objectFile)
			}
			NativeLinker.link(objectFile, executable, configuration)
			return executable
		} finally {
			Files.deleteIfExists(objectFile)
		}
	}

	internal fun emitSnippet(ast: ASTStructure, forcePrint: Boolean = false): String = withModule(ast, forcePrint) { module ->
		val ir = LLVMPrintModuleToString(module)
		try {
			ir.string
		} finally {
			LLVMDisposeMessage(ir)
		}
	}

	private fun <T> withModule(ast: ASTStructure, forcePrint: Boolean, semantics: SemanticModel = SemanticAnalyzer(abi.layout).analyze(ast), action: (LLVMModuleRef) -> T): T {
		semantics.requireExecutableLoops()
		if (semantics.target != abi.layout) throw InternalErrors.invariant("Snippet and emission targets differ")
		val targetABI = abi // Resolve and reject unsupported targets before generating any IR.
		val context = LLVMContextCreate()
		val module = LLVMModuleCreateWithNameInContext("cloth", context)
		val builder = LLVMCreateBuilderInContext(context)

		try {
			configureModule(module, targetABI)
			val types = LLVMTypes(context, targetABI.layout)
			val nativeIntType = types.value(targetABI.intType)

			val functionType = LLVMFunctionType(nativeIntType, null as PointerPointer<*>?, 0, 0)

			val mainFunction = LLVMAddFunction(module, "main", functionType)

			val entryBlock = LLVMAppendBasicBlockInContext(context, mainFunction, "entry")

			LLVMPositionBuilderAtEnd(builder, entryBlock)

			val bindings = EmitBindings(context, builder, target = targetABI.layout)
			val casts = EmitCast(context, builder, semantics, targetABI)
			val strings = EmitString(context, builder, targetABI)
			val frame = EmitFunctionFrame(context, builder, entryBlock, targetABI.intType, nativeIntType, EmitStringArena(context, module, targetABI), null)
			val expressionEmitter = EmitExpression(context, builder, bindings, semantics, casts, strings, EmitPower(context, builder, abi), abi, frame = frame)
			val statementEmitter = EmitStatement(expressionEmitter, bindings, semantics)

			var result: LLVMValueRef? = LLVMConstInt(types.value(ValueType.INT), 0L, 0)

			for (statement in ast.statements) {
				result = statementEmitter.emit(statement)
			}

			val resultShape = ast.statements.lastOrNull()?.let { semantics.resultOf(it) } ?: ResultShape.Single(ValueType.INT)
			if (forcePrint && result != null && resultShape is ResultShape.Single) emitPrint(context, module, builder, result, resultShape.type, casts, strings)

			frame.emitReturn(LLVMConstInt(nativeIntType, 0L, 0))
			frame.finish()
			checkLLVM(BackendErrors::invalidIR) { error ->
				LLVMVerifyModule(module, LLVMReturnStatusAction, error)
			}
			return action(module)
		} finally {
			LLVMDisposeBuilder(builder)
			LLVMDisposeModule(module)
			LLVMContextDispose(context)
		}
	}

	internal fun emitPrint(context: LLVMContextRef, module: LLVMModuleRef, builder: LLVMBuilderRef, result: LLVMValueRef, type: ValueType, casts: EmitCast, strings: EmitString) {
		if (type == ValueType.CHAR) {
			EmitCharacter(context, builder, abi).print(module, result)
			return
		}
		if (type == ValueType.STRING) {
			strings.print(module, result)
			return
		}
		if (type == ValueType.TYPEINFO) {
			strings.print(module, strings.metadata.text(result))
			return
		}
		val types = LLVMTypes(context, abi.layout)
		val printfType = PointerPointer<LLVMTypeRef>(1L).use { parameters ->
			parameters.put(0, LLVMPointerTypeInContext(context, 0))
			LLVMFunctionType(types.value(abi.intType), parameters, 1, 1)
		}
		val printf = LLVMAddFunction(module, "printf", printfType)
		val floatingPoint = type.floating
		val boolean = type == ValueType.BOOL
		val argumentType = abi.printArgument(type)
		val precision = if (type is CFloat) ".${EmitHalf.arithmeticType(type).roundTripDigits}" else ""
		val formatString = "%$precision${abi.printSpecifier(type)}\n"
		val format = LLVMBuildGlobalStringPtr(builder, formatString, "result_format")
		val printValue = when {
			boolean                                       -> LLVMBuildSelect(builder, result, LLVMBuildGlobalStringPtr(builder, Keywords.TRUE.toString(), "bool_true"), LLVMBuildGlobalStringPtr(builder, Keywords.FALSE.toString(), "bool_false"), "bool_text")
			floatingPoint                                 -> casts.toFloating(result, type, argumentType)
			type.integer && type.bits < argumentType.bits -> if (type.unsigned) LLVMBuildZExt(builder, result, types.value(argumentType), "printfunsigned")
			else LLVMBuildSExt(builder, result, types.value(argumentType), "printfsigned")

			else                                          -> result
		}
		PointerPointer<LLVMValueRef>(2L).use { arguments ->
			arguments.put(0, format)
			arguments.put(1, printValue)
			LLVMBuildCall2(builder, printfType, printf, arguments, 2, "")
		}
	}

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
