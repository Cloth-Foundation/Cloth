package cloth

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.DiagnosticReporter
import cloth.parser.declaration.ClassDeclaration
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.declaration.InterfaceDeclaration
import cloth.parser.expression.NameExpression
import cloth.parser.expression.ProviderAccessExpression
import cloth.semantic.generics.ClosedTypeAnalyzer
import cloth.semantic.generics.GenericAnalyzer
import cloth.semantic.generics.NominalTypeName
import cloth.semantic.symbols.FunctionGroupSymbol
import cloth.semantic.typing.FunctionAnalyzer
import cloth.semantic.typing.FunctionSemanticModel
import cloth.settings.OutputKind
import cloth.source.SourceGraph
import cloth.source.SourceGraphLoader
import cloth.target.TargetLayout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ImportSemanticsTest {

	private val target = TargetLayout("synthetic-64", pointerBits = 64, cLongBits = 64)
	private fun graph(root: Path, files: Map<String, String>, entry: String = "Main.co"): SourceGraph {
		for ((name, text) in files) {
			val path = root.resolve(name)
			Files.createDirectories(path.parent)
			Files.writeString(path, text.trimIndent())
		}
		return SourceGraphLoader.load(CompilationRequest(root.resolve(entry), root.resolve("not-created/out.o"), listOf(SourceModule("M", root)), outputKind = OutputKind.OBJECT))
	}

	private fun check(root: Path, files: Map<String, String>, entry: String = "Main.co") = FunctionAnalyzer(target).analyze(graph(root, files, entry))
	private fun fileOf(name: String) = Path.of(name).fileName.toString()
	private fun function(model: FunctionSemanticModel, file: String, name: String) = model.declarations.functions.single { it.name.lexeme == name && fileOf(it.name.span.source!!.name) == file }
	private fun calls(model: FunctionSemanticModel, file: String, name: String) = model.declarations.calls.keys.filter { it.name.lexeme == name && fileOf(it.span.source!!.name) == file }
	private fun fail(code: DiagnosticCode, root: Path, files: Map<String, String>): ClothDiagnostic = assertFailsWith<ClothDiagnostic> { check(root, files) }.also { assertEquals(code, it.diagnosticCode, it.summary) }

	@Test
	fun `selective calls keep original symbols and the defining file private helpers`(@TempDir root: Path) {
		if (java.lang.Boolean.getBoolean("cloth.test.nativeFreeSemantics")) {
			assertEquals("false", System.getProperty("org.bytedeco.javacpp.loadlibraries"))
			assertNotNull(javaClass.classLoader.getResource("org/bytedeco/llvm/LLVM/LLVMValueRef.class"))
			assertNull(javaClass.classLoader.getResource("org/bytedeco/llvm/windows-x86_64/LLVM-C.dll"))
			assertNull(javaClass.classLoader.getResource("org/bytedeco/llvm/linux-x86_64/libLLVM.so"))
		}
		val model = check(root, mapOf("Main.co" to "using Lib: answer\nfn<int> main() = answer()", "Lib.co" to "pub fn<int> answer() = helper()\nfn<int> helper() = 42"))
		assertSame(function(model, "Lib.co", "answer"), model.selectedFunction(calls(model, "Main.co", "answer").single()))
		assertSame(function(model, "Lib.co", "helper"), model.selectedFunction(calls(model, "Lib.co", "helper").single()))
		assertNull(model.declarations.fileScope.lookup("helper"))
	}

	@Test
	fun `wildcards expose public declarations without exposing private helpers`(@TempDir root: Path) {
		fail(DiagnosticCode.UNKNOWN_NAME, root, mapOf("Main.co" to "using Lib: *\nfn<int> main() = hidden()", "Lib.co" to "pub fn<int> answer() = hidden()\nfn<int> hidden() = 1"))
	}

	@Test
	fun `explicit private imports label the selector and its original declaration`(@TempDir root: Path) {
		for (declaration in listOf("fn Hidden() {}", "typedef<struct> Hidden {}", "typedef<class> Hidden", "typedef<enum> Hidden { One }", "typedef<interface> Hidden {}")) {
			val failure = fail(DiagnosticCode.IMPORT_PRIVATE_NAME, root, mapOf("Main.co" to "using Lib: Hidden\nfn main() {}", "Lib.co" to declaration))
			assertEquals(listOf("Main.co", "Lib.co"), failure.sourceLabels.map { fileOf(it.span.source!!.name) })
			val rendered = StringBuilder()
			DiagnosticReporter(rendered).report(failure)
			assertContains(rendered.toString(), "C3103")
			assertContains(rendered.toString(), "Private declaration here")
		}
	}

	@Test
	fun `interfaces retain original imported identities and defining scope across a cycle`(@TempDir root: Path) {
		val graph = graph(root, mapOf(
			"Main.co" to """
				using Contract: Value
				using Bridge: Reader
				typedef<interface> Hidden {}
				typedef<class> Box : Value<int>
				fn take(Reader<int> view, Hidden helper) {}
			""",
			"Contract.co" to """
				using Bridge: Reader
				typedef<interface> Hidden {}
				pub typedef<interface> Value<T> {
				    mt<T> read()
				    mt<typeinfo> info() = default (Hidden::type)
				}
			""",
			"Bridge.co" to """
				using Contract: *
				pub typedef<interface> Reader<T> : Value<T> { mt<T> other() }
			""",
		))
		val names = GenericAnalyzer(target).analyze(graph)
		val main = graph.primary.unit.declarations
		val contract = graph.sources.single { fileOf(it.unit.source!!.name) == "Contract.co" }.unit.declarations
		val reader = graph.sources.single { fileOf(it.unit.source!!.name) == "Bridge.co" }.unit.declarations.single() as InterfaceDeclaration
		val box = main.filterIsInstance<ClassDeclaration>().single()
		val value = assertIs<CInterface>(assertIs<NominalTypeName>(names.nameOf(box.parents.single().type)).type)
		assertSame(contract[1], value.declaration)
		assertSame(value, assertIs<NominalTypeName>(names.nameOf(reader.parents.single())).type)

		val fallback = assertIs<ExpressionBody>(value.declaration.methods[1].fallback?.body)
		val receiver = assertIs<NameExpression>(assertIs<ProviderAccessExpression>(fallback.expression).receiver)
		val privateType = assertIs<CInterface>(assertIs<NominalTypeName>(names.receiverName(receiver)).type)
		assertSame(contract[0], privateType.declaration)
		val callerType = (main.last() as FunctionDeclaration).parameters[1].type
		assertNotSame(privateType, assertIs<NominalTypeName>(names.nameOf(callerType)).type)
	}

	@Test
	fun `distinct interface exports with the same spelling report an import conflict`(@TempDir root: Path) {
		fail(DiagnosticCode.IMPORT_NAME_CONFLICT, root, mapOf(
			"Main.co" to "using First: I\nusing Second: *\nfn main() {}",
			"First.co" to "pub typedef<interface> I {}",
			"Second.co" to "pub typedef<interface> I {}",
		))
	}

	@Test
	fun `interface applications through cyclic imports merge or conflict by their original identity`(@TempDir root: Path) {
		val files = mapOf(
			"Main.co" to "using Left: Left\nusing Right: *\ntypedef<class> Box : Left, Right",
			"Left.co" to "using Right: Right\nusing Contract: Value\npub typedef<interface> Left : Value<int> {}",
			"Right.co" to "using Left: Left\nusing Contract: *\npub typedef<interface> Right : Value<int<32>> {}",
			"Contract.co" to "pub typedef<interface> Value<T> {}",
		)
		val model = ClosedTypeAnalyzer(target).analyze(graph(root, files))
		val interfaces = model.interfaces.classes.values.single().implementations.keys
		assertEquals(setOf("Left", "Right", "Value<int>"), interfaces.map { it.spelling }.toSet())
		assertEquals(3, interfaces.size)
		val changed = files + ("Right.co" to files.getValue("Right.co").replace("Value<int<32>>", "Value<string>"))
		val conflict = fail(DiagnosticCode.CONFLICTING_INTERFACE_APPLICATION, root, changed)
		assertEquals(setOf("Main.co", "Left.co", "Right.co"), conflict.sourceLabels.map { fileOf(it.span.source!!.name) }.toSet())
	}

	@Test
	fun `absent selectors are distinct from private selectors`(@TempDir root: Path) {
		fail(DiagnosticCode.IMPORT_NAME_NOT_FOUND, root, mapOf("Main.co" to "using Lib: missing\nfn main() {}", "Lib.co" to "pub fn available() {}"))
	}

	@Test
	fun `imports are never implicitly reexported`(@TempDir root: Path) {
		for (selection in listOf("Thing", "make")) {
			fail(DiagnosticCode.IMPORT_NAME_NOT_FOUND, root, mapOf("Main.co" to "using Middle: $selection\nfn main() {}", "Middle.co" to "using Leaf: *\npub fn middle() {}", "Leaf.co" to "pub typedef<struct> Thing {}\npub fn<int> make() = 1"))
		}
		fail(DiagnosticCode.UNSUPPORTED_TYPE, root, mapOf("Main.co" to "using Middle: *\nfn work(Thing value) {}", "Middle.co" to "using Leaf: *\npub fn middle() {}", "Leaf.co" to "pub typedef<struct> Thing {}"))
	}

	@Test
	fun `mixed type function selectors work in either order`(@TempDir root: Path) {
		for (selection in listOf("Tag, make", "make, Tag")) {
			val model = check(root, mapOf("Main.co" to "using Lib: $selection\nfn<Tag> work() = make()", "Lib.co" to "pub typedef<struct> Tag { int value }\npub fn<Tag> make() = { 3 }"))
			assertSame(model.declarations.structs.getValue("Tag"), model.typeOf(function(model, "Main.co", "work")).returns)
		}
	}

	@Test
	fun `a selected spelling can populate the separate type and callable namespaces`(@TempDir root: Path) {
		val model = check(root, mapOf("Main.co" to "using Lib: Thing\nfn<int> work() { def<Thing> value = {}; return Thing() }", "Lib.co" to "pub typedef<struct> Thing {}\npub fn<int> Thing() = 3"))
		assertNotNull(model.declarations.structs["Thing"])
		assertIs<FunctionGroupSymbol>(model.declarations.fileScope.lookup("Thing"))
	}

	@Test
	fun `own declarations and distinct imported type identities conflict`(@TempDir root: Path) {
		val failure = fail(DiagnosticCode.IMPORT_NAME_CONFLICT, root, mapOf("Main.co" to "using Lib: Thing\ntypedef<struct> Thing {}\nfn main() {}", "Lib.co" to "pub typedef<struct> Thing {}"))
		assertEquals(listOf("Main.co", "Main.co", "Lib.co"), failure.sourceLabels.map { fileOf(it.span.source!!.name) })
	}

	@Test
	fun `functions from different files never merge into an overload group`(@TempDir root: Path) {
		val failure = fail(DiagnosticCode.IMPORT_NAME_CONFLICT, root, mapOf("Main.co" to "using Left: choose\nusing Right: choose\nfn main() {}", "Left.co" to "pub fn<int> choose(int x) = x", "Right.co" to "pub fn<int> choose(string x) = 2"))
		assertEquals(4, failure.sourceLabels.size)
		assertEquals("Earlier import here", failure.sourceLabels.last().text)
	}

	@Test
	fun `an imported function conflicts with a local overload regardless of signature`(@TempDir root: Path) {
		fail(DiagnosticCode.IMPORT_NAME_CONFLICT, root, mapOf("Main.co" to "using Lib: choose\nfn<int> choose(string x) = 2", "Lib.co" to "pub fn<int> choose(int x) = x"))
	}

	@Test
	fun `repeated selected wildcard and self imports are idempotent`(@TempDir root: Path) {
		val model = check(root, mapOf("Main.co" to "using Lib: answer\nusing Lib: *\nusing Lib: answer\nfn<int> main() = answer()", "Lib.co" to "using Lib: answer\npub fn<int> answer() = 42"))
		assertEquals(2, model.declarations.functions.size)
		assertEquals(1, assertIs<FunctionGroupSymbol>(model.declarations.fileScope.lookup("answer")).overloads.size)
	}

	@Test
	fun `selected overload groups include all public overloads and exclude private ones`(@TempDir root: Path) {
		val library = "pub fn<int> choose(int x) = x\npub fn<int> choose(string x) = 2\nfn<int> choose(bool x) = 3\npub fn<int> internalCall() = choose(true)"
		val model = check(root, mapOf("Main.co" to "using Lib: choose\nfn<int> main() = choose(1) + choose(\"x\")", "Lib.co" to library))
		val group = assertIs<FunctionGroupSymbol>(model.declarations.fileScope.lookup("choose"))
		assertEquals(2, group.overloads.size)
		assertTrue(group.overloads.all { it.declaration.visibility != null })
		val privateChoice = model.selectedFunction(calls(model, "Lib.co", "choose").single())
		assertNull(privateChoice.declaration.visibility)
		fail(DiagnosticCode.NO_MATCHING_CALL, root, mapOf("Main.co" to "using Lib: choose\nfn<int> main() = choose(true)", "Lib.co" to library))
	}

	@Test
	fun `nearer lexical bindings can shadow an imported function`(@TempDir root: Path) {
		check(root, mapOf("Main.co" to "using Lib: answer\nfn<int> main() { def answer = 7; return answer }", "Lib.co" to "pub fn<int> answer() = 1"))
	}

	@Test
	fun `imported functions preserve ordinary builtin shadowing`(@TempDir root: Path) {
		val model = check(root, mapOf("Main.co" to "using Lib: print\nfn<int> main() = print(1)", "Lib.co" to "pub fn<int> print(int x) = x"))
		assertSame(function(model, "Lib.co", "print"), model.selectedFunction(calls(model, "Main.co", "print").single()))
	}

	@Test
	fun `default expressions keep the callee private lookup context`(@TempDir root: Path) {
		val model = check(root, mapOf("Main.co" to "using Lib: answer\nfn<int> helper() = 99\nfn<int> main() = answer()", "Lib.co" to "fn<int> helper() = 3\npub fn<int> answer(opt<int> value ?: helper()) = value"))
		assertSame(function(model, "Lib.co", "helper"), model.selectedFunction(calls(model, "Lib.co", "helper").single()))
	}

	@Test
	fun `private signature types flow through calls without exposing their names to callers`(@TempDir root: Path) {
		val model = check(root, mapOf("Main.co" to "using Lib: make, read\nfn<int> main() = read(make()) + read({ 7 })", "Lib.co" to "typedef<struct> Hidden { int value }\npub fn<Hidden> make() = { 3 }\npub fn<int> read(Hidden value) = value.value"))
		assertNull(model.declarations.structs["Hidden"])
		assertIs<CStruct>(model.typeOf(function(model, "Lib.co", "make")).returns)
	}

	@Test
	fun `private default aggregate types resolve in the defining file`(@TempDir root: Path) {
		check(root, mapOf("Main.co" to "using Lib: answer\nfn<int> main() = answer()", "Lib.co" to "typedef<struct> Hidden { int value }\npub fn<int> answer(opt<Hidden> box ?: ({ 4 })) = box.value"))
	}

	@Test
	fun `same spelled private functions and structs in different files remain distinct`(@TempDir root: Path) {
		val model = check(root, mapOf("Main.co" to "using Left: left\nusing Right: right\nfn work() { def a = left(); def b = right() }", "Left.co" to "typedef<struct> Hidden { int a }\nfn<Hidden> helper() = { 1 }\npub fn<Hidden> left() = helper()", "Right.co" to "typedef<struct> Hidden { string b }\nfn<Hidden> helper() = { \"x\" }\npub fn<Hidden> right() = helper()"))
		val left = model.typeOf(function(model, "Left.co", "left")).returns
		val right = model.typeOf(function(model, "Right.co", "right")).returns
		assertNotSame(left, right)
		assertEquals("Hidden", left.spelling)
		assertEquals("Hidden", right.spelling)
		assertEquals(2, model.declarations.allStructs.size)
		assertSame(function(model, "Left.co", "helper"), model.selectedFunction(calls(model, "Left.co", "helper").single()))
		assertSame(function(model, "Right.co", "helper"), model.selectedFunction(calls(model, "Right.co", "helper").single()))
	}

	@Test
	fun `same spelled private nominal parameters do not accept each other`(@TempDir root: Path) {
		fail(DiagnosticCode.NO_MATCHING_CALL, root, mapOf("Main.co" to "using Left: make\nusing Right: read\nfn<int> main() = read(make())", "Left.co" to "typedef<struct> Hidden { int value }\npub fn<Hidden> make() = { 1 }", "Right.co" to "typedef<struct> Hidden { int value }\npub fn<int> read(Hidden value) = value.value"))
	}

	@Test
	fun `mutually recursive functions bind across circular imports`(@TempDir root: Path) {
		val model = check(root, mapOf("Main.co" to "using Even: even\nfn<bool> main() = even(4)", "Even.co" to "using Odd: odd\npub fn<bool> even(int n) = if (n == 0) { true } else { odd(n - 1) }", "Odd.co" to "using Even: even\npub fn<bool> odd(int n) = if (n == 0) { false } else { even(n - 1) }"))
		assertSame(function(model, "Odd.co", "odd"), model.selectedFunction(calls(model, "Even.co", "odd").single()))
		assertSame(function(model, "Even.co", "even"), model.selectedFunction(calls(model, "Odd.co", "even").single()))
	}

	@Test
	fun `class field identities cross cycles and share one builtin Object root`(@TempDir root: Path) {
		val model = check(root, mapOf("Main.co" to "using Left: Left\nusing Right: Right\nfn work(Left a, Right b, Object root) {}", "Left.co" to "using Right: Right\npub typedef<class> Left(pub def<Right?> peer ?: (null))", "Right.co" to "using Left: Left\npub typedef<class> Right(pub def<Left?> peer ?: (null))"))
		val classes = model.declarations.classDefinitions.keys.associateBy { it.spelling }
		assertTrue(classes.getValue("Right") === assertIs<CNullable>(classes.getValue("Left").fields.single().type).payload)
		assertTrue(classes.getValue("Left") === assertIs<CNullable>(classes.getValue("Right").fields.single().type).payload)
		assertTrue(classes.values.all { it.base === model.declarations.objectRoot })
		assertSame(model.declarations.objectRoot, model.typeOf(function(model, "Main.co", "work")).parameters.last())
	}

	@Test
	fun `cross file recursive inline structs remain invalid`(@TempDir root: Path) {
		val failure = fail(DiagnosticCode.RECURSIVE_STRUCT, root, mapOf("Main.co" to "using Left: *\nfn main() {}", "Left.co" to "using Right: Right\npub typedef<struct> Left { Right value }", "Right.co" to "using Left: Left\npub typedef<struct> Right { Left value }"))
		assertTrue(failure.sourceLabels.mapNotNull { it.span.source?.name }.distinct().size >= 2)
	}

	@Test
	fun `cross file inheritance cycles remain invalid`(@TempDir root: Path) {
		fail(DiagnosticCode.INVALID_CLASS_MEMBER, root, mapOf("Main.co" to "using Left: *\nfn main() {}", "Left.co" to "using Right: Right\npub typedef<class> Left: Right", "Right.co" to "using Left: Left\npub typedef<class> Right: Left"))
	}

	@Test
	fun `imported struct methods retain the defining file helper scope`(@TempDir root: Path) {
		val model = check(root, mapOf("Main.co" to "using Lib: Point\nfn<int> main() { def<Point> p = { 3 }; return p.read() }", "Lib.co" to "fn<int> helper(int x) = x\npub typedef<struct> Point { int value; mt<int> read() = helper(self.value) }"))
		assertSame(function(model, "Lib.co", "helper"), model.selectedFunction(calls(model, "Lib.co", "helper").single()))
	}

	@Test
	fun `imported enum cases and generated readers keep their original owner`(@TempDir root: Path) {
		val model = check(root, mapOf("Main.co" to "using Lib: Kind\nfn<Kind> main() = Kind.One", "Lib.co" to "pub typedef<enum> Kind { One, Two }"))
		assertSame(model.declarations.enums.getValue("Kind"), model.typeOf(function(model, "Main.co", "main")).returns)
	}

	@Test
	fun `all loaded private and unused declarations are checked`(@TempDir root: Path) {
		val failure = fail(DiagnosticCode.UNKNOWN_NAME, root, mapOf("Main.co" to "using Lib: available\nfn main() {}", "Lib.co" to "pub fn available() {}\nfn bad() { unknownName }"))
		assertEquals("Lib.co", fileOf(failure.sourceLabels.first().span.source!!.name))
	}

	@Test
	fun `class member privacy still applies through an imported type`(@TempDir root: Path) {
		fail(DiagnosticCode.PRIVATE_CLASS_MEMBER, root, mapOf("Main.co" to "using Lib: Box\nfn<int> main() { def box = Box::new(1); return box.secret }", "Lib.co" to "pub typedef<class> Box(def<int> secret)"))
	}

	@Test
	fun `inherited methods distinguish private parameter identities with the same spelling`(@TempDir root: Path) {
		val model = check(root, mapOf("Main.co" to "using Child: Child, makeB\nusing Parent: makeA\nfn<int> work(Child value) = value.take(makeA()) + value.take(makeB())", "Child.co" to "using Parent: Base\ntypedef<struct> Token {}\npub fn<Token> makeB() = {}\npub typedef<class> Child: Base { pub mt<int> take(Token value) = 2 }", "Parent.co" to "typedef<struct> Token {}\npub fn<Token> makeA() = {}\npub typedef<class> Base { pub mt<int> take(Token value) = 1 }"))
		val calls = model.sourceMemberCalls.values.map { it.callable as cloth.semantic.symbols.MethodSymbol }
		assertEquals(setOf("Base", "Child"), calls.map { it.owner.spelling }.toSet())
	}

	@Test
	fun `private class constructors with identical names use their own field layouts`(@TempDir root: Path) {
		val model = check(root, mapOf("Main.co" to "using Left: left\nusing Right: right\nfn work() { def a = left(); def b = right() }", "Left.co" to "typedef<class> Hidden(pub def<int> value)\npub fn<Hidden> left() = Hidden::new(1)", "Right.co" to "typedef<class> Hidden(pub def<string> value)\npub fn<Hidden> right() = Hidden::new(\"x\")"))
		val left = assertIs<CClass>(model.typeOf(function(model, "Left.co", "left")).returns)
		val right = assertIs<CClass>(model.typeOf(function(model, "Right.co", "right")).returns)
		assertNotSame(left, right)
		assertIs<CInt>(left.fields.single().type)
		assertEquals(ValueType.STRING, right.fields.single().type)
		assertEquals(2, model.declarations.classDefinitions.size)
	}

	@Test
	fun `private enums with identical names retain distinct cases and generated readers`(@TempDir root: Path) {
		val model = check(root, mapOf("Main.co" to "using Left: left\nusing Right: right\nfn work() { def a = left(); def b = right() }", "Left.co" to "typedef<enum> Hidden { Left }\npub fn<Hidden> left() = Hidden.Left", "Right.co" to "typedef<enum> Hidden { Right }\npub fn<Hidden> right() = Hidden.Right"))
		assertNotSame(model.typeOf(function(model, "Left.co", "left")).returns, model.typeOf(function(model, "Right.co", "right")).returns)
		assertEquals(2, model.declarations.enumTypes.size)
	}

	@Test
	fun `type providers use defining file types and keep target layouts per analysis`(@TempDir root: Path) {
		val graph = graph(root, mapOf("Main.co" to "using Left: left\nusing Right: right\nfn<typeinfo> work() = left()", "Left.co" to "typedef<struct> Hidden { usize value }\npub fn<typeinfo> left() = Hidden::type", "Right.co" to "typedef<struct> Hidden { int<64> value }\npub fn<typeinfo> right() = Hidden::type"))
		for (bits in listOf(32, 64)) {
			val model = FunctionAnalyzer(TargetLayout("synthetic-$bits", pointerBits = bits, cLongBits = bits)).analyze(graph)
			assertEquals(ValueType.TYPEINFO, model.typeOf(function(model, "Main.co", "work")).returns)
			val left = model.declarations.allStructs.single { fileOf(it.name.span.source!!.name) == "Left.co" }
			assertEquals(CUSize(bits), left.fields.single().type)
		}
	}

	@Test
	fun `circular binding crosses module roots without adding module names to lookup`(@TempDir root: Path) {
		val frontend = Files.createDirectory(root.resolve("Frontend"))
		val backend = Files.createDirectory(root.resolve("Backend"))
		val entry = Files.writeString(frontend.resolve("Main.co"), "using Alpha: alpha\nfn<int> main() = alpha(4)")
		Files.writeString(frontend.resolve("Alpha.co"), "using Beta: beta\npub fn<int> alpha(int n) = if (n == 0) { 0 } else { beta(n - 1) }")
		Files.writeString(backend.resolve("Beta.co"), "using Alpha: alpha\npub fn<int> beta(int n) = if (n == 0) { 0 } else { alpha(n - 1) }")
		val graph = SourceGraphLoader.load(CompilationRequest(entry, root.resolve("out.o"), listOf(SourceModule("Frontend", frontend), SourceModule("Backend", backend)), outputKind = OutputKind.OBJECT))
		val model = FunctionAnalyzer(target).analyze(graph)
		assertSame(function(model, "Beta.co", "beta"), model.selectedFunction(calls(model, "Alpha.co", "beta").single()))
		assertSame(function(model, "Alpha.co", "alpha"), model.selectedFunction(calls(model, "Beta.co", "alpha").single()))
	}

	@Test
	fun `importing a type does not publish its instance methods as free functions`(@TempDir root: Path) {
		fail(DiagnosticCode.IMPORT_NAME_NOT_FOUND, root, mapOf("Main.co" to "using Lib: read\nfn main() {}", "Lib.co" to "pub typedef<struct> Box { int value; mt<int> read() = value }"))
	}
}
