package cloth.emitter.callables

import cloth.data.CClass
import cloth.data.CEnum
import cloth.data.CStruct
import cloth.emitter.storage.EmitClassRuntime
import cloth.emitter.storage.ObjectAccess
import cloth.emitter.storage.ReceiverAccess
import cloth.semantic.ResultShape
import cloth.semantic.symbols.CallableContractSymbol
import cloth.semantic.symbols.ConstructorSymbol
import cloth.semantic.symbols.DestructorSymbol
import cloth.semantic.symbols.InterfaceMethodSymbol
import cloth.semantic.symbols.MethodSymbol
import cloth.semantic.typing.FunctionType
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*

internal data class ConstructorAllocation(val bytes: LLVMValueRef, val tag: LLVMValueRef)

/** One private Cloth ABI layout for the declaration, its body and all its callers. */
internal class EmittedCallable(
	context: LLVMContextRef,
	module: LLVMModuleRef,
	private val name: String,
	callable: CallableContractSymbol,
	val type: FunctionType,
	hasClasses: Boolean,
	private val results: EmitResults,
	acceptsOmissions: Boolean = true,
	classes: () -> EmitClassRuntime,
) {

	private val pointer = LLVMPointerTypeInContext(context, 0)
	private val flag = LLVMInt1TypeInContext(context)
	private val objectReceiver = callable is InterfaceMethodSymbol ||
		(callable is MethodSymbol || callable is DestructorSymbol) && callable.receiver?.owner is CClass
	val enumReceiver = callable is MethodSymbol && callable.owner is CEnum
	val replayableStruct = hasClasses && callable is MethodSymbol && callable.owner is CStruct
	private val prefix = when {
		objectReceiver                -> listOf(LLVMInt64TypeInContext(context), flag)
		enumReceiver                  -> listOf(results.abiType(checkNotNull(callable.receiver).owner))
		callable is ConstructorSymbol -> classes().let { listOf(it.size, it.indexType) }
		callable is MethodSymbol      -> List(if (replayableStruct) 4 else 2) { pointer }
		else                          -> emptyList()
	}
	private val explicitEnd = prefix.size + type.parameters.size
	val needsResultArena = type.results.values.any { it.indirect }
	private val arenaSlot = explicitEnd.takeIf { needsResultArena }
	private val flagStart = explicitEnd + if (needsResultArena) 1 else 0
	private val omissionSlots = if (acceptsOmissions) callable.parameters.indices.filter { callable.parameters[it].declaration.optional }
		.mapIndexed { offset, parameter -> parameter to flagStart + offset }.toMap() else emptyMap()
	val hasOmissions: Boolean get() = omissionSlots.isNotEmpty()
	private val parameterTypes = prefix + type.parameters.map(results::abiType) + listOfNotNull(pointer.takeIf { needsResultArena }) + List(omissionSlots.size) { flag }

	val signature: LLVMTypeRef = PointerPointer<LLVMTypeRef>(parameterTypes.size.toLong()).use { parameters ->
		parameterTypes.forEachIndexed { index, type -> parameters.put(index.toLong(), type) }
		LLVMFunctionType(results.abiType(type.results), parameters, parameterTypes.size, 0)
	}
	val reference: LLVMValueRef = LLVMAddFunction(module, name, signature).also { LLVMSetLinkage(it, LLVMInternalLinkage) }

	private fun argumentSlot(index: Int) = prefix.size + index
	fun parameter(builder: LLVMBuilderRef, index: Int): LLVMValueRef = results.decode(builder, LLVMGetParam(reference, argumentSlot(index)), type.parameters[index])
	fun omissionFlag(index: Int): LLVMValueRef? = omissionSlots[index]?.let { LLVMGetParam(reference, it) }
	val callerArena: LLVMValueRef? get() = arenaSlot?.let { LLVMGetParam(reference, it) }

	fun objectAccess(): ObjectAccess? = if (objectReceiver) ObjectAccess(LLVMGetParam(reference, 0), LLVMGetParam(reference, 1)) else null
	fun enumValue(): LLVMValueRef = LLVMGetParam(reference, 0)
	fun structAccess(): ReceiverAccess = ReceiverAccess(
		LLVMGetParam(reference, 0), LLVMGetParam(reference, 1),
		if (replayableStruct) LLVMGetParam(reference, 2) else null,
		if (replayableStruct) LLVMGetParam(reference, 3) else null,
	)

	fun allocation(): ConstructorAllocation = ConstructorAllocation(LLVMGetParam(reference, 0), LLVMGetParam(reference, 1))

	/** The caller evaluates operands and snapshots values. This packs the ABI values. */
	fun call(
		builder: LLVMBuilderRef,
		omittedParameters: List<Int> = emptyList(),
		returnArena: (() -> LLVMValueRef)? = null,
		resultName: String = "call_result",
		prepare: Arguments.() -> Unit,
	): LLVMValueRef? {
		val values = arrayOfNulls<LLVMValueRef>(parameterTypes.size)
		Arguments(builder, values).prepare()
		for (parameter in omittedParameters) {
			val slot = argumentSlot(parameter)
			check(parameter in omissionSlots && values[slot] == null) { "Invalid omitted parameter $parameter for '$name'" }
			values[slot] = LLVMConstNull(parameterTypes[slot])
		}
		arenaSlot?.let { values[it] = checkNotNull(returnArena) { "Missing result arena for '$name'" }() }
		for ((parameter, slot) in omissionSlots) {
			values[slot] = LLVMConstInt(flag, if (parameter in omittedParameters) 1 else 0, 0)
		}
		val empty = type.results == ResultShape.Empty
		val result = PointerPointer<LLVMValueRef>(values.size.toLong()).use { arguments ->
			values.forEachIndexed { index, value ->
				arguments.put(index.toLong(), checkNotNull(value) { "Missing ABI operand $index for '$name'" })
			}
			LLVMBuildCall2(builder, signature, reference, arguments, values.size, if (empty) "" else resultName)
		}
		return if (empty) null else results.decode(builder, result, type.results)
	}

	inner class Arguments internal constructor(private val builder: LLVMBuilderRef, private val values: Array<LLVMValueRef?>) {

		fun argument(index: Int, value: LLVMValueRef) {
			values[argumentSlot(index)] = results.encode(builder, value, type.parameters[index])
		}

		fun setReceiver(access: ObjectAccess) {
			values[0] = access.identity
			values[1] = access.duringDestruction
		}

		fun setReceiver(access: ReceiverAccess) {
			values[0] = access.address
			values[1] = access.arena
			if (replayableStruct) {
				values[2] = access.resolver ?: LLVMConstNull(pointer)
				values[3] = access.environment ?: LLVMConstNull(pointer)
			}
		}

		fun setReceiver(value: LLVMValueRef) {
			values[0] = value
		}

		fun setAllocation(allocation: ConstructorAllocation) {
			values[0] = allocation.bytes
			values[1] = allocation.tag
		}
	}
}
