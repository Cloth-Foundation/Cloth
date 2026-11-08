package cloth

import cloth.builtin.BuiltinStorageContract
import cloth.builtin.attachments.ArrayMutation
import cloth.data.CArray
import cloth.data.CUSize
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.statement.BindingDeclaration
import cloth.semantic.MethodReceiver
import cloth.semantic.typing.FunctionAnalyzer
import cloth.target.TargetLayout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GrowableArrayTypingTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("growable-arrays.co", source.trimIndent())).scanTokens()).parse()
	private fun analyze(source: String) = FunctionAnalyzer(nativeTestLayout).analyze(unit(source))
	private fun layout(bits: Int) = TargetLayout("stage1-$bits", bits, cLongBits = 32)
	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { analyze(source) }
		assertEquals(code, error.diagnosticCode, error.summary)
		assertLocated(error)
	}

	@Test
	fun `explicit growable and inferred fixed array types remain distinct from current counts`() {
		val model = analyze("""
			fn main() {
			    def fixed = [1, 2, 3]
			    def<int[]> growing = fixed
			    def copied = growing
			    growing = [4]
			    def later = growing
			}
		""")
		val declarations = (model.declarations.functions.single().declaration.body as BlockBody).statements.filterIsInstance<BindingDeclaration>()
		assertEquals(listOf("int[3]", "int[]", "int[]", "int[]"), declarations.map { model.types.typeOf(it).spelling })
		assertEquals(3.toBigInteger(), model.types.arrayShapes[declarations[2].initializer]?.first())
		assertEquals(1.toBigInteger(), model.types.arrayShapes[declarations[3].initializer]?.first())
	}

	@Test
	fun `all mutators carry stored receivers explicit arguments and independent element result types`() {
		val model = analyze("""
			fn main() {
			    def<string[]> values := []
			    values.reserve(8)
			    values.append("one")
			    values.insert(0, "zero")
			    def removed = values.removeAt(1)
			    values.clear()
			}
		""")
		assertEquals(ArrayMutation.entries.toSet(), model.types.arrayMutations.values.map { it.operation }.toSet())
		for ((call, plan) in model.types.arrayMutations) {
			assertIs<MethodReceiver.Stored>(plan.receiver)
			assertEquals(call.arguments.size, plan.arguments.size)
			assertEquals(BuiltinStorageContract.MUTATING_RECEIVER, model.types.memberCalls.getValue(call).overload.storage)
		}
		assertEquals("string", model.types.arrayMutations.values.single { it.operation == ArrayMutation.REMOVE_AT }.result.spelling)
	}

	@Test
	fun `final parameters and temporary growable receivers support mutation`() {
		val model = analyze("""
			fn<int[]> make() = [1, 2]
			fn<int> changed(final int[] values) { values.append(3); return make().removeAt(0) }
			fn main() { changed(make()) }
		""")
		assertTrue(model.types.arrayMutations.values.any { it.receiver is MethodReceiver.Temporary })
		assertTrue(model.types.arrayMutations.values.any { it.receiver is MethodReceiver.Stored })
	}

	@Test
	fun `class fields and nested struct fields retain their destination owners`() {
		val model = analyze("""
			typedef<struct> Holder { int[] items }
			typedef<class> Box {
			    def<int[]> items = []
			    pub mt append(int value) { self.items.append(value) }
			}
			fn main() {
			    def<Holder> holder := { [1] }
			    holder.items.append(2)
			    def box = Box::new
			    box.append(3)
			    box::delete
			}
		""")
		assertTrue(model.types.arrayMutations.values.any { it.receiver is MethodReceiver.StoredInInstance })
		assertTrue(model.types.arrayMutations.values.any { it.receiver is MethodReceiver.Stored && it.receiver.place.steps.isNotEmpty() })
	}

	@Test
	fun `fixed receivers and embedded row length changes are rejected`() {
		failure("fn main() { def fixed = [1, 2]; fixed.append(3) }", DiagnosticCode.INVALID_ARRAY_MUTATION)
		failure("fn<int[2]> make() = [1, 2]\nfn main() { make().clear() }", DiagnosticCode.INVALID_ARRAY_MUTATION)
		failure("fn main() { def<int[][]> rows = [[1, 2]]; rows[0].append(3) }", DiagnosticCode.INVALID_ARRAY_MUTATION)
		analyze("fn main() { def<int[][]> rows = [[1, 2]]; def<int[]> row = rows[0]; row.append(3); rows[0].reserve(8) }")
	}

	@Test
	fun `array defaults cannot mutate a read only parameter`() {
		failure("fn work(int[] items, opt<int> first ?: items.removeAt(0)) {}\nfn main() {}", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
	}

	@Test
	fun `array receiver presence must survive explicit argument effects`() {
		failure("""
			fn main() {
			    def<int[]?> items = [1]
			    def<int> argument = { items = null; return 2 }
			    if (items != null) items.append(argument)
			}
		""", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		failure("fn main() { def<int[]?> items = null; items.clear() }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `row witnesses survive clearing and nullable rows establish the first width`() {
		failure("fn main() { def<int[][]> rows = [[1, 2]]; rows.clear(); rows.append([3]) }", DiagnosticCode.INVALID_ARRAY)
		failure("fn main() { def<int[]?[]> rows = [null]; rows.append([1, 2]); rows.clear(); rows.append([3]) }", DiagnosticCode.INVALID_ARRAY)
		failure("fn main() { def<int[]?[]> rows = [null]; rows[0] = [1, 2]; rows.clear(); rows.append([3]) }", DiagnosticCode.INVALID_ARRAY)
		analyze("fn main() { def<int[][3]> rows = []; rows.append([1, 2, 3]); rows.clear(); rows.append([4, 5, 6]) }")
	}

	@Test
	fun `mutation invalidates indexed nullable proofs while reserve preserves them`() {
		val source = """
			typedef<struct> Row { string? text }
			fn main() {
			    def<Row[]> rows = [{ "hello" }]
			    if (rows[0].text != null) {
			        rows.OPERATION
			        println(rows[0].text::size)
			    }
			}
		"""
		failure(source.replace("OPERATION", "clear()"), DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		analyze(source.replace("OPERATION", "reserve(8)"))
	}

	@Test
	fun `array field mutation invalidates indexed proofs through a possible class alias`() {
		failure("""
			typedef<struct> Row { string? text }
			typedef<class> Box (pub def<Row[]> rows)
			fn work(Box first, Box second) {
			    if (first.rows[0].text != null) {
			        second.rows.clear()
			        println(first.rows[0].text::size)
			    }
			}
			fn main() {}
		""", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `array field owner must survive argument evaluation`() {
		failure("""
			typedef<class> Box (pub def<int[]> values)
			fn main() {
			    def box = Box::new([1])
			    box.values.append(if (true) { box::delete; 2 } else { box::delete; 3 })
			}
		""", DiagnosticCode.DELETED_CLASS_ACCESS)
	}

	@Test
	fun `metadata keeps the declared growable type after mutation`() {
		val model = analyze("fn main() { def<int[]> values = [1, 2]; def info = values::type; values.clear(); def later = values::type }")
		val represented = model.types.metadata.values.filterIsInstance<CArray>()
		assertTrue(represented.isNotEmpty())
		assertTrue(represented.all { it.size == null })
	}

	@Test
	fun `known current counts constrain fixed receptions arguments and returns without changing type`() {
		failure("fn main() { def<int[]> values = [1]; def<int[2]> fixed = values }", DiagnosticCode.INVALID_ARRAY)
		failure("fn take(int[2] values) {}\nfn main() { def<int[]> values = [1]; take(values) }", DiagnosticCode.INVALID_ARRAY)
		failure("fn<int[2]> make() { def<int[]> values = [1]; return values }\nfn main() {}", DiagnosticCode.INVALID_ARRAY)
	}

	@Test
	fun `broadcast and multiple results preserve explicit growable annotations`() {
		val model = analyze("""
			fn<int[], int[]> pair() = [1], [2, 3]
			fn main() {
			    def<int[]> first, second = [1, 2]
			    def<int[], int[]> left, right = pair()
			    first.append(3)
			    right.clear()
			}
		""")
		assertTrue(model.types.receptions.values.flatMap { it.slots }.all { it.type.spelling == "int[]" })
	}

	@Test
	fun `unsized struct fields have native retained shape storage`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val output = runStructProgram(abi, "typedef<struct> Holder { int[] items }\nfn main() { def<Holder> holder = { [1] }; holder.items = [2, 3]; println(holder.items) }", directory.resolve("field_${abi.layout.sizeBits}"))
			assertEquals("[2, 3]\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `array methods retain exact element and usize argument identities`() {
		assertFailsWith<ClothDiagnostic> { analyze("fn main() { def<int[]> values = []; def<int<64>> wide = 1; values.append(wide) }") }
		assertFailsWith<ClothDiagnostic> { analyze("fn main() { def<int[]> values = []; def index = 0; values.removeAt(index) }") }
		assertFailsWith<ClothDiagnostic> { analyze("fn main() { def<int[]> values = []; values.append(value=1) }") }
		assertFailsWith<ClothDiagnostic> { analyze("fn main() { def<int[]> values = []; values.insert(0) }") }
		analyze("fn main() { def<int<64>[]> values = []; values.append(1); def<usize> index = 0; values.removeAt(index) }")
	}

	@Test
	fun `attachment specialization does not cache the host count width in a reused AST`() {
		val ast = unit("fn main() { def<int[]> values = []; values.reserve(8) }")
		for (bits in listOf(32, 64, 32)) {
			val model = FunctionAnalyzer(layout(bits)).analyze(ast)
			assertEquals(CUSize(bits), model.types.arrayMutations.values.single().arguments.single().parameterType)
		}
		val large = unit("fn main() { def<int[]> values = []; values.reserve(4294967296) }")
		FunctionAnalyzer(layout(64)).analyze(large)
		val error = assertFailsWith<ClothDiagnostic> { FunctionAnalyzer(layout(32)).analyze(large) }
		assertEquals(DiagnosticCode.INTEGER_RANGE, error.diagnosticCode)
	}

	@Test
	fun `candidate trials commit only the selected array receiver facts`() {
		val model = analyze("""
			fn consume(int value) {}
			fn consume(string value) {}
			fn main() { def<string[]> words = ["one"]; consume(words.removeAt(0)) }
		""")
		assertEquals(1, model.types.arrayMutations.size)
		assertEquals("string", model.types.arrayMutations.values.single().result.spelling)
	}

	@Test
	fun `branches loops and deferred captures do not retain an optimistic current length`() {
		for (effect in listOf("if (flag) values.clear()", "while (flag) { values.clear(); break }", "def<void> change = { values.clear() }\nchange")) {
			val model = analyze("fn work(bool flag) { def<int[]> values := [1, 2]\n$effect\ndef after = values\n}\nfn main() {}")
			val function = model.declarations.functions.first().declaration
			val after = (function.body as BlockBody).statements.filterIsInstance<BindingDeclaration>().last()
			assertNull(model.types.arrayShapes[after.initializer]?.first(), effect)
			assertEquals("int[]", model.types.typeOf(after).spelling)
		}
	}

	@Test
	fun `nullable removal does not invent a present result`() {
		failure("fn main() { def<int?[]> values = [null]; def<int?> removed = values.removeAt(0); println(removed + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `array method names do not change class method result presence`() {
		analyze("""
			typedef<class> Source {
			    pub mt<int> removeAt(usize index) = 1
			    pub mt<int> append(int value) = value
			    pub mt<Source> identity() = self
			    pub mt<Source> split(char delimiter) = self
			}
			fn<Source> make() = Source::new
			fn main() {
			    def direct = Source::new
			    def alias = direct
			    def returned = make()
			    def<int?> first = direct.removeAt(0)
			    def<int?> second = alias.append(2)
			    def<int?> third = returned.removeAt(0)
			    def<int?> fourth = returned.identity().removeAt(0)
			    println(first + second + third + fourth)
			    def words = "one,two".split(',')
			    def<string?> word = words.removeAt(0)
			    println(word::size)
			    def<int[]> numbers = [1]
			    def<int?> number = numbers.removeAt(0)
			    println(number + 1)
			    direct::delete
			    returned::delete
			}
		""")
	}

	@Test
	fun `native emission reports the stage boundary before producing output`(@TempDir directory: Path) {
		val output = directory.resolve("not_emitted")
		val error = assertFailsWith<ClothDiagnostic> { LLVMEmitter().compile(unit("fn main() { def<int[]> values = []; values.append(1); values.clear() }"), output) }
		assertEquals(DiagnosticCode.UNSUPPORTED_ARRAY_MUTATION, error.diagnosticCode)
		assertLocated(error)
		assertFalse(Files.exists(output))
		assertFalse(Files.exists(directory.resolve("not_emitted.exe")))
	}
}
