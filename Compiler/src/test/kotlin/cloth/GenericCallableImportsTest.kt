package cloth

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.semantic.typing.FunctionAnalyzer
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GenericCallableImportsTest {
	private fun analyze(root: Path, sources: Map<String, String>) = run {
		for ((name, text) in sources) {
			val path = root.resolve(name)
			Files.createDirectories(path.parent)
			Files.writeString(path, text.trimIndent())
		}
		val graph = SourceGraphLoader.load(CompilationRequest(root.resolve("Main.co"), root.resolve("absent/out.o"), listOf(SourceModule("M", root)), outputKind = OutputKind.OBJECT))
		FunctionAnalyzer(genericTarget()).analyze(graph)
	}

	@Test
	fun `maintained imported List fixture is fully checked with closed methods and private slots`(@TempDir root: Path) {
		val fixture = Files.readString(Path.of(".agents/skills/cloth-generics/references/acceptance-fixtures.md"))
		val sources = Regex("```cloth\\R(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(fixture).take(2).map { it.groupValues[1] }.toList()
		val model = analyze(root, mapOf("collections/List.co" to sources[0], "Main.co" to sources[1]))
		assertEquals(6, model.declarations.genericViews!!.types.instances.size)
		assertEquals(31, model.declarations.callables.size) // main plus three primary/nine-method Lists.
		assertTrue(model.types.arrayMutations.isNotEmpty())
		assertTrue(model.declarations.genericViews.templates!!.declarations.genericViews!!.types.symbolic)
	}

	@Test
	fun `definition private helper functions and caller private actual types retain their scopes`(@TempDir root: Path) {
		analyze(root, mapOf(
			"Library.co" to "fn<int> helper() = 1\npub typedef<class> Box<T> { pub mt<T> read(T value) { helper(); return value } }",
			"Main.co" to "using Library : Box\ntypedef<class> Hidden {}\nfn main() { def b = Box<Hidden>::new; def h = Hidden::new; b.read(h); h::delete; b::delete }"
		))
	}

	@Test
	fun `public generic subclasses cannot inherit a public method exposing a private type`(@TempDir root: Path) {
		val error = assertFailsWith<ClothDiagnostic> { analyze(root, mapOf(
			"Library.co" to """
				typedef<struct> Secret { int value }
				typedef<class> Base<T> { pub mt<Secret> expose() { return { 41 } } }
				pub typedef<class> Child<T> : Base<T>()
			""",
			"Main.co" to "using Library : Child\nfn main() {}",
		)) }
		assertEquals(DiagnosticCode.PRIVATE_GENERIC_SIGNATURE, error.diagnosticCode)
		assertContains(error.summary, "'Secret'")
		assertLocated(error)
	}

	@Test
	fun `public generic subclasses check substituted inherited field types`(@TempDir root: Path) {
		val error = assertFailsWith<ClothDiagnostic> { analyze(root, mapOf(
			"Library.co" to """
				typedef<struct> Secret { int value }
				pub typedef<class> Base<T> (pub def<T> value)
				pub typedef<class> Child<T> : Base<Secret>({ 42 })
			""",
			"Main.co" to "using Library : Child\nfn main() {}",
		)) }
		assertEquals(DiagnosticCode.PRIVATE_GENERIC_SIGNATURE, error.diagnosticCode)
		assertContains(error.summary, "'Secret'")
		assertLocated(error)
	}

	@Test
	fun `public generic subclasses check inferred results of inherited public operators`(@TempDir root: Path) {
		val error = assertFailsWith<ClothDiagnostic> { analyze(root, mapOf(
			"Library.co" to """
				typedef<struct> Secret { int value }
				typedef<class> Base<T> (def<Secret> hidden) { pub operator +(int other) = hidden }
				pub typedef<class> Child<T> : Base<T>({ 42 })
			""",
			"Main.co" to "using Library : Child\nfn main() {}",
		)) }
		assertEquals(DiagnosticCode.PRIVATE_GENERIC_SIGNATURE, error.diagnosticCode)
		assertContains(error.summary, "'Secret'")
		assertLocated(error)
	}

	@Test
	fun `inherited public generic members accept caller private types and respect shadowing`(@TempDir root: Path) {
		analyze(root, mapOf(
			"Library.co" to """
				typedef<struct> Secret { int value }
				typedef<class> Base<T> (pub def<T> value, def<Secret> hidden) {
				    pub mt<T> read() = value
				    pub mt<Secret> shadowed() = hidden
				}
				pub typedef<class> Child<T> (T value) : Base<T>(value, { 42 }) {
				    pub mt<int> shadowed() = 42
				}
			""",
			"Main.co" to """
				using Library : Child
				typedef<struct> Local { int value }
				fn<int> main() {
				    def item = Child<Local>::new({ 7 })
				    def<Local> field = item.value
				    def<Local> read = item.read()
				    def<int> number = item.shadowed()
				    item::delete
				    return read.value + field.value + number
				}
			""",
		))
	}

	@Test
	fun `unused imported generic bodies fail in the original source`(@TempDir root: Path) {
		val error = assertFailsWith<ClothDiagnostic> { analyze(root, mapOf(
			"Library.co" to "pub typedef<class> Box<T> { pub mt<T> read(T value) = missing }",
			"Main.co" to "using Library : Box\nfn main() {}"
		)) }
		assertEquals(DiagnosticCode.UNKNOWN_NAME, error.diagnosticCode)
		assertTrue(error.sourceLabels.first().span.source!!.name.endsWith("Library.co"))
	}
}
