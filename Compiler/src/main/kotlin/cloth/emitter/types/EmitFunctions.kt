package cloth.emitter.types

import cloth.data.CArray
import cloth.data.CNullable
import cloth.data.ValueType
import cloth.diagnostics.BackendErrors
import cloth.diagnostics.InternalErrors
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.expression.CallExpression
import cloth.semantic.FunctionSemanticModel
import cloth.semantic.ResolvedCall
import cloth.semantic.ResultShape
import cloth.semantic.symbols.FunctionSymbol
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import java.util.*

/** All signatures are declared before any body, including recursive overloads. */
internal class EmitFunctions(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI, private val semantics: FunctionSemanticModel) {

	private val llvmTypes = LLVMTypes(context, abi.layout)
	fun omissionCount(function: FunctionSymbol) = function.parameters.count { it.declaration.optional }
	private val results = EmitResults(context, abi.layout)
	private val functions = IdentityHashMap<FunctionSymbol, LLVMValueRef>()
	private val functionTypes = IdentityHashMap<FunctionSymbol, LLVMTypeRef>()
	private val stringStorage = EmitStringArena(context, module, abi)
	fun reference(function: FunctionSymbol): LLVMValueRef = functions.getValue(function)
	fun signature(function: FunctionSymbol): LLVMTypeRef = functionTypes.getValue(function)

	/** Raw binary16 bits avoid requiring external half-precision ABI conversion helpers. */
	private fun abiType(type: ValueType): LLVMTypeRef = results.abiType(type)
	fun encode(builder: LLVMBuilderRef, value: LLVMValueRef, type: ValueType): LLVMValueRef = results.encode(builder, value, type)
	fun decode(builder: LLVMBuilderRef, value: LLVMValueRef, type: ValueType): LLVMValueRef = results.decode(builder, value, type)

	fun emitAll() {
		for ((index, function) in semantics.declarations.functions.withIndex()) {
			val type = semantics.typeOf(function)
			// String and array results copy into an invocation arena supplied by the caller.
			val buffered = type.results.values.any { it.indirect }
			val flagStart = type.parameters.size + if (buffered) 1 else 0
			val parameterCount = flagStart + omissionCount(function)
			val signature = PointerPointer<LLVMTypeRef>(parameterCount.toLong()).use { parameters ->
				type.parameters.forEachIndexed { position, parameter -> parameters.put(position.toLong(), abiType(parameter)) }
				if (buffered) parameters.put(type.parameters.size.toLong(), LLVMPointerTypeInContext(context, 0))
				repeat(omissionCount(function)) { parameters.put((flagStart + it).toLong(), LLVMInt1TypeInContext(context)) }
				LLVMFunctionType(results.abiType(type.results), parameters, parameterCount, 0)
			}
			functionTypes[function] = signature
			val value = LLVMAddFunction(module, "cloth.fn.$index.${function.name.lexeme}", signature)
			LLVMSetLinkage(value, LLVMInternalLinkage)
			functions[function] = value
		}
		for (function in semantics.declarations.functions) emitBody(function)
	}

