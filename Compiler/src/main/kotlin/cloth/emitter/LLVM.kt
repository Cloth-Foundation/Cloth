package cloth.emitter

import cloth.ast.ASTStructure
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.Expression
import cloth.parser.expression.IntegerLiteral
import cloth.parser.expression.FloatingPointLiteral
import cloth.parser.expression.UnaryExpression
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.Statement
import cloth.token.Operators
import cloth.util.ExecutableFormat
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import java.nio.file.Files
import java.nio.file.Path

class LLVMEmitter {

	fun compile(ast: ASTStructure, output: Path, forcePrint: Boolean = true): Path {
		val executable = ExecutableFormat.current.executablePath(output).toAbsolutePath().normalize()
		Files.createDirectories(executable.parent)
		val objectFile = Files.createTempFile("cloth-", ".o")

		try {
			withModule(ast, forcePrint) { module ->
				emitObject(module, objectFile)
			}
			NativeLinker.link(objectFile, executable)
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
		val context = LLVMContextCreate()
		val module = LLVMModuleCreateWithNameInContext("cloth", context)
		val builder = LLVMCreateBuilderInContext(context)

		try {
			val int32Type = LLVMInt32TypeInContext(context)
			val float64Type = LLVMDoubleTypeInContext(context)

			val functionType = LLVMFunctionType(int32Type, null as PointerPointer<*>?, 0, 0)

			val mainFunction = LLVMAddFunction(module, "main", functionType)

			val entryBlock = LLVMAppendBasicBlockInContext(context, mainFunction, "entry")

			LLVMPositionBuilderAtEnd(builder, entryBlock)

			fun emitExpression(expression: Expression): LLVMValueRef {
				return when (expression) {
					is IntegerLiteral -> LLVMConstInt(int32Type, expression.value.toLong(), 1)
					is FloatingPointLiteral -> LLVMConstReal(float64Type, expression.value)

					is UnaryExpression  -> {
						val operand = emitExpression(expression.operand)
						when (expression.operator.lexeme) {
							Operators.PLUS.toString()  -> operand
							Operators.MINUS.toString() -> if (isFloatingPoint(operand)) {
								LLVMBuildFNeg(builder, operand, "negtmp")
							} else {
								LLVMBuildNeg(builder, operand, "negtmp")
							}
							else                       -> error("Unsupported unary operator: ${expression.operator}")
						}
					}

					is BinaryExpression -> {
						var left = emitExpression(expression.left)
						var right = emitExpression(expression.right)
						val floatingPoint = isFloatingPoint(left) || isFloatingPoint(right)
						if (floatingPoint) {
							if (!isFloatingPoint(left)) left = LLVMBuildSIToFP(builder, left, float64Type, "leftfloat")
							if (!isFloatingPoint(right)) right = LLVMBuildSIToFP(builder, right, float64Type, "rightfloat")
						}

						if (floatingPoint) when (expression.operator.lexeme) {
							Operators.PLUS.toString()     -> LLVMBuildFAdd(builder, left, right, "addtmp")
							Operators.MINUS.toString()    -> LLVMBuildFSub(builder, left, right, "subtmp")
							Operators.MULTIPLY.toString() -> LLVMBuildFMul(builder, left, right, "multmp")
							Operators.DIVIDE.toString()   -> LLVMBuildFDiv(builder, left, right, "divtmp")
							Operators.MODULO.toString()   -> LLVMBuildFRem(builder, left, right, "modtmp")
							else                          -> error("Unsupported binary operator: ${expression.operator}")
						} else when (expression.operator.lexeme) {
							Operators.PLUS.toString()     -> LLVMBuildAdd(builder, left, right, "addtmp")
							Operators.MINUS.toString()    -> LLVMBuildSub(builder, left, right, "subtmp")
							Operators.MULTIPLY.toString() -> LLVMBuildMul(builder, left, right, "multmp")
							Operators.DIVIDE.toString()   -> LLVMBuildSDiv(builder, left, right, "divtmp")
							Operators.MODULO.toString()   -> LLVMBuildSRem(builder, left, right, "modtmp")
							else                          -> error("Unsupported binary operator: ${expression.operator}")
						}
					}

					else                -> error("Unsupported expression: $expression")
				}
			}

			fun emitStatement(statement: Statement): LLVMValueRef {
				return when (statement) {
					is ExpressionStatement -> emitExpression(statement.expression)

					else                   -> error("Unsupported statement: $statement")
				}
			}

			var result = LLVMConstInt(int32Type, 0L, 0)

			for (statement in ast.statements) {
				result = emitStatement(statement)
			}

			if (forcePrint) emitPrint(context, module, builder, result)

			LLVMBuildRet(builder, LLVMConstInt(int32Type, 0L, 0))
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

	private fun isFloatingPoint(value: LLVMValueRef): Boolean = LLVMGetTypeKind(LLVMTypeOf(value)) == LLVMDoubleTypeKind

	private fun emitPrint(context: LLVMContextRef, module: LLVMModuleRef, builder: LLVMBuilderRef, result: LLVMValueRef) {
		val printfType = PointerPointer<LLVMTypeRef>(1L).use { parameters ->
			parameters.put(0, LLVMPointerTypeInContext(context, 0))
			LLVMFunctionType(LLVMInt32TypeInContext(context), parameters, 1, 1)
		}
		val printf = LLVMAddFunction(module, "printf", printfType)
		val formatString = if (isFloatingPoint(result)) "%.17g\n" else "%d\n"
		val format = LLVMBuildGlobalStringPtr(builder, formatString, "result_format")
		PointerPointer<LLVMValueRef>(2L).use { arguments ->
			arguments.put(0, format)
			arguments.put(1, result)
			LLVMBuildCall2(builder, printfType, printf, arguments, 2, "")
		}
	}

	private fun emitObject(module: LLVMModuleRef, output: Path) {
		val triple = LLVMGetDefaultTargetTriple()
		try {
			val target = LLVMTargetRef()
			checkLLVM("Unable to find the native target") { error ->
				LLVMGetTargetFromTriple(triple, target, error)
			}
			val machine = LLVMCreateTargetMachine(target, triple.string, "generic", "", LLVMCodeGenLevelDefault, LLVMRelocPIC, LLVMCodeModelDefault)
			check(machine != null && !machine.isNull) { "Unable to create the native target machine" }

			try {
				LLVMSetTarget(module, triple)
				val layout = LLVMCreateTargetDataLayout(machine)
				try {
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
		} finally {
			LLVMDisposeMessage(triple)
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
