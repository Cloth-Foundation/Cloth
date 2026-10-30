package cloth.emitter

import cloth.ast.ASTStructure
import cloth.emitter.types.EmitExpression
import cloth.emitter.types.EmitStatement
import cloth.emitter.types.EmitBindings
import cloth.emitter.types.EmitCast
import cloth.emitter.types.EmitHalf
import cloth.emitter.types.EmitCharacter
import cloth.emitter.types.EmitString
import cloth.semantic.SemanticAnalyzer
import cloth.semantic.ValueType
import cloth.token.Keywords
import cloth.settings.LinkOptions
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

	fun compile(ast: ASTStructure, output: Path, forcePrint: Boolean = true): Path {
		val configuration = linker // Validate the toolchain before creating output files or generating code.
		val executable = abi.executableFormat.executablePath(output).toAbsolutePath().normalize()
		Files.createDirectories(executable.parent)
		val objectFile = Files.createTempFile("cloth-", ".o")

		try {
			withModule(ast, forcePrint) { module ->
				emitObject(module, objectFile)
			}
			NativeLinker.link(objectFile, executable, configuration)
			return executable
		} finally {
			Files.deleteIfExists(objectFile)
		}
	}

	fun emit(ast: ASTStructure, forcePrint: Boolean = false): String = withModule(ast, forcePrint) { module ->
		val ir = LLVMPrintModuleToString(module)
		try {
			ir.string
		} finally {
			LLVMDisposeMessage(ir)
		}
	}

	private fun <T> withModule(ast: ASTStructure, forcePrint: Boolean, action: (LLVMModuleRef) -> T): T {
		val targetABI = abi // Resolve and reject unsupported targets before generating any IR.
		val semantics = SemanticAnalyzer().analyze(ast)
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
			val expressionEmitter = EmitExpression(context, builder, bindings, semantics, casts, strings)
			val statementEmitter = EmitStatement(expressionEmitter, bindings, semantics)

			var result = LLVMConstInt(types.value(ValueType.INT), 0L, 0)

			for (statement in ast.statements) {
				result = statementEmitter.emit(statement)
			}

			if (forcePrint) emitPrint(context, module, builder, result,
				ast.statements.lastOrNull()?.let { semantics.typeOf(it) } ?: ValueType.INT, casts, strings)

			LLVMBuildRet(builder, LLVMConstInt(nativeIntType, 0L, 0))
			checkLLVM("Invalid LLVM module") { error ->
				LLVMVerifyModule(module, LLVMReturnStatusAction, error)
			}
			return action(module)
		} finally {
			LLVMDisposeBuilder(builder)
			LLVMDisposeModule(module)
			LLVMContextDispose(context)
		}
	}

	private fun emitPrint(context: LLVMContextRef, module: LLVMModuleRef, builder: LLVMBuilderRef,
		result: LLVMValueRef, type: ValueType, casts: EmitCast, strings: EmitString) {
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
			boolean -> LLVMBuildSelect(builder, result,
				LLVMBuildGlobalStringPtr(builder, Keywords.TRUE.toString(), "bool_true"),
				LLVMBuildGlobalStringPtr(builder, Keywords.FALSE.toString(), "bool_false"), "bool_text")
			floatingPoint -> casts.toFloating(result, type, argumentType)
			type.integer && type.bits < argumentType.bits -> if (type.unsigned) LLVMBuildZExt(builder, result, types.value(argumentType), "printfunsigned")
				else LLVMBuildSExt(builder, result, types.value(argumentType), "printfsigned")
			else -> result
		}
		PointerPointer<LLVMValueRef>(2L).use { arguments ->
			arguments.put(0, format)
			arguments.put(1, printValue)
			LLVMBuildCall2(builder, printfType, printf, arguments, 2, "")
		}
	}

	private fun emitObject(module: LLVMModuleRef, output: Path) {
		val target = LLVMTargetRef()
		BytePointer(abi.targetTriple).use { triple ->
			checkLLVM("Unable to find the native target") { error ->
				LLVMGetTargetFromTriple(triple, target, error)
			}
		}
		val machine = LLVMCreateTargetMachine(target, abi.targetTriple, "generic", "", LLVMCodeGenLevelDefault, LLVMRelocPIC, LLVMCodeModelDefault)
		check(machine != null && !machine.isNull) { "Unable to create the native target machine" }

		try {
			val layout = LLVMCreateTargetDataLayout(machine)
			try {
				val pointer = LLVMPointerTypeInContext(LLVMGetModuleContext(module), 0)
				check(LLVMSizeOfTypeInBits(layout, pointer) == abi.pointerBits.toLong()) {
					"C ABI profile '${abi.profile}' does not match LLVM's pointer width for '${abi.targetTriple}'"
				}
				LLVMSetModuleDataLayout(module, layout)
			} finally {
				LLVMDisposeTargetData(layout)
			}
			checkLLVM("Unable to emit native object code") { error ->
				LLVMTargetMachineEmitToFile(machine, module, output.toString(), LLVMObjectFile, error)
			}
		} finally {
			LLVMDisposeTargetMachine(machine)
		}
	}

	private fun checkLLVM(message: String, action: (BytePointer) -> Int) {
		val error = BytePointer(null as Pointer?)
		try {
			check(action(error) == 0) { "$message: ${if (error.isNull) "Unknown LLVM error" else error.string}" }
		} finally {
			if (!error.isNull) LLVMDisposeMessage(error)
		}
	}

	companion object { init {
		check(LLVMInitializeNativeTarget() == 0) { "Unable to initialize the native LLVM target" }
		check(LLVMInitializeNativeAsmPrinter() == 0) { "Unable to initialize the native LLVM assembly printer" }
	}
	}
}