	private fun emitBody(function: FunctionSymbol) {
		val builder = LLVMCreateBuilderInContext(context)
		try {
			val value = reference(function)
			val signature = semantics.typeOf(function)
			val entry = LLVMAppendBasicBlockInContext(context, value, "entry")
			LLVMPositionBuilderAtEnd(builder, entry)
			val frame = EmitFunctionFrame(context, builder, entry, signature.results, results.abiType(signature.results), stringStorage, if (signature.results.values.any { it.indirect }) LLVMGetParam(value, signature.parameters.size) else null)
			val bindings = EmitBindings(context, builder, entry, abi.layout)
			val casts = EmitCast(context, builder, semantics.types, abi)
			val strings = EmitString(context, builder, abi)
			val expressions = EmitExpression(context, builder, bindings, semantics.types, casts, strings, EmitPower(context, builder, abi), abi, this, semantics, signature.results, frame)
			var flagIndex = signature.parameters.size + if (signature.results.values.any { it.indirect }) 1 else 0
			function.parameters.forEachIndexed { index, parameter ->
				val type = signature.parameters[index]
				val fallback = parameter.declaration.defaultValue
				val argument = if (fallback == null) decode(builder, LLVMGetParam(value, index), type) else {
					val suppliedBlock = LLVMAppendBasicBlockInContext(context, value, "parameter_supplied")
					val defaultBlock = LLVMAppendBasicBlockInContext(context, value, "parameter_default")
					val merge = LLVMAppendBasicBlockInContext(context, value, "parameter_ready")
					LLVMBuildCondBr(builder, LLVMGetParam(value, flagIndex++), defaultBlock, suppliedBlock)
					LLVMPositionBuilderAtEnd(builder, suppliedBlock)
					// Omitted placeholders are never decoded, copied, or shape-checked.
					val supplied = decode(builder, LLVMGetParam(value, index), type)
					val suppliedEnd = LLVMGetInsertBlock(builder)
					LLVMBuildBr(builder, merge)
					LLVMPositionBuilderAtEnd(builder, defaultBlock)
					val resolved = expressions.transferArrayValue(expressions.adaptValue(expressions.emitValue(fallback), semantics.typeOf(fallback), type), type)
					val defaultEnd = LLVMGetInsertBlock(builder)
					LLVMBuildBr(builder, merge)
					LLVMPositionBuilderAtEnd(builder, merge)
					val phi = LLVMBuildPhi(builder, llvmTypes.value(type), "parameter_value")
					PointerPointer<LLVMValueRef>(2L).use { values ->
						PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
							values.put(0, supplied); values.put(1, resolved)
							blocks.put(0, suppliedEnd); blocks.put(1, defaultEnd)
							LLVMAddIncoming(phi, values, blocks, 2)
						}
					}
					phi
				}
				bindings.declare(parameter, parameter.name.lexeme, type, argument)
			}
			when (val body = function.declaration.body) {
				is BlockBody      -> {
					expressions.emitStatements(body.statements)
					if (!expressions.terminated()) {
						if (signature.results != ResultShape.Empty) throw InternalErrors.invariant("Validated non-void function fell through during emission")
						frame.emitReturn(null)
					}
				}

				is ExpressionBody -> {
					val result = if (signature.results == ResultShape.Empty) expressions.emitDiscarded(body.expression) else expressions.emit(body.expression)
					if (!expressions.terminated()) {
						frame.emitReturn(if (signature.results == ResultShape.Empty) null else results.encode(builder, expressions.adaptResults(checkNotNull(result), semantics.resultOf(body.expression), signature.results), signature.results))
					}
				}
			}
			frame.finish()
		} finally {
			LLVMDisposeBuilder(builder)
		}
	}

	fun emitCall(call: CallExpression, expressions: EmitExpression, builder: LLVMBuilderRef): LLVMValueRef? {
		val plan = semantics.selectedCall(call) as ResolvedCall.Source
		val selected = plan.function
		val type = plan.type
		val buffered = type.results.values.any { it.indirect }
		val flagStart = type.parameters.size + if (buffered) 1 else 0
		val argumentCount = flagStart + omissionCount(selected)
		val result = PointerPointer<LLVMValueRef>(argumentCount.toLong()).use { arguments ->
			// Snapshot each argument in written order, then place it in its mapped slot.
			call.arguments.forEachIndexed { index, expression ->
				val destination = plan.binding.parameterForArgument[index]
				val parameter = type.parameters[destination]
				val adapted = expressions.adaptValue(expressions.emitValue(expression), semantics.typeOf(expression), parameter)
				val snapshot = expressions.transferArrayValue(adapted, parameter)
				arguments.put(destination.toLong(), encode(builder, snapshot, parameter))
			}
			for (index in plan.binding.omittedParameters) arguments.put(index.toLong(), LLVMConstNull(abiType(type.parameters[index])))
			if (buffered) arguments.put(type.parameters.size.toLong(), expressions.stringReturnArena())
			var flag = flagStart
			selected.parameters.forEachIndexed { index, parameter ->
				if (parameter.declaration.optional) arguments.put((flag++).toLong(), LLVMConstInt(LLVMInt1TypeInContext(context), if (index in plan.binding.omittedParameters) 1 else 0, 0))
			}
			LLVMBuildCall2(builder, signature(selected), reference(selected), arguments, argumentCount, if (type.results == ResultShape.Empty) "" else "call_result")
		}
		return if (type.results == ResultShape.Empty) null else results.decode(builder, result, type.results)
	}

	companion object {

		fun validate(semantics: FunctionSemanticModel) {
			fun supported(type: ValueType): Boolean = when (type) {
				is CArray    -> type.element != ValueType.VOID && supported(type.element)
				is CNullable -> supported(type.payload)
				else         -> type.numeric || type in setOf(ValueType.VOID, ValueType.BOOL, ValueType.CHAR, ValueType.STRING, ValueType.TYPEINFO)
			}
			for (function in semantics.declarations.functions) {
				val type = semantics.typeOf(function)
				type.results.values.forEachIndexed { index, result ->
					if (!supported(result)) throw BackendErrors.functionType(result, function.declaration.returnTypes[index].span)
				}
				function.parameters.forEachIndexed { index, parameter ->
					if (!supported(type.parameters[index])) throw BackendErrors.functionType(type.parameters[index], parameter.declaration.type.span)
				}
			}
		}
	}
}
