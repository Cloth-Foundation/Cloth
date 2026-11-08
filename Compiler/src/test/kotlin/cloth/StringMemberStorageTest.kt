package cloth

import cloth.builtin.BuiltinBody
import cloth.builtin.BuiltinOverload
import cloth.builtin.BuiltinStorageContract
import cloth.builtin.attachments.PrimitiveAttachments
import cloth.data.ValueType
import cloth.data.CArray
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.emitter.storage.ArrayTemporaryAnalysis
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.typing.FunctionAnalyzer
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringMemberStorageTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("member-storage.co", source.trimIndent())).scanTokens()).parse()

	@Test
	fun `only explicit independent read only contracts permit member reclamation`() {
		assertEquals(BuiltinStorageContract.UNKNOWN, BuiltinOverload(emptyList(), ValueType.VOID, BuiltinBody { _, _ -> null }).storage)
		assertTrue(PrimitiveAttachments.all(nativeTestLayout).all { member -> member.overloads.all { it.storage == if (member.receiver is CArray) BuiltinStorageContract.MUTATING_RECEIVER else BuiltinStorageContract.READ_ONLY_INDEPENDENT_RESULT } })
		val model = FunctionAnalyzer(nativeTestLayout).analyze(unit("fn<bool> query(string text) = text.isEmpty()"))
		val (expression, call) = model.types.memberCalls.entries.single()
		assertTrue(ArrayTemporaryAnalysis(model).canReclaim(expression))
		// Model a future member with an unproven body without mutating global registrations.
		model.types.memberCalls[expression] = call.copy(overload = call.overload.copy(storage = BuiltinStorageContract.UNKNOWN))
		assertFalse(ArrayTemporaryAnalysis(model).canReclaim(expression))
		val mutating = FunctionAnalyzer(nativeTestLayout).analyze(unit("fn work(int[] values) { values.reserve(8) }"))
		assertFalse(ArrayTemporaryAnalysis(mutating).canReclaim(mutating.types.memberCalls.keys.single()))
	}

	@Test
	fun `captured publication keeps reclamation conservative even for read only members`() {
		val model = FunctionAnalyzer(nativeTestLayout).analyze(unit("""
			fn<string> fresh() = "published"
			fn main() {
			    def saved = "old"
			    def<string> change = { saved = fresh(); return "old" }
			    saved.contains(change)
			}
		"""))
		val expression = model.types.memberCalls.keys.single()
		assertFalse(ArrayTemporaryAnalysis(model).canReclaim(expression))
	}

	@Test
	fun `member cleanup and escaping buffered results verify across all target profiles`(@TempDir directory: Path) {
		val ast = unit("""
			fn<string> keep(string text) {
			    text.trim().contains("x")
			    text.toChars()
			    return text.trim()
			}
			fn<usize> find(string text) = text.trim().indexOf("x") ?: 0
		""")
		for ((index, triple) in listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin").withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emit(ast), "llvm.stackrestore.p0")
			assertTrue(Files.size(emitter.compileObject(ast, directory.resolve("storage_$index.o"))) > 0)
		}
	}

	@Test
	fun `member temporaries remain bounded inside unsafe outer loops on Windows x64 and x86`(@TempDir directory: Path) {
		assumeTrue(ExecutableFormat.current == ExecutableFormat.PE)
		val payload = "x".repeat(4096)
		val ast = unit("""
			fn<string> produce() = "kept"
			fn<int> main() {
			    def saved = produce()
			    def published = produce()
			    for (def i <- 0..5000) {
			        published = produce()
			        "$payload".removePrefix("missing")
			        if (!produce().trim().startsWith("k")) return 1
			    }
			    if (saved != "kept" || published != "kept") return 2
			    return 0
			}
		""")
		for ((index, triple) in listOf("x86_64-pc-windows-msvc", "i686-pc-windows-msvc").withIndex()) {
			val executable = LLVMEmitter(NativeCABI.forTarget(triple)).compile(ast, directory.resolve("bounded_$index"))
			val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
			try {
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), triple)
				val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
				assertEquals(0, process.exitValue(), output)
				assertEquals(processCompletion(), output)
			} finally {
				if (process.isAlive) process.destroyForcibly()
			}
		}
	}
}
