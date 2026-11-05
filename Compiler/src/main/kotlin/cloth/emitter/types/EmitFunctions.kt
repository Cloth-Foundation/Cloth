package cloth.emitter.types

import cloth.data.CArray
import cloth.data.CNullable
import cloth.data.CStruct
import cloth.data.ValueType
import cloth.diagnostics.BackendErrors
import cloth.diagnostics.InternalErrors
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.expression.Expression
import cloth.semantic.FunctionSemanticModel
import cloth.semantic.FunctionType
import cloth.semantic.ResolvedCall
import cloth.semantic.ResultShape
import cloth.semantic.symbols.CallableSymbol
import cloth.semantic.symbols.MethodSymbol
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import java.util.*

/** All signatures are declared before any body, including recursive overloads. */
internal class EmitFunctions(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI, private val semantics: FunctionSemanticModel) {

	private val llvmTypes = LLVMTypes(context, abi.layout)
	fun omissionCount(function: CallableSymbol) = function.parameters.count { it.declaration.optional }
	private val results = EmitResults(context, abi.layout)
	private val functions = IdentityHashMap<CallableSymbol, LLVMValueRef>()
	private val functionTypes = IdentityHashMap<CallableSymbol, LLVMTypeRef>()
	private val stringStorage = EmitStringArena(context, module, abi)
	fun reference(function: CallableSymbol): LLVMValueRef = functions.getValue(function)
	fun signature(function: CallableSymbol): LLVMTypeRef = functionTypes.getValue(function)

	/** Raw binary16 bits avoid requiring external half-precision ABI conversion helpers. */
	private fun abiType(type: ValueType): LLVMTypeRef = results.abiType(type)
	fun encode(builder: LLVMBuilderRef, value: LLVMValueRef, type: ValueType): LLVMValueRef = results.encode(builder, value, type)
	fun decode(builder: LLVMBuilderRef, value: LLVMValueRef, type: ValueType): LLVMValueRef = results.decode(builder, value, type)

	private class CallLayout(callable: CallableSymbol, type: FunctionType) {

		val explicitStart = if (callable is MethodSymbol) 2 else 0
		val resultArena = (explicitStart + type.parameters.size).takeIf { type.results.values.any { slot -> slot.indirect } }
		val flagStart = explicitStart + type.parameters.size + if (resultArena != null) 1 else 0
	}

	fun emitAll() {
		if (semantics.declarations.structs.isNotEmpty()) StructStorageLayout(context, module, semantics).validate()
		for ((index, function) in semantics.declarations.callables.withIndex()) {
			val type = semantics.typeOf(function)
			// Indirect results copy into an invocation arena supplied by the caller.
			val layout = CallLayout(function, type)
			val parameterCount = layout.flagStart + omissionCount(function)
			val signature = PointerPointer<LLVMTypeRef>(parameterCount.toLong()).use { parameters ->
				val pointer = LLVMPointerTypeInContext(context, 0)
				repeat(layout.explicitStart) { parameters.put(it.toLong(), pointer) }
				type.parameters.forEachIndexed { position, parameter -> parameters.put((layout.explicitStart + position).toLong(), abiType(parameter)) }
				layout.resultArena?.let { parameters.put(it.toLong(), pointer) }
				repeat(omissionCount(function)) { parameters.put((layout.flagStart + it).toLong(), LLVMInt1TypeInContext(context)) }
				LLVMFunctionType(results.abiType(type.results), parameters, parameterCount, 0)
			}
			functionTypes[function] = signature
			val kind = if (function is MethodSymbol) "method" else "fn"
			val value = LLVMAddFunction(module, "cloth.$kind.$index.${function.name.lexeme}", signature)
			LLVMSetLinkage(value, LLVMInternalLinkage)
			functions[function] = value
		}
		for (function in semantics.declarations.callables) emitBody(function)
	}

	private fun emitBody(function: CallableSymbol) {
		val builder = LLVMCreateBuilderInContext(context)
		try {
			val value = reference(function)
			val signature = semantics.typeOf(function)
			val layout = CallLayout(function, signature)
			val entry = LLVMAppendBasicBlockInContext(context, value, "entry")
			LLVMPositionBuilderAtEnd(builder, entry)
			val frame = EmitFunctionFrame(context, builder, entry, signature.results, results.abiType(signature.results), stringStorage, layout.resultArena?.let { LLVMGetParam(value, it) })
			val bindings = EmitBindings(context, builder, entry, abi.layout)
			if (function is MethodSymbol) bindings.bindReceiver(function.receiver, LLVMGetParam(value, 0), LLVMGetParam(value, 1))
			val casts = EmitCast(context, builder, semantics.types, abi)
			val strings = EmitString(context, builder, abi)
			val expressions = EmitExpression(context, builder, bindings, semantics.types, casts, strings, EmitPower(context, builder, abi), abi, this, semantics, signature.results, frame)
			var flagIndex = layout.flagStart
			function.parameters.forEachIndexed { index, parameter ->
				val position = layout.explicitStart + index
				val type = signature.parameters[index]
				val fallback = parameter.declaration.defaultValue
				val argument = if (fallback == null) decode(builder, LLVMGetParam(value, position), type) else {
					val suppliedBlock = LLVMAppendBasicBlockInContext(context, value, "parameter_supplied")
					val defaultBlock = LLVMAppendBasicBlockInContext(context, value, "parameter_default")
					val merge = LLVMAppendBasicBlockInContext(context, value, "parameter_ready")
					LLVMBuildCondBr(builder, LLVMGetParam(value, flagIndex++), defaultBlock, suppliedBlock)
					LLVMPositionBuilderAtEnd(builder, suppliedBlock)
					// Omitted placeholders are never decoded, copied, or shape-checked.
					val supplied = decode(builder, LLVMGetParam(value, position), type)
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

	fun emitCall(plan: ResolvedCall.Source, supplied: List<Expression>, expressions: EmitExpression, builder: LLVMBuilderRef): LLVMValueRef? {
		val selected = plan.callable
		val type = plan.type
		val layout = CallLayout(selected, type)
		val argumentCount = layout.flagStart + omissionCount(selected)
		val result = PointerPointer<LLVMValueRef>(argumentCount.toLong()).use { arguments ->
			plan.receiver?.let { receiver ->
				val (address, ownerArena) = expressions.emitReceiver(receiver, (selected as MethodSymbol).owner)
				arguments.put(0, address); arguments.put(1, ownerArena)
			}
			// Snapshot each argument in written order, then place it in its mapped slot.
			supplied.forEachIndexed { index, expression ->
				val destination = plan.binding.parameterForArgument[index]
				val parameter = type.parameters[destination]
				val adapted = expressions.adaptValue(expressions.emitValue(expression), semantics.typeOf(expression), parameter)
				val snapshot = expressions.transferArrayValue(adapted, parameter)
				arguments.put((layout.explicitStart + destination).toLong(), encode(builder, snapshot, parameter))
			}
			for (index in plan.binding.omittedParameters) arguments.put((layout.explicitStart + index).toLong(), LLVMConstNull(abiType(type.parameters[index])))
			layout.resultArena?.let { arguments.put(it.toLong(), expressions.stringReturnArena()) }
			var flag = layout.flagStart
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
				is CStruct   -> true // Field types and layouts are validated at their declarations.
				else         -> type.numeric || type in setOf(ValueType.VOID, ValueType.BOOL, ValueType.CHAR, ValueType.STRING, ValueType.TYPEINFO)
			}
			for (function in semantics.declarations.callables) {
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
