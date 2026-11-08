package cloth.builtin

import cloth.data.ProviderReceiver
import cloth.data.ProviderResult
import cloth.data.ValueType
import cloth.data.implicitText
import cloth.diagnostics.InternalErrors
import cloth.emitter.types.BuiltinEmissionContext
import cloth.target.TargetLayout
import cloth.token.Operators
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.LLVMTypeOf

/** Definitions are reusable across compilations and never retain native handles. */
internal data class BuiltinProperties(val name: String)
internal data class BuiltinDefinition<out T : BuiltinFunction>(val name: String, val function: T) {

	val signatures: String
		get() = function.overloads.joinToString("; ") { overload ->
			"$name${Operators.PAREN_OPEN}${overload.parameters.joinToString("${Operators.COMMA} ") { it.spelling }}${Operators.PAREN_CLOSE} ${Operators.POINTER_RIGHT} ${overload.returns.spelling}"
		}
}

internal abstract class BuiltinFunction(val properties: BuiltinProperties) {

	abstract val overloads: List<BuiltinOverload>
}

/** UNKNOWN is deliberately the default. The opt-in contract covers both body and
 * adapters: operands are read-only, nothing is published/retained, and buffered
 * results own independent storage. It is a storage promise, not an IO purity flag.
 * MUTATING_RECEIVER instead requires a separately resolved place/temporary owner;
 * it never qualifies for read-only member copy elision or early cleanup.
 */
internal enum class BuiltinStorageContract {

	UNKNOWN,
	MUTATING_RECEIVER,
	READ_ONLY_INDEPENDENT_RESULT
}

internal data class BuiltinOverload(val parameters: List<BuiltinParameter>, val returns: ValueType, val body: BuiltinBody, val storage: BuiltinStorageContract = BuiltinStorageContract.UNKNOWN)

internal fun interface BuiltinBody {

	fun emit(context: BuiltinEmissionContext, arguments: List<LLVMValueRef>): LLVMValueRef?
}

/** Parameter constraints are compiler contracts, not boxed source-language types. */
internal sealed interface BuiltinParameter {

	val spelling: String

	val expectedType: ValueType? get() = null

	fun bind(actual: ValueType, target: TargetLayout): BuiltinArgument?

	data class Exact(val type: ValueType) : BuiltinParameter {

		override val spelling get() = type.spelling
		override val expectedType get() = type
		override fun bind(actual: ValueType, target: TargetLayout) = if (type.accepts(actual)) BuiltinArgument(actual, type) else null
	}

	data object Text : BuiltinParameter {

		override val spelling = "printable value"
		override fun bind(actual: ValueType, target: TargetLayout): BuiltinArgument? {
			if (actual == ValueType.NEVER) return BuiltinArgument(actual, ValueType.STRING)
			val text = actual.implicitText(target) ?: return null
			return BuiltinArgument(actual, ValueType.STRING, text)
		}
	}
}

internal data class BuiltinArgument(val sourceType: ValueType, val parameterType: ValueType, val text: ProviderResult<*>? = null) {

	fun adapt(context: BuiltinEmissionContext, value: LLVMValueRef): LLVMValueRef = text?.emit(context.providers, ProviderReceiver.Value(sourceType, value)) ?: value
}

/** Contains committed argument conversions, never AST evaluation or name lookup. */
internal data class BoundBuiltinCall(val definition: BuiltinDefinition<*>, val overload: BuiltinOverload, val arguments: List<BuiltinArgument>) {

	fun emit(context: BuiltinEmissionContext, values: List<LLVMValueRef>): LLVMValueRef? {
		if (values.size != arguments.size) throw InternalErrors.invariant("Built-in argument count changed after resolution")
		val adapted = arguments.zip(values) { argument, value -> argument.adapt(context, value) }
		val result = overload.body.emit(context, adapted)
		if (overload.returns == ValueType.VOID) {
			if (result != null) throw InternalErrors.invariant("Void built-in returned an LLVM value")
		} else if (result == null || LLVMTypeOf(result).address() != overload.returns.llvmValue(context.context, context.abi.layout).address()) {
			throw InternalErrors.invariant("Built-in result does not match '${overload.returns.spelling}'")
		}
		return result
	}
}
