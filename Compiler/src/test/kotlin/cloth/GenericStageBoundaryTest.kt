package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.typing.FunctionAnalyzer
import cloth.semantic.declarations.TypeResolver
import cloth.semantic.symbols.SignatureType
import cloth.parser.declaration.FunctionDeclaration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GenericStageBoundaryTest {
	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(source)) }.also {
		assertEquals(code, it.diagnosticCode, it.summary)
		assertLocated(it)
	}

	@Test
	fun `well named generic declarations now complete semantic checking`() {
		for (source in listOf(
			"typedef<class> Box<T> {}\nfn main() {}",
			"typedef<struct> Slot<T> { T value }\nfn main() {}",
			"typedef<class> Box<T> {}\nfn main() { Box<int>::new }"
		)) assertNotNull(FunctionAnalyzer(nativeTestLayout).analyze(structUnit(source)).declarations.genericViews)
	}

	@Test
	fun `generic name failures precede the closed compilation boundary`() {
		failure("typedef<class> Box<T, T> {}\nfn main() {}", DiagnosticCode.INVALID_GENERIC_PARAMETER)
		failure("typedef<class> Box<T> {}\nfn main() { Box::type }", DiagnosticCode.RAW_GENERIC_TYPE)
		failure("typedef<class> Box<T> {}\nfn main() { Box<int, string>::new }", DiagnosticCode.GENERIC_ARITY)
		failure("typedef<class> Box<T> {}\nfn main() { Box<void>::new }", DiagnosticCode.INVALID_GENERIC_ARGUMENT)
	}

	@Test
	fun `closed layout cycles and expanding requirements precede the callable stage boundary`() {
		failure("typedef<struct> Loop<T> { Loop<T>[] value }\nfn take(Loop<int> value) {}", DiagnosticCode.RECURSIVE_STRUCT)
		failure("typedef<class> Expands<T> (def<Expands<T[]>?> next ?: null)\nfn take(Expands<int> value) {}", DiagnosticCode.GENERIC_TEMPLATE_DEPENDENCY_LIMIT)
	}

	@Test
	fun `generic diagnostics retain codes source snapshots and explanatory labels`() {
		val error = failure("typedef<class> Box<T> {}\nfn main() { Box::type }", DiagnosticCode.RAW_GENERIC_TYPE)
		val rendered = StringBuilder()
		DiagnosticReporter(rendered).report(error)
		assertContains(rendered.toString(), "C3110")
		assertContains(rendered.toString(), "explicit type arguments")
		assertEquals(listOf(2, 1), error.sourceLabels.map { it.span.startLine })
	}

	@Test
	fun `inferred generic construction emits objects on all nine target profiles`(@TempDir directory: Path) {
		val ast = structUnit("typedef<class> Box<T>(T value) {}\nfn main() { def box = Box::new(1); box::delete }")
		for ((i, triple) in structTargetTriples.withIndex()) {
			val output = directory.resolve("generic_$i.o")
			LLVMEmitter(NativeCABI.forTarget(triple)).compileObject(ast, output)
			assertTrue(Files.size(output) > 0)
		}
	}

	@Test
	fun `legacy type and signature APIs cannot erase an application into a nongeneric nominal`() {
		val syntax = (structUnit("fn<Box<int>> made() = null").declarations.single() as FunctionDeclaration).returnTypes.single()
		for (check in listOf<() -> Unit>({ TypeResolver(nativeTestLayout).resolve(syntax) }, { SignatureType.of(syntax) })) {
			val error = assertFailsWith<ClothDiagnostic> { check() }
			assertEquals(DiagnosticCode.UNSUPPORTED_GENERIC_TYPES, error.diagnosticCode)
			assertLocated(error)
		}
	}

	@Test
	fun `ordinary class construction widths casts comparisons and value names still execute`(@TempDir directory: Path) {
		val source = """
			typedef<class> Counter (pub def<int> value)
			fn main() {
			    def Box = 1
			    def T = 2
			    println(Box < T)
			    println((9 -> int<8>) < 10)
			    def counter = Counter::new(3)
			    println(counter.value)
			    println(int<129>::type::width)
			    counter::delete
			}
		"""
		for (abi in structRunners()) assertEquals("true\ntrue\n3\n129\n" + processCompletion().replace("\r\n", "\n"), runStructProgram(abi, source, directory.resolve("compatible_${abi.layout.sizeBits}")))
	}
}
