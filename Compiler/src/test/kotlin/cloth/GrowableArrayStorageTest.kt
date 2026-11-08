package cloth

import cloth.data.*
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.NativeLinker
import cloth.emitter.ToolchainResolver
import cloth.emitter.callables.EmitFunctions
import cloth.emitter.storage.*
import cloth.emitter.text.EmitString
import cloth.semantic.typing.FunctionSemanticModel
import cloth.settings.LinkOptions
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Private helper probes deliberately do not lower Cloth's public mutator calls. */
class GrowableArrayStorageTest {
	private val values = """
		typedef<struct> Cell { string label, int[] items, int[]?[]? rows, int<129> wide }
		typedef<enum> Shade { Light, Dark }
		pub typedef<class> Item (pub def<int> number)
		fn<int[]> integers() = [1, 2, 3]
		fn<bool> integersValid(int[] values) = values::size == 3 && values[0] == 1 && values[2] == 3
		fn<int<65536>[]> wide() = [1, 2]
		fn<bool> wideValid(int<65536>[] values) = values::size == 2 && values[0] == 1 && values[1] == 2
		fn<int<129>?[]> optionalWide() = [null, 18446744073709551617]
		fn<bool> optionalWideValid(int<129>?[] values) {
		    if (values::size != 2 || values[0] != null || values[1] == null) return false
		    return values[1] == 18446744073709551617
		}
		fn<string[]> words() = ["é\0中", "", "😀"]
		fn<bool> wordsValid(string[] values) = values::size == 3 && values[0] == "é\0中" && values[1] == "" && values[2] == "😀"
		fn<string?[]> optionalWords() = [null, "kept"]
		fn<bool> optionalWordsValid(string?[] values) = values::size == 2 && values[0] == null && values[1] == "kept"
		fn<Cell[]> cells() = [{ "first", [1, 2], [null, [3, 4]], 5 }, { "second", [], null, 6 }]
		fn<bool> cellsValid(Cell[] values) {
		    if (values::size != 2 || values[0].label != "first" || values[1].label != "second") return false
		    if (values[0].items[1] != 2 || values[1].items::size != 0 || values[0].wide != 5) return false
		    def<int[]?[]?> rows = values[0].rows
		    if (rows == null) return false
		    def<int[]?> row = rows[1]
		    if (row == null) return false
		    return row[1] == 4 && values[1].rows == null
		}
		fn<Shade[]> shades() = Shade::values
		fn<bool> shadesValid(Shade[] values) = values::size == 2 && values[0] == Shade.Light && values[1] == Shade.Dark
		fn<Item[]> items() = [Item::new(7), Item::new(8)]
		fn<bool> itemsValid(Item[] values) = values::size == 2 && values[0].number == 7 && values[1].number == 8
		fn destroyItems(Item[] values) { values[0]::delete; values[1]::delete }
		fn<int[]?[]> rows() = [null, [3, 4], null]
		fn<bool> rowsValid(int[]?[] values) {
		    if (values::size != 3 || values[0] != null || values[2] != null) return false
		    def<int[]?> row = values[1]
		    if (row == null) return false
		    return row[0] == 3 && row[1] == 4
		}
	"""

