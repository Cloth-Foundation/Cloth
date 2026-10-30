package cloth.emitter.types

import cloth.diagnostics.BackendErrors
import cloth.diagnostics.InternalErrors
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.parser.declaration.*
import cloth.parser.expression.CallExpression
import cloth.semantic.FunctionSemanticModel
import cloth.semantic.ValueType
import cloth.semantic.symbols.FunctionSymbol
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import java.util.IdentityHashMap

/** All signatures are declared before any body, including recursive overloads. */
internal class EmitFunctions(private val context: LLVMContextRef, private val module: LLVMModuleRef, private val abi: NativeCABI, private val semantics: FunctionSemanticModel) {

	private val types = LLVMTypes(context)
	private val functions = IdentityHashMap<FunctionSymbol, LLVMValueRef>()
	private val functionTypes = IdentityHashMap<FunctionSymbol, LLVMTypeRef>()
	private val stringStorage = EmitStringArena(context, module, abi)
	fun reference(function: FunctionSymbol): LLVMValueRef = functions.getValue(function)
	fun signature(function: FunctionSymbol): LLVMTypeRef = functionTypes.getValue(function)

	/** Raw binary16 bits avoid requiring external half-precision ABI conversion helpers. */
	private fun abiType(type: ValueType): LLVMTypeRef = if (type == ValueType.HALF) LLVMInt16TypeInContext(context) else types.value(type)
	fun encode(builder: LLVMBuilderRef, value: LLVMValueRef, type: ValueType): LLVMValueRef =
		if (type == ValueType.HALF) LLVMBuildBitCast(builder, value, abiType(type), "half_argument_bits") else value
	fun decode(builder: LLVMBuilderRef, value: LLVMValueRef, type: ValueType): LLVMValueRef =
		if (type == ValueType.HALF) LLVMBuildBitCast(builder, value, types.value(type), "half_result") else value

	fun emitAll() {
		for ((index, function) in semantics.declarations.functions.withIndex()) {
			val type = semantics.typeOf(function)
			// A string result is copied into an invocation-owned arena supplied by its caller.
			val parameterCount = type.parameters.size + if (type.returns.buffered) 1 else 0
			val signature = PointerPointer<LLVMTypeRef>(parameterCount.toLong()).use { parameters ->
				type.parameters.forEachIndexed { position, parameter -> parameters.put(position.toLong(), abiType(parameter)) }
				if (type.returns.buffered) parameters.put(type.parameters.size.toLong(), LLVMPointerTypeInContext(context, 0))
				LLVMFunctionType(abiType(type.returns), parameters, parameterCount, 0)
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
			val frame = EmitFunctionFrame(context, builder, entry, signature.returns, abiType(signature.returns), stringStorage,
				if (signature.returns.buffered) LLVMGetParam(value, signature.parameters.size) else null)
			val bindings = EmitBindings(context, builder, entry)
			function.parameters.forEachIndexed { index, parameter ->
				val argument = decode(builder, LLVMGetParam(value, index), signature.parameters[index])
				bindings.declare(parameter, parameter.name.lexeme, signature.parameters[index], argument)
			}
			val casts = EmitCast(context, builder, semantics.types, abi)
			val strings = EmitString(context, builder, abi)
			val expressions = EmitExpression(context, builder, bindings, semantics.types, casts, strings, EmitPower(context, builder, abi), this, semantics, signature.returns, frame)
			when (val body = function.declaration.body) {
				is BlockBody -> {
					expressions.emitStatements(body.statements)
					if (!expressions.terminated()) {
						if (signature.returns != ValueType.VOID) throw InternalErrors.invariant("Validated non-void function fell through during emission")
						frame.emitReturn(null)
					}
				}
				is ExpressionBody -> {
					val result = expressions.emit(body.expression)
					if (!expressions.terminated()) {
						frame.emitReturn(if (signature.returns == ValueType.VOID) null else encode(builder, checkNotNull(result), signature.returns))
					}
				}
			}
			frame.finish()
		} finally { LLVMDisposeBuilder(builder) }
	}

	fun emitCall(call: CallExpression, expressions: EmitExpression, builder: LLVMBuilderRef): LLVMValueRef? {
		val selected = semantics.selectedFunction(call)
		val type = semantics.typeOf(selected)
		val argumentCount = call.arguments.size + if (type.returns.buffered) 1 else 0
		val result = PointerPointer<LLVMValueRef>(argumentCount.toLong()).use { arguments ->
			// Snapshot each argument in source order, before entering the callee.
			call.arguments.forEachIndexed { index, expression -> arguments.put(index.toLong(), encode(builder, expressions.emitValue(expression), type.parameters[index])) }
			if (type.returns.buffered) arguments.put(call.arguments.size.toLong(), expressions.stringReturnArena())
			LLVMBuildCall2(builder, signature(selected), reference(selected), arguments, argumentCount, if (type.returns == ValueType.VOID) "" else "call_result")
		}
		return if (type.returns == ValueType.VOID) null else decode(builder, result, type.returns)
	}

	companion object {
		fun validate(semantics: FunctionSemanticModel) {
			fun supported(type: ValueType): Boolean = if (type is ValueType.ArrayValue) type.element != ValueType.VOID && supported(type.element) else type.numeric || type in setOf(ValueType.VOID, ValueType.BOOL, ValueType.CHAR, ValueType.STRING)
			for (function in semantics.declarations.functions) {
				val type = semantics.typeOf(function)
				if (!supported(type.returns)) throw BackendErrors.functionType(type.returns, function.declaration.returnType?.span ?: function.name.span)
				function.parameters.forEachIndexed { index, parameter ->
					if (!supported(type.parameters[index])) throw BackendErrors.functionType(type.parameters[index], parameter.declaration.type.span)
				}
			}
		}
	}
}
