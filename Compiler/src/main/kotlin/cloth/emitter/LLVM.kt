package cloth.emitter

import cloth.ast.ASTStructure
import cloth.ast.SourceUnit
import cloth.diagnostics.BackendErrors
import cloth.diagnostics.ClothDiagnostic
import cloth.emitter.types.*
import cloth.semantic.SemanticAnalyzer
import cloth.semantic.SemanticModel
import cloth.semantic.ValueType
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.FunctionSemanticModel
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.ProgramKind
import cloth.settings.LinkOptions
import cloth.token.Keywords
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import java.nio.file.Files
import java.nio.file.Path

class LLVMEmitter(private val linkOptions: LinkOptions = LinkOptions()) {

	private val abi by lazy { NativeCABI.native() }
	private val linker by lazy { ToolchainResolver.native(abi, linkOptions) }

	fun compile(unit: SourceUnit, output: Path): Path {
		val program = ProgramAnalyzer().analyze(unit)
		EmitFunctions.validate(program.functions)
		val configuration = linker // Entry/type/feature failures precede linker discovery and output creation.
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
		} finally { Files.deleteIfExists(objectFile) }
	}

	fun emitExecutable(unit: SourceUnit): String {
		val program = ProgramAnalyzer().analyze(unit)
		return withFunctions(program.functions) { context, module, functions, _ ->
			EmitEntryPoint(context, module, abi).emit(program, functions)
			verify(module)
			val ir = LLVMPrintModuleToString(module)
			try { ir.string } finally { LLVMDisposeMessage(ir) }
		}
	}

	/** Declaration module only; use emitExecutable to include program startup. */
	fun emit(unit: SourceUnit): String = withFunctions(unit) { _, module, _, _ ->
		val ir = LLVMPrintModuleToString(module)
		try { ir.string } finally { LLVMDisposeMessage(ir) }
	}

	fun compileObject(unit: SourceUnit, output: Path): Path = withFunctions(ProgramAnalyzer().analyze(unit, ProgramKind.LIBRARY).functions) { _, module, _, _ ->
		val target = output.toAbsolutePath().normalize()
		Files.createDirectories(target.parent)
		emitObject(module, target)
		target
	}

	/** Module composition hook used by native tests and entry-point emission. */
	internal fun <T> withFunctions(unit: SourceUnit, action: (LLVMContextRef, LLVMModuleRef, EmitFunctions, FunctionSemanticModel) -> T): T = withFunctions(FunctionAnalyzer().analyze(unit), action)

	private fun <T> withFunctions(semantics: FunctionSemanticModel, action: (LLVMContextRef, LLVMModuleRef, EmitFunctions, FunctionSemanticModel) -> T): T {
		EmitFunctions.validate(semantics)
		val targetABI = abi
		val context = LLVMContextCreate()
		val module = LLVMModuleCreateWithNameInContext("cloth", context)
		try {
			LLVMSetTarget(module, targetABI.targetTriple)
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
		val semantics = SemanticAnalyzer().analyze(ast)
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

	private fun <T> withModule(ast: ASTStructure, forcePrint: Boolean, semantics: SemanticModel = SemanticAnalyzer().analyze(ast), action: (LLVMModuleRef) -> T): T {
		val targetABI = abi // Resolve and reject unsupported targets before generating any IR.
		val context = LLVMContextCreate()
		val module = LLVMModuleCreateWithNameInContext("cloth", context)
		val builder = LLVMCreateBuilderInContext(context)

		try {
			LLVMSetTarget(module, targetABI.targetTriple)
			val types = LLVMTypes(context)
			val nativeIntType = types.value(targetABI.intType)

			val functionType = LLVMFunctionType(nativeIntType, null as PointerPointer<*>?, 0, 0)

			val mainFunction = LLVMAddFunction(module, "main", functionType)

			val entryBlock = LLVMAppendBasicBlockInContext(context, mainFunction, "entry")

			LLVMPositionBuilderAtEnd(builder, entryBlock)

			val bindings = EmitBindings(context, builder)
			val casts = EmitCast(context, builder, semantics, targetABI)
			val strings = EmitString(context, builder, targetABI)
			val expressionEmitter = EmitExpression(context, builder, bindings, semantics, casts, strings, EmitPower(context, builder, abi), abi)
			val statementEmitter = EmitStatement(expressionEmitter, bindings, semantics)

			var result: LLVMValueRef? = LLVMConstInt(types.value(ValueType.INT), 0L, 0)

			for (statement in ast.statements) {
				result = statementEmitter.emit(statement)
			}

			if (forcePrint && result != null) emitPrint(context, module, builder, result, ast.statements.lastOrNull()?.let { semantics.typeOf(it) } ?: ValueType.INT, casts, strings)

			LLVMBuildRet(builder, LLVMConstInt(nativeIntType, 0L, 0))
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
		val types = LLVMTypes(context)
		val printfType = PointerPointer<LLVMTypeRef>(1L).use { parameters ->
			parameters.put(0, LLVMPointerTypeInContext(context, 0))
			LLVMFunctionType(types.value(abi.intType), parameters, 1, 1)
		}
		val printf = LLVMAddFunction(module, "printf", printfType)
		val floatingPoint = type.floating
		val boolean = type == ValueType.BOOL
		val argumentType = abi.printArgument(type)
		val precision = if (type is ValueType.Floating) ".${EmitHalf.arithmeticType(type).roundTripDigits}" else ""
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

	internal fun emitObject(module: LLVMModuleRef, output: Path) {
		val target = LLVMTargetRef()
		BytePointer(abi.targetTriple).use { triple ->
			checkLLVM(BackendErrors::target) { error ->
				LLVMGetTargetFromTriple(triple, target, error)
			}
		}
		val machine = LLVMCreateTargetMachine(target, abi.targetTriple, "generic", "", LLVMCodeGenLevelDefault, LLVMRelocPIC, LLVMCodeModelDefault)
		if (machine == null || machine.isNull) throw BackendErrors.machine()

		try {
			val layout = LLVMCreateTargetDataLayout(machine)
			try {
				val pointer = LLVMPointerTypeInContext(LLVMGetModuleContext(module), 0)
				if (LLVMSizeOfTypeInBits(layout, pointer) != abi.pointerBits.toLong()) throw BackendErrors.layout(abi.profile.name, abi.targetTriple)
				LLVMSetModuleDataLayout(module, layout)
			} finally {
				LLVMDisposeTargetData(layout)
			}
			checkLLVM(BackendErrors::objectCode) { error ->
				LLVMTargetMachineEmitToFile(machine, module, output.toString(), LLVMObjectFile, error)
			}
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
		if (LLVMInitializeNativeTarget() != 0) throw BackendErrors.initialize("target")
		if (LLVMInitializeNativeAsmPrinter() != 0) throw BackendErrors.initialize("assembly printer")
	}
	}
}
