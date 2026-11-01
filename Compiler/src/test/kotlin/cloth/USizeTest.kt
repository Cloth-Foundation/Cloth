package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.LLVMTypes
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.CallExpression
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.SemanticAnalyzer
import cloth.target.TargetLayout
import org.bytedeco.llvm.global.LLVM.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class USizeTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("usize.co", source.trimIndent())).scanTokens()).parseDeclarations()
	private fun snippet(source: String) = Parser(Lexer(SourceFile("usize.co", source.trimIndent())).scanTokens()).parseSnippet()
	private fun synthetic32() = NativeCABI.native().layout.copy(
		targetTriple = "i686-unknown-linux-gnu", pointerBits = 32, sizeBits = 32,
		pointerAlignmentBits = 32, cLongBits = 32,
	)

	private fun failure(source: String, code: DiagnosticCode, target: TargetLayout = TargetLayout.native()) {
		val diagnostic = assertFailsWith<ClothDiagnostic>(source) { FunctionAnalyzer(target).analyze(unit(source)) }
		assertEquals(code, diagnostic.diagnosticCode, source)
		assertLocated(diagnostic)
	}

	private fun native(directory: Path, name: String, source: String, output: String = "", error: String? = null) {
		val executable = LLVMEmitter().compile(unit(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "$name timed out")
			val actual = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
			if (error == null) {
				assertEquals(0, process.exitValue(), "$name: $actual")
				assertEquals(output.replace("\n", System.lineSeparator()) + processCompletion(), actual)
			} else {
				assertEquals(1, process.exitValue(), "$name: $actual")
				assertContains(actual, error)
				assertFalse(actual.contains("PEC:"), actual)
			}
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	@Test
	fun `source resolution and literal limits follow each compilation target`() {
		val context = LLVMContextCreate()
		try {
			val small = synthetic32()
			val wide = NativeCABI.native().layout
			for (target in listOf(small, wide, small)) {
				val type = CUSize(target.sizeBits)
				val ast = snippet("def<usize> value = ${type.maximum}\ndef copy := value\ndef<int<64>> fixed = 9223372036854775807\nvalue::max")
				val semantics = SemanticAnalyzer(target).analyze(ast)
				assertEquals(listOf(type, type, CInt(64), type), ast.statements.map { semantics.typeOf(it) })
				val functions = FunctionAnalyzer(target).analyze(unit("fn<usize> identity(usize value) = value\nfn<usize[]> make(usize value) = [value, 1]"))
				val identity = functions.typeOf(functions.declarations.functions.first())
				assertEquals(listOf(type), identity.parameters)
				assertEquals(type, identity.returns)
				assertEquals(CArray(type), functions.typeOf(functions.declarations.functions.last()).returns)
				assertEquals(target.sizeBits, LLVMGetIntTypeWidth(LLVMTypes(context, target).value(type)))
				assertEquals("usize", type.spelling)
				assertEquals("usize", type.metadataSpelling)
				assertNotEquals<ValueType>(CInt(target.sizeBits, true), type)
				for ((name, value) in listOf("min" to BigInteger.ZERO, "max" to type.maximum)) {
					val provider = assertIs<ProviderResult.IntegerConstant>(type.provider(name, target))
					assertSame(type, provider.type)
					assertEquals(value, provider.value)
				}
				val size = snippet("usize::size")
				assertEquals(type, SemanticAnalyzer(target).analyze(size).typeOf(size.statements.single()))
				assertEquals(BigInteger.valueOf((target.sizeBits / 8).toLong()), assertIs<ProviderResult.IntegerConstant>(type.provider("size", target)).value)
				failure("fn<usize> bad() = -1", DiagnosticCode.INTEGER_RANGE, target)
				if (target.sizeBits < 64) failure("fn<usize> bad() = ${type.maximum + BigInteger.ONE}", DiagnosticCode.INTEGER_RANGE, target)
			}
			failure("fn<usize> bad() = 4294967296", DiagnosticCode.INTEGER_RANGE, small)
			FunctionAnalyzer(wide).analyze(unit("fn<usize> good() = 4294967296"))
			failure("fn<usize> bad() = 18446744073709551616", DiagnosticCode.INVALID_INTEGER)
		} finally {
			LLVMContextDispose(context)
		}
	}

	@Test
	fun `usize identity stays strict across assignments returns calls arrays and operators`() {
		for (target in listOf(synthetic32(), NativeCABI.native().layout)) {
			val fixed = "unsigned int<${target.sizeBits}>"
			failure("fn bad() { def<usize> size = 1\n def<$fixed> other = size }", DiagnosticCode.TYPE_MISMATCH, target)
			failure("fn bad() { def<$fixed> other = 1\n def<usize> size = other }", DiagnosticCode.TYPE_MISMATCH, target)
			failure("fn<usize> bad($fixed value) = value", DiagnosticCode.RETURN_TYPE, target)
			failure("fn take($fixed value) {}\nfn bad(usize size) { take(size) }", DiagnosticCode.NO_MATCHING_CALL, target)
			failure("fn bad(usize size, $fixed fixed) { [size, fixed] }", DiagnosticCode.INVALID_ARRAY, target)
			for (operator in listOf("+", "-", "*", "/", "%", "==", "!=", "<", ">", "<=", ">=")) {
				failure("fn bad(usize size, $fixed fixed) { size $operator fixed }", DiagnosticCode.MIXED_INTEGERS, target)
			}
			failure("fn bad() { def<usize<32>> size = 1 }", DiagnosticCode.UNSUPPORTED_TYPE, target)
			failure("fn bad() { def<unsigned usize> size = 1 }", DiagnosticCode.INVALID_UNSIGNED, target)
			failure("fn bad() { def<usize> size := 1\n size = 2 }", DiagnosticCode.IMMUTABLE_BINDING, target)
			failure("fn bad() { def<usize> size = 1.0 }", DiagnosticCode.TYPE_MISMATCH, target)
			failure("fn bad(usize size) { size -> bool }", DiagnosticCode.INVALID_CAST, target)
			failure("fn bad() { true -> usize }", DiagnosticCode.INVALID_CAST, target)
			failure("fn bad() { usize::str }", DiagnosticCode.INVALID_PROVIDER_RECEIVER, target)
			failure("fn bad() { usize::max = 1 }", DiagnosticCode.READ_ONLY_PROVIDER, target)
			failure("fn bad() { usize::max / 0 }", DiagnosticCode.INTEGER_ZERO_DIVISOR, target)
		}
		for (target in listOf(synthetic32(), NativeCABI.native().layout)) {
			val diagnostic = assertFailsWith<ClothDiagnostic> { ProgramAnalyzer(target).analyze(unit("fn<usize> main() = 0")) }
			assertEquals(DiagnosticCode.INVALID_MAIN_RETURN, diagnostic.diagnosticCode)
			assertLocated(diagnostic)
		}
	}

	@Test
	fun `overloads distinguish usize from an equally wide unsigned integer`() {
		for (target in listOf(synthetic32(), NativeCABI.native().layout)) {
			val fixed = "unsigned int<${target.sizeBits}>"
			val source = unit("""
				fn choose(usize value) {}
				fn choose($fixed value) {}
				fn choose(int value) {}
				fn test() {
				    def<usize> size = 7
				    def<$fixed> fixed = 7
				    choose(size)
				    choose(fixed)
				    choose(7)
				}
			""")
			val model = FunctionAnalyzer(target).analyze(source)
			val body = model.declarations.functions.last().declaration.body as BlockBody
			val calls = body.statements.filterIsInstance<ExpressionStatement>().map { it.expression as CallExpression }
			assertEquals(listOf(CUSize(target.sizeBits), CInt(target.sizeBits, true), CInt()), calls.map { model.typeOf(model.selectedFunction(it)).parameters.single() })
			failure("fn choose(usize value) {}\nfn choose($fixed value) {}\nfn bad() { choose(7) }", DiagnosticCode.AMBIGUOUS_CALL, target)
			FunctionAnalyzer(target).analyze(unit("fn take(usize value) {}\nfn test() { take(7) }"))
		}
	}

	@Test
	fun `native arithmetic casts and formatting follow existing unsigned rules`(@TempDir directory: Path) {
		val type = CUSize(NativeCABI.native().layout.sizeBits)
		val castChecks = ValueType.INTEGER_WIDTHS.flatMap { width ->
			listOf(false, true).map { unsigned ->
				val fixed = CInt(width, unsigned)
				val name = "${if (unsigned) "unsigned" else "signed"}$width"
				val source = if (unsigned) fixed.maximum else -BigInteger.ONE
				val converted = if (unsigned) fixed.maximum.min(type.maximum) else type.maximum
				val narrowed = if (unsigned) fixed.maximum.min(type.maximum) else if (width <= type.width) -BigInteger.ONE else type.maximum
				"def<${fixed.spelling}> $name = $source\nif (($name -> usize) != $converted || (usize::max -> ${fixed.spelling}) != $narrowed) { return 9 }"
			}
		}.joinToString("\n")
		native(directory, "numeric", """
			fn<usize> identity(usize value) = value
			fn<usize> add(usize a, usize b) = a + b
			fn<int> main() {
			    def<usize> max = usize::max
			    if (max + 1 != 0 || (0 -> usize) - 1 != max) { return 1 }
			    if (max / 2 != ${type.maximum / BigInteger.TWO} || max % 2 != 1) { return 2 }
			    if ((-1 -> usize) != max || (max -> int<8>) != -1) { return 3 }
			    def<unsigned int<8>> byte = 255
			    if ((byte -> usize) != 255 || (max -> unsigned int<${type.width}>) != ${type.maximum}) { return 4 }
			    if ((12.75 -> usize) != 12 || (-0.75 -> usize) != 0 || ((12 -> usize) -> float<64>) != 12.0) { return 5 }
			    def<usize> value = identity(5)
			    value++
			    value--
			    value += 3
			    value *= 2
			    value /= 4
			    value %= 3
			    value -= 1
			    if (value != 0 || add(2, 3) != 5 || identity(3) ** 4 != 81) { return 6 }
			    if (!(max > 1) || !(max >= max) || !(value < 1) || !(value <= 0)) { return 7 }
			    if (identity(42)::str != "42" || #"{max}" != "${type.maximum}") { return 8 }
			    $castChecks
			    if (((12 -> usize) -> float<16> -> usize) != 12 || ((12.75 -> float<16>) -> usize) != 12) { return 10 }
			    println(identity(42))
			    return 0
			}
		""", output = "42\n")
	}

	@Test
	fun `native arrays blocks binding lists and copies preserve usize values`(@TempDir directory: Path) {
		native(directory, "transport", """
			fn<usize[]> make(usize value) = [value, value + 1]
			fn<usize[]> modify(usize[] values) { values[0] = 99
			    return values }
			fn<usize> factorial(usize value) = if (value == 0) { 1 } else { value * factorial(value - 1) }
			fn<int> main() {
			    def<usize> calls = 0
			    def<usize> next = { calls += 1
			        return calls }
			    def<usize> a, b = next
			    if (calls != 1 || a != 1 || b != 1) { return 1 }
			    a, b = next
			    if (calls != 2 || a != 2 || b != 2) { return 2 }
			    def values := make(10)
			    def copy = values
			    def changed = modify(values)
			    def<usize> index = 1
			    values[index] = 20
			    if (copy[index] != 11 || values[index] != 20 || changed[0] != 99 || values[0] != 10) { return 3 }
			    def<usize[2][2]> matrix = [[1, 2], [3, 4]]
			    matrix[index][0] = 7
			    if (matrix[1][0] != 7 || factorial(5) != 120) { return 4 }
			    return 0
			}
		""")
	}

	@Test
	fun `native metadata keeps primitive identity and providers evaluate once`(@TempDir directory: Path) {
		val width = NativeCABI.native().layout.sizeBits
		native(directory, "metadata", """
			fn<typeinfo> describe() = usize::type
			fn<usize> bound(usize value) = value::max
			fn<usize> width(typeinfo value) = value::width
			fn<int> choose(usize value) = 1
			fn<int> choose(unsigned int<$width> value) = 2
			fn<int> choose(int value) = 3
			fn<int> main() {
			    def<usize> value = 42
			    if (!(value is usize) || value is unsigned int<$width>) { return 1 }
			    if (value::type != usize::type || describe() != usize::type || usize::type == unsigned int<$width>::type) { return 2 }
			    if (usize::type::str != "usize" || #"{describe()}" != "usize" || usize[2][3]::type::str != "usize[2][3]") { return 3 }
			    if (usize::type::width != $width || width(describe()) != $width || !usize::type::isnumeric || !usize::type::isunsigned) { return 4 }
			    if (usize::size != ${width / 8} || usize::min != 0 || bound(value) != usize::max) { return 5 }
			    if (usize[2]::type::element != usize::type || usize[2]::type == unsigned int<$width>[2]::type) { return 6 }
			    def<usize> calls = 0
			    def<usize> effect = { calls += 1
			        return 7 }
			    def maximum = effect::max
			    if (calls != 1 || maximum != usize::max || maximum::type != usize::type) { return 7 }
			    def<usize> size = usize::size
			    if (size::type != usize::type) { return 8 }
			    if (choose(value) != 1 || choose(value -> unsigned int<$width>) != 2 || choose(1) != 3) { return 9 }
			    println(describe())
			    return 0
			}
		""", output = "usize\n")
	}

	@Test
	fun `native cast division and index failures remain controlled`(@TempDir directory: Path) {
		val badFloats = listOf("-1.0", "18446744073709551616.0", "zero / zero", "1.0 / zero", "-1.0 / zero")
		for ((index, value) in badFloats.withIndex()) {
			native(directory, "float_$index", "fn main() { def<float<64>> zero = 0.0 -> float<64>\n ($value) -> usize }", error = "Invalid numeric cast")
		}
		for ((index, operator) in listOf("/", "%").withIndex()) {
			native(directory, "division_$index", "fn<usize> calculate(usize n, usize d) = n $operator d\nfn main() { calculate(1, 0) }", error = "by zero")
		}
		native(directory, "bounds", "fn main() { def<usize> index = -1 -> usize\n def values = [1]\n values[index] }", error = "Array index out of bounds")
	}

	@Test
	fun `all measurement providers retain target usize identity through source contracts`() {
		val measurements = listOf("int<64>::size", "float<16>::size", "bool::size", "char::size", "usize::size", "int[3]::size", "[1, 2]::size", "\"é😀\"::size", "int[2][3]::type::rank", "int[3]::type::extent", "int<64>::type::width")
		for (target in listOf(synthetic32(), NativeCABI.native().layout, synthetic32())) {
			val expected = CUSize(target.sizeBits)
			val fixed = "unsigned int<${target.sizeBits}>"
			for (expression in measurements) {
				val ast = snippet("def<usize> measured = $expression\ndef copied = measured\n[$expression, 0]")
				val semantics = SemanticAnalyzer(target).analyze(ast)
				assertEquals(listOf(expected, expected, CArray(expected, 2)), ast.statements.map { semantics.typeOf(it) }, expression)
				FunctionAnalyzer(target).analyze(unit("fn<usize> measure() = $expression\nfn<usize> identity(usize value) = value\nfn<usize> pass() = identity($expression)"))
				failure("fn bad() { def<$fixed> wrong = $expression }", DiagnosticCode.TYPE_MISMATCH, target)
				failure("fn<$fixed> wrong() = $expression", DiagnosticCode.RETURN_TYPE, target)
				failure("fn take($fixed value) {}\nfn bad() { take($expression) }", DiagnosticCode.NO_MATCHING_CALL, target)
			}
			for (type in listOf<ValueType>(CInt(8), CInt(64, true), CFloat(16), expected)) {
				assertEquals(type, assertNotNull(type.provider("min", target)).type)
				assertEquals(type, assertNotNull(type.provider("max", target)).type)
			}
			val counts = FunctionAnalyzer(target).analyze(unit("fn<usize> outer(int[] values) = values::size\nfn<usize> scalars(string text) = text::size\nfn<usize> rank(typeinfo info) = info::rank\nfn<usize> extent(typeinfo info) = info::extent\nfn<usize> width(typeinfo info) = info::width"))
			assertTrue(counts.types.providers.values.all { it.type == expected })
		}
	}

	@Test
	fun `native measurement results select usize overloads and preserve effects and casts`(@TempDir directory: Path) {
		val width = NativeCABI.native().layout.sizeBits
		native(directory, "measurement_contracts", """
			fn<int> select(usize count) = 1
			fn<int> select(unsigned int<$width> count) = 2
			fn<usize> arrayCount(int[] value) = value::size
			fn<usize> scalarCount(string value) = value::size
			fn<usize> width(typeinfo value) = value::width
			fn<usize> rank(typeinfo value) = value::rank
			fn<usize> extent(typeinfo value) = value::extent
			fn<unsigned int<64>> persist(string text) = text::size -> unsigned int<64>
			fn<int> main() {
			    def<usize[]> sizes = [int<64>::size, float<16>::size, bool::size, char::size, usize::size, arrayCount([1, 2]), scalarCount("é\0😀"), rank(int[2][3]::type), extent(int[3]::type), width(int<64>::type)]
			    if (sizes::size != 10 || sizes[0] != 8 || sizes[1] != 2 || sizes[2] != 1 || sizes[3] != 4 || sizes[5] != 2 || sizes[6] != 3 || sizes[7] != 2 || sizes[8] != 3 || sizes[9] != 64) { return 1 }
			    if (select(int::size) != 1 || select(arrayCount([])) != 1 || select(scalarCount("😀")) != 1 || select(rank(int::type)) != 1 || select(extent(int[0]::type)) != 1 || select(width(float<64>::type)) != 1) { return 2 }
			    if (!(sizes[0] is usize) || sizes[0]::type != usize::type || sizes::type::element != usize::type || sizes[0]::size::type != usize::type) { return 3 }
			    if (select(unsigned int<$width>::max) != 2 || int[18446744073709551615]::type::extent != usize::max || int[18446744073709551615]::size != usize::max) { return 4 }
			    def<usize> calls = 0
			    def<int[]> array = { calls += 1
			        return [1, 2, 3] }
			    def<string> text = { calls += 1
			        return "é😀" }
			    def<typeinfo> metadata = { calls += 1
			        return int[3]::type }
			    def<usize> a, b = array::size
			    def<usize> c = text::size
			    def<usize> d = metadata::extent
			    if (calls != 3 || a != 3 || b != 3 || c != 2 || d != 3) { return 5 }
			    if (persist("é😀") != 2 || (persist("é😀") -> usize) != 2 || (sizes[0] -> unsigned int<$width>) != 8) { return 6 }
			    return 0
			}
		""")
	}

	@Test
	fun `supported target IR uses native integer values for usize signatures`() {
		for (architecture in listOf("x86_64", "aarch64")) for (platform in listOf("pc-windows-msvc", "unknown-linux-gnu", "apple-darwin")) {
			val abi = NativeCABI.forTarget("$architecture-$platform")
			val ir = LLVMEmitter(abi).emit(unit("fn<usize> identity(usize value) = value\nfn<usize[]> copy(usize[] values) = values\nfn<typeinfo> describe() = usize::type\nfn<usize> count(usize[] values) = values::size\nfn<usize> scalars(string text) = text::size\nfn<usize> rank(typeinfo value) = value::rank\nfn<usize> extent(typeinfo value) = value::extent\nfn<usize> width(typeinfo value) = value::width"))
			assertContains(ir, "define internal i${abi.layout.sizeBits} @cloth.fn.0.identity(i${abi.layout.sizeBits}")
			assertContains(ir, "target triple = \"${abi.targetTriple}\"")
			assertContains(ir, "target datalayout =")
			assertContains(ir, "usize")
			assertFalse(ir.contains("unsigned usize"))
		}
	}
}
