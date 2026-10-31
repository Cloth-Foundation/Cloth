package cloth

import cloth.data.CArray
import cloth.data.CInt
import cloth.data.ProviderReceiverForm
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.expression.*
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProviderSyntaxTest {

	private fun snippet(source: String) = Parser(Lexer(SourceFile("providers.co", source.trimIndent())).scanTokens()).parseSnippet()
	private fun unit(source: String) = Parser(Lexer(SourceFile("providers.co", source.trimIndent())).scanTokens()).parse()
	private fun expression(source: String) = (snippet(source).statements.single() as ExpressionStatement).expression

	@Test
	fun `postfix access chains left to right with indexing and tight precedence`() {
		val addition = assertIs<BinaryExpression>(expression("matrix[0]::max::min + int<64>::max"))
		val minimum = assertIs<ProviderAccessExpression>(addition.left)
		assertEquals("min", minimum.name.lexeme)
		val maximum = assertIs<ProviderAccessExpression>(minimum.receiver)
		assertEquals("max", maximum.name.lexeme)
		assertIs<IndexExpression>(maximum.receiver)
		val type = assertIs<TypeReceiverExpression>(assertIs<ProviderAccessExpression>(addition.right).receiver)
		assertEquals("64", type.annotation.width?.lexeme)
		assertIs<ProviderAccessExpression>(assertIs<IndexExpression>(expression("items::max[0]")).array)
		val arrays = assertIs<TypeReceiverExpression>(assertIs<ProviderAccessExpression>(expression("int[2][]::size")).receiver)
		assertEquals(listOf("2", null), arrays.annotation.arraySizes.map { it?.lexeme })
	}

	@Test
	fun `parentheses select a distinct state call and never call a provider`() {
		val call = assertIs<StateCallExpression>(expression("int::max()"))
		assertEquals("max", call.name.lexeme)
		assertTrue(call.arguments.isEmpty())
		assertIs<ProviderAccessExpression>(expression("int::max"))
		val failure = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(unit("fn<int> test() = int::max()")) }
		assertEquals(DiagnosticCode.UNSUPPORTED_STATE_CALL, failure.diagnosticCode)
		assertLocated(failure)
	}

	@Test
	fun `integer bounds preserve width signedness and explicit receiver forms`() {
		for (width in listOf(8, 16, 32, 64)) for (unsigned in listOf(false, true)) {
			val type = CInt(width, unsigned)
			val ast = snippet("${type.spelling}::min::max")
			val semantics = SemanticAnalyzer().analyze(ast)
			val outer = (ast.statements.single() as ExpressionStatement).expression as ProviderAccessExpression
			assertEquals(type, semantics.typeOf(outer))
			assertEquals(ProviderReceiverForm.VALUE, semantics.providerAccess(outer).receiverForm)
			assertEquals(ProviderReceiverForm.TYPE, semantics.providerAccess(outer.receiver as ProviderAccessExpression).receiverForm)
		}
		val arrays = snippet("[int<64>::max, 1]")
		assertEquals(CArray(CInt(64), 2), SemanticAnalyzer().analyze(arrays).typeOf(arrays.statements.single()))
	}

	@Test
	fun `accessors are typed values and do not retarget their receiver from outer context`() {
		val ast = snippet("1 + int<8>::max")
		assertEquals(CInt(8), SemanticAnalyzer().analyze(ast).typeOf(ast.statements.single()))
		for (source in listOf("def<int<64>> wide = int::max", "def<int<64>> wide = 1::max")) {
			val failure = assertFailsWith<ClothDiagnostic> { SemanticAnalyzer().analyze(snippet(source)) }
			assertEquals(DiagnosticCode.TYPE_MISMATCH, failure.diagnosticCode)
			assertLocated(failure)
		}
		val failure = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(unit("fn<int<64>> wrong() = int::max")) }
		assertEquals(DiagnosticCode.RETURN_TYPE, failure.diagnosticCode)
	}

	@Test
	fun `unknown uppercase and unavailable providers fail at the accessor name`() {
		for (source in listOf("int::MAX", "int::missing", "bool::min", "int::addr")) {
			val failure = assertFailsWith<ClothDiagnostic> { SemanticAnalyzer().analyze(snippet(source)) }
			assertEquals(DiagnosticCode.UNKNOWN_PROVIDER, failure.diagnosticCode)
			assertLocated(failure)
			assertEquals(source.substringAfter("::"), failure.sourceLabels.single().span.let {
				source.substring(it.startColumn - 1, it.endColumn - 1)
			})
		}
		val failure = assertFailsWith<ClothDiagnostic> { SemanticAnalyzer().analyze(snippet("int")) }
		assertEquals(DiagnosticCode.TYPE_RECEIVER_VALUE, failure.diagnosticCode)
	}

	@Test
	fun `all assignment and update forms reject direct accessor targets`() {
		for (operator in listOf("= 1", ":= 1", "+= 1", "-= 1", "*= 2", "/= 2", "%= 2", "++", "--")) {
			val failure = assertFailsWith<ClothDiagnostic> { snippet("int::max $operator") }
			assertEquals(DiagnosticCode.READ_ONLY_PROVIDER, failure.diagnosticCode, operator)
			assertLocated(failure)
			assertEquals(6, failure.sourceLabels.single().span.startColumn)
		}
	}

	@Test
	fun `provider errors remain intrinsic inside overload probes and fail at the first detected error`() {
		val source = "fn<int> take(int value) = value\nfn<int> take(int<64> value) = 0\nfn<int> test() = take(int::missing)"
		val failure = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(unit(source)) }
		assertEquals(DiagnosticCode.UNKNOWN_PROVIDER, failure.diagnosticCode)
		assertLocated(failure)
		val first = assertFailsWith<ClothDiagnostic> { SemanticAnalyzer().analyze(snippet("int::first\nint::second")) }
		assertEquals(DiagnosticCode.UNKNOWN_PROVIDER, first.diagnosticCode)
		assertContains(first.summary, "first")
		assertEquals(1, first.sourceLabels.single().span.startLine)
	}

	@Test
	fun `type receivers emit constants without runtime instance storage`() {
		val ir = LLVMEmitter().emit(unit("fn<int> test() = int::max"))
		assertContains(ir, "ret i32 2147483647")
		assertFalse(ir.contains("alloca"))
	}

	@Test
	fun `integer bounds execute for every width signedness and receiver form`(@TempDir directory: Path) {
		val checks = buildString {
			for (width in listOf(8, 16, 32, 64)) for (unsigned in listOf(false, true)) {
				val type = CInt(width, unsigned)
				appendLine("if (${type.spelling}::min != ${type.minimum} || ${type.spelling}::max != ${type.maximum}) { return 1 }")
				appendLine("def<${type.spelling}> value${width}_$unsigned = 7")
				appendLine("if (value${width}_$unsigned::min != ${type.minimum} || value${width}_$unsigned::max != ${type.maximum}) { return 2 }")
			}
		}
		native(directory, "bounds", "fn<int> main() {\n$checks\nreturn 0\n}")
	}

	@Test
	fun `value receivers preserve captured effects and copied results stay mutable`(@TempDir directory: Path) {
		native(directory, "effects", """
			fn<int> main() {
			    def count = 0
			    def<int<8>> next = {
			        count += 1
			        return 7
			    }
			    def copied = next::max
			    copied -= 1
			    def low = next::min
			    if (count != 2 || copied != 126 || low != -128) { return 1 }
			    def max = 7
			    def min = 8
			    def values = [int<8>::max, 1]
			    if (max::max != int::max || min::min != int::min || values[1]::max::min != -128) { return 2 }
			    return 0
			}
		""")
	}

	@Test
	fun `overload selection preserves provider facts across exact and contextual candidates`(@TempDir directory: Path) {
		native(directory, "overloads", """
			fn<int> choose(int<8> a, int<8> b) = 1
			fn<int> choose(int<64> a, int<64> b) = 2
			fn<int> exact(int<8> value) = 3
			fn<int> exact(int<64> value) = 4
			fn<int> main() {
			    if (choose(int<64>::max, 1) != 2 || exact(int<8>::max) != 3) { return 1 }
			    return 0
			}
		""")
	}

	@Test
	fun `indexed constant providers preserve bounds failures`(@TempDir directory: Path) {
		native(directory, "bounds_failure", "fn<int> main() {\ndef a = [1]\nreturn a[8]::max\n}", "Array index out of bounds")
	}

	@Test
	fun `a receiver returning from the function produces no accessor value`(@TempDir directory: Path) {
		native(directory, "early_return", "fn<int> main() = (if (true) { return 0 } else { return 1 })::max")
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
				assertNotEquals(0, process.exitValue(), name); assertContains(output, expectedError)
			}
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
