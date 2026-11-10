package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GuardedAccessTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("guarded.co", source.trimIndent())).scanTokens()).parse()

	@Test
	fun `each nullable receiver needs its own guard and skipped source is checked`() {
		val types = """
			typedef<class> Leaf(pub def<int> value) {
			    pub mt<int, int> pair() = value, value
			}
			typedef<class> Root(pub def<Leaf?> leaf) {}
		""".trimIndent()
		for ((source, code) in listOf(
			"fn<int?> read(Root? root) = root?.leaf.value" to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS,
			"fn<int> read(Root? root) = (root?.leaf).value" to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS,
			"fn<int?> read(Leaf leaf) = leaf?.value" to DiagnosticCode.INVALID_GUARDED_RECEIVER,
			"fn read(Root? root) { root?.leaf?.missing() }" to DiagnosticCode.UNKNOWN_MEMBER,
			"fn read(Leaf? leaf) { def value = leaf?.value }" to DiagnosticCode.EXPLICIT_NULLABLE_TYPE,
			"fn read(Leaf? leaf) { leaf?.pair() }" to DiagnosticCode.MULTIPLE_RESULTS_IN_SCALAR,
			"fn read(Leaf? leaf) { leaf?.value; println(leaf.value) }" to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS,
			"fn read() { def leaf = Leaf::new(1); def<Leaf?> alias = leaf; leaf::delete; println(alias?.value) }" to DiagnosticCode.DELETED_CLASS_ACCESS,
		)) {
			val diagnostic = assertFailsWith<ClothDiagnostic>(source) { FunctionAnalyzer(nativeTestLayout).analyze(parse("$types\n$source")) }
			assertEquals(code, diagnostic.diagnosticCode, diagnostic.summary)
			assertLocated(diagnostic)
		}
	}

	private val program = """
		fn<string> stamp(string text) { print(text); return text }
		typedef<class> Leaf(pub def<string?> text) {
		    pub mt<string?> show(opt<string> tag ?: stamp("D")) { print(tag); return text }
		    pub mt<string> echo(string tag) = tag
		    pub mt touch(string tag) = println(tag)
		}
		typedef<class> Branch(pub def<Leaf?> child, pub def<string> label) {
		    pub def<int[2]> numbers = [7, 8]
		    pub mt<Leaf?> get() { print("H"); return child }
		}
		typedef<class> Box<T>(pub def<T?> value) {
		    pub mt<T?> read(Box<T>? other) = other?.value
		}
		fn<Branch?> see(Branch? branch) { print("R"); return branch }
		fn<usize> index() { print("I"); return 1 }
		fn<int> early(Branch? branch) {
		    println(branch?.get()?.echo(if (true) { return 8 } else { "unused" }).toUpperCase() ?: "skipped")
		    return 7
		}
		fn main() {
		    def leaf = Leaf::new("abc")
		    def noText = Leaf::new(null)
		    def live = Branch::new(leaf, "hello")
		    def missing = Branch::new(null, "missing")
		    def empty = Branch::new(noText, "empty")
		    println(see(null)?.get()?.show(tag=stamp("A")) ?: stamp("F"))
		    println(see(missing)?.get()?.show(tag=stamp("A")) ?: stamp("F"))
		    println(see(live)?.get()?.show(tag=stamp("A")) ?: stamp("F"))
		    println(see(empty)?.get()?.show() ?: "no text")
		    println(see(null)?.numbers[index()])
		    println(see(live)?.numbers[index()])
		    println(see(live)?.numbers::size)
		    def<string?> upper = see(live)?.label.toUpperCase()
		    live::delete
		    println(upper)
		    def<Leaf?> selected = null
		    selected?.touch(stamp("unreachable"))
		    selected = leaf
		    def<string> reset = { selected = null; return "once" }
		    selected?.touch(reset)
		    println(selected == null)
		    println(early(null))
		    println(early(empty))
		    def box = Box<string>::new("boxed")
		    def<string?> saved = box.read(box)
		    println(box.read(null))
		    box::delete
		    println(saved)
		    missing::delete
		    empty::delete
		    leaf::delete
		    noText::delete
		}
	""".trimIndent()

	@Test
	fun `chains evaluate reached receivers and arguments once and preserve nullable results`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("guarded.co"), program)
		val expected = listOf("RFF", "RHFF", "RHAAabc", "RHDDno text", "Rnull", "RI8", "R2", "RHELLO", "once", "true", "skipped", "7", "H8", "null", "boxed")
			.joinToString(System.lineSeparator(), postfix = System.lineSeparator()) + processCompletion()
		for (abi in structRunners()) {
			val artifact = ClothCompiler.compile(CompilationRequest(source, directory.resolve("guarded_${abi.layout.sizeBits}"), targetTriple = abi.targetTriple)).artifact
			assertEquals(expected, runExecutable(artifact))
		}
	}

	@Test
	fun `guarded branches verify executable IR on all supported targets`() {
		val unit = parse(program)
		for (triple in structTargetTriples) {
			val ir = LLVMEmitter(NativeCABI.forTarget(triple)).emitExecutable(unit)
			assertTrue(ir.contains("target triple = \"$triple\""))
		}
	}
}
