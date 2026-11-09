package cloth

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.data.CInterface
import cloth.data.ValueType
import cloth.diagnostics.DiagnosticCode
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.ExpressionBody
import cloth.parser.expression.CallExpression
import cloth.parser.expression.MemberCallExpression
import cloth.parser.statement.ReturnStatement
import cloth.semantic.ObjectReceiver
import cloth.semantic.interfaces.InterfaceImplementation
import cloth.semantic.typing.FunctionAnalyzer
import cloth.semantic.typing.ResolvedCall
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class InterfaceCallResolutionTest {
	private val declarations = """
		typedef<interface> Parent {
		    mt<int, string> collect(opt<int> first ?: 4, opt<string> second ?: "parent", int key)
		}
		typedef<interface> Child : Parent {
		    mt<int, string> collect(opt<int> offset ?: 8, opt<string> label ?: "child", int id) = default (offset + id, label)
		    mt<int> fallbackOnly() = default (1)
		}
		typedef<interface> Inherited : Parent {}
		typedef<class> Impl : Child, Inherited {
		    override: mt<int, string> collect(int x, string y, int z) { return x + z, y }
		}
	""".trimIndent()

	@Test
	fun `parent and child views keep independent labels defaults and ordered results`() {
		val model = analyzeInterfaces("""
			$declarations
			fn<int, string> fromChild(Child value) = value.collect(id=7, label="given")
			fn<int, string> fromParent(Parent value) = value.collect(key=9, first=3)
			fn<int, string> fromInherited(Inherited value) = value.collect(key=9, first=3)
		""")
		val declarations = model.declarations
		for ((name, omitted, placement) in listOf(
			Triple("fromChild", "offset", listOf(2, 1)),
			Triple("fromParent", "second", listOf(2, 0)),
			Triple("fromInherited", "second", listOf(2, 0)),
		)) {
			val function = declarations.functions.single { it.name.lexeme == name }
			val call = assertIs<MemberCallExpression>(assertIs<ExpressionBody>(function.declaration.body).expression)
			val plan = model.interfaceMemberCalls.getValue(call)
			assertSame(plan.owner, model.typeOf(function).parameters.single())
			assertEquals(listOf(ValueType.INT, ValueType.STRING), plan.type.results.values)
			assertEquals(placement, plan.binding.parameterForArgument)
			val parameter = plan.contract.parameters[plan.binding.omittedParameters.single()]
			assertEquals(omitted, parameter.name.lexeme)
			assertNotNull(parameter.declaration.defaultValue)
			assertSame(call.receiver, assertIs<ObjectReceiver.Explicit>(plan.receiver).expression)
			assertEquals(if (name == "fromChild") "Child" else "Parent", plan.contract.owner.spelling)
			val implementations = declarations.interfaces.classes.values.single().implementations
			val target = assertIs<InterfaceImplementation.ClassMethod>(implementations.getValue(plan.owner).getValue(plan.contract.requirement))
			assertNull(target.declaration.visibility)
			assertEquals(listOf("x", "y", "z"), target.declaration.parameters.map { it.name.lexeme })
		}
	}

	@Test
	fun `interface calls use their own labels and preserve direct class visibility`() {
		val cases = listOf(
			"fn bad(Child value) { value.collect(z=1) }" to DiagnosticCode.INVALID_ARGUMENT_BINDING,
			"fn bad(Parent value) { value.collect(id=1) }" to DiagnosticCode.INVALID_ARGUMENT_BINDING,
			"fn bad(Impl value) { value.collect(1, \"arg\", 2) }" to DiagnosticCode.PRIVATE_CLASS_MEMBER,
			"fn bad(Impl value) { value.fallbackOnly() }" to DiagnosticCode.UNKNOWN_MEMBER,
		)
		for ((source, code) in cases) interfaceFailure("$declarations\n$source", code)
	}

	@Test
	fun `overloads prefer exact types and reject competing interface adaptations`() {
		val overloads = """
			typedef<interface> Left {}
			typedef<interface> Right {}
			typedef<class> Both : Left, Right
			typedef<interface> Choose {
			    mt<int> pick(Left value)
			    mt<int> pick(Right value)
			    mt<int> exact(Left value)
			    mt<int> exact(Both value)
			}
		""".trimIndent()
		interfaceFailure("$overloads\nfn<int> bad(Choose view, Both item) = view.pick(item)", DiagnosticCode.AMBIGUOUS_CALL)
		val model = analyzeInterfaces("$overloads\nfn<int> run(Choose view, Both item) = view.exact(item)")
		val selected = model.interfaceMemberCalls.values.single()
		assertEquals("Both", selected.type.parameters.single().spelling)
	}

	@Test
	fun `generic defaults check interface self and keep separate closed body facts`() {
		val model = analyzeInterfaces("""
			typedef<interface> Read<T> {
			    mt<T> read(opt<typeinfo> marker ?: T::type)
			    mt<T> again() = default (read())
			    mt<T> explicit() = default { return self.read() }
			}
			fn take(Read<int> numbers, Read<string> text) {}
		""")
		val types = model.typeOf(model.declarations.functions.single()).parameters
		for ((type, result) in types.zip(listOf(ValueType.INT, ValueType.STRING))) {
			val owner = assertIs<CInterface>(type)
			val methods = model.declarations.interfaceMethods.values.filter { it.owner === owner }.associateBy { it.name.lexeme }
			val read = methods.getValue("read")
			val fallback = checkNotNull(read.parameters.single().declaration.defaultValue)
			assertEquals(result, model.types.representedResultsOf(fallback).single())
			assertNull(read.declaration.fallback)
			val implicit = assertIs<ExpressionBody>(methods.getValue("again").declaration.fallback?.body)
			val plan = assertIs<ResolvedCall.Interface>(model.selectedCall(assertIs<CallExpression>(implicit.expression)))
			assertSame(owner, plan.owner)
			assertSame(read, plan.contract)
			assertSame(owner, assertIs<ObjectReceiver.Self>(plan.receiver).symbol.owner)
			assertEquals(listOf(0), plan.binding.omittedParameters)
			val block = assertIs<BlockBody>(methods.getValue("explicit").declaration.fallback?.body)
			val call = assertIs<MemberCallExpression>(assertIs<ReturnStatement>(block.statements.single()).value)
			val explicit = model.interfaceMemberCalls.getValue(call)
			assertEquals(result, explicit.type.results.values.single())
			assertSame(owner, explicit.owner)
		}
	}

	@Test
	fun `unused symbolic interface bodies and argument defaults are checked`() {
		interfaceFailure("typedef<interface> I<T> { mt<T> read() = default (1) }", DiagnosticCode.RETURN_TYPE)
		interfaceFailure("typedef<interface> I<T> { mt<T> read(opt<T> value ?: 1) }", DiagnosticCode.TYPE_MISMATCH)
		interfaceFailure("typedef<interface> I { mt<int> read() = default (self.field) }", DiagnosticCode.UNKNOWN_MEMBER)
		interfaceFailure("""
			typedef<interface> I {
			    mt<int> read(opt<int> value ?: self.other())
			    mt<int> other()
			}
		""", DiagnosticCode.INVALID_PARAMETER_DEFAULT)
	}

	@Test
	fun `interface default expressions bind helpers in the defining file`(@TempDir root: Path) {
		val library = Files.writeString(root.resolve("Library.co"), """
			fn<int> seed() = 4
			pub typedef<interface> Read<T> {
			    mt<T> read(opt<int> marker ?: seed())
			    mt<T> again() = default (read())
			}
		""".trimIndent())
		val main = Files.writeString(root.resolve("Main.co"), """
			using Library : Read
			fn<int> seed() = 9
			fn<int> client(Read<int> value) = value.again()
		""".trimIndent())
		val graph = SourceGraphLoader.load(CompilationRequest(main, root.resolve("absent/out.o"), listOf(SourceModule("M", root)), outputKind = OutputKind.OBJECT))
		val model = FunctionAnalyzer(genericTarget()).analyze(graph)
		val read = model.declarations.interfaceMethods.values.single { it.name.lexeme == "read" }
		val default = assertIs<CallExpression>(read.parameters.single().declaration.defaultValue)
		assertEquals(library, Path.of(model.selectedFunction(default).name.span.source!!.name))
		val call = model.interfaceMemberCalls.values.single()
		assertEquals("Read<int>", call.owner.spelling)
		assertEquals("again", call.contract.name.lexeme)
	}
}
