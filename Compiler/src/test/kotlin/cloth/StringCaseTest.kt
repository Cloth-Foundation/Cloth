package cloth

import cloth.backend.IntegerKernelProgram
import cloth.builtin.BuiltinStorageContract
import cloth.builtin.attachments.PrimitiveAttachments
import cloth.data.UnicodeStringCaseMappings
import cloth.data.UnicodeStringCaseProperty
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.text.EmitStringCaseData
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.typing.FunctionAnalyzer
import cloth.settings.CompilerSettings
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.*

class StringCaseTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("string-case.co", source.trimIndent())).scanTokens()).parse()
	private fun literal(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\u0000", "\\0").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(unit(source)) }
		assertEquals(code, error.diagnosticCode, source)
		assertLocated(error)
	}

	private fun raw(name: String, digest: String): String {
		val bytes = GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/cloth/unicode/17.0.0/$name.gz"))).use { it.readBytes() }
		assertEquals(digest, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
		return bytes.decodeToString(throwOnInvalidSequence = true)
	}

	/** Expected mappings come from complete official inputs, not production tables or host casing. */
	private class Oracle(val mappings: Map<Boolean, Map<Int, List<Int>>>, val cased: BitSet, val ignored: BitSet) {

		fun convert(text: String, upper: Boolean): String {
			val points = text.codePoints().toArray()
			return buildString {
				for ((position, point) in points.withIndex()) {
					var targets = mappings.getValue(upper)[point] ?: listOf(point)
					if (!upper && point == 0x3a3) {
						var before = position - 1
						while (before >= 0 && ignored[points[before]]) before--
						var after = position + 1
						while (after < points.size && ignored[points[after]]) after++
						if (before >= 0 && cased[points[before]] && (after == points.size || !cased[points[after]])) targets = listOf(0x3c2)
					}
					for (target in targets) appendCodePoint(target)
				}
			}
		}
	}

	private val oracle by lazy {
		val mappings = mapOf(true to sortedMapOf<Int, List<Int>>(), false to sortedMapOf())
		for (line in raw("UnicodeData.txt", "2e1efc1dcb59c575eedf5ccae60f95229f706ee6d031835247d843c11d96470c").lineSequence()) {
			if (line.isBlank()) continue
			val fields = line.split(';')
			for ((upper, field) in listOf(true to 12, false to 13)) if (fields[field].isNotEmpty()) mappings.getValue(upper)[fields[0].toInt(16)] = listOf(fields[field].toInt(16))
		}
		for (line in raw("SpecialCasing.txt", "efc25faf19de21b92c1194c111c932e03d2a5eaf18194e33f1156e96de4c9588").lineSequence()) {
			val content = line.substringBefore('#').trim()
			if (content.isEmpty()) continue
			val fields = content.split(';').map { it.trim() }
			if (fields[4].isNotEmpty()) continue
			for ((upper, field) in listOf(true to 3, false to 1)) mappings.getValue(upper)[fields[0].toInt(16)] = fields[field].split(' ').filter { it.isNotBlank() }.map { it.toInt(16) }
		}
		val cased = BitSet(0x110000)
		val ignored = BitSet(0x110000)
		for (line in raw("DerivedCoreProperties.txt", "24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08").lineSequence()) {
			val fields = line.substringBefore('#').split(';').map { it.trim() }
			if (fields.size < 2) continue
			val bits = when (fields[1]) {
				"Cased" -> cased; "Case_Ignorable" -> ignored; else -> continue
			}
			val ends = fields[0].split("..")
			bits.set(ends.first().toInt(16), ends.last().toInt(16) + 1)
		}
		Oracle(mappings.mapValues { (_, entries) -> entries.filter { it.value != listOf(it.key) } }, cased, ignored)
	}

	@Test
	fun `string case registrations retain independent string results and immutable pinned data`() {
		val provider = PrimitiveAttachments.string(nativeTestLayout)
		for ((name, registration) in listOf("toUpperCase" to provider.TO_UPPER_CASE, "toLowerCase" to provider.TO_LOWER_CASE)) {
			assertSame(registration, PrimitiveAttachments[ValueType.STRING, name, nativeTestLayout])
			assertEquals(ValueType.STRING, registration.overload.returns)
			assertEquals(emptyList(), registration.parameters)
			assertEquals(BuiltinStorageContract.READ_ONLY_INDEPENDENT_RESULT, registration.overload.storage)
			assertEquals(ValueType.CHAR, PrimitiveAttachments[ValueType.CHAR, name, nativeTestLayout]?.overload?.returns)
		}
		assertEquals("17.0.0", UnicodeStringCaseMappings.VERSION)
		assertEquals(listOf(1580, 1488), listOf(true, false).map { UnicodeStringCaseMappings.mappings(it).size })
		for (upper in listOf(true, false)) {
			assertEquals(oracle.mappings.getValue(upper), UnicodeStringCaseMappings.mappings(upper).associate { it.source to it.targets })
			assertFailsWith<UnsupportedOperationException> { (UnicodeStringCaseMappings.mappings(upper) as MutableList).clear() }
			assertFailsWith<UnsupportedOperationException> { (UnicodeStringCaseMappings.mappings(upper).first().targets as MutableList).clear() }
		}
		for (property in UnicodeStringCaseProperty.entries) assertFailsWith<UnsupportedOperationException> { (UnicodeStringCaseMappings.ranges(property) as MutableList).clear() }
	}

	@Test
	fun `all full mappings execute through string conversion with independent official expectations`(@TempDir directory: Path) {
		val points = (oracle.mappings.values.flatMap { it.keys } + listOf(0, 0x7f, 0x80, 0x7ff, 0x800, 0x378, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x10ffff)).toSortedSet()
		val checks = points.chunked(48).mapIndexed { index, chunk ->
			// NUL separates every source scalar so final sigma cannot acquire a cased predecessor.
			val text = chunk.joinToString("\u0000") { String(Character.toChars(it)) }
			"if (!check(${literal(text)}, ${literal(oracle.convert(text, true))}, ${literal(oracle.convert(text, false))})) { println($index); return 1 }"
		}
		native(directory, "full_mappings", """
			fn<bool> check(string text, string upper, string lower) = text.toUpperCase() == upper && text.toLowerCase() == lower
			fn<int> main() {
			    ${checks.joinToString("\n")}
			    return 0
			}
		""")
	}

	@Test
	fun `final sigma uses original scalar context and possessive case ignorable sequences`(@TempDir directory: Path) {
		val prefixes = listOf("", "A", "α", "1", "!", "\u0345", "A\u0345", "A'", "A\u0301", "A\u200d", "AΣ", "A\u02b0")
		val suffixes = listOf("", "A", "α", "1", "!", "\u0345", "\u0345A", "'\u0301", "'\u0301A", "\u200d", "\u200dA")
		val cases = prefixes.flatMap { before -> suffixes.map { after -> before + "Σ" + after } } + listOf("AΣ" + "\u0301".repeat(4096), "AΣ" + "\u0301".repeat(4096) + "B", "\u0345".repeat(4096) + "Σ")
		val checks = cases.mapIndexed { index, text -> "if (lower(${literal(text)}) != ${literal(oracle.convert(text, false))}) { println($index); return 1 }" }
		native(directory, "sigma", "fn<string> lower(string text) = text.toLowerCase()\nfn<int> main() {\n${checks.joinToString("\n")}\nreturn 0\n}")
	}

	@Test
	fun `mapping and context helpers agree for every valid scalar on native targets`() = IntegerKernelProgram.scratch { directory ->
		val mappingRows = listOf(true, false).map { upper -> oracle.mappings.getValue(upper).map { (source, targets) -> listOf(source, targets.size) + targets + List(3 - targets.size) { 0 } } }
		fun ranges(bits: BitSet): List<List<Int>> = buildList {
			var first = bits.nextSetBit(0)
			while (first >= 0) {
				val end = bits.nextClearBit(first)
				add(listOf(first, end - 1)); first = bits.nextSetBit(end)
			}
		}

		val rows = mappingRows + listOf(ranges(oracle.cased), ranges(oracle.ignored))
		for (abi in IntegerKernelProgram.targets()) IntegerKernelProgram.run(abi, directory, "string-case-all-${abi.architecture}") {
			case("full_mappings_and_properties") {
				val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
				val word = LLVMInt32TypeInContext(context)
				val bool = LLVMInt1TypeInContext(context)
				val tables = rows.mapIndexed { kind, entries ->
					val sentinel = if (kind < 2) listOf(0x110000, 1, 0x110000, 0, 0) else listOf(0x110000, 0x110000)
					val flattened = (entries + listOf(sentinel)).flatten()
					val table = LLVMAddGlobal(module, LLVMArrayType2(word, flattened.size.toLong()), "oracle.case.$kind")
					PointerPointer<LLVMValueRef>(flattened.size.toLong()).use { values ->
						flattened.forEachIndexed { index, value -> values.put(index.toLong(), int(value)) }
						LLVMSetInitializer(table, LLVMConstArray2(word, values, flattened.size.toLong()))
					}
					LLVMSetGlobalConstant(table, 1); LLVMSetLinkage(table, LLVMPrivateLinkage)
					table
				}
				val function = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
				val entry = LLVMGetInsertBlock(builder)
				fun block(name: String) = LLVMAppendBasicBlockInContext(context, function, name)
				val test = block("scalar_check")
				val body = block("scalar_body")
				val advance = block("scalar_advance")
				val done = block("scalar_done")
				LLVMBuildBr(builder, test)
				LLVMPositionBuilderAtEnd(builder, test)
				val point = LLVMBuildPhi(builder, word, "code_point")
				val cursors = rows.map { LLVMBuildPhi(builder, word, "oracle_cursor") }
				LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, point, int(0x110000), "more_scalars"), body, done)
				LLVMPositionBuilderAtEnd(builder, body)
				val nextCursors = mutableListOf<LLVMValueRef>()
				val production = EmitStringCaseData(context, builder)
				var correct = LLVMConstInt(bool, 1, 0)
				for (kind in rows.indices) {
					val index = LLVMBuildMul(builder, cursors[kind], int(if (kind < 2) 5 else 2), "oracle_index")
					fun load(column: Int) = PointerPointer<LLVMValueRef>(1L).use { indices ->
						indices.put(0, LLVMBuildAdd(builder, index, int(column), "oracle_column"))
						LLVMBuildLoad2(builder, word, LLVMBuildInBoundsGEP2(builder, word, tables[kind], indices, 1, "oracle_address"), "oracle_value")
					}

					val first = load(0)
					val last = if (kind < 2) first else load(1)
					val matches = LLVMBuildAnd(builder, LLVMBuildICmp(builder, LLVMIntUGE, point, first, "after_first"), LLVMBuildICmp(builder, LLVMIntULE, point, last, "before_last"), "oracle_matches")
					val atEnd = LLVMBuildICmp(builder, LLVMIntEQ, point, last, "last_oracle_scalar")
					nextCursors.add(LLVMBuildSelect(builder, atEnd, LLVMBuildAdd(builder, cursors[kind], int(1), "advance_oracle"), cursors[kind], "next_cursor"))
					if (kind < 2) {
						val actual = production.mapping(point, kind == 0)
						for (column in 0..3) {
							val fallback = if (column == 0) int(1) else if (column == 1) point else int(0)
							val wanted = LLVMBuildSelect(builder, matches, load(column + 1), fallback, "expected_mapping")
							correct = LLVMBuildAnd(builder, correct, LLVMBuildICmp(builder, LLVMIntEQ, LLVMBuildExtractValue(builder, actual, column, "actual_mapping"), wanted, "mapping_matches"), "all_match")
						}
					} else {
						val actual = production.property(point, if (kind == 2) UnicodeStringCaseProperty.CASED else UnicodeStringCaseProperty.CASE_IGNORABLE)
						correct = LLVMBuildAnd(builder, correct, LLVMBuildICmp(builder, LLVMIntEQ, actual, matches, "property_matches"), "all_match")
					}
				}
				LLVMBuildCondBr(builder, correct, advance, done)
				LLVMPositionBuilderAtEnd(builder, advance)
				val next = LLVMBuildAdd(builder, point, int(1), "next_point")
				val nextScalar = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, next, int(0xd800), "skip_surrogates"), int(0xe000), next, "next_scalar")
				LLVMBuildBr(builder, test)
				fun incoming(phi: LLVMValueRef, first: LLVMValueRef, later: LLVMValueRef) {
					PointerPointer<LLVMValueRef>(2L).use { values ->
						PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
							values.put(0, first); values.put(1, later); blocks.put(0, entry); blocks.put(1, advance); LLVMAddIncoming(phi, values, blocks, 2)
						}
					}
				}
				incoming(point, int(0), nextScalar)
				for (kind in rows.indices) incoming(cursors[kind], int(0), nextCursors[kind])
				LLVMPositionBuilderAtEnd(builder, done)
				val passed = LLVMBuildPhi(builder, bool, "all_full_case_data_match")
				PointerPointer<LLVMValueRef>(2L).use { values ->
					PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
						values.put(0, LLVMConstInt(bool, 1, 0)); values.put(1, LLVMConstInt(bool, 0, 0)); blocks.put(0, test); blocks.put(1, body); LLVMAddIncoming(passed, values, blocks, 2)
					}
				}
				check(passed, "full mappings and both context properties for all 1112064 valid scalars")
				for (kind in rows.indices) status(cursors[kind], rows[kind].size, "all independent oracle rows consumed")
			}
		}
	}

	@Test
	fun `receivers evaluate once and nullable proofs early returns and intrinsic errors remain ordinary`(@TempDir directory: Path) {
		native(directory, "effects", """
			fn<string> upper(string? text) { if (text != null) return text.toUpperCase(); return "fallback" }
			fn<string> lower(string? text) = (text ?: "İ").toLowerCase()
			fn<int> stop() { (if (true) { return 3 } else { "bad" }).toLowerCase() }
			fn main() {
			    def calls = 0
			    def<string> next = { calls++; print("R"); return "Straße" }
			    println(next.toUpperCase())
			    println(next.toLowerCase())
			    println(calls)
			    println(upper("Straße")); println(lower(null))
			    def<string?> saved = "ΟΣ".toLowerCase()
			    println(saved.endsWith("ς"))
			    false && next.toUpperCase().isEmpty()
			    true || next.toLowerCase().isEmpty()
			    println(calls); println(stop())
			}
		""", "RSTRASSE\nRstraße\n2\nSTRASSE\ni\u0307\ntrue\n2\n3\n")
		failure("fn f(string? text) { text.toUpperCase() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn main() { \"x\".toLowerCase(1) }", DiagnosticCode.CALL_ARITY)
		failure("fn main() { \"x\".toUpperCase(locale = \"tr\") }", DiagnosticCode.INVALID_ARGUMENT_BINDING)
		failure("fn main() { string.toLowerCase() }", DiagnosticCode.TYPE_RECEIVER_VALUE)
		failure("fn<char> f(string text) = text.toUpperCase()", DiagnosticCode.RETURN_TYPE)
		failure("fn take(char c) {}\nfn take(int i) {}\nfn main() { take(\"x\".toLowerCase(true)) }", DiagnosticCode.CALL_ARITY)
	}

	@Test
	fun `independent string results survive copies recursive ordered and deferred returns and arrays`(@TempDir directory: Path) {
		native(directory, "results", """
			fn<string> text() = "Stra" + "ße"
			fn<string, string> pair() = text().toUpperCase(), "ΟΣ".toLowerCase()
			fn<string> recurse(int n) = if (n == 0) { text().toUpperCase() } else { recurse(n - 1).toLowerCase() }
			fn main() {
			    def upper, lower = pair()
			    pair() -> void
			    println(upper); println(lower)
			    println(recurse(3))
			    def<string[]> data = [text().toUpperCase(), "İ".toLowerCase()]
			    def copy = data
			    copy[0] = "changed"
			    println(data[0]); println(copy[0]); println(data[1]::size)
			    def<string> deferred = { return text().toLowerCase() }
			    println(deferred)
			    println("  Straße  ".trim().toUpperCase().substring(4, 6))
			    println("A\0ß😀".toUpperCase()::size)
			}
		""", "STRASSE\nος\nstrasse\nSTRASSE\nchanged\n2\nstraße\nSS\n5\n")
	}

	@Test
	fun `full casing is independent of child locale and leaves char conversions unchanged`(@TempDir directory: Path) {
		val source = """
			fn main() {
			    println("iIİı".toUpperCase()); println("iIİı".toLowerCase())
			    println("ßﬃ".toUpperCase()); println("".toLowerCase().isEmpty())
			    println('ß'.toUpperCase()); println('İ'.toLowerCase())
			}
		"""
		val executable = compile(directory, "locales", source)
		val expected = "IIİI\niii\u0307ı\nSSFFI\ntrue\nß\ni\n".replace("\n", System.lineSeparator()) + processCompletion()
		for (locale in listOf("C", "tr_TR.UTF-8", "lt_LT.UTF-8")) {
			val launch = ProcessBuilder(executable.toString()).redirectErrorStream(true)
			launch.environment()["LANG"] = locale; launch.environment()["LC_ALL"] = locale
			val process = launch.start()
			try {
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), locale)
				val output = process.inputStream.readBytes().decodeToString(throwOnInvalidSequence = true)
				assertEquals(0, process.exitValue(), output)
				assertEquals(expected, output, locale)
			} finally {
				if (process.isAlive) process.destroyForcibly()
			}
		}
	}

	@Test
	fun `invalid casing stops before a second diagnostic output and linker work`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("invalid.co"), "fn main() { \"x\".toUpperCase(true); \"x\".charAt(-1) }")
		val output = directory.resolve("invalid.o")
		val diagnostic = StringBuilder()
		assertEquals(1, runCompiler(arrayOf("--emit", "object", source.toString(), output.toString(), "--linker", "missing-linker"), DiagnosticReporter(diagnostic)))
		assertContains(diagnostic.toString(), "C3023")
		assertFalse(diagnostic.toString().contains("C3004"))
		assertFalse(Files.exists(output))
	}

	@Test
	fun `string casing verifies IR and objects across every target`(@TempDir directory: Path) {
		val ast = unit("""
			fn<string, string> change(string text) {
			    text.toUpperCase()
			    return text.toUpperCase(), text.toLowerCase()
			}
		""")
		for ((index, triple) in IntegerKernelProgram.triples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emit(ast)
			assertContains(ir, "string_case_check")
			assertContains(ir, "cloth.string.unicode.case_ignorable")
			assertContains(ir, "llvm.stackrestore.p0")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("case_$index.o"))) > 0)
		}
	}

	@Test
	fun `maintained example and discarded string storage execute on native targets`(@TempDir directory: Path) {
		val example = Files.readString(Path.of("examples", "StringCase.co"))
		val payload = "ß".repeat(2048)
		for ((index, abi) in IntegerKernelProgram.targets().withIndex()) {
			native(directory, "example_$index", example, "STRASSE\ni\u0307\nος\nοσα\n4\nß\ni\n", "--target", abi.targetTriple)
			native(directory, "bounded_$index", """
				fn<string> fresh() = "kept"
				fn<int> main() {
				    def saved = fresh()
				    def published = fresh()
				    for (def i <- 0..300) {
				        published = fresh()
				        "$payload".toUpperCase()
				        "$payload".toLowerCase()
				    }
				    if (saved != "kept" || published != "kept") return 1
				    return 0
				}
			""", "", "--target", abi.targetTriple)
		}
	}

	private fun compile(directory: Path, name: String, source: String, vararg options: String): Path {
		val input = Files.writeString(directory.resolve("$name.co"), source.trimIndent())
		return compileArtifact(CompilerSettings(arrayOf(input.toString(), directory.resolve(name).toString(), *options)))
	}

	private fun native(directory: Path, name: String, source: String, expected: String = "", vararg options: String) {
		assertEquals(expected.replace("\n", System.lineSeparator()) + processCompletion(), runExecutable(compile(directory, name, source, *options)))
	}
}
