package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TypeInfoTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("typeinfo.co", source.trimIndent())).scanTokens()).parse()
	private fun failure(source: String, code: DiagnosticCode = DiagnosticCode.INVALID_METADATA_ACCESS): ClothDiagnostic {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(unit(source)) }
		assertEquals(code, error.diagnosticCode, source)
		assertLocated(error)
		return error
	}

	@Test
	fun `metadata uses one value type and preserves canonical spelling without changing diagnostics`() {
		assertEquals("int", CInt().spelling)
		assertEquals("int<32>", CInt().metadataSpelling)
		assertEquals("unsigned int<32>[2][]", CArray(CArray(CInt(unsigned = true)), 2).metadataSpelling)
		for (source in listOf("int::type", "float::type", "unsigned int<64>[0][]::type", "typeinfo::type", "void::type", "true::type", "\"x\"::type")) {
			val ast = Parser(Lexer(source).scanTokens()).parseSnippet()
			assertEquals(CTypeInfo, SemanticAnalyzer().analyze(ast).typeOf(ast.statements.single()))
		}
		val ir = LLVMEmitter().emit(unit("fn<typeinfo> describe() = unsigned int<64>[18446744073709551615][]::type"))
		assertContains(ir, "private constant")
		assertContains(ir, "ret ptr")
		assertFalse(ir.contains("alloca"))
	}

	@Test
	fun `known invalid operations report the provider name and retain intrinsic errors in probes`() {
		for (expression in listOf("int::type::element", "int::type::extent", "int[]::type::extent", "int[][2]::type::extent", "bool::type::width", "char::type::width", "int[2]::type::width", "typeinfo::type::width", "int[2]::type::element::element")) {
			val error = failure("fn main() { $expression }")
			assertContains(error.summary, "::${expression.substringAfterLast("::")}")
		}
		failure("fn take(int value) {}\nfn take(string value) {}\nfn main() { take(string::type::width) }")
		failure("fn main() { def info = int::type\ninfo::element }")
		failure("fn main() { def<typeinfo> info = int::type\ninfo = string::type\ninfo::width }")
		failure("fn main() { def info := int[2][]::type\ndef child = info::element\nchild::extent }")
	}

	@Test
	fun `metadata restrictions keep contextual names read-only results and strict types`() {
		for (expression in listOf("typeinfo::str", "typeinfo::element", "typeinfo::rank", "typeinfo::width", "typeinfo::isarray")) failure("fn main() { $expression }", DiagnosticCode.INVALID_PROVIDER_RECEIVER)
		for (expression in listOf("int::width", "int::type::WIDTH", "int::type::addr", "int::type::size")) failure("fn main() { $expression }", DiagnosticCode.UNKNOWN_PROVIDER)
		failure("fn main() { def<int> info = int::type }", DiagnosticCode.TYPE_MISMATCH)
		failure("fn<int> wrong() = int::type::width", DiagnosticCode.RETURN_TYPE)
		failure("fn main() { int::type == 1 }", DiagnosticCode.INCOMPARABLE_TYPES)
		failure("fn main() { int::type < float::type }", DiagnosticCode.BINARY_OPERANDS)
		val error = assertFailsWith<ClothDiagnostic> { unit("fn main() { int::type::width = 2 }") }
		assertEquals(DiagnosticCode.READ_ONLY_PROVIDER, error.diagnosticCode)
	}

	@Test
	fun `exact equality and formatting survive function boundaries and mutable copies`(@TempDir directory: Path) {
		native(directory, "metadata_values", """
			fn<typeinfo> integer() = int<32>::type
			fn<typeinfo> choose(bool wide) = if (wide) { float<64>::type } else { unsigned int::type }
			fn<typeinfo> copy(typeinfo value) = value
			fn<string> text(typeinfo value) = #"{value}"
			fn<int> main() {
			    def<typeinfo> info = int::type
			    def independent = info
			    info = choose(true)
			    if (independent != integer() || info != float<64>::type || info == independent) { return 1 }
			    if (int::type != int<32>::type || int::type == unsigned int::type || int::type == int<64>::type) { return 2 }
			    if (choose(false)::str != "unsigned int<32>" || text(info) != "float<64>" || copy(info)::str != #"{info}") { return 3 }
			    if (info::type != typeinfo::type || typeinfo::type::str != "typeinfo" || void::type::str != "void") { return 4 }
			    return 0
			}
		""")
	}

	@Test
	fun `all numeric formats expose exact width signedness and explicit width text`(@TempDir directory: Path) {
		val types = buildList<ValueType> {
			for (width in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) add(CInt(width, unsigned))
			for (width in ValueType.FLOAT_WIDTHS) add(CFloat(width))
		}
		val checks = types.joinToString("\n") { type ->
			val spelling = type.spelling
			"""
				if ($spelling::type::width != ${type.bits} || width($spelling::type) != ${type.bits}) { return 1 }
				if (!$spelling::type::isnumeric || $spelling::type::isunsigned != ${type.unsigned}) { return 2 }
				if ($spelling::type::str != "${type.metadataSpelling}" || #"{$spelling::type}" != "${type.metadataSpelling}") { return 3 }
			""".trimIndent()
		}
		native(directory, "numeric_metadata", "fn<unsigned int<64>> width(typeinfo value) = value::width\nfn<int> main() {\n$checks\nreturn 0\n}")
	}

	@Test
	fun `array metadata retains every static extent and removes one dimension at a time`(@TempDir directory: Path) {
		native(directory, "array_metadata", """
			fn<bool> same(typeinfo left, typeinfo right) = left == right
			fn<typeinfo> child(typeinfo info) = info::element
			fn<unsigned int<64>> extent(typeinfo info) = info::extent
			fn<typeinfo> staticType(int[][] values) = values::type
			fn<int> main() {
			    def info = unsigned int<8>[2][0][]::type
			    if (info::str != "unsigned int<8>[2][0][]" || info::rank != 3 || !info::isarray || !info::hasextent || info::extent != 2) { return 1 }
			    if (child(info) != unsigned int<8>[0][]::type || info::element::extent != 0 || !info::element::hasextent) { return 2 }
			    if (info::element::element != unsigned int<8>[]::type || info::element::element::hasextent || info::element::element::rank != 1) { return 3 }
			    if (info::element::element::element != unsigned int<8>::type || info::element::element::element::rank != 0) { return 4 }
			    if (!same(int[]::type, int[]::type) || same(int[]::type, int[3]::type) || same(int[2][3]::type, int[2][]::type)) { return 5 }
			    if (extent(int[18446744073709551615][]::type) != unsigned int<64>::max || extent(int[0][]::type) != 0) { return 6 }
			    if (staticType([[1,2],[3,4]]) != int[][]::type || staticType([[1,2],[3,4]])::hasextent) { return 7 }
			    if (int[][2]::type::element != int[2]::type || int[][2]::type::hasextent) { return 8 }
			    return 0
			}
		""")
	}

	@Test
	fun `non-numeric metadata predicates are total and guarded queries are safe`(@TempDir directory: Path) {
		native(directory, "guards", """
			fn<unsigned int<64>> bits(typeinfo value) = if (value::isnumeric) { value::width } else { 0 }
			fn<unsigned int<64>> count(typeinfo value) = if (value::hasextent) { value::extent } else { 0 }
			fn<typeinfo> elementOrSelf(typeinfo value) = if (value::isarray) { value::element } else { value }
			fn<int> main() {
			    def values = [bool::type, char::type, string::type, typeinfo::type, void::type, int[]::type]
			    if (values[0]::rank != 0 || values[0]::isunsigned || values[0]::isnumeric || values[0]::isarray || values[0]::hasextent) { return 1 }
			    if (bits(char::type) != 0 || bits(int<8>::type) != 8 || count(int[]::type) != 0 || count(int[3]::type) != 3) { return 2 }
			    if (elementOrSelf(string::type) != string::type || elementOrSelf(int[3]::type) != int::type) { return 3 }
			    if (values[5]::isnumeric || values[5]::isunsigned || !values[5]::isarray) { return 4 }
			    return 0
			}
		""")
	}

	@Test
	fun `unknown invalid metadata operations terminate with controlled source-located errors`(@TempDir directory: Path) {
		for ((query, argument, returns) in listOf(Triple("element", "int::type", "typeinfo"), Triple("extent", "int[]::type", "unsigned int<64>"), Triple("extent", "string::type", "unsigned int<64>"), Triple("width", "bool::type", "unsigned int<64>"))) {
			native(directory, "invalid_${query}_${argument.hashCode().toUInt()}", "fn<$returns> inspect(typeinfo value) = value::$query\nfn main() { inspect($argument) }", "Type metadata ::$query requires")
		}
	}

	@Test
	fun `metadata travels in arrays returns and conditional expressions without changing static types`(@TempDir directory: Path) {
		native(directory, "collections", """
			fn<typeinfo[]> all() = [int::type, int[]::type, float<64>::type]
			fn<typeinfo> select(bool first) = if (first) { int[2]::type } else { string::type }
			fn<typeinfo> identity(typeinfo info) = info
			fn<int> pick(typeinfo info, int<8> x) = 1
			fn<int> pick(string info, int<64> x) = 2
			fn<int> main() {
			    def values = all()
			    def copied = values
			    values[0] = bool::type
			    if (copied[0] != int::type || values[0] != bool::type || all()[1]::element != int::type) { return 1 }
			    if (select(true)::element != int::type || select(false)::str != "string" || identity(copied[2])::width != 64) { return 2 }
			    if (pick(int::type, 1) != 1 || identity(int::type)::type != typeinfo::type) { return 3 }
			    def<typeinfo[]> empty = []
			    if (empty::size != 0 || empty::type::element != typeinfo::type) { return 4 }
			    return 0
			}
		""")
	}

	@Test
	fun `metadata facts merge branches and invalidate captured mutations`(@TempDir directory: Path) {
		failure("fn test(bool choice) { def info = int::type\nif (choice) { info = string::type } else { info = string::type }\ninfo::width }")
		native(directory, "flow", """
			fn<int> run(bool choice) {
			    def info = string::type
			    if (choice) { info = int::type } else { info = float<64>::type }
			    if (info::width == 0) { return 1 }
			    def<typeinfo> change = { info = int<8>::type
			        return info }
			    change::type
			    if (info::width != 8) { return 2 }
			    return 0
			}
			fn<int> main() { if (run(true) != 0 || run(false) != 0) { return 1 }
			    return 0 }
		""")
		native(directory, "captured_invalid", """
			fn main() {
			    def info = int::type
			    def<typeinfo> change = { info = string::type
			        return info }
			    change::type
			    info::width
			}
		""", "Type metadata ::width requires")
	}

	@Test
	fun `type evaluates value receivers once preserves bounds and handles terminating receivers`(@TempDir directory: Path) {
		native(directory, "effects", """
			fn<int> main() {
			    def calls = 0
			    def<int> number = { calls += 1
			        return 2 }
			    def info = number::type
			    if (calls != 1 || info != int::type || (number + 1)::type != int::type || calls != 2) { return 1 }
			    return 0
			}
		""")
		native(directory, "bounds", "fn main() { def values = [1]\nvalues[8]::type }", "Array index out of bounds")
		native(directory, "termination", "fn<int> main() = (if (true) { return 0 } else { return 1 })::type::width -> int")
	}

	private fun native(directory: Path, name: String, source: String, expectedError: String? = null) {
		val executable = LLVMEmitter().compile(unit(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "$name timed out")
			val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
			if (expectedError == null) {
				assertEquals(0, process.exitValue(), "$name: $output"); assertEquals("", output)
			} else {
				assertNotEquals(0, process.exitValue(), name)
				assertContains(output, expectedError)
				if (expectedError.startsWith("Type metadata")) assertContains(output, "typeinfo.co")
			}
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
