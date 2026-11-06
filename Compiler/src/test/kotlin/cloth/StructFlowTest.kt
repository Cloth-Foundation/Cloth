package cloth

import cloth.data.CNullable
import cloth.data.ProviderResult
import cloth.data.ValueType
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.expression.FieldAccessExpression
import cloth.parser.expression.IfExpression
import cloth.parser.expression.ProviderAccessExpression
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.SemanticException
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class StructFlowTest {

	private fun analyze(source: String) = FunctionAnalyzer(nativeTestLayout).analyze(Parser(Lexer(SourceFile("struct-flow.co", """
		typedef<struct> Point { int x }
		typedef<struct> Record { Point? position, bool flag }
	""".trimIndent() + "\n" + source.trimIndent())).scanTokens()).parse())

	private fun unproven(source: String) = assertFailsWith<SemanticException> { analyze(source) }.also { assertEquals(DiagnosticCode.NULLABLE_PAYLOAD_ACCESS, it.diagnosticCode, source) }

	@Test
	fun `guarded field proof survives sibling writes and preserves declared metadata`() {
		val model = analyze("""
			fn work(final Record r) {
			    if (r.position != null && r.position.x > 0) {
			        r.flag = true
			        r.position.x++
			        def copy = r.position
			        def metadata = r.position::type
			        if (r.position == null) { return }
			    }
			}
		""")
		val body = assertIs<BlockBody>(model.declarations.functions.single().declaration.body)
		val branch = assertIs<IfExpression>(assertIs<ExpressionStatement>(body.statements.single()).expression).branches.single()
		val copy = assertIs<BindingDeclaration>(branch.statements[2])
		val read = assertIs<FieldAccessExpression>(copy.initializer)
		val point = model.declarations.structs.getValue("Point")
		assertSame(point, model.typeOf(copy))
		assertEquals(CNullable(point), model.types.payloadReads[read])
		val metadata = assertIs<ProviderAccessExpression>(assertIs<BindingDeclaration>(branch.statements[3]).initializer)
		assertEquals(CNullable(point), model.types.metadata[metadata])
	}

	@Test
	fun `field and containing struct replacement invalidate a proof`() {
		for (write in listOf("r.position = null", "r = { null, false }")) unproven("""
			fn work(Record r) {
			    if (r.position != null) {
			        $write
			        println(r.position.x)
			    }
			}
		""")
	}

	@Test
	fun `constant indexed field proofs distinguish disjoint writes from unknown indices`() {
		analyze("""
			fn work(Record[2] records) {
			    if (records[0].position != null) {
			        records[1].position = null
			        println(records[0].position.x)
			    }
			}
		""")
		unproven("""
			fn work(Record[2] records, int index) {
			    if (records[0].position != null) {
			        records[index] = { null, false }
			        println(records[0].position.x)
			    }
			}
		""")
	}

	@Test
	fun `captured writes and repeating loops invalidate proofs on final bindings`() {
		unproven("""
			fn work(final Record r) {
			    def<void> clear = { r.position = null }
			    if (r.position != null) {
			        clear
			        println(r.position.x)
			    }
			}
		""")
		unproven("""
			fn work(final Record r, bool repeat) {
			    if (r.position != null) {
			        while (repeat) {
			            println(r.position.x)
			            r.position = null
			        }
			    }
			}
		""")
	}

	@Test
	fun `indexed nullable results do not manufacture binding presence`() {
		unproven("""
			fn work(Point?[1] values) {
			    def<Point?> copy = values[0]
			    println(copy.x)
			}
		""")
	}

	@Test
	fun `scalar storage size retains the nullable field type after a guard`() {
		val model = analyze("""
			typedef<struct> Entry { int? number }
			fn work(Entry e) {
			    if (e.number != null) { e.number::size }
			}
		""")
		val body = assertIs<BlockBody>(model.declarations.functions.single().declaration.body)
		val branch = assertIs<IfExpression>(assertIs<ExpressionStatement>(body.statements.single()).expression).branches.single()
		val access = assertIs<ProviderAccessExpression>(assertIs<ExpressionStatement>(branch.statements.single()).expression)
		assertEquals(CNullable(ValueType.INT), assertIs<ProviderResult.StorageSize>(model.types.providerAccess(access).result).receiverType)
	}
}
