package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.SemanticException
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EqualityOperandContextTest {

	private val values = """
		typedef<enum> Kind { Identifier, Number; operator<string> ==() = self::name.toLowerCase() }
		typedef<struct> Word { string text; operator<string> ==() = text }
		typedef<class> Box (pub def<string> text) { operator<string> ==() = text }
		typedef<enum> Explicit {
		    Value;
		    operator<string> ==() = "equal"
		    operator<string> !=() = "different"
		}
		typedef<enum> Fallback {
		    Value;
		    operator<string> ==() = "fallback"
		    operator<int> !=() = 3
		}
		typedef<enum> Width (pub def<int> value = 129) {
		    Value;
		    operator<int<auto>> ==() = value -> int<auto>
		}
		typedef<enum> Full {
		    Value;
		    operator ==(Full other) = false
		    operator<Full> ==() -> string { print("unexpected"); return "x" }
		}
		fn<bool> present(Kind? kind) {
		    if (kind != null) return kind == "identifier"
		    return false
		}
		fn<int> main() {
		    if (!(Kind.Identifier == "identifier") || !("identifier" == Kind.Identifier)) return 1
		    if (Kind.Identifier != "identifier" || "identifier" != Kind.Identifier || Kind.Identifier == "number") return 2
		    if (Kind.Identifier != Kind.Identifier || Kind.Identifier == Kind.Number || Full.Value == Full.Value) return 3
		    def<Word> word = { "word" }
		    def box = Box::new("box")
		    if (word != "word" || "box" != box) return 4
		    if (Explicit.Value == "different" || Explicit.Value != "different" || !(Explicit.Value != "equal")) return 5
		    if (Fallback.Value != "fallback" || Fallback.Value != 3) return 6
		    def<int<8>> narrow = 129 -> int<8>
		    def<int<129>> wide = 129
		    if (Width.Value != narrow || wide != Width.Value) return 7
		    def<Kind?> absent = null
		    if (absent != null || absent == Kind.Identifier || !present(Kind.Identifier)) return 8
		    box::delete
		    return 0
		}
	"""

	@Test
	fun `enum string contexts and inequality fallback preserve ordinary comparisons`(@TempDir directory: Path) {
		for (abi in structRunners()) assertEquals(processCompletion().replace("\r\n", "\n"), runStructProgram(abi, values, directory.resolve("equality_${abi.layout.sizeBits}")))
	}

	@Test
	fun `both contexts run once in source order with buffered snapshots`(@TempDir directory: Path) {
		val source = """
			typedef<struct> Left { operator<Right> ==() -> string { print("L"); return "same" } }
			typedef<struct> Right { operator<Left> ==() -> string { print("R"); return "same" } }
			fn<Left> left() { print("l"); return {} }
			fn<Right> right() { print("r"); return {} }
			typedef<class> Text (pub def<string> value) { operator<string> ==() = value }
			fn<string> change(Text text) { text.value = "changed"; return "original" }
			fn main() {
			    if (!(left() == right())) { println("incorrect") }
			    def text = Text::new("original")
			    if (!(text == change(text))) { println("lost snapshot") }
			    text::delete
			}
		"""
		for (abi in structRunners()) assertEquals("lLrR" + processCompletion().replace("\r\n", "\n"), runStructProgram(abi, source, directory.resolve("order_${abi.layout.sizeBits}")))
	}

	@Test
	fun `context calls invalidate aliased presence before later operands and after comparison`() {
		for (operator in listOf("==", "!=")) for (after in listOf(false, true)) {
			val source = """
				typedef<class> Item (pub def<int> value)
				typedef<class> Holder (pub def<Item?> item)
				typedef<class> Clear (def<Holder> holder) {
				    operator<int> ==() -> int { holder.item = null; return 0 }
				}
				fn<int> test(Clear clear, Holder holder) {
				    if (holder.item != null) {
				        BODY
				    }
				    return 0
				}
			""".replace("BODY", if (after) "clear $operator 0\nreturn holder.item.value" else "if (clear $operator holder.item.value) return 1")
			val failure = assertFailsWith<SemanticException> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(source)) }
			assertEquals(DiagnosticCode.NULLABLE_PAYLOAD_ACCESS, failure.diagnosticCode)
		}
	}

	@Test
	fun `contexts cannot chain bypass nullable presence or replace identity`() {
		val sources = listOf(
			"typedef<enum> E { A; operator<string> ==() = self::name }\nfn<bool> test(E? value) = value == \"A\"",
			"typedef<enum> E { A; operator<string> ===() = self::name }",
			"typedef<enum> A { X; operator<string> ==() = B.X }\ntypedef<enum> B { X; operator<string> ==() = \"x\" }\nfn<bool> test() = A.X == \"x\"",
			"typedef<enum> E { A; operator<string> ==() = 1 }\nfn<bool> test() = E.A == \"A\"",
		)
		for (source in sources) assertFailsWith<SemanticException>(source) { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(source)) }
	}

	@Test
	fun `comparison contexts retain the readonly default receiver restriction`() {
		val source = "typedef<class> E { operator<int> ==() = 0 }\nfn take(E value, opt<bool> same ?: value == 0) {}"
		assertFailsWith<SemanticException> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(source)) }
	}

	@Test
	fun `equality context fixture emits verified executable IR and objects across all profiles`(@TempDir directory: Path) {
		val unit = structUnit(values)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(unit), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("contexts_$index.o"))) > 0)
		}
	}
}