	private fun Probe.typedMovement() {
		for (name in listOf("integers", "wide", "optionalWide", "words", "optionalWords", "cells", "shades", "items", "rows")) {
			val incoming = storage.create(entry)
			val owning = storage.create(entry)
			val savedArena = storage.create(entry)
			val type = semantics.typeOf(semantics.declarations.functions.single { it.name.lexeme == name }).results.values.single() as CArray
			val input = checkNotNull(sourceCall(name, incoming))
			val copied = storage.arrays.copy(builder, owning, input, type)
			storage.release(builder, incoming)
			val before = allocations()
			val previousData = LLVMBuildExtractValue(builder, copied, 0, "previous_data")
			val grown = storage.arrays.reserve(builder, owning, copied, type, count(32))
			expect(LLVMBuildICmp(builder, LLVMIntNE, previousData, LLVMBuildExtractValue(builder, grown, 0, "moved_data"), "storage_moved"), "$name did not move")
			expect(equal(allocations(), LLVMBuildAdd(builder, before, count(1), "one_growth")), "$name copied payloads while relocating")
			expect(LLVMBuildICmp(builder, LLVMIntUGE, arrays.capacity(grown, type), count(32), "reserved_capacity"), "$name lost capacity")
			expect(checkNotNull(sourceCall(name + "Valid", null, grown)), "$name changed during relocation")
			val saved = storage.arrays.copy(builder, savedArena, grown, type)
			expect(equal(arrays.capacity(saved, type), LLVMBuildExtractValue(builder, saved, 1, "copied_length")), "copy retained spare capacity")
			storage.arrays.truncate(builder, grown, type, count(0))
			storage.release(builder, owning)
			expect(checkNotNull(sourceCall(name + "Valid", null, saved)), "$name copy did not survive source release")
			if (name == "items") sourceCall("destroyItems", null, saved)
			storage.release(builder, savedArena)
		}
	}

	@Test
	fun `typed relocation transfers live slots and releases buffered payloads once`(@TempDir directory: Path) {
		for (abi in structRunners()) probe(abi, directory.resolve("typed_${abi.layout.sizeBits}"), values) { typedMovement() }
	}

	@Test
	fun `typed storage verifies IR and objects on every supported profile`(@TempDir directory: Path) {
		for ((index, triple) in structTargetTriples.withIndex()) {
			val output = directory.resolve("typed_$index.o")
			val ir = emitProbe(NativeCABI.forTarget(triple), output, values) { typedMovement() }
			assertContains(ir, "cloth.rt.array.reserve")
			assertContains(ir, "cloth.rt.buffer.move")
			assertContains(ir, "target triple = \"$triple\"")
			assertTrue(Files.size(output) > 0)
		}
	}

