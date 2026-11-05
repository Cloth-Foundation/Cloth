package cloth

import cloth.backend.IntegerKernelProgram
import cloth.builtin.BuiltinStorageContract
import cloth.builtin.attachments.CharPredicate
import cloth.builtin.attachments.PrimitiveAttachments
import cloth.data.UnicodeCharacterProperties
import cloth.data.UnicodeCharacterProperty
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.types.primitives.EmitCharMember
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticAnalyzer
import cloth.token.Operators
import org.bytedeco.javacpp.BytePointer
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

class CharAttachmentsTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("char-members.co", source.trimIndent())).scanTokens()).parse()
	private fun scalar(point: Int) = "${Operators.QUOTATION}${String(Character.toChars(point))}${Operators.QUOTATION}"
	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(unit(source)) }
		assertEquals(code, error.diagnosticCode, source)
		assertLocated(error)
	}

	/** Independent raw-UCD parser, without production ranges or JVM Unicode predicates. */
	private fun oracle(): ByteArray {
		val expected = ByteArray(0x110000)
		val inputs = listOf(
			"DerivedGeneralCategory.txt" to "d62e5bab70ca74f099343f71224fa051cb1fdd61a1ab45c0488c44cfc0b6102e",
			"DerivedCoreProperties.txt" to "24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08",
			"PropList.txt" to "130dcddcaadaf071008bdfce1e7743e04fdfbc910886f017d9f9ac931d8c64dd",
		)
		for ((name, hash) in inputs) {
			val bytes = GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/cloth/unicode/17.0.0/$name.gz"))).use { it.readBytes() }
			assertEquals(hash, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
			assertContains(bytes.toString(Charsets.UTF_8).lineSequence().first(), "17.0.0")
			for (line in bytes.toString(Charsets.UTF_8).lineSequence()) {
				val fields = line.substringBefore('#').split(';').map { it.trim() }
				if (fields.size != 2) continue
				val flag = when (name) {
					"DerivedGeneralCategory.txt" -> when (fields[1]) {
						"Lu", "Ll", "Lt", "Lm", "Lo" -> 2; "Nd" -> 4; else -> 0
					}

					"DerivedCoreProperties.txt"  -> when (fields[1]) {
						"Uppercase" -> 16; "Lowercase" -> 32; else -> 0
					}

					else                         -> if (fields[1] == "White_Space") 1 else 0
				}
				if (flag == 0) continue
				val ends = fields[0].split("..")
				for (point in ends.first().toInt(16)..ends.last().toInt(16)) expected[point] = (expected[point].toInt() or flag).toByte()
			}
		}
		for (point in expected.indices) if (expected[point].toInt() and 6 != 0) expected[point] = (expected[point].toInt() or 8).toByte()
		return expected
	}

	@Test
	fun `named registrations expose only approved methods and immutable pinned ranges`() {
		val chars = PrimitiveAttachments.CHAR
		val predicates = listOf(chars.IS_WHITESPACE, chars.IS_LETTER, chars.IS_DIGIT, chars.IS_LETTER_OR_DIGIT, chars.IS_UPPER_CASE, chars.IS_LOWER_CASE)
		assertEquals(predicates + listOf(chars.TO_INT, chars.TO_UPPER_CASE, chars.TO_LOWER_CASE), chars.all.toList())
		for (method in chars.all) {
			assertSame(method, PrimitiveAttachments[ValueType.CHAR, method.definition.name])
			assertEquals(emptyList(), method.parameters)
			assertEquals(ValueType.CHAR, method.receiver)
			assertEquals(BuiltinStorageContract.READ_ONLY_INDEPENDENT_RESULT, method.overload.storage)
		}
		assertTrue(predicates.all { it.overload.returns == ValueType.BOOL })
		assertEquals(ValueType.INT, chars.TO_INT.overload.returns)
		assertNull(PrimitiveAttachments[ValueType.STRING, "isLetter"])
		assertEquals(ValueType.CHAR, PrimitiveAttachments[ValueType.CHAR, "toUpperCase"]?.overload?.returns)
		assertEquals(ValueType.CHAR, PrimitiveAttachments[ValueType.CHAR, "toLowerCase"]?.overload?.returns)
		assertNull(PrimitiveAttachments[ValueType.CHAR, "IsDigit"])
		assertNotSame(chars.TO_INT, PrimitiveAttachments.BOOL.TO_INT)
		assertEquals("17.0.0", UnicodeCharacterProperties.VERSION)
		for ((property, counts) in listOf(UnicodeCharacterProperty.LETTER to (684 to 145672), UnicodeCharacterProperty.DIGIT to (72 to 770), UnicodeCharacterProperty.UPPERCASE to (660 to 2006), UnicodeCharacterProperty.LOWERCASE to (677 to 2595))) {
			val ranges = UnicodeCharacterProperties.ranges(property)
			assertEquals(counts.first, ranges.size)
			assertEquals(counts.second, ranges.sumOf { it.last - it.first + 1 })
			assertFailsWith<UnsupportedOperationException> { (ranges as MutableList).clear() }
		}
	}

	@Test
	fun `all valid Unicode scalars match independently parsed official inputs on both native targets`() = IntegerKernelProgram.scratch { directory ->
		val expected = oracle()
		assertEquals(25, expected.count { it.toInt() and 1 != 0 })
		assertEquals(26, expected[0x16ea0].toInt()) // Letter, LetterOrDigit and Uppercase.
		assertEquals(16, expected[0x24b6].toInt()) // Circled A: uppercase, not Letter.
		assertEquals(32, expected[0x345].toInt()) // Lowercase combining mark, not Letter.
		for (abi in IntegerKernelProgram.targets()) IntegerKernelProgram.run(abi, directory, "char-all-scalars-${abi.architecture}") {
			case("unicode_scalars") {
				val module = LLVMGetGlobalParent(LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder)))
				val byte = LLVMInt8TypeInContext(context)
				val bool = LLVMInt1TypeInContext(context)
				val word = LLVMInt32TypeInContext(context)
				val initializer = BytePointer(*expected).use { LLVMConstStringInContext2(context, it, expected.size.toLong(), 1) }
				val reference = LLVMAddGlobal(module, LLVMTypeOf(initializer), "test.unicode.expected")
				LLVMSetInitializer(reference, initializer); LLVMSetGlobalConstant(reference, 1); LLVMSetLinkage(reference, LLVMPrivateLinkage)
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
				LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntULT, point, int(0x110000), "more_scalars"), body, done)
				LLVMPositionBuilderAtEnd(builder, body)
				var actual = LLVMConstInt(byte, 0, 0)
				val emission = EmitCharMember(context, builder)
				for ((operation, bit) in listOf(CharPredicate.WHITESPACE to 1, CharPredicate.LETTER to 2, CharPredicate.DIGIT to 4, CharPredicate.LETTER_OR_DIGIT to 8, CharPredicate.UPPERCASE to 16, CharPredicate.LOWERCASE to 32)) {
					val mask = LLVMBuildSelect(builder, emission.predicate(point, operation), LLVMConstInt(byte, bit.toLong(), 0), LLVMConstInt(byte, 0, 0), "classification_bit")
					actual = LLVMBuildOr(builder, actual, mask, "classifications")
				}
				val wanted = PointerPointer<LLVMValueRef>(1L).use { indices ->
					indices.put(0, point); LLVMBuildLoad2(builder, byte, LLVMBuildInBoundsGEP2(builder, byte, reference, indices, 1, "expected_address"), "expected_flags")
				}
				LLVMBuildCondBr(builder, LLVMBuildICmp(builder, LLVMIntEQ, actual, wanted, "matches_oracle"), advance, done)
				LLVMPositionBuilderAtEnd(builder, advance)
				val next = LLVMBuildAdd(builder, point, int(1), "next_point")
				val scalar = LLVMBuildSelect(builder, LLVMBuildICmp(builder, LLVMIntEQ, next, int(0xd800), "skip_surrogates"), int(0xe000), next, "next_scalar")
				LLVMBuildBr(builder, test)
				PointerPointer<LLVMValueRef>(2L).use { values ->
					PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
						values.put(0, int(0)); values.put(1, scalar); blocks.put(0, entry); blocks.put(1, advance); LLVMAddIncoming(point, values, blocks, 2)
					}
				}
				LLVMPositionBuilderAtEnd(builder, done)
				val passed = LLVMBuildPhi(builder, bool, "all_scalars_match")
				PointerPointer<LLVMValueRef>(2L).use { values ->
					PointerPointer<LLVMBasicBlockRef>(2L).use { blocks ->
						values.put(0, LLVMConstInt(bool, 1, 0)); values.put(1, LLVMConstInt(bool, 0, 0)); blocks.put(0, test); blocks.put(1, body); LLVMAddIncoming(passed, values, blocks, 2)
					}
				}
				check(passed, "all 1112064 valid Unicode scalars")
			}
		}
	}

	@Test
	fun `source classification code points nullable proofs snapshots and results execute on both targets`(@TempDir directory: Path) {
		val source = """
			fn<char> receiver() { print("R"); return '中' }
			fn<char> recurse(int n) = if (n == 0) { '😀' } else { recurse(n - 1) }
			fn<int, bool> describe(char c) = c.toInt(), c.isLetter()
			fn<int> maybe(char? c) { if (c != null) return c.toInt(); return -1 }
			fn<int> main() {
			    println(receiver().isLetter())
			    def<char> value = '7'
			    def saved = value.toInt()
			    value = 'A'
			    println(saved); println(value.toInt())
			    println('٧'.isDigit()); println('²'.isDigit())
			    println('Ⓐ'.isLetter()); println('Ⓐ'.isUpperCase())
			    println('ª'.isLowerCase()); println('ǅ'.isLetter()); println('ǅ'.isUpperCase()); println('ǅ'.isLowerCase())
			    println('́'.isLetter()); println('\0'.isWhitespace()); println('　'.isWhitespace())
			    println(maybe(null)); println(maybe('x'))
			    def<char?> absent = null
			    println((absent ?: '0').toInt())
			    def point, letter = describe('é')
			    println(point); println(letter)
			    println(recurse(3).toInt())
			    println("A😀中".toChars()[2].isLetter())
			    println('x'.isLetter().toInt()); println('7'.toInt().clamp(0, 100))
			    if (${scalar(0x10ffff)}.toInt() != 1114111 || ${scalar(0x16ea0)}.isUpperCase() != true) return 1
			    if (${scalar(0x16ebb)}.isLowerCase() != true || ${scalar(0x11db0)}.isLetter() != true) return 2
			    println('😀'.toInt()::type)
			    return 0
			}
		"""
		val expected = "Rtrue\n55\n65\ntrue\nfalse\nfalse\ntrue\ntrue\ntrue\nfalse\nfalse\nfalse\nfalse\ntrue\n-1\n120\n48\n233\ntrue\n128512\ntrue\n1\n55\nint<32>\n" + completion()
		for (abi in IntegerKernelProgram.targets()) assertEquals(expected, execute(directory, "source_${abi.architecture}", source, abi))
	}

	@Test
	fun `arity namespaces nullable receivers and existing scalar restrictions remain strict`() {
		failure("fn main() { 'a'.isLetter(true) }", DiagnosticCode.CALL_ARITY)
		failure("fn main() { 'a'.toInt(1) }", DiagnosticCode.CALL_ARITY)
		failure("fn main() { 'a'.isWhitespace(value = true) }", DiagnosticCode.INVALID_ARGUMENT_BINDING)
		failure("fn f(char? c) { c.isDigit() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn main() { \"a\".isLetter() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn main() { char.toInt() }", DiagnosticCode.TYPE_RECEIVER_VALUE)
		failure("fn main() { 'a'::isDigit() }", DiagnosticCode.UNSUPPORTED_STATE_CALL)
		failure("fn main() { isDigit() }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn<int<64>> f(char c) = c.toInt()", DiagnosticCode.RETURN_TYPE)
		failure("fn main() { '7' -> int }", DiagnosticCode.INVALID_CAST)
		failure("fn main() { 'a' + 1 }", DiagnosticCode.BINARY_OPERANDS)
		failure("fn f(bool x) {}\nfn f(int x) {}\nfn main() { f('a'.isDigit(true)) }", DiagnosticCode.CALL_ARITY)
		failure("fn main() { false && 'a'.isDigit(true) }", DiagnosticCode.CALL_ARITY)
	}

	@Test
	fun `early returns skipped receivers and captured deferred character reads preserve evaluation`(@TempDir directory: Path) {
		val source = """
			fn<char> bad() { print("BAD"); return 'x' }
			fn<int> stop() { (if (true) { return 7 } else { 'x' }).toInt() }
			fn<int> classify() { (if (true) { return 8 } else { 'x' }).isLetter() }
			fn main() {
			    false && bad().isLetter()
			    true || bad().isDigit()
			    println(stop()); println(classify())
			    def n = 0
			    def<char> next = { n++; return if (n == 1) { 'A' } else { '7' } }
			    println(next.isLetter()); println(next.isDigit()); println(n)
			}
		"""
		assertEquals("7\n8\ntrue\ntrue\n2\n" + completion(), execute(directory, "flow", source, NativeCABI.native()))
	}

	@Test
	fun `example and parameterized helpers verify and emit objects for all nine profiles`(@TempDir directory: Path) {
		val source = Files.readString(Path.of("examples", "CharAttachments.co"))
		val example = unit(source)
		val fixture = unit("""
			fn<bool, bool, bool, bool, bool, bool, int> inspect(char c) = c.isWhitespace(), c.isLetter(), c.isDigit(), c.isLetterOrDigit(), c.isUpperCase(), c.isLowerCase(), c.toInt()
		""")
		for ((index, triple) in IntegerKernelProgram.triples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			val ir = emitter.emit(fixture)
			assertContains(ir, "cloth.character.letter")
			assertContains(ir, "private constant")
			assertFalse(ir.contains("iswalpha")); assertFalse(ir.contains("iswdigit"))
			assertTrue(Files.size(emitter.compileObject(fixture, directory.resolve("chars_$index.o"))) > 0)
			assertTrue(Files.size(emitter.compileObject(example, directory.resolve("example_$index.o"))) > 0)
		}
		for (abi in IntegerKernelProgram.targets()) assertEquals("true\ntrue\ntrue\ntrue\ntrue\ntrue\n55\n128512\nfalse\n" + completion(), execute(directory, "example_${abi.architecture}", source, abi))
		val snippet = Parser(Lexer("'7'.toInt()").scanTokens()).parseSnippet()
		assertEquals(ValueType.INT, SemanticAnalyzer().analyze(snippet).typeOf((snippet.statements.single() as cloth.parser.statement.ExpressionStatement).expression))
	}

	@Test
	fun `first invalid member stops CLI before object linker or later diagnostics`(@TempDir directory: Path) {
		val source = directory.resolve("invalid.co")
		val objectFile = directory.resolve("invalid.o")
		Files.writeString(source, "fn main() { 'a'.isLetter(true); \"x\".isDigit() }")
		val diagnostic = StringBuilder()
		assertEquals(1, runCompiler(arrayOf("--emit", "object", source.toString(), objectFile.toString(), "--linker", "missing-linker"), DiagnosticReporter(diagnostic)))
		assertContains(diagnostic.toString(), "C3023")
		assertFalse(diagnostic.toString().contains("C3071")); assertFalse(Files.exists(objectFile))
	}

	private fun completion() = processCompletion().replace("\r\n", "\n")
	private fun execute(directory: Path, name: String, source: String, abi: NativeCABI): String {
		val executable = LLVMEmitter(abi).compile(unit(source), directory.resolve(name))
		val log = directory.resolve("$name.log")
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
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
