package cloth.emitter

import cloth.ast.ASTStructure
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.Expression
import cloth.parser.expression.NumberLiteral
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.Statement
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

// Import your AST classes from their package.

class LLVMEmitter {

	fun emit(ast: ASTStructure): String {
		val context = LLVMContextCreate()
		val module = LLVMModuleCreateWithNameInContext("cloth", context)
		val builder = LLVMCreateBuilderInContext(context)

		try {
			val int32Type = LLVMInt32TypeInContext(context)

			val functionType = LLVMFunctionType(
				int32Type,
				null as PointerPointer<*>?,
				0,
				0
			)

			val mainFunction = LLVMAddFunction(
				module,
				"main",
				functionType
			)

			val entryBlock = LLVMAppendBasicBlockInContext(
				context,
				mainFunction,
				"entry"
			)

			LLVMPositionBuilderAtEnd(builder, entryBlock)

			fun emitExpression(expression: Expression): LLVMValueRef {
				return when (expression) {
					is NumberLiteral -> LLVMConstInt(
						int32Type,
						expression.value.toLong(),
						1
					)

					is BinaryExpression -> {
						val left = emitExpression(expression.left)
						val right = emitExpression(expression.right)

						// Assumes operator is a String, as in the last example.
						when (expression.operator.lexeme) {
							"+" -> LLVMBuildAdd(
								builder,
								left,
								right,
								"addtmp"
							)

							else -> error(
								"Unsupported operator: ${expression.operator.lexeme}"
							)
						}
					}

					else -> error(
						"Unsupported expression: $expression"
					)
				}
			}

			fun emitStatement(statement: Statement): LLVMValueRef {
				return when (statement) {
					is ExpressionStatement ->
						emitExpression(statement.expression)

					else -> error(
						"Unsupported statement: $statement"
					)
				}
			}

			// Empty programs return 0.
			var result = LLVMConstInt(int32Type, 0L, 0)

			for (statement in ast.statements) {
				result = emitStatement(statement)
			}

			// Emit one return after all expressions.
			LLVMBuildRet(builder, result)

			val ir = LLVMPrintModuleToString(module)

			return try {
				ir.string
			} finally {
				LLVMDisposeMessage(ir)
			}
		} finally {
			LLVMDisposeBuilder(builder)
			LLVMDisposeModule(module)
			LLVMContextDispose(context)
		}
	}
}