	@Test
	fun `scalar growth is geometric and old capacity is reclaimed immediately`(@TempDir directory: Path) {
		for (abi in structRunners()) probe(abi, directory.resolve("geometric_${abi.layout.sizeBits}")) {
			val destination = storage.create(entry)
			val type = CArray(CInt())
			val initial = storage.arrays.reserve(builder, destination, arrays.literal(type.element, emptyList()), type, count(0))
			val initialAllocations = allocations()
			val initialBytes = live()
			val start = LLVMGetInsertBlock(builder)
			val check = LLVMAppendBasicBlockInContext(context, main, "grow_check")
			val body = LLVMAppendBasicBlockInContext(context, main, "grow")
			val done = LLVMAppendBasicBlockInContext(context, main, "grown")
			LLVMBuildBr(builder, check)
			LLVMPositionBuilderAtEnd(builder, check)
			val value = LLVMBuildPhi(builder, type.llvmValue(context, abi.layout), "array")
			val index = LLVMBuildPhi(builder, size, "index")
			LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, index, count(4096), "more_values"), body, done)
			LLVMPositionBuilderAtEnd(builder, body)
			val reserved = storage.arrays.reserve(builder, destination, value, type, LLVMBuildAdd(builder, index, count(1), "minimum"))
			val appended = storage.arrays.initialize(builder, reserved, type, memory.resize(index, LLVMInt32TypeInContext(context)))
			val next = LLVMBuildAdd(builder, index, count(1), "next")
			val end = LLVMGetInsertBlock(builder)
			LLVMBuildBr(builder, check)
			incoming(value, start to initial, end to appended)
			incoming(index, start to count(0), end to next)
			LLVMPositionBuilderAtEnd(builder, done)
			expect(equal(LLVMBuildSub(builder, allocations(), initialAllocations, "growths"), count(13)), "scalar growth was not geometric")
			expect(equal(moved(), count(4095 * 4L)), "scalar relocation moved more than the geometric live prefixes")
			expect(equal(arrays.index(value, type.element, count(4095), CUSize(abi.layout.sizeBits)), LLVMConstInt(LLVMInt32TypeInContext(context), 4095, 0)), "relocation lost a live value")
			val retained = live()
			val finalCapacity = arrays.capacity(value, type)
			// One final owner allocation remains, rather than the entire geometric history.
			expect(LLVMBuildICmp(builder, LLVMIntULE, retained, LLVMBuildAdd(builder, initialBytes, LLVMBuildMul(builder, finalCapacity, count(4), "element_bytes"), "live_bound"), "bounded_live_bytes"), "superseded capacity leaked")
			val noGrowth = storage.arrays.reserve(builder, destination, value, type, count(2))
			expect(equal(arrays.owner(noGrowth, type), arrays.owner(value, type)), "satisfied reserve changed ownership")
			storage.release(builder, destination)
		}
	}

	@Test
	fun `buffered clear and reinitialization reuse capacity with bounded live bytes`(@TempDir directory: Path) {
		for (abi in structRunners()) probe(abi, directory.resolve("reuse_${abi.layout.sizeBits}")) {
			val destination = storage.create(entry)
			val type = CArray(CString)
			val initial = storage.arrays.reserve(builder, destination, arrays.literal(type.element, emptyList()), type, count(8))
			val baseline = live()
			val start = LLVMGetInsertBlock(builder)
			val check = LLVMAppendBasicBlockInContext(context, main, "reuse_check")
			val body = LLVMAppendBasicBlockInContext(context, main, "reuse")
			val done = LLVMAppendBasicBlockInContext(context, main, "reused")
			LLVMBuildBr(builder, check)
			LLVMPositionBuilderAtEnd(builder, check)
			val index = LLVMBuildPhi(builder, size, "iteration")
			LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, index, count(1000), "more_iterations"), body, done)
			LLVMPositionBuilderAtEnd(builder, body)
			val filled = storage.arrays.initialize(builder, initial, type, strings.literal("é\u0000中"))
			expect(strings.equal(arrays.index(filled, CString, count(0), CUSize(abi.layout.sizeBits)), strings.literal("é\u0000中")), "initialized slot changed")
			storage.arrays.truncate(builder, filled, type, count(0))
			expect(equal(live(), baseline), "released string payload remained live")
			val next = LLVMBuildAdd(builder, index, count(1), "next_iteration")
			val end = LLVMGetInsertBlock(builder)
			LLVMBuildBr(builder, check)
			incoming(index, start to count(0), end to next)
			LLVMPositionBuilderAtEnd(builder, done)
			expect(equal(arrays.capacity(initial, type), count(8)), "clear lost capacity")
			storage.release(builder, destination)
		}
	}

	@Test
	fun `moving a parent rehomes child owners before later child growth`(@TempDir directory: Path) {
		for (abi in structRunners()) probe(abi, directory.resolve("rehome_${abi.layout.sizeBits}")) {
			val destination = storage.create(entry)
			val rowType = CArray(CString)
			val type = CArray(rowType)
			val row = arrays.literal(CString, listOf({ strings.literal("kept") }))
			val owned = storage.arrays.copy(builder, destination, arrays.literal(rowType, listOf({ row })), type)
			val moved = storage.arrays.reserve(builder, destination, owned, type, count(16))
			val child = arrays.index(moved, rowType, count(0), CUSize(abi.layout.sizeBits))
			val childArena = storage.arrays.elementArena(builder, moved, type, count(0), destination)
			val before = allocations()
			val childMoved = storage.arrays.reserve(builder, childArena, child, rowType, count(32))
			expect(equal(allocations(), LLVMBuildAdd(builder, before, count(1), "one_child_growth")), "child owner was copied after parent movement")
			expect(strings.equal(arrays.index(childMoved, CString, count(0), CUSize(abi.layout.sizeBits)), strings.literal("kept")), "child payload changed")
			storage.release(builder, destination)
		}
	}

	@Test
	fun `borrowed input and arena crossings always create independent owned values`(@TempDir directory: Path) {
		for (abi in structRunners()) probe(abi, directory.resolve("borrowed_${abi.layout.sizeBits}")) {
			val first = storage.create(entry)
			val second = storage.create(entry)
			val type = CArray(CString)
			val borrowed = arrays.literal(CString, listOf({ strings.literal("original") }))
			val owned = storage.arrays.reserve(builder, first, borrowed, type, count(16))
			val independent = storage.arrays.reserve(builder, second, owned, type, count(1))
			storage.arrays.truncate(builder, owned, type, count(0))
			storage.release(builder, first)
			expect(strings.equal(arrays.index(independent, CString, count(0), CUSize(abi.layout.sizeBits)), strings.literal("original")), "arena crossing borrowed source payload")
			expect(strings.equal(arrays.index(borrowed, CString, count(0), CUSize(abi.layout.sizeBits)), strings.literal("original")), "borrowed literal changed")
			storage.release(builder, second)
		}
	}

	@Test
	fun `invalid capacity fails before reading a fake payload`(@TempDir directory: Path) {
		for (abi in structRunners()) probe(abi, directory.resolve("overflow_${abi.layout.sizeBits}"), expectedFailure = "Unable to allocate array storage") {
			val type = CArray(CInt())
			storage.arrays.reserve(builder, storage.create(entry), arrays.descriptor(LLVMConstNull(pointer), count(1), type), type, memory.constant(memory.maximum))
		}
	}

	@Test
	fun `copy rejects a corrupt live count before reading an inaccessible payload`(@TempDir directory: Path) {
		for (abi in structRunners()) probe(abi, directory.resolve("invalid_length_${abi.layout.sizeBits}"), expectedFailure = "Array length exceeds its capacity") {
			val type = CArray(CInt())
			val input = arrays.descriptor(LLVMConstNull(pointer), count(2), type)
			val invalid = LLVMBuildInsertValue(builder, input, count(1), type.capacityIndex, "invalid_capacity")
			val destination = storage.create(entry)
			storage.arrays.copy(builder, destination, invalid, type)
			storage.release(builder, destination)
		}
	}

	@Test
	fun `allocation failure terminates before publication or later initialization`(@TempDir directory: Path) {
		for (abi in structRunners()) for (failure in listOf(1L, 2L, 3L)) probe(abi, directory.resolve("failure_${abi.layout.sizeBits}_$failure"), failAt = failure, expectedFailure = "Unable to allocate returned string storage") {
			val type = CArray(CString)
			val destination = storage.create(entry)
			val owned = storage.arrays.copy(builder, destination, arrays.literal(CString, emptyList()), type)
			val grown = storage.arrays.reserve(builder, destination, owned, type, count(8))
			storage.arrays.initialize(builder, grown, type, strings.literal("unpublished"))
			storage.release(builder, destination)
		}
	}

	private fun probe(abi: NativeCABI, output: Path, source: String = "fn unused() {}", failAt: Long? = null, expectedFailure: String? = null, body: Probe.() -> Unit) {
		val objectFile = output.resolveSibling(output.fileName.toString() + ".o")
		emitProbe(abi, objectFile, source, failAt, body)
		val executable = abi.executableFormat.executablePath(output)
		NativeLinker.link(objectFile, executable, ToolchainResolver.native(abi, LinkOptions()))
		val result = runExecutable(executable, if (expectedFailure == null) 0 else 1).replace("\r\n", "\n")
		if (expectedFailure != null) assertContains(result, expectedFailure)
		else assertEquals("balanced\n", result)
	}

	private fun emitProbe(abi: NativeCABI, output: Path, source: String, failAt: Long? = null, body: Probe.() -> Unit): String {
		val emitter = LLVMEmitter(abi)
		var ir = ""
		emitter.withFunctions(structUnit(source)) { context, module, functions, semantics ->
			val builder = LLVMCreateBuilderInContext(context)
			try {
				val probe = Probe(context, module, builder, functions, semantics, abi)
				probe.body()
				probe.expect(probe.equal(probe.live(), probe.count(0)), "live array storage remains")
				probe.expect(probe.equal(probe.outstanding(), probe.count(0)), "unbalanced array ownership")
				val putsType = probe.signature(LLVMInt32TypeInContext(context), probe.pointer)
				val existing = LLVMGetNamedFunction(module, "puts")
				val puts = if (existing == null || existing.isNull) LLVMAddFunction(module, "puts", putsType) else existing
				probe.call(putsType, puts, LLVMBuildGlobalStringPtr(builder, "balanced", "result"))
				LLVMBuildRet(builder, LLVMConstInt(LLVMInt32TypeInContext(context), 0, 0))
				probe.installAllocator(failAt)
				emitter.verify(module)
				ir = LLVMPrintModuleToString(module).string
				emitter.emitObject(module, output)
			} finally { LLVMDisposeBuilder(builder) }
		}
		return ir
	}

	private class Probe(
		val context: LLVMContextRef, val module: LLVMModuleRef, val builder: LLVMBuilderRef,
		val functions: EmitFunctions, val semantics: FunctionSemanticModel, val abi: NativeCABI,
	) {
		val size = CUSize(abi.layout.sizeBits).llvmValue(context, abi.layout)
		val pointer = LLVMPointerTypeInContext(context, 0)
		val memory = EmitMemory(context, builder, abi)
		val main = LLVMAddFunction(module, "main", signature(LLVMInt32TypeInContext(context)))
		val entry = LLVMAppendBasicBlockInContext(context, main, "entry")
		val arrays = EmitArray(context, builder, abi)
		val storage = functions.stringStorage
		val strings = EmitString(context, builder, abi)
		private fun global(name: String) = LLVMAddGlobal(module, size, name).also { LLVMSetInitializer(it, count(0)); LLVMSetLinkage(it, LLVMPrivateLinkage) }
		private val allocated = global("test.allocations")
		private val current = global("test.live")
		private val owners = global("test.outstanding")
		private val movement = global("test.moved")
		init { LLVMPositionBuilderAtEnd(builder, entry) }
		fun count(value: Long) = LLVMConstInt(size, value, 0)
		fun allocations() = LLVMBuildLoad2(builder, size, allocated, "allocations")
		fun live() = LLVMBuildLoad2(builder, size, current, "live_bytes")
		fun outstanding() = LLVMBuildLoad2(builder, size, owners, "outstanding")
		fun moved() = LLVMBuildLoad2(builder, size, movement, "moved_bytes")
		fun equal(left: LLVMValueRef, right: LLVMValueRef) = LLVMBuildICmp(builder, LLVMIntEQ, left, right, "equal")
		fun expect(value: LLVMValueRef, message: String) = memory.guard(value, message)
		fun signature(result: LLVMTypeRef, vararg parameters: LLVMTypeRef): LLVMTypeRef = PointerPointer<LLVMTypeRef>(parameters.size.toLong()).use { values ->
			parameters.forEachIndexed { index, type -> values.put(index.toLong(), type) }; LLVMFunctionType(result, values, parameters.size, 0)
		}
		fun call(type: LLVMTypeRef, function: LLVMValueRef, vararg arguments: LLVMValueRef): LLVMValueRef = PointerPointer<LLVMValueRef>(arguments.size.toLong()).use { values ->
			arguments.forEachIndexed { index, value -> values.put(index.toLong(), value) }; LLVMBuildCall2(builder, type, function, values, arguments.size, "")
		}
		fun sourceCall(name: String, destination: LLVMValueRef?, vararg arguments: LLVMValueRef): LLVMValueRef? {
			val source = semantics.declarations.functions.single { it.name.lexeme == name }
			return functions.callable(source).call(builder, returnArena = destination?.let { { it } }) { arguments.forEachIndexed { index, value -> argument(index, value) } }
		}
		fun incoming(phi: LLVMValueRef, vararg entries: Pair<LLVMBasicBlockRef, LLVMValueRef>) = PointerPointer<LLVMValueRef>(entries.size.toLong()).use { values -> PointerPointer<LLVMBasicBlockRef>(entries.size.toLong()).use { blocks ->
			entries.forEachIndexed { index, (block, value) -> values.put(index.toLong(), value); blocks.put(index.toLong(), block) }; LLVMAddIncoming(phi, values, blocks, entries.size)
		} }
		private fun offset(data: LLVMValueRef, amount: Long) = PointerPointer<LLVMValueRef>(1L).use { values -> values.put(0, count(amount)); LLVMBuildInBoundsGEP2(builder, LLVMInt8TypeInContext(context), data, values, 1, "tracked_bytes") }
		private fun update(slot: LLVMValueRef, amount: LLVMValueRef, subtract: Boolean = false) {
			val previous = LLVMBuildLoad2(builder, size, slot, "previous_metric")
			LLVMBuildStore(builder, if (subtract) LLVMBuildSub(builder, previous, amount, "decreased_metric") else LLVMBuildAdd(builder, previous, amount, "increased_metric"), slot)
		}
		/** Preserve malloc alignment, count requested live bytes, and poison every free. */
		fun installAllocator(failAt: Long?) {
			// Count actual live-byte memcpy operations in the private reserve helpers.
			// Copy helpers and allocation bookkeeping are separate from relocation.
			var function = LLVMGetFirstFunction(module)
			val moves = mutableListOf<LLVMValueRef>()
			while (function != null && !function.isNull) {
				if (LLVMGetValueName(function).string.startsWith("cloth.rt.array.reserve.")) {
					var block = LLVMGetFirstBasicBlock(function)
					while (block != null && !block.isNull) {
						var instruction = LLVMGetFirstInstruction(block)
						while (instruction != null && !instruction.isNull) {
							if (LLVMIsACallInst(instruction)?.isNull == false && LLVMGetValueName(LLVMGetCalledValue(instruction)).string.startsWith("llvm.memcpy.")) moves.add(instruction)
							instruction = LLVMGetNextInstruction(instruction)
						}
						block = LLVMGetNextBasicBlock(block)
					}
				}
				function = LLVMGetNextFunction(function)
			}
			for (instruction in moves) {
				LLVMPositionBuilderBefore(builder, instruction)
				update(movement, LLVMGetOperand(instruction, 2))
			}
			val malloc = LLVMGetNamedFunction(module, "malloc")
			check(malloc != null && !malloc.isNull)
			LLVMSetValueName2(malloc, "test.malloc", 11)
			LLVMSetLinkage(malloc, LLVMInternalLinkage)
			val mallocType = signature(pointer, size)
			val realMalloc = LLVMAddFunction(module, "malloc", mallocType)
			LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, malloc, "entry"))
			update(allocated, count(1))
			if (failAt != null) {
				val fail = LLVMAppendBasicBlockInContext(context, malloc, "injected_failure")
				val success = LLVMAppendBasicBlockInContext(context, malloc, "allocated")
				LLVMBuildCondBr(builder, equal(allocations(), count(failAt)), fail, success)
				LLVMPositionBuilderAtEnd(builder, fail); LLVMBuildRet(builder, LLVMConstNull(pointer))
				LLVMPositionBuilderAtEnd(builder, success)
			}
			val bytes = LLVMGetParam(malloc, 0)
			update(current, bytes); update(owners, count(1))
			val allocation = call(mallocType, realMalloc, LLVMBuildAdd(builder, bytes, count(16), "tracked_capacity"))
			LLVMBuildStore(builder, bytes, allocation)
			LLVMBuildRet(builder, offset(allocation, 16))
			val free = LLVMGetNamedFunction(module, "free")
			check(free != null && !free.isNull)
			LLVMSetValueName2(free, "test.free", 9)
			LLVMSetLinkage(free, LLVMInternalLinkage)
			val freeType = signature(LLVMVoidTypeInContext(context), pointer)
			val realFree = LLVMAddFunction(module, "free", freeType)
			LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, free, "entry"))
			val released = LLVMGetParam(free, 0)
			val original = offset(released, -16)
			val capacity = LLVMBuildLoad2(builder, size, original, "released_bytes")
			update(current, capacity, subtract = true); update(owners, count(1), subtract = true)
			LLVMBuildMemSet(builder, released, LLVMConstInt(LLVMInt8TypeInContext(context), 0xa5, 0), capacity, 1)
			call(freeType, realFree, original)
			LLVMBuildRetVoid(builder)
		}
	}
}
