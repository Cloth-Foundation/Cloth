package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.FileBindings
import cloth.settings.LinkOptions
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportIntegrationTest {

	private fun sources(root: Path, files: Map<String, String>) {
		for ((name, text) in files) {
			val path = root.resolve(name)
			Files.createDirectories(path.parent)
			Files.writeString(path, text.trimIndent())
		}
	}

	private fun request(root: Path, kind: OutputKind = OutputKind.EXECUTABLE, target: String? = null, options: LinkOptions = LinkOptions()) = CompilationRequest(root.resolve("App/Main.co"), root.resolve("output/program" + if (kind == OutputKind.OBJECT) ".o" else ""), listOf(SourceModule("App", root.resolve("App")), SourceModule("Lib", root.resolve("Lib"))), outputKind = kind, targetTriple = target, linkOptions = options)
	private fun tree(root: Path, files: Map<String, String>): CompilationRequest {
		Files.createDirectories(root.resolve("Lib"))
		sources(root, files)
		return request(root)
	}

	private fun execute(request: CompilationRequest, status: Int = 0, text: String = "") {
		val artifact = ClothCompiler.compile(request).artifact
		val process = ProcessBuilder(artifact.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Imported program timed out")
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			assertEquals(status, process.exitValue(), output)
			assertEquals(text.replace("\n", System.lineSeparator()) + processCompletion(status), output)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}

	@Test
	fun `selective wildcard repeated and circular imports emit each callable once`(@TempDir root: Path) {
		val request = tree(root, mapOf("App/Main.co" to "using math.Even: even\nusing math.Odd: *\nusing math.Even: even\nfn<int> main() { if (!even(12) || !odd(13)) return 21; print(\"cycle\"); return 7 }", "App/math/Even.co" to "using math.Odd: odd\nusing math.Even: *\npub fn<bool> even(int n) { if (n == 0) return true; return odd(n - 1) }", "Lib/math/Odd.co" to "using math.Even: even\npub fn<bool> odd(int n) { if (n == 0) return false; return even(n - 1) }"))
		val abi = NativeCABI.native()
		val model = ProgramAnalyzer(abi.layout).analyze(FileBindings.graph(SourceGraphLoader.load(request)))
		val ir = LLVMEmitter(abi).emitExecutable(model)
		assertEquals(3, ir.lineSequence().count { it.startsWith("define ") && "@cloth.fn." in it })
		assertEquals(1, ir.lineSequence().count { it.startsWith("define i32 @${if (abi.windows) "wmain" else "main"}(") })
		execute(request, 7, "cycle")
	}

	@Test
	fun `only own primary main selects startup while other main overloads are callable`(@TempDir root: Path) {
		execute(tree(root, mapOf("App/Main.co" to "using Other: callMain\nfn<int> main() = callMain()", "Lib/Other.co" to "pub fn<float> main(float value) = value\nfn<int> main(bool flag) { if (flag) return 7; return 8 }\npub fn<int> callMain() = main(true)")), 7)
	}

	@Test
	fun `imported main never satisfies missing primary entry and duplicate labels stay primary`(@TempDir root: Path) {
		val request = tree(root, mapOf("App/Main.co" to "using Other: main\nfn helper() {}", "Lib/Other.co" to "pub fn main() {}"))
		val missing = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request) }
		assertEquals(DiagnosticCode.MISSING_MAIN, missing.diagnosticCode)
		assertTrue(missing.sourceLabels.all { it.span.source?.name == request.sourceFile.toString() })
		Files.writeString(request.sourceFile, "using Other: *\nfn main() {}\nfn main(string[] args) {}")
		// Avoid a distinct import-name conflict while retaining another file's main.
		Files.writeString(root.resolve("Lib/Other.co"), "fn main() {}")
		val duplicate = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request) }
		assertEquals(DiagnosticCode.DUPLICATE_MAIN, duplicate.diagnosticCode)
		assertEquals(2, duplicate.sourceLabels.size)
		assertTrue(duplicate.sourceLabels.all { it.span.source?.name == request.sourceFile.toString() })
		assertFalse(Files.exists(request.outputFile.parent))
	}

	@Test
	fun `defining file defaults and buffered multiple results keep private call and copy semantics`(@TempDir root: Path) {
		execute(tree(root, mapOf("App/Main.co" to """
				using Packet: *
				fn<string> helper() = "caller"
				fn main() {
				    def packet, words = make()
				    def copy = packet
				    copy.words[0] = "changed"
				    print(packet.first() + words[0] + copy.first())
				}
			""", "Lib/Packet.co" to """
				fn<string> helper() = "origin"
				pub typedef<struct> Packet { string[1] words; mt<string> first() = words[0] }
				pub fn<Packet, string[]> make(opt<string> word ?: helper()) {
				    def<Packet> packet = { [word] }
				    return packet, packet.words
				}
			""")), text = "originoriginchanged")
	}

	@Test
	fun `same spelled private structs classes enums and helpers retain their emission owners`(@TempDir root: Path) {
		execute(tree(root, mapOf("App/Main.co" to "using Left: left\nusing Right: right\nfn main() { print(left()); print(right()) }", "App/Left.co" to """
				typedef<struct> Hidden { int value; mt<int> read() = value }
				typedef<enum> Mark { Left }
				typedef<class> Box(pub def<int> value)
				fn<int> helper() { def<Hidden> item = { 3 }; return item.read() }
				pub fn<string> left() {
				    def box = Box::new(helper())
				    def text = #"{box.value}:{Mark.Left}"
				    box::delete
				    return text
				}
			""", "Lib/Right.co" to """
				typedef<struct> Hidden { string value; mt<string> read() = value }
				typedef<enum> Mark { Right }
				typedef<class> Box(pub def<string> value)
				fn<string> helper() { def<Hidden> item = { "x" }; return item.read() }
				pub fn<string> right() {
				    def box = Box::new(helper())
				    def text = #"{box.value}:{Mark.Right}"
				    box::delete
				    return text
				}
			""")), text = "3:Leftx:Right")
	}

	@Test
	fun `nominal metadata identity survives returns nullable and array wrappers without qualifying text`(@TempDir root: Path) {
		execute(tree(root, mapOf("App/Main.co" to """
				using Left: left, leftArray, leftNullable
				using Right: right, rightArray, rightNullable
				fn<int> main() {
				    if (left() == right() || leftArray() == rightArray() || leftNullable() == rightNullable()) return 21
				    if (left() != left() || leftArray() != leftArray() || leftNullable() != leftNullable()) return 22
				    if (leftArray()::element != left() || rightArray()::element != right()) return 23
				    print(left()); print(right())
				    return 0
				}
			""", "App/Left.co" to "typedef<struct> Hidden { int value }\npub fn<typeinfo> left() = Hidden::type\npub fn<typeinfo> leftArray() { def<Hidden[1]> values = [{ 0 }]; return values::type }\npub fn<typeinfo> leftNullable() { def<Hidden?> value = null; return value::type }", "Lib/Right.co" to "typedef<struct> Hidden { int value }\npub fn<typeinfo> right() = Hidden::type\npub fn<typeinfo> rightArray() { def<Hidden[1]> values = [{ 0 }]; return values::type }\npub fn<typeinfo> rightNullable() { def<Hidden?> value = null; return value::type }")), text = "HiddenHidden")
	}

	@Test
	fun `multi file objects honor every target with ordinary mains and no linker or startup`(@TempDir root: Path) {
		tree(root, mapOf("App/Main.co" to "using Lib: answer\nfn<int> use() = answer()", "Lib/Lib.co" to "pub fn<int> answer() = 42\nfn<bool> main() = true\nfn<float> main(float n) = n"))
		val targets = listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "arm64-apple-darwin")
		for ((index, target) in targets.withIndex()) {
			val request = request(root, OutputKind.OBJECT, target, LinkOptions(linker = root.resolve("missing-linker")))
			val artifact = ClothCompiler.compile(CompilationRequest(request.sourceFile, root.resolve("objects/$index.o"), request.modules, OutputKind.OBJECT, targetTriple = target, linkOptions = request.linkOptions)).artifact
			assertTrue(Files.size(artifact) > 8, target)
			val bytes = Files.readAllBytes(artifact).toString(Charsets.ISO_8859_1)
			assertFalse("PEC: " in bytes, target)
			assertFalse("Invalid Unicode in command-line argument" in bytes, target)
		}
	}
}
