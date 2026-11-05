package cloth

import cloth.backend.IntegerKernelProgram
import cloth.builtin.BuiltinStorageContract
import cloth.builtin.attachments.PrimitiveAttachments
import cloth.data.UnicodeCharacterCaseMappings
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.types.EmitCharCase
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.FunctionAnalyzer
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

class CharCaseTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("char-case.co", source.trimIndent())).scanTokens()).parse()
	private fun failure(source: String, code: DiagnosticCode) {
		val diagnostic = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(unit(source)) }
		assertEquals(code, diagnostic.diagnosticCode, source)
		assertLocated(diagnostic)
	}

	private fun oracle(): Map<Boolean, List<Pair<Int, Int>>> {
		val bytes = GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/cloth/unicode/17.0.0/UnicodeData.txt.gz"))).use { it.readBytes() }
		assertEquals("2e1efc1dcb59c575eedf5ccae60f95229f706ee6d031835247d843c11d96470c", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
		// Independent raw-file parser: column 12 is simple uppercase, 13 lowercase.
		// Missing fields deliberately stay absent; the native oracle returns input.
		val entries = mutableMapOf(true to mutableListOf<Pair<Int, Int>>(), false to mutableListOf())
		for (line in bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }) {
			val fields = line.split(';')
			assertEquals(15, fields.size)
			for ((upper, field) in listOf(true to 12, false to 13)) if (fields[field].isNotEmpty()) {
				val source = fields[0].toInt(16)
				val target = fields[field].toInt(16)
				assertTrue(source in 0..0x10ffff && source !in 0xd800..0xdfff)
				assertTrue(target in 0..0x10ffff && target !in 0xd800..0xdfff)
				entries.getValue(upper).add(source to target)
			}
		}
		return entries.mapValues { (_, rows) -> rows.sortedBy { it.first } }
	}

	@Test
	fun `case registrations have exact char contracts and immutable pinned mappings`() {
		for ((name, registration) in listOf("toUpperCase" to PrimitiveAttachments.CHAR.TO_UPPER_CASE, "toLowerCase" to PrimitiveAttachments.CHAR.TO_LOWER_CASE)) {
			assertSame(registration, PrimitiveAttachments[ValueType.CHAR, name])
			assertEquals(ValueType.CHAR, registration.receiver)
			assertEquals(ValueType.CHAR, registration.overload.returns)
			assertEquals(emptyList(), registration.parameters)
			assertEquals(BuiltinStorageContract.READ_ONLY_INDEPENDENT_RESULT, registration.overload.storage)
			assertNull(PrimitiveAttachments[ValueType.STRING, name])
		}
		assertEquals("17.0.0", UnicodeCharacterCaseMappings.VERSION)
		assertEquals(1505, UnicodeCharacterCaseMappings.mappings(true).size)
		assertEquals(1488, UnicodeCharacterCaseMappings.mappings(false).size)
		for (upper in listOf(true, false)) assertFailsWith<UnsupportedOperationException> { (UnicodeCharacterCaseMappings.mappings(upper) as MutableList).clear() }
	}

	@Test
	fun `both simple mappings match raw Unicode data for every valid scalar on both native targets`() = IntegerKernelProgram.scratch { directory ->
		val expected = oracle()
		for (abi in IntegerKernelProgram.targets()) IntegerKernelProgram.run(abi, directory, "char-case-all-${abi.architecture}") {
			case("all_scalars") {
				val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
				val word = LLVMInt32TypeInContext(context)
				val bool = LLVMInt1TypeInContext(context)
				// A sorted sequential cursor is independent of the production binary
				// search, and avoids a dense multi-megabyte expected-value table.
				val tables = expected.mapValues { (upper, rows) ->
					val withSentinel = rows + (0x110000 to 0x110000)
					val table = LLVMAddGlobal(module, LLVMArrayType2(word, withSentinel.size * 2L), "test.case.$upper")
					PointerPointer<LLVMValueRef>(withSentinel.size * 2L).use { values ->
						withSentinel.forEachIndexed { index, (source, target) -> values.put(index * 2L, int(source)); values.put(index * 2L + 1, int(target)) }
						LLVMSetInitializer(table, LLVMConstArray2(word, values, withSentinel.size * 2L))
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
				val cursors = listOf(true, false).associateWith { LLVMBuildPhi(builder, word, "oracle_cursor") }
				LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, point, int(0x110000), "more_scalars"), body, done)
				LLVMPositionBuilderAtEnd(builder, body)
				val nextCursors = mutableMapOf<Boolean, LLVMValueRef>()
				var correct = LLVMConstInt(bool, 1, 0)
				for (upper in listOf(true, false)) {
					val cursor = cursors.getValue(upper)
					val index = LLVMBuildMul(builder, cursor, int(2), "oracle_index")
					fun load(offset: LLVMValueRef) = PointerPointer<LLVMValueRef>(1L).use { indices ->
						indices.put(0, offset); LLVMBuildLoad2(builder, word, LLVMBuildInBoundsGEP2(builder, word, tables.getValue(upper), indices, 1, "oracle_address"), "oracle_value")
					}

					val key = load(index)
					val target = load(LLVMBuildAdd(builder, index, int(1), "target_index"))
					val matches = LLVMBuildICmp(builder, LLVMIntEQ, point, key, "mapped_key")
					val wanted = LLVMBuildSelect(builder, matches, target, point, "expected_case")
					nextCursors[upper] = LLVMBuildSelect(builder, matches, LLVMBuildAdd(builder, cursor, int(1), "advance_oracle"), cursor, "next_cursor")
					val actual = EmitCharCase(context, builder).emit(point, upper)
					correct = LLVMBuildAnd(builder, correct, LLVMBuildICmp(builder, LLVMIntEQ, actual, wanted, "mapping_matches"), "both_match")
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
				for (upper in listOf(true, false)) incoming(cursors.getValue(upper), int(0), nextCursors.getValue(upper))
				LLVMPositionBuilderAtEnd(builder, done)
				val passed = LLVMBuildPhi(builder, bool, "all_simple_mappings_match")
				PointerPointer<LLVMValueRef>(2L).use { values ->
					PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
						values.put(0, LLVMConstInt(bool, 1, 0)); values.put(1, LLVMConstInt(bool, 0, 0)); blocks.put(0, test); blocks.put(1, body); LLVMAddIncoming(passed, values, blocks, 2)
					}
				}
				check(passed, "both simple mappings for all 1112064 valid scalars")
				for (upper in listOf(true, false)) status(cursors.getValue(upper), expected.getValue(upper).size, "all oracle entries consumed")
			}
		}
	}

	@Test
	fun `source calls retain char results snapshots nullable proofs ordered returns and once only effects`(@TempDir directory: Path) {
		val source = """
			fn<char> receiver() { print("R"); return 'a' }
			fn<char> toUpperCase() = '!'
			fn<char, char> pair(char c) = c.toUpperCase(), c.toLowerCase()
			fn<char> upper(char? c) { if (c != null) return c.toUpperCase(); return '?' }
			fn<char> lower(char? c) = (c ?: 'X').toLowerCase()
			fn<char> recursive(int n) = if (n == 0) { 'é'.toUpperCase() } else { recursive(n - 1).toLowerCase() }
			fn<int> main() {
			    println(receiver().toUpperCase())
			    def c = 'a'
			    def saved = c.toUpperCase()
			    c = 'b'
			    println(saved); println(c.toLowerCase())
			    def first, second = pair('é')
			    println(first); println(second)
			    println(upper(null)); println(upper('z')); println(lower(null)); println(lower('Z'))
			    println(recursive(3))
			    def calls = 0
			    def<char> read = { calls++; return c }
			    println(read.toUpperCase()); println(calls)
			    def<char[]> chars = ['a', 'Z', 'ß']
			    def copy = chars[0].toUpperCase()
			    chars[0] = 'x'
			    println(copy); println(chars[1].toLowerCase())
			    println("a😀".toChars()[0].toUpperCase())
			    println('a'.toUpperCase().isUpperCase()); println('ß'.toUpperCase().isUpperCase())
			    println('a'.toUpperCase()::type)
			    return 0
			}
		"""
		val expected = "RA\nA\nb\nÉ\né\n?\nZ\nx\nz\né\nB\n1\nA\nz\nA\ntrue\nfalse\nchar\n" + completion()
		for (abi in IntegerKernelProgram.targets()) assertEquals(expected, execute(directory, "values_${abi.architecture}", source, abi))
	}

	@Test
	fun `simple behavior is unchanged for unmapped values and independent of language sensitive casing`(@TempDir directory: Path) {
		val source = """
			fn<int> main() {
			    println('i'.toUpperCase()); println('I'.toLowerCase())
			    println('İ'.toLowerCase()); println('ı'.toUpperCase())
			    println('Σ'.toLowerCase()); println('ς'.toUpperCase())
			    println('ß'.toUpperCase()); println('ﬃ'.toUpperCase())
			    println('7'.toUpperCase()); println('😀'.toLowerCase())
			    println('\0'.toUpperCase().toInt())
			    println('ǅ'.toUpperCase()); println('ǅ'.toLowerCase())
			    return 0
			}
		"""
		for (abi in IntegerKernelProgram.targets()) assertEquals("I\ni\ni\nI\nσ\nΣ\nß\nﬃ\n7\n😀\n0\nǄ\nǆ\n" + completion(), execute(directory, "locale_${abi.architecture}", source, abi, "tr_TR.UTF-8"))
	}

	@Test
	fun `early exits short circuits and deferred reevaluation use normal member flow`(@TempDir directory: Path) {
		val source = """
			fn<char> bad() { print("BAD"); return 'a' }
			fn<int> stop() { (if (true) { return 1 } else { 'a' }).toUpperCase() }
			fn<int> main() {
			    false && bad().toUpperCase() == 'A'
			    true || bad().toLowerCase() == 'a'
			    println(stop())
			    def n = 0
			    while ('a'.toUpperCase() == 'A' && n < 1000) { n++ }
			    do { n-- } while ('Z'.toLowerCase() == 'z' && n > 0)
			    return n
			}
		"""
		assertEquals("1\n" + completion(), execute(directory, "flow", source, NativeCABI.native()))
	}

	@Test
	fun `strict receiver arity named and result errors remain intrinsic`() {
		failure("fn main() { 'a'.toUpperCase(true) }", DiagnosticCode.CALL_ARITY)
		failure("fn main() { 'A'.toLowerCase(locale = \"tr\") }", DiagnosticCode.INVALID_ARGUMENT_BINDING)
		failure("fn f(char? c) { c.toUpperCase() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn main() { \"a\".toUpperCase() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn main() { char.toLowerCase() }", DiagnosticCode.TYPE_RECEIVER_VALUE)
		failure("fn main() { 'a'::toUpperCase() }", DiagnosticCode.UNSUPPORTED_STATE_CALL)
		failure("fn main() { toLowerCase() }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn<int> f(char c) = c.toUpperCase()", DiagnosticCode.RETURN_TYPE)
		failure("fn<string> f(char c) = c.toLowerCase()", DiagnosticCode.RETURN_TYPE)
		failure("fn f(char c) {}\nfn f(int n) {}\nfn main() { f('a'.toUpperCase(true)) }", DiagnosticCode.CALL_ARITY)
		failure("fn main() { false && 'a'.toLowerCase(true) == 'a' }", DiagnosticCode.CALL_ARITY)
	}

	@Test
	fun `maintained example and parameterized casing verify objects on all profiles`(@TempDir directory: Path) {
		val source = Files.readString(Path.of("examples", "CharCase.co"))
		val example = unit(source)
		val fixture = unit("fn<char, char> change(char c) = c.toUpperCase(), c.toLowerCase()")
		for ((index, triple) in IntegerKernelProgram.triples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emit(fixture)
			assertContains(ir, "cloth.character.simple_uppercase")
			assertContains(ir, "cloth.character.simple_lowercase")
			assertTrue(Files.size(emitter.compileObject(fixture, directory.resolve("case_$index.o"))) > 0)
			assertTrue(Files.size(emitter.compileObject(example, directory.resolve("example_$index.o"))) > 0)
		}
		for (abi in IntegerKernelProgram.targets()) assertEquals("A\nz\nÉ\nσ\nß\ni\n😀\nchar\n" + completion(), execute(directory, "example_${abi.architecture}", source, abi))
	}

	@Test
	fun `first invalid casing call stops CLI before object linker and later errors`(@TempDir directory: Path) {
		val source = directory.resolve("invalid.co")
		val objectFile = directory.resolve("invalid.o")
		Files.writeString(source, "fn main() { 'a'.toUpperCase(true); \"x\".toLowerCase() }")
		val diagnostic = StringBuilder()
		assertEquals(1, runCompiler(arrayOf("--emit", "object", source.toString(), objectFile.toString(), "--linker", "missing-linker"), DiagnosticReporter(diagnostic)))
		assertContains(diagnostic.toString(), "C3023")
		assertFalse(diagnostic.toString().contains("C3071")); assertFalse(Files.exists(objectFile))
	}

	private fun completion() = processCompletion().replace("\r\n", "\n")
	private fun execute(directory: Path, name: String, source: String, abi: NativeCABI, locale: String? = null): String {
		val executable = LLVMEmitter(abi).compile(unit(source), directory.resolve(name))
		val log = directory.resolve("$name.log")
		val launch = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(log.toFile())
		if (locale != null) {
			launch.environment()["LC_ALL"] = locale; launch.environment()["LANG"] = locale
		}
		val process = launch.start()
		try {
			assertTrue(process.waitFor(60, TimeUnit.SECONDS), name)
			val output = Files.readString(log).replace("\r\n", "\n")
			assertEquals(0, process.exitValue(), output.take(2000))
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
