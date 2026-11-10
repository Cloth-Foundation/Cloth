package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.api.SourceModuleRole
import cloth.data.CFloat
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GenericFunctionsTest {

	@Test
	fun `explicit applications intern exact arguments and coexist with ordinary overloads`() {
		val source = genericUnit("""
			fn<int> id(int value) = value + 1
			fn<T> id<T>(T value) = value
			fn<T> id<T, U>(T value, U ignored) = value
			fn main() {
			    def<int> ordinary = id(64)
			    def<int> a = id<int>(64)
			    def<int> b = id<int<32>>(64)
			    def<float<64>> precise = id<float<64>>(0.1)
			    def<string> label = id<string, int>("label", 2)
			}
		""")
		val model = FunctionAnalyzer(genericTarget()).analyze(source)
		val specializations = assertNotNull(model.declarations.genericViews).functions
		assertEquals(listOf(listOf(ValueType.INT), listOf(CFloat(64)), listOf(ValueType.STRING, ValueType.INT)), specializations.keys.map { it.arguments })
		assertEquals(listOf(ValueType.INT, CFloat(64), ValueType.STRING), specializations.values.map { model.typeOf(it).returns })
	}

	@Test
	fun `unused functions are checked and dependent recursion is bounded`() {
		failure("fn<T> id<T>(T value) = missing", DiagnosticCode.UNKNOWN_NAME)
		failure("fn<T> id<T>(T value) = value + 1", DiagnosticCode.BINARY_OPERANDS)
		failure("fn expand<T>() = expand<T[]>()", DiagnosticCode.GENERIC_TEMPLATE_DEPENDENCY_LIMIT)
		failure("typedef<struct> Hidden { int value }\npub fn<Hidden> reveal<T>() = { 1 }", DiagnosticCode.PRIVATE_GENERIC_SIGNATURE)
	}

	@Test
	fun `type argument and value argument failures retain located diagnostics`() {
		val template = "fn<T> id<T>(T value) = value\n"
		for ((call, code) in listOf(
			"id(64)" to DiagnosticCode.GENERIC_ARITY,
			"id<int, string>(64)" to DiagnosticCode.GENERIC_ARITY,
			"id<void>(64)" to DiagnosticCode.INVALID_GENERIC_ARGUMENT,
			"id<int>(\"wrong\")" to DiagnosticCode.NO_MATCHING_CALL,
			"def id = 1; id<int>(64)" to DiagnosticCode.NOT_CALLABLE,
			"println<int>(64)" to DiagnosticCode.GENERIC_ARITY,
		)) failure(template + "fn main() { $call }", code)
		failure(template + "fn<U> id<U>(U value) = value", DiagnosticCode.DUPLICATE_FUNCTION)
		val entry = assertFailsWith<ClothDiagnostic> { ProgramAnalyzer(genericTarget()).analyze(genericUnit("fn main<T>() {}")) }
		assertEquals(DiagnosticCode.INVALID_MAIN_PARAMETERS, entry.diagnosticCode)
		assertLocated(entry)
	}

	@Test
	fun `actual magic import returns exact numeric and buffered values through the native ABI`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("Main.co"), """
			using cloth.lang.num.NumberUtilities : magic
			typedef<struct> Box<T> {
			    T value;
			    mt<T> read() = magic<T>(value)
			}
			fn<int> someFunction() { print("called "); return 1 }
			fn<T> repeat<T>(int count, T value, opt<T> chosen ?: magic<T>(value)) {
			    if (count == 0) return chosen
			    return repeat<T>(count - 1, chosen)
			}
			fn main() {
			    def<int> x = magic<int>(64) + someFunction()
			    println(x)
			    println(magic<int>(someFunction()))
			    println(magic<unsigned int<8>>(255))
			    def<float<64>> tenth = 0.1
			    println(magic<float<64>>(0.1) == tenth)
			    println(magic<int<129>>(18446744073709551617))
			    println(magic<usize>(usize::max) == usize::max)
			    def<int[]?> empty = magic<int[]?>(null)
			    println(empty == null)
			    def<Box<string>> box = magic<Box<string>>({ "kept" })
			    println(box.read())
			    def<int[]> original = [2, 3]
			    def<int[]> copy = repeat<int[]>(2, original)
			    copy[0] = 9
			    println(original[0])
			    println(copy[0])
			}
		""".trimIndent())
		val modules = listOf(SourceModule("App", directory), SourceModule("StandardLibrary", Path.of("Standard-Library").toAbsolutePath(), SourceModuleRole.STANDARD_LIBRARY))
		val expected = "called 65\ncalled 1\n255\ntrue\n18446744073709551617\ntrue\ntrue\nkept\n2\n9\n".replace("\n", System.lineSeparator()) + processCompletion()
		for (abi in structRunners()) {
			val request = CompilationRequest(source, directory.resolve("magic_${abi.layout.sizeBits}"), modules, targetTriple = abi.targetTriple)
			assertEquals(expected, runExecutable(ClothCompiler.compile(request).artifact))
		}
	}

	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(genericTarget()).analyze(genericUnit(source)) }
		assertEquals(code, error.diagnosticCode, error.summary)
		assertLocated(error)
	}
}
