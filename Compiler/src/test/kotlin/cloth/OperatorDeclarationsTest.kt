package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.parser.declaration.ClassDeclaration
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.MethodDeclaration
import cloth.parser.expression.CastExpression
import cloth.semantic.FunctionAnalyzer
import cloth.semantic.SemanticException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class OperatorDeclarationsTest {

	private val program = """
		typedef<class> Counter (def<int> points) {
		    operator +(int extra) = self.points + extra
		    operator +(int extra, self) -> int { return extra - self.points }
		    operator +(int<64> extra) = 64
		    operator -() = 0 - self.points
		    operator <(Counter other) = self.points < other.points
		}
		typedef<class> Child (int points) : Counter(points)
		typedef<struct> Tag {
		    string text;
		    operator +(Tag left, self) = left.text + self.text
		}
		fn<int> amount() { print(1); return 10 }
		fn<Counter> receiver(Counter value) { print(2); return value }
		fn<int> main() {
		    def child = Child::new(3)
		    println(amount() + receiver(child))
		    println(receiver(child) + amount())
		    if (child + 10 != 13 || child + (10 -> int<64>) != 64) return 1
		    if (-child != -3 || child < child) return 2
		    def<Tag> left = { "before" }
		    def<Tag> right = { left.text = "after"; return { "-right" } }
		    println(left + right)
		    child::delete
		    return 0
		}
	"""

	@Test
	fun `parser records receiver side providers and nested context width syntax`() {
		val unit = structUnit("""
			typedef<class> Player (def<int> points) {
			    operator +(
			        int bonus,
			        self
			    ) -> int { return bonus + self.points }
			    operator<int<auto>> +() = self.points -> int<auto>
			    operator ::str() = "Player"
			    operator is(Object other) = true
			    operator ===(Object other) = false
			}
		""")
		val members = assertIs<ClassDeclaration>(unit.declarations.single()).members.filterIsInstance<MethodDeclaration>()
		assertTrue(checkNotNull(members[0].operator).rightReceiver)
		assertEquals("int", members[0].returnTypes.single().name.lexeme)
		assertEquals("auto", members[1].operator?.selector?.width?.lexeme)
		assertEquals("auto", assertIs<CastExpression>(assertIs<ExpressionBody>(members[1].body).expression).target.width?.lexeme)
		assertTrue(checkNotNull(members[2].operator).provider)
		assertEquals(listOf("is", "==="), members.drop(3).map { it.name.lexeme })
	}

	@Test
	fun `overloads execute in source order with inherited lookup and buffered snapshots`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val output = runStructProgram(abi, program, directory.resolve("operators_${abi.layout.sizeBits}"))
			assertEquals("127\n2113\nbefore-right\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `operator fixture verifies and emits objects on all supported profiles`(@TempDir directory: Path) {
		val unit = structUnit(program)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(unit), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("operator_$index.o"))) > 0)
		}
	}

	@Test
	fun `missing and ambiguous overloads are compile errors`() {
		val missing = """
			typedef<class> A { operator +(int other) = other }
			fn<int> add(A a) = a + "wrong"
		"""
		val ambiguous = """
			typedef<class> A { operator +(B other) = 1 }
			typedef<class> B { operator +(A other, self) = 2 }
			fn<int> add(A a, B b) = a + b
		"""
		for ((source, message) in listOf(missing to "No matching overload", ambiguous to "is ambiguous")) {
			val error = assertFailsWith<SemanticException> { FunctionAnalyzer().analyze(structUnit(source)) }
			assertEquals(DiagnosticCode.OPERATOR_MATCH, error.diagnosticCode)
			assertContains(checkNotNull(error.message), message)
		}
	}

	@Test
	fun `invalid declarations fail before emission`() {
		for (member in listOf(
			"operator *(int left, int right) = 0",
			"operator <(int other) -> int = 0",
			"operator +(int other) { return other }",
			"operator<unsigned int<auto>> +() = 1",
			"operator +(int other) = self + other",
		)) {
			val error = assertFailsWith<SemanticException> { FunctionAnalyzer().analyze(structUnit("typedef<class> A { $member }")) }
			assertEquals(DiagnosticCode.INVALID_OPERATOR_DECLARATION, error.diagnosticCode)
		}
		val duplicate = """
			typedef<class> A {
			    operator +(int other) = other
			    operator +(int<32> other) = other
			}
		"""
		val error = assertFailsWith<SemanticException> { FunctionAnalyzer().analyze(structUnit(duplicate)) }
		assertEquals(DiagnosticCode.DUPLICATE_METHOD, error.diagnosticCode)
	}

	@Test
	fun `operator calls retain receiver and nullable result checks`() {
		val stale = """
			typedef<class> A { operator +(int other, self) = other }
			fn main() {
			    def a = A::new
			    a::delete
			    println(1 + a)
			}
		"""
		val absent = """
			typedef<class> A { operator +(int other) -> int? = null }
			fn<int> read(A a) {
			    def<int?> value = a + 1
			    return value + 1
			}
		"""
		val readOnly = """
			typedef<class> A { operator +(int other) = other }
			fn<int> read(A a, opt<int> value ?: a + 1) = value
		"""
		for ((source, code) in listOf(
			stale to DiagnosticCode.DELETED_CLASS_ACCESS,
			absent to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS,
			readOnly to DiagnosticCode.INVALID_PARAMETER_DEFAULT,
		)) {
			val error = assertFailsWith<SemanticException> { FunctionAnalyzer().analyze(structUnit(source)) }
			assertEquals(code, error.diagnosticCode)
		}
	}
}
