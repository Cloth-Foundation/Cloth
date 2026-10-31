package cloth

import cloth.builtin.BuiltinFunctions
import cloth.builtin.PrintFunction
import cloth.builtin.PrintlnFunction
import cloth.data.CInt
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.CallExpression
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.ResolvedCall
import cloth.semantic.symbols.BuiltinGroupSymbol
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BuiltinFunctionsTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("builtins.co", source.trimIndent())).scanTokens()).parse()
	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic {
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer().analyze(unit(source)) }
		assertEquals(code, error.diagnosticCode, source)
		assertLocated(error)
		return error
	}

	@Test
	fun `named factory registrations are the complete read-only lookup list`() {
		assertEquals(listOf(BuiltinFunctions.PRINT, BuiltinFunctions.PRINTLN), BuiltinFunctions.all)
		assertSame(BuiltinFunctions.PRINT, BuiltinFunctions["print"])
		assertSame(BuiltinFunctions.PRINTLN, BuiltinFunctions["println"])
		assertIs<PrintFunction>(BuiltinFunctions.PRINT.function)
		assertIs<PrintlnFunction>(BuiltinFunctions.PRINTLN.function)
		assertNull(BuiltinFunctions["printf"])
		assertNull(BuiltinFunctions["Print"])
		assertFailsWith<UnsupportedOperationException> {
			(BuiltinFunctions.all as MutableList).clear()
		}
	}

	@Test
	fun `unimported calls retain registry identities concrete types and selected text recipes`() {
		val model = FunctionAnalyzer().analyze(unit("fn main() {\n print(10)\n println(int[3]::type)\n println()\n}"))
		val statements = (model.declarations.functions.single().declaration.body as BlockBody).statements
		val calls = statements.map { (it as ExpressionStatement).expression as CallExpression }
		assertSame(BuiltinFunctions.PRINT, assertIs<BuiltinGroupSymbol>(model.declarations.candidatesOf(calls[0])).definition)
		val print = assertIs<ResolvedCall.Builtin>(model.selectedCall(calls[0])).call
		assertSame(BuiltinFunctions.PRINT, print.definition)
		assertEquals(ValueType.INT, print.arguments.single().sourceType)
		assertEquals(ValueType.STRING, print.arguments.single().parameterType)
		assertEquals(ValueType.INT.provider("str"), print.arguments.single().text)
		assertEquals(ValueType.TYPEINFO, assertIs<ResolvedCall.Builtin>(model.selectedCall(calls[1])).call.arguments.single().sourceType)
		assertTrue(assertIs<ResolvedCall.Builtin>(model.selectedCall(calls[2])).call.arguments.isEmpty())
		calls.forEach { assertEquals(ValueType.VOID, model.typeOf(it)) }
	}

	@Test
	fun `arity unsupported arrays void misuse and invalid expressions fail at their source`() {
		for (call in listOf("print()", "print(1, 2)", "println(1, 2)")) {
			val error = failure("fn main() { $call }", DiagnosticCode.CALL_ARITY)
			assertContains(error.guidance.orEmpty(), "Built-in signatures")
			assertEquals(1, error.sourceLabels.size)
		}
		val array = failure("fn main() { println([1, 2]) }", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		assertContains(array.summary, "int[2]")
		assertEquals(1, array.sourceLabels.size)
		assertContains(array.guidance.orEmpty(), "str provider")
		failure("fn main() { println(println(1)) }", DiagnosticCode.VOID_VALUE)
		failure("fn main() { def x = print(1) }", DiagnosticCode.VOID_VALUE)
		failure("fn<int> value() = println(1)", DiagnosticCode.RETURN_TYPE)
		failure("fn main() { println(1 + true) }", DiagnosticCode.BINARY_OPERANDS)
		failure("fn main() { println(missing) }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn main() { Print(1) }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn main() { printf(1) }", DiagnosticCode.UNKNOWN_NAME)
		failure("fn main() { def x = print }", DiagnosticCode.FUNCTION_VALUE)
	}

	@Test
	fun `lexical bindings and whole file function groups shadow builtins`() {
		failure("fn main() {\n def print = 1\n print(2)\n}", DiagnosticCode.NOT_CALLABLE)
		failure("fn main(int println) { println(1) }", DiagnosticCode.NOT_CALLABLE)
		failure("fn println(bool value) {}\nfn main() { println(1) }", DiagnosticCode.NO_MATCHING_CALL)
		failure("fn println(bool value) {}\nfn main() { println() }", DiagnosticCode.CALL_ARITY)
		val assignment = failure("fn main() { print = 1 }", DiagnosticCode.EXPECTED_BINDING)
		assertEquals(1, assignment.sourceLabels.size)
	}

	@Test
	fun `print and println preserve text newline choices and silent final values`(@TempDir directory: Path) {
		native(directory, "output", """
			fn main() {
			    print("Hello ")
			    println(42)
			    println()
			    print("")
			    print("tail")
			    100 + 1
			}
		""", "Hello 42\n\ntail")
	}

	@Test
	fun `all integer widths signedness and floating formats use shared text conversion`(@TempDir directory: Path) {
		val statements = mutableListOf<String>()
		val expected = mutableListOf<String>()
		for (bits in ValueType.INTEGER_WIDTHS) for (unsigned in listOf(false, true)) {
			val type = CInt(bits, unsigned)
			for (value in listOf(type.minimum, type.maximum)) {
				val name = "n${statements.size}"
				statements += "def<${type.spelling}> $name = $value\nprintln($name)"
				expected += value.toString()
			}
		}
		for (bits in ValueType.FLOAT_WIDTHS) {
			statements += "println(1.25 -> float<$bits>)"
			expected += "1.25"
		}
		statements += "println(precise(1.0000000000000002))"
		expected += "1.0000000000000002"
		// Printing supplies no floating context: this literal keeps float32 precision.
		statements += "println(1.0000000000000002 + precise(0.0))"
		expected += "1"
		native(directory, "numbers", "fn<float<64>> precise(float<64> value) = value\nfn main() {\n${statements.joinToString("\n")}\n}", expected.joinToString("\n", postfix = "\n"))
	}

	@Test
	fun `boolean unicode NUL percent strings interpolation and array metadata print without loss`(@TempDir directory: Path) {
		native(directory, "text", """
			fn main() {
			    println(true)
			    println(false)
			    println('😀')
			    print("é😀\0%done")
			    println(#" {int::type} {unsigned int<64>::type}")
			    println(int[2][3]::type)
			    println(typeinfo::type)
			}
		""", "true\nfalse\n😀\né😀\u0000%done int<32> unsigned int<64>\nint<32>[2][3]\ntypeinfo\n")
	}

	@Test
	fun `string arguments and scalar providers remain valid through returned buffered values`(@TempDir directory: Path) {
		native(directory, "buffers", """
			fn<string> text(int n) = #"value {n} 😀"
			fn<string> suffix(string value) = value + "!"
			fn<int[]> numbers() = [7, 8]
			fn main() {
			    def saved = text(1)
			    println(suffix(text(2)))
			    println(saved)
			    println(numbers()[0])
			    println(numbers()::size)
			    println(numbers()::type)
			}
		""", "value 2 😀!\nvalue 1 😀\n7\n2\nint<32>[]\n")
	}

	@Test
	fun `argument blocks execute once and their side effects precede output`(@TempDir directory: Path) {
		native(directory, "effects", """
			fn main() {
			    def counter = 0
			    def<int> next = {
			        counter += 1
			        print("next:")
			        return counter
			    }
			    println(next)
			    println(next)
			    println(counter)
			}
		""", "next:1\nnext:2\n2\n")
	}

	@Test
	fun `early returns suppress conversion output and newline on the exiting paths`(@TempDir directory: Path) {
		native(directory, "returns", """
			fn<int> choose(bool early) {
			    println(if (early) { return 7 } else { 9 })
			    return 8
			}
			fn leave() {
			    println(if (true) { return } else { return })
			}
			fn main() {
			    leave()
			    println(choose(true))
			    println(choose(false))
			}
		""", "7\n9\n8\n")
	}

	@Test
	fun `overload probes commit nested builtin calls and file functions shadow only their own name`(@TempDir directory: Path) {
		native(directory, "overloads", """
			fn<int> print(int value) = value + 1
			fn<int> choose(int value, bool flag) = value
			fn<int> choose(int<64> value, int flag) = 0
			fn main() {
			    println(choose(if (true) {
			        println("inside")
			        6
			    } else { 0 }, true))
			    println(print(41))
			}
		""", "inside\n6\n42\n")
	}

	@Test
	fun `ordinary void contracts accept print bodies and void expression returns`(@TempDir directory: Path) {
		native(directory, "void", """
			fn write() = print("a")
			fn finish() { return println("b") }
			fn main() {
			    write()
			    finish()
			}
		""", "ab\n")
	}

	@Test
	fun `multiple calls and fresh compilations share only module local native helpers`() {
		val emitter = LLVMEmitter()
		val source = unit("fn helper() { print(1) }\nfn main() {\n helper()\n println(2)\n println()\n}")
		repeat(2) {
			val ir = emitter.emitExecutable(source)
			assertEquals(1, ir.lineSequence().count { it.startsWith("define internal void @cloth.runtime.write.stdout(") })
			assertEquals(1, ir.lineSequence().count { it.startsWith("declare i32 @putchar(") })
			assertFalse(ir.contains("@putchar.1"))
			assertFalse(ir.contains("@cloth.fn.0.print"))
		}
		val silent = emitter.emitExecutable(unit("fn main() { 1 + 2 }"))
		assertFalse(silent.contains("@cloth.runtime.write.stdout"))
		assertFalse(silent.contains("@putchar"))
	}

	private fun native(directory: Path, name: String, source: String, expected: String) {
		val executable = LLVMEmitter().compile(unit(source), directory.resolve(name))
		val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
		try {
			assertTrue(process.waitFor(15, TimeUnit.SECONDS), "$name timed out")
			val bytes = process.inputStream.readBytes()
			assertEquals(0, process.exitValue(), bytes.toString(Charsets.UTF_8))
			assertContentEquals(expected.replace("\n", System.lineSeparator()).toByteArray(Charsets.UTF_8), bytes, name)
		} finally {
			if (process.isAlive) process.destroyForcibly()
		}
	}
}
