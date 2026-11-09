package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.data.CInterface
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.parser.declaration.*
import cloth.parser.expression.NameExpression
import cloth.parser.expression.ProviderAccessExpression
import cloth.parser.statement.BindingDeclaration
import cloth.semantic.generics.ClosedTypeAnalyzer
import cloth.semantic.generics.GenericAnalyzer
import cloth.semantic.generics.NominalTypeName
import cloth.semantic.typing.FunctionAnalyzer
import cloth.settings.OutputKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class InterfaceNamesTest {
	private val target = genericTarget()

	@Test
	fun `interface owner parameters bind forward parents signatures and both kinds of default`() {
		val unit = genericUnit("""
			typedef<class> T {}
			typedef<interface> Child<T> : Parent<T> {
			    mt<T> read(T value, opt<T> fallback ?: value)
			    mt<typeinfo> info(opt<typeinfo> metadata ?: T::type) = default {
			        def<T[]> buffer = []
			        return T::type
			    }
			}
			typedef<interface> Parent<T> { mt<T> get() }
			typedef<class> Box<T> : Child<T>
			fn take(Child<int> value) {}
		""")
		val model = GenericAnalyzer(target).analyze(unit)
		val child = assertIs<InterfaceDeclaration>(unit.declarations[1])
		val parent = assertIs<InterfaceDeclaration>(unit.declarations[2])
		val box = assertIs<ClassDeclaration>(unit.declarations[3])
		val t = model.parametersOf(child).single()
		assertNotSame(t, model.parametersOf(parent).single())
		assertSame(t, model.nameOf(child.parents.single().typeArguments.single()))
		assertSame(parent, assertIs<CInterface>(assertIs<NominalTypeName>(model.nameOf(child.parents.single())).type).declaration)

		val (required, defaulted) = child.methods
		for (type in required.returnTypes + required.parameters.map { it.type }) assertSame(t, model.nameOf(type))
		val parameterDefault = assertIs<ProviderAccessExpression>(defaulted.parameters.single().defaultValue)
		assertSame(t, model.receiverName(assertIs<NameExpression>(parameterDefault.receiver)))
		val body = assertIs<BlockBody>(defaulted.fallback?.body)
		assertSame(t, model.nameOf(assertNotNull(assertIs<BindingDeclaration>(body.statements.first()).type)))

		val parentUse = box.parents.single().type
		assertSame(model.parametersOf(box).single(), model.nameOf(parentUse.typeArguments.single()))
		val valueUse = assertIs<FunctionDeclaration>(unit.declarations.last()).parameters.single().type
		assertSame(assertIs<NominalTypeName>(model.nameOf(parentUse)).type, assertIs<NominalTypeName>(model.nameOf(valueUse)).type)
	}

	@Test
	fun `invalid interface names and header kinds fail before the compilation boundary`() {
		val cases = listOf(
			"typedef<interface> I<T, T> {}" to DiagnosticCode.INVALID_GENERIC_PARAMETER,
			"typedef<interface> I<T> {}\ntypedef<class> C : I" to DiagnosticCode.RAW_GENERIC_TYPE,
			"typedef<interface> I<T> {}\ntypedef<class> C : I<int, string>" to DiagnosticCode.GENERIC_ARITY,
			"typedef<interface> I {}\ntypedef<class> C : I()" to DiagnosticCode.INVALID_INTERFACE_HEADER,
			"typedef<class> C {}\ntypedef<interface> I : C {}" to DiagnosticCode.INVALID_INTERFACE_HEADER,
			"typedef<class> A {}\ntypedef<class> B {}\ntypedef<class> C : A, B" to DiagnosticCode.INVALID_INTERFACE_HEADER,
			"typedef<class> I {}\ntypedef<interface> I {}" to DiagnosticCode.DUPLICATE_CLASS_TYPE,
		)
		for ((source, code) in cases) {
			val failure = assertFailsWith<ClothDiagnostic>(source) { FunctionAnalyzer(target).analyze(genericUnit(source)) }
			assertEquals(code, failure.diagnosticCode, failure.summary)
			assertLocated(failure)
		}
	}

	@Test
	fun `standalone override prefixes are rejected before the compilation boundary`() {
		val standalone = assertFailsWith<ClothDiagnostic> {
			FunctionAnalyzer(target).analyze(genericUnit("typedef<class> C { override: mt method() {} }"))
		}
		assertEquals(DiagnosticCode.INVALID_INTERFACE_OVERRIDE, standalone.diagnosticCode)
		assertLocated(standalone)

		val closed = ClosedTypeAnalyzer(target).analyze(genericUnit("typedef<interface> I<T> { mt<T> read() }\nfn take(I<int> value) {}"))
		val type = assertIs<CInterface>(closed.parameterTypes().single())
		assertSame(type, closed.interfaces.contracts.getValue(type).methods.values.single().owner)
	}

	@Test
	fun `compiler requests emit required calls and keep source diagnostics before emission`(@TempDir root: Path) {
		val source = Files.writeString(root.resolve("Main.co"), """
			typedef<interface> I { mt<int> read() }
			typedef<class> C : I { override: mt<int> read() = 1 }
			fn<int> main() { def<I> view = C::new; def answer = view.read(); view::delete; return answer - 1 }
		""".trimIndent())
		val output = root.resolve("output/artifact")
		val objectFile = ClothCompiler.compile(CompilationRequest(source, root.resolve("supported.o"), outputKind = OutputKind.OBJECT)).artifact
		assertTrue(Files.size(objectFile) > 0)
		Files.writeString(source, "typedef<interface> I { mt<int> read() }\ntypedef<class> C : I\nfn main() {}")
		val failure = assertFailsWith<ClothDiagnostic> {
			ClothCompiler.compile(CompilationRequest(source, output, outputKind = OutputKind.OBJECT))
		}
		assertEquals(DiagnosticCode.MISSING_INTERFACE_IMPLEMENTATION, failure.diagnosticCode)
		assertEquals(2, failure.sourceLabels.size)
		assertFalse(Files.exists(output.parent))
		Files.writeString(source, "typedef<interface> I { mt<int> read() }\nfn<int> run(I view) = view.missing()")
		val badCall = assertFailsWith<ClothDiagnostic> {
			ClothCompiler.compile(CompilationRequest(source, output, outputKind = OutputKind.OBJECT))
		}
		assertEquals(DiagnosticCode.UNKNOWN_MEMBER, badCall.diagnosticCode)
		assertFalse(Files.exists(output.parent))
	}

	@Test
	fun `incompatible interface array and cast uses fail before output`(@TempDir root: Path) {
		val source = root.resolve("Main.co")
		val output = root.resolve("output/artifact")
		val cases = listOf(
			"typedef<interface> I {}\ntypedef<class> C : I\nfn take(C[] values) { def<I[]> wrong = values }\nfn main() {}" to DiagnosticCode.TYPE_MISMATCH,
			"typedef<interface> I {}\ntypedef<interface> Other {}\nfn cast(I value) { value -> Other }\nfn main() {}" to DiagnosticCode.INVALID_CAST,
		)
		for ((text, code) in cases) {
			Files.writeString(source, text)
			for (kind in OutputKind.entries) {
				val failure = assertFailsWith<ClothDiagnostic> { ClothCompiler.compile(CompilationRequest(source, output, outputKind = kind)) }
				assertEquals(code, failure.diagnosticCode, failure.summary)
				assertEquals(source, Path.of(failure.sourceLabels.first().span.source!!.name))
				assertFalse(Files.exists(output.parent))
			}
		}
	}
}
