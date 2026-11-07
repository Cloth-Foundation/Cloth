package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.exception.SyntaxException
import cloth.semantic.ProgramAnalyzer
import cloth.semantic.declarations.DeclarationAnalyzer
import cloth.semantic.typing.FunctionAnalyzer
import cloth.settings.LinkOptions
import cloth.settings.OutputKind
import cloth.target.TargetLayout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** This suite must also run with LLVM and JavaCPP absent from the JVM classpath. */
class ImportStageBoundaryTest {

	@Test
	fun `one file semantic adapters reject unresolved imports before other declaration errors`() {
		if (java.lang.Boolean.getBoolean("cloth.test.nativeFreeImports")) {
			assertNull(javaClass.classLoader.getResource("org/bytedeco/llvm/global/LLVM.class"))
			assertNull(javaClass.classLoader.getResource("org/bytedeco/javacpp/Pointer.class"))
		}
		val text = "using Token: Token\nusing Other: *\nfn main() { unknownName }"
		val unit = Parser(Lexer(SourceFile("semantic.co", text)).scanTokens()).parse()
		for (bits in listOf(32, 64)) {
			val target = TargetLayout("synthetic-$bits", pointerBits = bits, cLongBits = bits)
			val checks = listOf<() -> Any>({ DeclarationAnalyzer(target).analyze(unit) }, { FunctionAnalyzer(target).analyze(unit) }, { ProgramAnalyzer(target).analyze(unit) })
			for (check in checks) {
				val failure = assertFailsWith<ClothDiagnostic> { check() }
				assertEquals(DiagnosticCode.IMPORTS_REQUIRE_SOURCE_GRAPH, failure.diagnosticCode)
				assertEquals(unit.imports.first().span, failure.sourceLabels.single().span)
			}
		}
	}

	@Test
	fun `private import binding fails before ABI linker or output work`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("entry.co"), "using Token: token\nfn main() { unknownName }")
		Files.writeString(directory.resolve("Token.co"), "fn token() {}")
		val output = directory.resolve("not-created/program")
		val request = CompilationRequest(source, output, modules = listOf(SourceModule("Frontend", directory)), targetTriple = "not-a-target", linkOptions = LinkOptions(linker = directory.resolve("missing-linker")))
		val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(request) }
		assertEquals(DiagnosticCode.IMPORT_PRIVATE_NAME, failure.diagnosticCode)
		assertEquals("using Token: token", failure.sourceLabels.first().span.source?.text?.lineSequence()?.first())
		assertFalse(Files.exists(output.parent))
	}

	@Test
	fun `standalone build and run report one import diagnostic and preserve stale output`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("entry with spaces λ.co"), "using Token: *\nfn main() {}")
		val output = Files.writeString(directory.resolve("existing-artifact"), "preserve")
		for (prefix in listOf(emptyList(), listOf("run"))) {
			val bytes = ByteArrayOutputStream()
			PrintStream(bytes, true, Charsets.UTF_8).use { stream ->
				val status = runCompiler((prefix + listOf(source.toString(), output.toString(), "--target", "not-a-target")).toTypedArray(), DiagnosticReporter(stream))
				assertEquals(1, status)
			}
			val rendered = bytes.toString(Charsets.UTF_8)
			assertEquals(1, Regex("C3097").findAll(rendered).count())
			assertContains(rendered, "No module context")
			assertContains(rendered, "Build the project through Shuttle")
			assertEquals("preserve", Files.readString(output))
		}
	}

	@Test
	fun `malformed import syntax precedes the unresolved graph check and target selection`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("invalid.co"), "using Token: Token, Token\nfn main() {}")
		val output = directory.resolve("not-created/entry.o")
		val failure = assertFailsWith<SyntaxException> { ClothCompiler.compile(CompilationRequest(source, output, outputKind = OutputKind.OBJECT, targetTriple = "not-a-target")) }
		assertEquals(DiagnosticCode.DUPLICATE_IMPORT_SELECTION, failure.diagnosticCode)
		assertEquals(2, failure.sourceLabels.size)
		assertFalse(Files.exists(output.parent))
	}
}
