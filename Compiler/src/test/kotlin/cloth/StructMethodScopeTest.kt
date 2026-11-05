package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.expression.*
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.FieldWriteStatement
import cloth.parser.statement.ReturnStatement
import cloth.parser.statement.UpdateStatement
import cloth.semantic.DeclarationAnalyzer
import cloth.semantic.SemanticException
import cloth.semantic.symbols.FunctionGroupSymbol
import cloth.semantic.symbols.MethodGroupSymbol
import cloth.semantic.symbols.ParameterSymbol
import cloth.semantic.symbols.ReceiverFieldSymbol
import com.opencastsoftware.yvette.handlers.graphical.GraphicalReportHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class StructMethodScopeTest {

	private fun analyze(source: String) = DeclarationAnalyzer().analyze(structUnit(source))

	@Test
	fun `method groups belong to nominal owners and a method main stays out of file scope`() {
		val model = analyze("""
			typedef<struct> First {
			    mt tag() {}
			    mt tag(int n) {}
			    mt main() {}
			}
			typedef<struct> Second { mt tag() {} }
			fn work() {}
		""")
		val first = model.structs.getValue("First")
		val second = model.structs.getValue("Second")
		assertEquals(listOf("work"), model.functions.map { it.name.lexeme })
		assertNull(model.fileScope.lookup("main"))
		assertNull(model.fileScope.lookup("tag"))
		assertEquals(listOf(0, 1), model.methodGroup(first, "tag")?.overloads?.map { it.parameters.size })
		assertEquals(listOf(0), model.methodGroup(second, "tag")?.overloads?.map { it.parameters.size })
		assertSame(first, model.symbolOf(first.declaration.methods.first()).owner)
		assertSame(second, model.symbolOf(second.declaration.methods.single()).owner)
	}

	@Test
	fun `lexical names win and explicit or implicit receiver members use the nominal owner`() {
		val model = analyze("""
			fn<int> reset() = 1
			typedef<struct> Meter {
			    int value;
			    mt<int> value() = value
			    mt<int> update(int value, opt<int> offset ?: self.value, opt<int> earlier ?: value) {
			        self.value = value
			        return reset() + self.reset()
			    }
			    mt<int> later() {
			        value++
			        def<int> block = { return next() }
			        return block
			    }
			    mt<int> next() = value()
			    mt<int> reset() = self.value
			}
		""")
		val owner = model.structs.getValue("Meter")
		val (getter, update, later, next) = model.methods
		val read = assertIs<NameExpression>(assertIs<ExpressionBody>(getter.declaration.body).expression)
		val field = assertIs<ReceiverFieldSymbol>(model.symbolOf(read))
		assertSame(getter.receiver, field.receiver)
		assertSame(owner.fields.single(), field.field)
		val parameters = update.declaration.parameters
		val defaultField = assertIs<FieldAccessExpression>(parameters[1].defaultValue)
		assertSame(update.receiver, model.symbolOf(assertIs<NameExpression>(defaultField.receiver)))
		assertSame(owner.fields.single(), model.receiverFields.getValue(defaultField).field)
		assertSame(update.parameters[0], model.symbolOf(assertIs<NameExpression>(parameters[2].defaultValue)))
		assertFalse(update.receiver.mutable)

		val (write, returned) = assertIs<BlockBody>(update.declaration.body).statements
		val fieldWrite = assertIs<FieldWriteStatement>(write)
		assertSame(update.receiver, model.fieldRoots.getValue(fieldWrite))
		assertIs<ParameterSymbol>(model.symbolOf(assertIs<NameExpression>(fieldWrite.value)))
		val addition = assertIs<BinaryExpression>(assertIs<ReturnStatement>(returned).value)
		assertIs<FunctionGroupSymbol>(model.candidatesOf(assertIs<CallExpression>(addition.left)))
		val explicitCall = assertIs<MemberCallExpression>(addition.right)
		assertSame(model.methodGroup(owner, "reset"), model.receiverCalls.getValue(explicitCall))

		val (increment, saved) = assertIs<BlockBody>(later.declaration.body).statements
		assertSame(later.receiver, assertIs<ReceiverFieldSymbol>(model.targetOf(assertIs<UpdateStatement>(increment))).receiver)
		val deferred = assertIs<FunctionBlockExpression>(assertIs<BindingDeclaration>(saved).initializer)
		val nestedReturn = assertIs<ReturnStatement>(assertIs<BlockBody>(deferred.declaration.body).statements.single())
		assertSame(next, assertIs<MethodGroupSymbol>(model.candidatesOf(assertIs<CallExpression>(nestedReturn.value))).overloads.single())
		val bareCall = assertIs<CallExpression>(assertIs<ExpressionBody>(next.declaration.body).expression)
		assertSame(getter, assertIs<MethodGroupSymbol>(model.candidatesOf(bareCall)).overloads.single())
	}

	@Test
	fun `first declaration error explains duplicate signatures and receiver misuse`() {
		val invalid = listOf(
			"typedef<struct> S { mt<int> tag(int x) = 1\n mt<bool> tag(final int<32> y) = true }" to DiagnosticCode.DUPLICATE_METHOD,
			"fn work() { self }" to DiagnosticCode.RECEIVER_OUTSIDE_METHOD,
			"typedef<struct> S { mt change(S other) { self = other } }" to DiagnosticCode.IMMUTABLE_BINDING,
			"typedef<struct> S { mt change(S other) { self, other = other, self } }" to DiagnosticCode.IMMUTABLE_BINDING,
			"typedef<struct> S { int field; mt<int> check() = self.missing }" to DiagnosticCode.INVALID_STRUCT_FIELD,
			"typedef<struct> S { mt check() { self.missing() } }" to DiagnosticCode.UNKNOWN_MEMBER,
			"typedef<struct> S { mt check(int other) { other() }\n mt other() {} }" to DiagnosticCode.NOT_CALLABLE,
			"typedef<struct> S { mt check(opt<int> x ?: self.read()) {}\n mt<int> read() = 1 }" to DiagnosticCode.INVALID_PARAMETER_DEFAULT,
			"typedef<struct> S { mt check(opt<int> x ?: read()) {}\n mt<int> read() = 1 }" to DiagnosticCode.INVALID_PARAMETER_DEFAULT,
			"typedef<struct> S { int field; mt check(opt<int> x ?: if (true) { field++; 0 } else { 1 }) {} }" to DiagnosticCode.INVALID_PARAMETER_DEFAULT,
		)
		for ((source, expected) in invalid) {
			val failure = assertFailsWith<SemanticException>(source) { analyze(source) }
			assertEquals(expected, failure.diagnosticCode, source)
		}
	}

	@Test
	fun `CLI stops at the first source diagnostic and a method main is not an entry point`(@TempDir directory: Path) {
		val source = directory.resolve("methods.co")
		val destination = directory.resolve("output/program")
		for ((program, expected) in listOf(
			"typedef<struct> S { mt main() {} }" to DiagnosticCode.MISSING_MAIN,
			"typedef<struct> S { mt main() { missing() } }\nfn main() {}" to DiagnosticCode.UNKNOWN_NAME,
			"typedef<struct> S { mt<int> main() = true }\nfn main() {}" to DiagnosticCode.RETURN_TYPE,
		)) {
			Files.writeString(source, program)
			val output = StringBuilder()
			val handler = GraphicalReportHandler.builder().withColours(false).withUnicode(false).withTerminalLinks(false).withCauseChain(false).buildFor(output)
			assertEquals(1, runCompiler(arrayOf(source.toString(), destination.toString(), "--linker", "missing-linker"), DiagnosticReporter(output, handler)))
			assertEquals(listOf(expected.id), Regex("C[0-9]{4}").findAll(output).map { it.value }.toList())
			assertFalse(Files.exists(destination.parent))
		}
	}
}
