package cloth

import cloth.data.CClass
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.semantic.generics.ClosedTypeAnalyzer
import cloth.semantic.generics.ClosedTypeState
import cloth.token.Keywords
import cloth.token.Operators
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GenericInstantiationLimitsTest {

	private fun analyze(source: String) = ClosedTypeAnalyzer(genericTarget()).analyze(genericUnit(source))
	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic = assertFailsWith<ClothDiagnostic> { analyze(source) }.also {
		assertEquals(code, it.diagnosticCode, it.summary)
		assertLocated(it)
		assertTrue(it.sourceLabels.size >= 2)
	}

	private fun chain(count: Int): String = (0 until count).joinToString("\n") { index ->
		val next = if (index == count - 1) 0 else index + 1
		"${Keywords.TYPEDEF}${Operators.LESS}${Keywords.CLASS}${Operators.GREATER} N$index${Operators.LESS}T${Operators.GREATER} (${Keywords.DEFINITION}${Operators.LESS}N$next${Operators.LESS}T${Operators.GREATER}${Operators.QUESTION}${Operators.GREATER} next ${Operators.ELVIS} ${Keywords.NULL})"
	} + "\nfn take(N0<int> value) {}"

	@Test
	fun `64 dependencies and same key reuse at the limit complete without another charge`() {
		val model = analyze(chain(64))
		assertEquals(64, model.instances.size)
		assertTrue(model.instances.all { it.state == ClosedTypeState.COMPLETE })
	}

	@Test
	fun `65th dependency and expanding array arguments stop with one located diagnostic`() {
		for (source in listOf(chain(65), "typedef<class> Expands<T> (def<Expands<T[]>?> next ?: null)\nfn take(Expands<int> value) {}")) {
			val error = failure(source, DiagnosticCode.GENERIC_DEPENDENCY_LIMIT)
			assertEquals("Generic instantiation dependency limit exceeded (64)", error.summary)
			val output = StringBuilder()
			DiagnosticReporter(output).report(error)
			assertContains(output.toString(), "C3112")
			assertContains(output.toString(), "dependency 1")
		}
	}

	private fun breadth(count: Int): String = "typedef<class> Box<T> {}\nfn main() {\n" + (1..count).joinToString("\n") { width ->
		"Box${Operators.LESS}${Keywords.INTEGER}${Operators.LESS}$width${Operators.GREATER}${Operators.GREATER}${Operators.COLON_COLON}type"
	} + "\nBox<int<1>>::type\n}"

	@Test
	fun `4096 distinct instances plus repeated routes succeed while 4097 is controlled`() {
		assertEquals(4096, analyze(breadth(4096)).instances.size)
		val error = failure(breadth(4097), DiagnosticCode.GENERIC_INSTANCE_LIMIT)
		assertEquals("Too many distinct generic instances (4096)", error.summary)
	}

	@Test
	fun `nullable normalization and exact arguments share only identical keys`() {
		val model = analyze("""
			typedef<class> Box<T> {}
			typedef<class> Uses<T> (def<Box<T?>?> first ?: null, def<Box<T>?> second ?: null)
			fn take(Uses<int?> value) {}
		""")
		val owner = model.parameterTypes().single() as CClass
		assertEquals(owner.fields[0].type, owner.fields[1].type)
		assertEquals(2, model.instances.size)
	}

	private fun nesting(field: String): String {
		val argument = "Box${Operators.LESS}".repeat(255) + Keywords.INTEGER + Operators.GREATER.toString().repeat(255)
		return "typedef<class> Box<T> {}\ntypedef<class> Uses<T> (def<$field?> value ?: null)\nfn take(Uses<$argument> value) {}"
	}

	@Test
	fun `substitution permits 256 argument layers and rejects 257 before interning`() {
		assertEquals(257, analyze(nesting("Box<T>")).instances.size)
		val error = failure(nesting("Box<Box<T>>"), DiagnosticCode.GENERIC_SUBSTITUTION_NESTING)
		assertEquals("Generic type argument nesting limit exceeded (256)", error.summary)
	}
}
