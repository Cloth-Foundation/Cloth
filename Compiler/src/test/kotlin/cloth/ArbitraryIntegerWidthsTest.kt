package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.expression.IntegerLiteral
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticAnalyzer
import cloth.semantic.SemanticModel
import cloth.target.TargetLayout
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ArbitraryIntegerWidthsTest {

	private val widths = listOf(1, 2, 3, 7, 8, 9, 12, 24, 31, 32, 33, 63, 64, 65, 127, 128, 129, 257, 1024, 4096, 65535, 65536)
	private val one = BigInteger.ONE
	private fun snippet(source: String) = Parser(Lexer(SourceFile("widths.co", source.trimIndent())).scanTokens()).parseSnippet()
	private fun unit(source: String) = Parser(Lexer(SourceFile("widths.co", source.trimIndent())).scanTokens()).parse()
	private fun check(source: String, target: TargetLayout = TargetLayout.native()): SemanticModel = SemanticAnalyzer(target).analyze(snippet(source))
	private fun declarations(source: String, target: TargetLayout = TargetLayout.native()) = FunctionAnalyzer(target).analyze(unit(source))
	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic {
		val failure = assertFailsWith<ClothDiagnostic> { check(source) }
		assertEquals(code, failure.diagnosticCode, source.take(200))
		assertLocated(failure)
		return failure
	}

	@Test
	fun `every whole fixed width constructs without enlarging machine or floating types`() {
		for (bits in 1..65536) for (unsigned in listOf(false, true)) {
			val type = CInt(bits, unsigned)
			assertEquals(bits, type.bits)
			assertEquals(unsigned, type.unsigned)
			assertTrue(type.acceptsLiteral(BigInteger.ZERO))
		}
		for (bits in listOf(Int.MIN_VALUE, -1, 0, 65537, Int.MAX_VALUE)) assertFailsWith<IllegalArgumentException> { CInt(bits) }
		for (bits in listOf(1, 7, 12, 31, 65, 65536)) {
			assertFailsWith<IllegalArgumentException> { CFloat(bits) }
			assertFailsWith<IllegalArgumentException> { CUSize(bits) }
			assertFailsWith<IllegalArgumentException> { CISize(bits) }
			assertFailsWith<IllegalArgumentException> { TargetLayout.native().copy(pointerBits = bits) }
			assertFailsWith<IllegalArgumentException> { TargetLayout.native().copy(sizeBits = bits) }
			assertFailsWith<IllegalArgumentException> { TargetLayout.native().copy(cLongBits = bits) }
		}
		assertEquals(CInt(32), ValueType.INT)
		assertEquals(CInt(32, true), ValueType.UINT)
		assertEquals(setOf(16, 32, 64), ValueType.FLOAT_WIDTHS)
	}

	@Test
	fun `signed and unsigned extrema are exact through the cap with contextual reassignment`() {
		for (bits in widths) for (unsigned in listOf(false, true)) {
			val type = CInt(bits, unsigned)
			val minimum = if (unsigned) BigInteger.ZERO else -one.shiftLeft(bits - 1)
			val maximum = one.shiftLeft(bits - if (unsigned) 0 else 1) - one
			assertEquals(minimum, type.minimum)
			assertEquals(maximum, type.maximum)
			for (value in listOf(minimum, maximum)) {
				val ast = snippet("def<${type.spelling}> x = $value\nx = $value")
				val model = SemanticAnalyzer().analyze(ast)
				assertTrue(ast.statements.all { model.typeOf(it) == type })
			}
			for (value in listOf(minimum - one, maximum + one)) {
				assertFalse(type.acceptsLiteral(value))
				failure("def<${type.spelling}> x = $value", if (value.abs().bitLength() > 65536) DiagnosticCode.INVALID_INTEGER else DiagnosticCode.INTEGER_RANGE)
			}
		}
	}

	@Test
	fun `all integer literal notations retain magnitude and selected odd or wide type`() {
		for (bits in listOf(1, 12, 31, 65, 257, 65536)) for (radix in listOf(2, 8, 10, 16)) {
			val maximum = one.shiftLeft(bits) - one
			val prefix = when (radix) {
				2 -> "0b"; 8 -> "0o"; 16 -> "0x"; else -> ""
			}
			val suffix = if (radix == 10) "I" else ""
			val ast = snippet("def<unsigned int<$bits>> value = $prefix${maximum.toString(radix)}$suffix")
			val model = SemanticAnalyzer().analyze(ast)
			assertEquals(CInt(bits, true), model.typeOf(ast.statements.single()))
		}
		failure("18446744073709551616", DiagnosticCode.INTEGER_RANGE)
		failure("${one.shiftLeft(65536)}", DiagnosticCode.INVALID_INTEGER)
		failure("def<int<31>> x = 2147483647", DiagnosticCode.INTEGER_RANGE)
		check("def<int<31>> x = 12")
	}

	@Test
	fun `width decoding is exact and floating width diagnostics retain their finite set`() {
		for (width in listOf("31", "0x1F", "0b11111", "0o37", "31i", "31I")) {
			val ast = snippet("def<int<$width>> x = 12")
			assertEquals(CInt(31), SemanticAnalyzer().analyze(ast).typeOf(ast.statements.single()))
		}
		for (width in listOf("0", "65537", "0x1_0001", "65537I", "4294967327", "18446744073709551647", "9".repeat(1000))) {
			val error = failure("def<int<$width>> x = 0", DiagnosticCode.UNSUPPORTED_WIDTH)
			assertContains(error.summary, "1 through 65536")
			val span = error.sourceLabels.single().span
			assertEquals(width, span.source!!.text.lines()[span.startLine - 1].substring(span.startColumn - 1, span.endColumn - 1))
		}
		val floating = failure("def<float<31>> x = 1.0", DiagnosticCode.UNSUPPORTED_WIDTH)
		assertContains(floating.summary, "16, 32, 64")
		assertFalse(floating.summary.contains("65536"))
		failure("def<int<1.5>> x = 0", DiagnosticCode.EXPECTED_TOKEN)
		failure("def<int<-1>> x = 0", DiagnosticCode.EXPECTED_TOKEN)
	}

	@Test
	fun `one bit integers have numeric ranges separate from bool`() {
		check("def<int<1>> signed = -1\nsigned = 0\ndef<unsigned int<1>> bit = 1\nbit = 0")
		failure("def<int<1>> value = 1", DiagnosticCode.INTEGER_RANGE)
		failure("def<unsigned int<1>> value = -1", DiagnosticCode.INTEGER_RANGE)
		failure("def<int<1>> value = true", DiagnosticCode.TYPE_MISMATCH)
		failure("true -> int<1>", DiagnosticCode.INVALID_CAST)
		failure("def<unsigned int<1>> value = 1\nif (value) {}", DiagnosticCode.NON_BOOLEAN_CONDITION)
		assertEquals(BigInteger.ZERO, CInt(1).maximum)
		assertEquals(-one, CInt(1).minimum)
	}

	@Test
	fun `wide bounds and storage providers can be analyzed without constructing LLVM carriers`() {
		for (bits in listOf(1, 12, 31, 65, 65536)) {
			val type = CInt(bits)
			assertSame(type, assertIs<ProviderResult.IntegerConstant>(type.provider("max")).type)
			assertIs<ProviderResult.StorageSize>(type.provider("size"))
			val model = declarations("fn probe() { ${type.spelling}::min\n${type.spelling}::max\n${type.spelling}::size\n${type.spelling}::type::width }")
			assertTrue(model.types.expressions.values.any { it == type })
		}
	}

	@Test
	fun `contextual literals compose through functions arrays defaults receptions and loops`() {
		declarations("""
			fn<int<31>, unsigned int<65>> pair() = 12, 34
			fn<int<31>> fallback(opt<int<31>> x ?: 12) = x
			fn<int<31>> choose(bool flag) = if (flag) { 12 } else { 13 }
			fn<int<31>> consume(bool flag) {
			    def<int<31>> a, b = 12
			    a, b = 1, 2
			    def<int<31>, unsigned int<65>> first, second = pair()
			    def<int<31>?> maybe = 12
			    maybe = null
			    def<int<31>> recovered = maybe ?: 13
			    def<int<31>[]> values = [1, 2, 3]
			    values[0] = 4
			    for (def<int<31>> i <- 1..=3) { a += i }
			    def<int<31>> selected = if (flag) { first } else { 3 }
			    return fallback(x = selected) + choose(flag) + recovered
			}
		""")
		declarations("fn<unsigned int<65536>> maximum(opt<unsigned int<65536>> x ?: unsigned int<65536>::max) = x")
		declarations("fn<float<64>> cast(int<65536> x) = x -> float<64>")
	}

	@Test
	fun `overloads preserve default preference normalized identity and isolated range probing`() {
		val model = declarations("""
			fn<int> pick(int x) = 0
			fn<int> pick(int<31> x) = 1
			fn<int> pick(unsigned int<65> x) = 2
			fn consume() {
			    pick(12)
			    def<int<31>> narrow = 12
			    pick(narrow)
			    pick(18446744073709551616)
			}
		""")
		for ((call, _) in model.calls.filterKeys { it.name.lexeme == "pick" }) {
			val expected = when (val argument = call.arguments.single()) {
				is IntegerLiteral -> if (argument.value == 12.toBigInteger()) ValueType.INT else CInt(65, true)
				else              -> CInt(31)
			}
			assertEquals(expected, model.typeOf(model.selectedFunction(call)).parameters.single())
		}
		val duplicate = assertFailsWith<ClothDiagnostic> { declarations("fn same(int<31> x) {}\nfn same(int<0x1F> x) {}") }
		assertEquals(DiagnosticCode.DUPLICATE_FUNCTION, duplicate.diagnosticCode)
	}

	@Test
	fun `already typed values still require exact widths signedness and identities`() {
		failure("def<int<31>> x = 12\ndef<int<32>> y = x", DiagnosticCode.TYPE_MISMATCH)
		failure("def<int<31>> x = 12\ndef<unsigned int<31>> y = x", DiagnosticCode.TYPE_MISMATCH)
		failure("def<int<31>> x = 12\ndef<int<65>> y = 12\nx + y", DiagnosticCode.MIXED_INTEGERS)
		failure("def<unsigned int<64>> x = 12\ndef<usize> y = x", DiagnosticCode.TYPE_MISMATCH)
		failure("def<int<31>> x = 12\ndef<float<64>> y = x", DiagnosticCode.TYPE_MISMATCH)
		failure("def<int<31>[]> x = [1]\ndef<int<32>[]> y = x", DiagnosticCode.TYPE_MISMATCH)
		check("def<int<31>> x = 12\ndef<int<65>> y = x -> int<65>")
	}

	@Test
	fun `division guards and wrapping constants retain full odd and wide integer semantics`() {
		for (bits in listOf(1, 12, 31, 65, 65536)) {
			failure("int<$bits>::min / -1", DiagnosticCode.INTEGER_DIVISION_OVERFLOW)
			failure("int<$bits>::min % -1", DiagnosticCode.INTEGER_DIVISION_OVERFLOW)
			failure("def<unsigned int<$bits>> x = 0\nx / 0", DiagnosticCode.INTEGER_ZERO_DIVISOR)
			assertEquals(CInt(bits).minimum, CInt(bits).wrap(CInt(bits).maximum + one))
			assertEquals(BigInteger.ZERO, CInt(bits, true).wrap(one.shiftLeft(bits)))
		}
	}

	@Test
	fun `fixed width cap never widens target count bounds when an AST is reused`() {
		val small = NativeCABI.forTarget("i686-pc-windows-msvc").layout
		val wide = NativeCABI.forTarget("x86_64-pc-windows-msvc").layout
		val ast = unit("fn probe() { int<31>[4294967296]::type }")
		assertEquals(DiagnosticCode.INVALID_ARRAY, assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(small).analyze(ast) }.diagnosticCode)
		FunctionAnalyzer(wide).analyze(ast)
		assertEquals(DiagnosticCode.INVALID_ARRAY, assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(small).analyze(ast) }.diagnosticCode)
		val maximum = one.shiftLeft(64) - one
		CArray(CInt(65536), maximum)
		assertFailsWith<IllegalArgumentException> { CArray(CInt(31), maximum + one) }
		val sizeAst = unit("fn<usize> size() = 4294967296")
		FunctionAnalyzer(wide).analyze(sizeAst)
		assertEquals(DiagnosticCode.INTEGER_RANGE, assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(small).analyze(sizeAst) }.diagnosticCode)
	}

	@Test
	fun `wide decimal values pass every emission API without a stage boundary`() = scratch { directory ->
		val abi = NativeCABI.native()
		val emitter = LLVMEmitter(abi)
		val ast = unit("fn main() { def<int<65>> x = 12\nx::str }")
		assertContains(emitter.emit(ast), "cloth.integer.decimal")
		assertContains(emitter.emitExecutable(ast), "cloth.integer.decimal")
		assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("result.o"))) > 0)
		assertTrue(Files.size(emitter.compile(ast, directory.resolve("result"))) > 0)
		assertContains(emitter.emitSnippet(snippet("def<int<65>> x = 12\nx::str")), "cloth.integer.decimal")
		assertTrue(Files.size(emitter.compileSnippet(snippet("def<int<65>> x = 12"), directory.resolve("snippet"))) > 0)
		for (source in listOf("fn probe(int<65> value) { println(value) }", "fn probe(int<65>[] values) { values::str }")) {
			assertContains(emitter.emit(unit(source)), "cloth.integer.decimal")
		}
		for (source in listOf("fn<int<65>> signature() = 0", "fn signature(int<65>[] values) {}", "fn probe() { int<65536>::size }", "fn probe() { 1 is int<65> }", "fn probe() { false && ((1 -> int<65>) == 1) }")) emitter.emit(unit(source))
		val context = LLVMContextCreate()
		try {
			assertEquals(LLVMPointerTypeKind, LLVMGetTypeKind(CInt(65536).llvmValue(context)))
			assertEquals((65536 / TargetLayout.native().pointerBits).toLong(), LLVMGetArrayLength2(CInt(65536).llvmStorage(context)))
		} finally {
			LLVMContextDispose(context)
		}
	}

	@Test
	fun `CLI preserves one original located diagnostic and creates no output`() = scratch { directory ->
		val cases = listOf("def<int<0x1_0001>> x = 0\r\ntrue + false" to DiagnosticCode.UNSUPPORTED_WIDTH, "def<int<65>> x = 12\r\ndef<unsigned int<8>> bad = -1" to DiagnosticCode.INTEGER_RANGE)
		for ((index, case) in cases.withIndex()) {
			val source = directory.resolve("case$index.co")
			val output = directory.resolve("out$index.o")
			Files.writeString(source, "fn main() {\r\n\t${case.first}\r\n}")
			val diagnostics = StringBuilder()
			assertEquals(1, runCompiler(arrayOf("--emit", "object", "--linker", "missing-linker", source.toString(), output.toString()), DiagnosticReporter(diagnostics)))
			assertContains(diagnostics.toString(), case.second.id)
			assertEquals(1, Regex("C[0-9]{4}").findAll(diagnostics).count())
			assertFalse(Files.exists(output))
			if (index == 0) assertContains(diagnostics.toString(), "0x1_0001")
		}
		val source = directory.resolve("wide.co")
		val output = directory.resolve("wide.o")
		Files.writeString(source, "fn main() { def<int<65>> x = 12\nprintln(x) }")
		assertEquals(0, runCompiler(arrayOf("--emit", "object", "--linker", "missing-linker", source.toString(), output.toString())))
		assertTrue(Files.size(output) > 0)
	}

	private fun scratch(action: (Path) -> Unit) {
		val root = Path.of("tmp").toAbsolutePath().normalize()
		Files.createDirectories(root)
		val directory = Files.createTempDirectory(root, "integer-width-stage2-")
		try {
			action(directory)
		} finally {
			check(directory.startsWith(root) && directory != root)
			Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
		}
	}
}
