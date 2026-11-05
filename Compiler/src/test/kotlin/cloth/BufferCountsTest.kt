package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.NativeLinker
import cloth.emitter.ToolchainResolver
import cloth.emitter.types.*
import cloth.emitter.types.primitives.EmitArray
import cloth.emitter.types.primitives.EmitString
import cloth.emitter.types.primitives.EmitStringArena
import cloth.emitter.types.primitives.EmitStringEdit
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticModel
import cloth.settings.LinkOptions
import cloth.target.TargetLayout
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.*
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BufferCountsTest {

	private fun unit(source: String) = Parser(Lexer(source.trimIndent()).scanTokens()).parseDeclarations()
	private fun layout(bits: Int, abi: NativeCABI = NativeCABI.native()) = abi.layout.copy(
		targetTriple = if (bits == 32) "i686-unknown-linux-gnu" else abi.targetTriple,
		pointerBits = bits, pointerAlignmentBits = bits, sizeBits = bits, cLongBits = if (bits == 32) 32 else abi.layout.cLongBits,
	)

	@Test
	fun `static extents including type-only metadata fit target usize`() {
		for (bits in listOf(32, 64)) {
			val target = layout(bits)
			val maximum = CUSize(bits).maximum
			FunctionAnalyzer(target).analyze(unit("fn<typeinfo> maximum() = int[0][$maximum]::type\nfn<usize> count() = int[$maximum]::size"))
			for (source in listOf("fn<typeinfo> bad() = int[0][${maximum + BigInteger.ONE}]::type", "fn bad(int[${maximum + BigInteger.ONE}] value) {}")) {
				val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(target).analyze(unit(source)) }
				assertEquals(DiagnosticCode.INVALID_ARRAY, error.diagnosticCode)
				assertContains(error.summary, "usize ($bits-bit target)")
				assertLocated(error)
			}
		}
		FunctionAnalyzer(layout(64)).analyze(unit("fn<typeinfo> valid() = int[4294967296]::type"))
	}

	@Test
	fun `global literal limits count encoded bytes and the terminator`() {
		// A tiny synthetic address space exercises the boundary without a multi-GB fixture.
		val tiny = layout(32).copy(pointerBits = 8, pointerAlignmentBits = 8, sizeBits = 8)
		fixture(tiny) {
			strings.literal("é".repeat(63)) // 126 UTF-8 bytes plus the NUL fit 127 bytes.
			for (text in listOf("x".repeat(127), "é".repeat(64))) {
				val span = Lexer("\"$text\"").scanTokens().first().span
				val error = assertFailsWith<ClothDiagnostic> { strings.literal(text, span) }
				assertEquals(DiagnosticCode.BUFFER_SIZE, error.diagnosticCode)
				assertLocated(error)
			}
		}
	}

	@Test
	fun `native descriptors distinguish zero maximum and unknown inner shapes`() {
		for (bits in listOf(32, 64)) fixture(layout(bits)) {
			val row = CArray(CInt(), CUSize(bits).maximum)
			val type = CArray(row, 0)
			assertEquals("{ ptr, i$bits, { i$bits, i1 } }", LLVMPrintTypeToString(type.llvmValue(context, target)).string)
			assertEquals((bits / 8 * 2).toLong(), LLVMABISizeOfType(LLVMGetModuleDataLayout(module), CString.llvmValue(context, target)))
			assertEquals((bits / 8 * 4).toLong(), LLVMABISizeOfType(LLVMGetModuleDataLayout(module), type.llvmValue(context, target)))
			val erased = CArray(CArray(CInt()))
			val unknown = arrays.descriptor(nullPointer, memory.count(0), erased)
			val maximum = arrays.descriptor(nullPointer, memory.count(0), type)
			val zero = arrays.descriptor(nullPointer, memory.count(0), CArray(CArray(CInt(), 0), 0))
			fun inner(value: LLVMValueRef, field: Int) = LLVMBuildExtractValue(builder, LLVMBuildExtractValue(builder, value, 2, "inner"), field, "component")
			assertEquals(0, LLVMConstIntGetZExtValue(inner(unknown, 1)))
			assertEquals(1, LLVMConstIntGetZExtValue(inner(maximum, 1)))
			assertEquals(1, LLVMConstIntGetZExtValue(inner(zero, 1)))
			assertEquals(CUSize(bits).maximum.toString(), LLVMConstIntGetZExtValue(inner(maximum, 0)).toULong().toString())
			val merged = arrays.matchShape(unknown, maximum, erased, merge = true)
			val constrained = arrays.constrain(merged, type)
			assertEquals(1, LLVMConstIntGetZExtValue(inner(constrained, 1)))
			assertEquals(CUSize(bits).maximum.toString(), LLVMConstIntGetZExtValue(inner(constrained, 0)).toULong().toString())
			val child = arrays.childShape(arrays.descriptor(nullPointer, memory.constant(CUSize(bits).maximum), row), constrained, row)
			arrays.matchShape(child, child, row)
			val raw = ProviderResult.ArrayExtent(erased, 1, CInt(64, true))
			assertEquals(-1L, LLVMConstIntGetZExtValue(raw.emit(providers, ProviderReceiver.Value(erased, unknown))))
			assertEquals(CUSize(bits).maximum.toString(), LLVMConstIntGetZExtValue(raw.emit(providers, ProviderReceiver.Value(erased, maximum))).toULong().toString())
		}
	}

	@Test
	fun `strings arrays metadata copies and providers agree at both count widths`() {
		for (bits in listOf(32, 64)) {
			val ir = fixture(layout(bits)) {
				val input = arrays.descriptor(pointer, count)
				assertEquals(bits, LLVMGetIntTypeWidth(LLVMTypeOf(ProviderResult.StringSize(CUSize(bits)).emit(providers, ProviderReceiver.Value(CString, input)))))
				assertEquals(bits, LLVMGetIntTypeWidth(LLVMTypeOf(ProviderResult.ArraySize(CArray(CInt()), CUSize(bits)).emit(providers, ProviderReceiver.Value(CArray(CInt()), input)))))
				val formatted = strings.interpolate(wide, CInt(64), casts, "test")
				val character = strings.interpolate(LLVMConstInt(CChar.llvmValue(context, target), 0x1f600, 0), CChar, casts, "test")
				val joined = strings.concatenate(formatted, character, "test")
				strings.equal(joined, strings.literal("42😀"))
				strings.write(module, joined)
				strings.metadata.text(strings.metadata.constant(CArray(CUSize(bits), 3)))
				val row = arrays.literal(CString, listOf({ joined }, { strings.literal("é\u0000中") }))
				val matrix = arrays.literal(CArray(CString), listOf({ row }, { row }))
				val textRecipe = assertIs<ProviderResult.ArrayText>(CArray(CArray(CString)).provider("str", target))
				textRecipe.emit(providers, ProviderReceiver.Value(textRecipe.receiverType, matrix))
				val arena = EmitStringArena(context, module, abi, target)
				val destination = arena.create(entry)
				arena.copyValue(builder, destination, matrix, CArray(CArray(CString)))
				val wideValues = arrays.literal(CInt(64), listOf({ wide }))
				arena.copyValue(builder, destination, wideValues, CArray(CInt(64)))
				arena.release(builder, destination)
			}
			assertContains(ir, "@malloc(i$bits")
			assertContains(ir, "@memcmp(ptr")
			assertContains(ir, "@snprintf(ptr")
			assertContains(ir, "@llvm.memcpy.p0.p0.i$bits")
			assertContains(ir, "phi i$bits")
			assertFalse(ir.contains("i65"))
			if (bits == 32) {
				assertFalse(ir.contains("phi i64"), ir)
				assertFalse(ir.contains("provider_count_widen"))
				assertContains(ir, "declare i32 @snprintf(ptr, i32, ptr, ...)")
				assertContains(ir, "declare i32 @memcmp(ptr, ptr, i32)")
				// This fixture deliberately gives i64 8-byte alignment with 4-byte pointers.
				assertContains(ir, "define internal { ptr, i32 } @cloth.rt.buffer.copy.align8")
				val alignedHelper = ir.substringAfter("define internal { ptr, i32 } @cloth.rt.buffer.copy.align8").substringBefore("\n}")
				assertContains(alignedHelper, "getelementptr inbounds i8, ptr %allocation, i32 8")
			}
		}
	}

	@Test
	fun `metadata fields constants and loads share the target measurement width`() {
		for (bits in listOf(32, 64)) {
			val ir = fixture(layout(bits)) {
				val type = CUSize(bits)
				val array = CArray(CInt(), type.maximum)
				val info = strings.metadata.constant(array)
				val record = LLVMGlobalGetValueType(info)
				for (field in 2..4) assertEquals(bits, LLVMGetIntTypeWidth(LLVMStructGetTypeAtIndex(record, field)))
				val extent = LLVMBuildExtractValue(builder, LLVMGetInitializer(info), 3, "maximum_extent")
				assertEquals(type.maximum.toString(), LLVMConstIntGetZExtValue(extent).toULong().toString())
				for (query in listOf(MetadataQuery.RANK, MetadataQuery.EXTENT, MetadataQuery.WIDTH)) {
					val receiver = if (query == MetadataQuery.WIDTH) strings.metadata.constant(CInt(64)) else info
					val recipe = assertNotNull(CTypeInfo.provider(query.provider.toString(), target))
					assertEquals(type, recipe.type)
					assertEquals(bits, LLVMGetIntTypeWidth(LLVMTypeOf(recipe.emit(providers, ProviderReceiver.Value(CTypeInfo, receiver)))))
				}
				val source = CInt(64)
				val storage = assertNotNull(source.provider("size", target))
				assertEquals(type, storage.type)
				assertEquals(8, LLVMConstIntGetZExtValue(storage.emit(providers, ProviderReceiver.Type(source))))
				val other = CUSize(if (bits == 32) 64 else 32)
				assertFailsWith<ClothDiagnostic> { ProviderResult.IntegerConstant(other, BigInteger.ONE, source).emit(providers, ProviderReceiver.Type(source)) }
			}
			assertContains(ir, "private constant { { ptr, i$bits }, ptr, i$bits, i$bits, i$bits, i1, i1, i1, i1, i1 }")
			if (bits == 32) {
				assertContains(ir, "%typeinfo_value = load i32, ptr")
				assertFalse(ir.contains("load i64"), ir)
				assertFalse(ir.contains("zext i32"), ir)
			}
		}
		assertFailsWith<IllegalArgumentException> { ProviderResult.ArraySize(CArray(CInt(), BigInteger.ONE.shiftLeft(32)), CUSize(32)) }
	}

	@Test
	fun `wide and signed indices are checked before native address narrowing`() {
		val ir = fixture(layout(32)) {
			val input = arrays.descriptor(pointer, count)
			arrays.checkedAddress(input, CInt(), wide, CInt(64, true))
			arrays.checkedAddress(input, CInt(), wide, CInt(64))
			arrays.checkedAddress(input, CInt(), argc, CInt(32))
		}
		assertContains(ir, "icmp ult i64")
		assertContains(ir, "icmp sge i64")
		assertContains(ir, "icmp sge i32")
		assertTrue(ir.indexOf("icmp ult i64") < ir.indexOf("trunc i64"), ir)
		assertTrue(ir.indexOf("array_read:") < ir.indexOf("trunc i64"), ir)
		assertContains(ir, "getelementptr inbounds i32, ptr %array_pointer, i32 %native_count_narrow")
	}

	@Test
	fun `Unicode startup loops and size_t calls use the selected count width`() {
		for (triple in listOf("x86_64-pc-windows-msvc", "x86_64-unknown-linux-gnu")) for (bits in listOf(32, 64)) for (withValues in listOf(false, true)) {
			val profile = NativeCABI.forTarget(triple)
			val ir = fixture(layout(bits, profile), profile) {
				val arguments = EmitArguments(context, module, abi, target)
				val value = arguments.emit(builder, argc, pointer, withValues)
				if (withValues) assertEquals(CArray(CString).llvmValue(context, target).address(), LLVMTypeOf(assertNotNull(value)).address())
				arguments.release(builder)
			}
			assertContains(ir, "phi i$bits")
			if (bits == 32) assertFalse(ir.contains("phi i64"), ir)
			if (profile.profile != NativeCABI.Profile.WINDOWS_LLP64) assertContains(ir, "define internal i$bits @cloth.rt.argument.utf8(ptr")
		}
	}

	@Test
	fun `native guards reject invalid sizes and negative indices before touching memory`(@TempDir directory: Path) {
		val abi = NativeCABI.native()
		val targets = if (abi.windows) listOf(abi, NativeCABI.forTarget("i686-pc-windows-msvc")).distinctBy { it.targetTriple } else listOf(abi)
		for (target in targets) nativeGuards(Files.createDirectories(directory.resolve(target.architecture.name)), target)
	}

	private fun nativeGuards(directory: Path, abi: NativeCABI) {
		val emitter = LLVMEmitter(abi)
		val objectFile = directory.resolve("count_guards.obj")
		val executable = abi.executableFormat.executablePath(directory.resolve("count_guards"))
		emitter.withFunctions(unit("fn unused() {}")) { context, module, _, _ ->
			val builder = LLVMCreateBuilderInContext(context)
			try {
				val integer = LLVMInt32TypeInContext(context)
				val pointer = LLVMPointerTypeInContext(context, 0)
				fun int(value: Int) = LLVMConstInt(integer, value.toLong(), 0)
				val signature = signature(integer, integer, pointer)
				val main = LLVMAddFunction(module, "main", signature)
				LLVMPositionBuilderAtEnd(builder, LLVMAppendBasicBlockInContext(context, main, "entry"))
				val argv = LLVMGetParam(main, 1)
				val address = PointerPointer<LLVMValueRef>(1L).use { indices -> indices.put(0, int(1)); LLVMBuildInBoundsGEP2(builder, pointer, argv, indices, 1, "case_slot") }
				val atoiType = signature(integer, pointer)
				val atoi = LLVMAddFunction(module, "atoi", atoiType)
				val selected = PointerPointer<LLVMValueRef>(1L).use { values -> values.put(0, LLVMBuildLoad2(builder, pointer, address, "case_text")); LLVMBuildCall2(builder, atoiType, atoi, values, 1, "case") }
				val cases = (0..10).map { LLVMAppendBasicBlockInContext(context, main, "case_$it") }
				val switch = LLVMBuildSwitch(builder, selected, cases.first(), cases.size)
				cases.forEachIndexed { index, block -> LLVMAddCase(switch, int(index), block) }
				for ((index, block) in cases.withIndex()) {
					LLVMPositionBuilderAtEnd(builder, block)
					val memory = EmitMemory(context, builder, abi)
					val arrays = EmitArray(context, builder, abi)
					val strings = EmitString(context, builder, abi)
					val nullPointer = LLVMConstNull(pointer)
					when (index) {
						0        -> memory.stack(LLVMInt64TypeInContext(context), memory.constant(memory.maximum), "oversized", "stride overflow rejected")
						1        -> {
							val arena = EmitStringArena(context, module, abi)
							val destination = arena.create(cases[index])
							arena.copy(builder, destination, arrays.descriptor(nullPointer, memory.constant(memory.maximumBytes)))
						}

						2        -> strings.concatenate(arrays.descriptor(nullPointer, memory.constant(memory.maximum)), strings.literal("x"), "guard test")
						3        -> arrays.checkedAddress(arrays.descriptor(nullPointer, memory.constant(memory.maximum)), CInt(), LLVMConstInt(LLVMInt64TypeInContext(context), -2, 1), CInt(64))
						4        -> {
							val arena = EmitStringArena(context, module, abi)
							arena.copyValue(builder, arena.create(cases[index]), arrays.descriptor(nullPointer, memory.constant(memory.maximum)), CArray(CInt(64)))
						}

						5        -> {
							val maximumCount = memory.maximumBytes / BigInteger.valueOf(8)
							val bytes = memory.elementBytes(memory.constant(maximumCount), LLVMInt64TypeInContext(context), "valid stride rejected")
							val sum = memory.add(memory.constant(memory.maximumBytes - BigInteger.ONE), memory.count(1), "valid sum rejected")
							val correct = LLVMBuildAnd(builder, LLVMBuildICmp(builder, LLVMIntEQ, bytes, memory.constant(maximumCount * BigInteger.valueOf(8)), "correct_bytes"), LLVMBuildICmp(builder, LLVMIntEQ, sum, memory.constant(memory.maximumBytes), "correct_sum"), "valid_boundaries")
							LLVMBuildRet(builder, LLVMBuildSelect(builder, correct, int(0), int(1), "status"))
						}

						6, 7     -> {
							// A synthetic string descriptor exercises only the measure pass;
							// overflow must terminate before any access to its fake payload.
							val bytes = if (index == 6) memory.maximumBytes else memory.maximumBytes - BigInteger.ONE
							val input = arrays.literal(CString, listOf({ arrays.descriptor(nullPointer, memory.constant(bytes)) }))
							val recipe = assertIs<ProviderResult.ArrayText>(CArray(CString).provider("str", abi.layout))
							val casts = EmitCast(context, builder, SemanticModel(abi.layout), abi)
							val providers = ProviderEmissionContext(context, builder, strings, casts, "guard test")
							recipe.emit(providers, ProviderReceiver.Value(recipe.receiverType, input))
						}

						8, 9, 10 -> {
							// Replacement bytes are deliberately inaccessible. Measuring must
							// reject output growth before allocation or the first payload copy.
							val replacementSize = if (index == 9) memory.maximumBytes else memory.maximumBytes / BigInteger.TWO + BigInteger.ONE
							val replacement = arrays.descriptor(nullPointer, memory.constant(replacementSize))
							EmitStringEdit(context, builder, abi, strings, "guard test").replace(strings.literal("xx"), strings.literal(if (index == 10) "" else "x"), replacement, firstOnly = index == 9)
						}
					}
					if (index != 5) LLVMBuildRet(builder, int(0))
				}
				emitter.verify(module)
				emitter.emitObject(module, objectFile)
			} finally {
				LLVMDisposeBuilder(builder)
			}
		}
		NativeLinker.link(objectFile, executable, ToolchainResolver.native(abi, LinkOptions()))
		val messages = listOf("stride overflow rejected", "Unable to allocate returned string storage", "String concatenation is too large", "Array index out of bounds", "Unable to allocate returned array storage", "", "Array text is too large", "Array text is too large", "String replace result is too large", "String replaceFirst result is too large", "String replace result is too large")
		for ((index, message) in messages.withIndex()) {
			val process = ProcessBuilder(executable.toString(), index.toString()).redirectErrorStream(true).start()
			try {
				assertTrue(process.waitFor(15, TimeUnit.SECONDS), "guard $index timed out")
				val output = process.inputStream.bufferedReader().use { it.readText() }
				if (index == 5) {
					assertEquals(0, process.exitValue(), output); assertEquals("", output)
				} else {
					assertNotEquals(0, process.exitValue(), output); assertContains(output, message)
				}
			} finally {
				if (process.isAlive) process.destroyForcibly()
			}
		}
	}

	private fun signature(result: LLVMTypeRef, vararg parameters: LLVMTypeRef): LLVMTypeRef = PointerPointer<LLVMTypeRef>(parameters.size.toLong()).use { values ->
		parameters.forEachIndexed { index, type -> values.put(index.toLong(), type) }
		LLVMFunctionType(result, values, parameters.size, 0)
	}

	/** Synthetic layouts verify helpers only; they neither enable nor execute a 32-bit ABI. */
	private fun fixture(target: TargetLayout, abi: NativeCABI = NativeCABI.native(), action: Fixture.() -> Unit): String {
		val context = LLVMContextCreate()
		val module = LLVMModuleCreateWithNameInContext("native_counts", context)
		val builder = LLVMCreateBuilderInContext(context)
		try {
			LLVMSetTarget(module, target.targetTriple)
			LLVMSetDataLayout(module, "e-p:${target.pointerBits}:${target.pointerAlignmentBits}-i64:64-n8:16:32:64")
			val fixture = Fixture(context, module, builder, target, abi)
			fixture.action()
			LLVMBuildRet(builder, LLVMConstInt(LLVMInt32TypeInContext(context), 0, 0))
			LLVMEmitter().verify(module)
			return LLVMPrintModuleToString(module).string
		} finally {
			LLVMDisposeBuilder(builder); LLVMDisposeModule(module); LLVMContextDispose(context)
		}
	}

	private inner class Fixture(val context: LLVMContextRef, val module: LLVMModuleRef, val builder: LLVMBuilderRef, val target: TargetLayout, val abi: NativeCABI) {

		val memory = EmitMemory(context, builder, abi, target)
		val function = LLVMAddFunction(module, "test", signature(LLVMInt32TypeInContext(context), memory.size, LLVMPointerTypeInContext(context, 0), LLVMInt64TypeInContext(context), LLVMInt32TypeInContext(context)))
		val entry = LLVMAppendBasicBlockInContext(context, function, "entry")
		val count = LLVMGetParam(function, 0)
		val pointer = LLVMGetParam(function, 1)
		val wide = LLVMGetParam(function, 2)
		val argc = LLVMGetParam(function, 3)
		val nullPointer = LLVMConstNull(LLVMPointerTypeInContext(context, 0))
		val arrays = EmitArray(context, builder, abi, target)
		val strings = EmitString(context, builder, abi, target)
		val casts = EmitCast(context, builder, SemanticModel(target = target), abi)
		val providers = ProviderEmissionContext(context, builder, strings, casts, "test")

		init {
			LLVMPositionBuilderAtEnd(builder, entry)
		}
	}
}
