package cloth

import cloth.backend.IntegerKernelProgram
import cloth.builtin.BuiltinParameter
import cloth.builtin.BuiltinStorageContract
import cloth.builtin.attachments.PrimitiveAttachments
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BoolAttachmentsTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("bool-members.co", source.trimIndent())).scanTokens()).parse()
	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(unit(source)) }
		assertEquals(code, error.diagnosticCode, source)
		assertLocated(error)
	}

	@Test
	fun `single named registration has the ordinary read only int32 contract`() {
		val provider = PrimitiveAttachments.BOOL
		assertEquals(listOf(provider.TO_INT), provider.all.toList())
		assertSame(provider.TO_INT, PrimitiveAttachments[ValueType.BOOL, "toInt"])
		assertEquals(ValueType.BOOL, provider.TO_INT.receiver)
		assertEquals(emptyList(), provider.TO_INT.parameters)
		assertEquals(listOf(BuiltinParameter.Exact(ValueType.BOOL)), provider.TO_INT.overload.parameters)
		assertEquals(ValueType.INT, provider.TO_INT.overload.returns)
		assertEquals(BuiltinStorageContract.READ_ONLY_INDEPENDENT_RESULT, provider.TO_INT.overload.storage)
		assertFailsWith<UnsupportedOperationException> { (provider.all as MutableCollection).clear() }
		assertNull(PrimitiveAttachments[ValueType.BOOL, "toint"])
		assertNull(PrimitiveAttachments[ValueType.BOOL, "abs"])
		assertNull(PrimitiveAttachments[ValueType.INT, "toInt"])
		assertNull(PrimitiveAttachments[ValueType.FLOAT, "toInt"])
	}

	@Test
	fun `loaded values receivers scores snapshots nullable proofs and ordered results execute on both host targets`(@TempDir directory: Path) {
		val source = """
			fn<bool> receiver(bool flag) { print("R"); return flag }
			fn<int> toInt() = 99
			fn<int> classify(bool? flag) {
			    if (flag != null) return flag.toInt()
			    return -1
			}
			fn<int, int> pair(bool a, bool b) = a.toInt(), b.toInt()
			fn<int> main() {
			    def yes = true
			    def no = false
			    println(yes.toInt()); println(no.toInt())
			    println(receiver(yes).toInt()); println(receiver(no).toInt())
			    def saved = yes.toInt()
			    yes = false
			    println(saved); println(yes.toInt())
			    println((saved > 0).toInt() + (saved == 1).toInt() + no.toInt())
			    def first, second = pair(true, false)
			    println(first); println(second)
			    println(classify(null)); println(classify(false)); println(classify(true))
			    def<bool?> absent = null
			    println((absent ?: true).toInt())
			    def calls = 0
			    def<bool> next = { calls++; return calls < 2 }
			    println(next.toInt()); println(next.toInt()); println(calls)
			    def<bool[]> flags = [true, false, true]
			    println(flags[1].toInt())
			    def count = 0
			    for (def flag <- flags) { count += flag.toInt() }
			    println(count)
			    println(true.toInt().abs()); println(false.toInt().signum())
			    println(true.toInt()::type)
			    return 0
			}
		"""
		val expected = "1\n0\nR1\nR0\n1\n0\n2\n1\n0\n-1\n0\n1\n1\n1\n0\n2\n0\n2\n1\n0\nint<32>\n" + completion()
		for (abi in IntegerKernelProgram.targets()) assertEquals(expected, execute(directory, "values_${abi.architecture}", source, abi))
	}

	@Test
	fun `strict zero argument receiver result and namespace rules stay located`() {
		failure("fn main() { true.toInt(false) }", DiagnosticCode.CALL_ARITY)
		failure("fn main() { true.toInt(flag = true) }", DiagnosticCode.INVALID_ARGUMENT_BINDING)
		failure("fn f(bool? flag) { flag.toInt() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn main() { 1.toInt() }", DiagnosticCode.UNKNOWN_MEMBER)
		failure("fn main() { bool.toInt() }", DiagnosticCode.TYPE_RECEIVER_VALUE)
		failure("fn main() { true::toInt() }", DiagnosticCode.UNSUPPORTED_STATE_CALL)
		failure("fn main() { toInt() }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn<int<64>> f(bool flag) = flag.toInt()", DiagnosticCode.RETURN_TYPE)
		failure("fn main() { true + 1 }", DiagnosticCode.BINARY_OPERANDS)
		failure("fn main() { true -> int }", DiagnosticCode.INVALID_CAST)
		failure("fn main() { false && true.toInt(false) == 0 }", DiagnosticCode.CALL_ARITY)
		failure("fn accept(int n) {}\nfn accept(float n) {}\nfn main() { accept(true.toInt(false)) }", DiagnosticCode.CALL_ARITY)
	}

	@Test
	fun `early receiver exits and short circuits retain checking without extra evaluation`(@TempDir directory: Path) {
		val source = """
			fn<bool> receiver() { print("BAD"); return true }
			fn<int> stop() { (if (true) { return 7 } else { false }).toInt() }
			fn<int> main() {
			    false && receiver().toInt() == 1
			    true || receiver().toInt() == 0
			    println(stop())
			    def count = 0
			    while ((count < 1000).toInt() == 1) { count++ }
			    do { count-- } while ((count > 0).toInt() == 1)
			    if (count != 0) return 1
			    return 0
			}
		"""
		assertEquals("7\n" + completion(), execute(directory, "flow", source, NativeCABI.native()))
	}

	@Test
	fun `maintained example executes on both targets and parameterized conversion verifies all profiles`(@TempDir directory: Path) {
		val example = unit(Files.readString(Path.of("examples", "BoolAttachments.co")))
		val fixture = unit("fn<int> convert(bool flag) = flag.toInt()")
		for ((index, triple) in IntegerKernelProgram.triples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emit(fixture), "zext i1")
			assertTrue(Files.size(emitter.compileObject(fixture, directory.resolve("bool_$index.o"))) > 0)
			assertTrue(Files.size(emitter.compileObject(example, directory.resolve("example_$index.o"))) > 0)
		}
		val source = Files.readString(Path.of("examples", "BoolAttachments.co"))
		for (abi in IntegerKernelProgram.targets()) assertEquals("1\n0\n1\nint<32>\n0\n" + completion(), execute(directory, "example_${abi.architecture}", source, abi))
		val snippet = Parser(Lexer("true.toInt()").scanTokens()).parseSnippet()
		assertEquals(ValueType.INT, SemanticAnalyzer().analyze(snippet).typeOf((snippet.statements.single() as cloth.parser.statement.ExpressionStatement).expression))
	}

	@Test
	fun `invalid first member call stops CLI before object and linker output`(@TempDir directory: Path) {
		val source = directory.resolve("invalid.co")
		val objectFile = directory.resolve("invalid.o")
		Files.writeString(source, "fn main() { true.toInt(false); 1.toInt() }")
		val diagnostic = StringBuilder()
		assertEquals(1, runCompiler(arrayOf("--emit", "object", source.toString(), objectFile.toString(), "--linker", "missing-linker"), DiagnosticReporter(diagnostic)))
		assertContains(diagnostic.toString(), "C3023")
		assertFalse(diagnostic.toString().contains("C3071")); assertFalse(Files.exists(objectFile))
	}

	private fun completion() = processCompletion().replace("\r\n", "\n")
	private fun execute(directory: Path, name: String, source: String, abi: NativeCABI): String {
		val executable = LLVMEmitter(abi).compile(unit(source), directory.resolve(name))
		val log = directory.resolve("$name.log")
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
		try {
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), name)
			val output = Files.readString(log).replace("\r\n", "\n")
			assertEquals(0, process.exitValue(), output)
			return output
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
