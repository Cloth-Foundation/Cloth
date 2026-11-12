package cloth

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.data.CStruct
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.parser.statement.ReturnStatement
import cloth.semantic.declarations.DeclarationAnalyzer
import cloth.semantic.generics.*
import cloth.settings.OutputKind
import cloth.source.SourceGraph
import cloth.source.SourceGraphLoader
import cloth.target.TargetLayout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GenericNamesTest {
	private val target = TargetLayout("generic-names-64", pointerBits = 64, cLongBits = 64)
	private fun unit(source: String) = Parser(Lexer(SourceFile("generic-names.co", source.trimIndent())).scanTokens()).parse()
	private fun graph(root: Path, sources: Map<String, String>): SourceGraph {
		for ((name, text) in sources) {
			val path = root.resolve(name)
			Files.createDirectories(path.parent)
			Files.writeString(path, text.trimIndent())
		}
		return SourceGraphLoader.load(CompilationRequest(root.resolve("Main.co"), root.resolve("not-created/out.o"), listOf(SourceModule("M", root)), outputKind = OutputKind.OBJECT))
	}
	private fun failure(source: String, code: DiagnosticCode): ClothDiagnostic = assertFailsWith<ClothDiagnostic> { GenericAnalyzer(target).analyze(unit(source)) }.also {
		assertEquals(code, it.diagnosticCode, it.summary)
		assertLocated(it)
	}

	@Test
	fun `owner parameters shadow outer types and retain separate declaration identities`() {
		if (java.lang.Boolean.getBoolean("cloth.test.nativeFreeGenerics")) {
			assertEquals("false", System.getProperty("org.bytedeco.javacpp.loadlibraries"))
			assertNull(javaClass.classLoader.getResource("org/bytedeco/llvm/windows-x86_64/LLVM-C.dll"))
			assertNull(javaClass.classLoader.getResource("org/bytedeco/llvm/linux-x86_64/libLLVM.so"))
		}
		val ast = unit("""
			typedef<class> T {}
			typedef<struct> First<T> { T value }
			typedef<struct> Second<T> { T value }
			fn take(T value) {}
		""")
		val model = GenericAnalyzer(target).analyze(ast)
		val first = ast.declarations[1] as StructDeclaration
		val second = ast.declarations[2] as StructDeclaration
		val a = model.parametersOf(first).single()
		val b = model.parametersOf(second).single()
		assertNotSame(a, b)
		assertSame(first, a.owner)
		assertEquals(0, a.index)
		assertSame(a, model.nameOf(first.fields.single().type))
		assertSame(b, model.nameOf(second.fields.single().type))
		assertIs<NominalTypeName>(model.nameOf((ast.declarations[3] as FunctionDeclaration).parameters.single().type))
	}

	@Test
	fun `headers defaults methods captures and result lists use their owner parameter`() {
		val ast = unit("""
			typedef<class> Box<T> (def<T> item, opt<typeinfo> metadata ?: T::type) {
			    pub mt<T, T?> read(T input, opt<T> fallback ?: input) {
			        def<T> deferred = { def<T> local = input; return local }
			        return deferred, null
			    }
			}
		""")
		val box = ast.declarations.single() as ClassDeclaration
		val model = GenericAnalyzer(target).analyze(ast)
		val t = model.parametersOf(box).single()
		assertSame(t, model.nameOf(box.parameters[0].parameter.type))
		val metadata = box.parameters[1].parameter.defaultValue as ProviderAccessExpression
		assertSame(t, model.receiverName(metadata.receiver as NameExpression))
		val method = box.members.single() as MethodDeclaration
		for (type in method.returnTypes + method.parameters.map { it.type }) assertSame(t, model.nameOf(type))
		val deferred = (method.body as BlockBody).statements.first() as BindingDeclaration
		assertSame(t, model.nameOf(assertNotNull(deferred.type)))
	}

	@Test
	fun `method-owned names extend the enclosing type scope throughout the callable`() {
		val ast = unit("""
			typedef<interface> View<T> {}
			typedef<class> Box<T> {
			    pub mt<T, U> pair<U: View<T>, N: Number>(T input, U value, opt<typeinfo> info ?: U::type) {
			        def<U> deferred = { def<T> copy = input; return value }
			        return input, self.echo<U>(deferred)
			    }
			    mt<U> echo<U>(U value) = value
			}
		""")
		val box = assertIs<ClassDeclaration>(ast.declarations[1])
		val (pair, echo) = box.members.filterIsInstance<MethodDeclaration>()
		val model = GenericAnalyzer(target).analyze(ast)
		val t = model.parametersOf(box).single()
		val (u, n) = model.parametersOf(pair)
		assertSame(pair, u.owner)
		assertEquals(1, n.index)
		assertNotSame(u, model.parametersOf(echo).single())
		val bound = assertNotNull(u.declaration.bound)
		assertSame(ast.declarations[0], assertIs<NominalTypeName>(model.nameOf(bound)).type.declaration)
		assertSame(t, model.nameOf(bound.typeArguments.single()))
		assertSame(NumberBoundName, model.nameOf(assertNotNull(n.declaration.bound)))
		assertSame(t, model.nameOf(pair.returnTypes[0]))
		assertSame(u, model.nameOf(pair.returnTypes[1]))
		assertSame(t, model.nameOf(pair.parameters[0].type))
		assertSame(u, model.nameOf(pair.parameters[1].type))
		val metadata = assertIs<ProviderAccessExpression>(pair.parameters[2].defaultValue)
		assertSame(u, model.receiverName(assertIs<NameExpression>(metadata.receiver)))
		val body = assertIs<BlockBody>(pair.body)
		val deferred = assertIs<BindingDeclaration>(body.statements[0])
		assertSame(u, model.nameOf(assertNotNull(deferred.type)))
		val block = assertIs<FunctionBlockExpression>(deferred.initializer)
		val copy = assertIs<BindingDeclaration>(assertIs<BlockBody>(block.declaration.body).statements.first())
		assertSame(t, model.nameOf(assertNotNull(copy.type)))
		val result = assertIs<ResultListExpression>(assertIs<ReturnStatement>(body.statements[1]).value)
		val call = assertIs<MemberCallExpression>(result.values[1])
		val argument = assertNotNull(call.typeArguments).arguments.single()
		assertSame(u, model.nameOf(argument))
		assertSame(pair, model.uses.single { it.type === argument }.owner)
	}

	@Test
	fun `struct method arguments resolve in the calling file`(@TempDir root: Path) {
		val graph = graph(root, mapOf(
			"Main.co" to "using Lib: Slot\nusing Model: Item\nfn read(Slot<int> slot, Item item) { slot.echo<Item>(item) }",
			"Lib.co" to "typedef<class> Item {}\npub typedef<struct> Slot<T> { T item; mt<U> echo<U>(U value) = value }",
			"Model.co" to "pub typedef<class> Item {}",
		))
		val model = GenericAnalyzer(target).analyze(graph)
		val slot = graph.sources.single { it.path.fileName.toString() == "Lib.co" }.unit.declarations[1] as StructDeclaration
		val method = slot.methods.single()
		assertSame(model.parametersOf(slot).single(), model.nameOf(slot.fields.single().type))
		assertSame(model.parametersOf(method).single(), model.nameOf(method.returnTypes.single()))
		val caller = graph.sources.single { it.path.fileName.toString() == "Main.co" }.unit.declarations.single() as FunctionDeclaration
		val call = assertIs<MemberCallExpression>(assertIs<ExpressionStatement>(assertIs<BlockBody>(caller.body).statements.single()).expression)
		val type = assertIs<NominalTypeName>(model.nameOf(assertNotNull(call.typeArguments).arguments.single())).type
		assertSame(graph.sources.single { it.path.fileName.toString() == "Model.co" }.unit.declarations.single(), type.declaration)
	}

	@Test
	fun `method parameter conflicts and out of scope names report ordinary name errors`() {
		val conflict = failure("""
			typedef<class> Box<T> {
			    mt echo<T>() {}
			}
		""", DiagnosticCode.INVALID_GENERIC_PARAMETER)
		assertEquals(listOf(2, 1), conflict.sourceLabels.map { it.span.startLine })
		val duplicate = failure("typedef<struct> Slot { mt echo<U, U>() {} }", DiagnosticCode.INVALID_GENERIC_PARAMETER)
		assertEquals(2, duplicate.sourceLabels.size)
		failure("""
			typedef<class> Box<T> {
			    mt<U> echo<U>(U value) = value
			    mt<U> other() = null
			}
		""", DiagnosticCode.UNSUPPORTED_TYPE)
	}

	@Test
	fun `explicit member argument types are checked before specialization`() {
		val invalid = assertFailsWith<ClothDiagnostic> {
			DeclarationAnalyzer(target).analyze(unit("""
				typedef<class> Box { mt<U> echo<U>(U value) = value }
				fn read(Box box) { box.echo<Missing>(1) }
			"""))
		}
		assertEquals(DiagnosticCode.UNSUPPORTED_TYPE, invalid.diagnosticCode)
		assertContains(invalid.summary, "'Missing'")
		assertLocated(invalid)
		failure("fn read(int value) { value.echo<void>(1) }", DiagnosticCode.INVALID_GENERIC_ARGUMENT)
		failure("typedef<class> Box<T> {}\nfn read(int value) { value.echo<Box<int, string>>(1) }", DiagnosticCode.GENERIC_ARITY)
	}

	@Test
	fun `valid method names reach closed analysis and body specialization`() {
		val ast = unit("""
			typedef<class> Box { pub mt<U> echo<U: Number>(U value) = value }
			fn read(Box box) { box.echo<int>(1) }
		""")
		assertTrue(ClosedTypeAnalyzer(target).analyze(ast).instances.isEmpty())
		val views = assertNotNull(DeclarationAnalyzer(target).analyze(ast).genericViews)
		assertEquals("echo", views.methods.values.single().name.lexeme)
	}

	@Test
	fun `value bindings do not replace annotation parameters and retain provider receiver precedence`() {
		val ast = unit("""
			typedef<class> Box<T> {
			    pub mt<typeinfo> declared() = T::type
			    pub mt<typeinfo> received(int T) = T::type
			    pub mt<T> copy(T T) { def<T> value = T; return value }
			}
		""")
		val box = ast.declarations.single() as ClassDeclaration
		val model = GenericAnalyzer(target).analyze(ast)
		val methods = box.members.filterIsInstance<MethodDeclaration>()
		fun receiver(method: MethodDeclaration) = ((method.body as ExpressionBody).expression as ProviderAccessExpression).receiver as NameExpression
		assertSame(model.parametersOf(box).single(), model.receiverName(receiver(methods[0])))
		assertNull(model.receiverName(receiver(methods[1])))
		assertSame(model.parametersOf(box).single(), model.nameOf(methods[2].parameters.single().type))
	}

	@Test
	fun `duplicates and owner named parameters fail before the compilation boundary`() {
		for (source in listOf("typedef<class> Box<T, T> {}", "typedef<struct> Box<Box> {}")) {
			val error = failure(source, DiagnosticCode.INVALID_GENERIC_PARAMETER)
			assertEquals(2, error.sourceLabels.size)
		}
	}

	@Test
	fun `owner parameters do not leak to other declarations or accept type applications`() {
		failure("typedef<class> Box<T> {}\nfn take(T value) {}", DiagnosticCode.UNSUPPORTED_TYPE)
		failure("typedef<class> Box<T> { pub mt<U> read(T value) = value }", DiagnosticCode.UNSUPPORTED_TYPE)
		failure("typedef<struct> Box<T> { T<int> value }", DiagnosticCode.INVALID_GENERIC_ARGUMENT)
	}

	@Test
	fun `arity distinguishes templates nongeneric types and bare generic value annotations`() {
		val prefix = "typedef<class> Box<T> {}\ntypedef<class> Plain {}\n"
		failure(prefix + "fn take(Box<int, string> value) {}", DiagnosticCode.GENERIC_ARITY)
		failure(prefix + "fn take(Plain<int> value) {}", DiagnosticCode.GENERIC_ARITY)
		failure(prefix + "fn take(Box value) {}", DiagnosticCode.RAW_GENERIC_TYPE)
		failure(prefix + "fn main() { Box::type }", DiagnosticCode.RAW_GENERIC_TYPE)
	}

	@Test
	fun `first encountered application failure stops before later annotation failures`() {
		val error = failure("typedef<class> Box<T> {}\nfn main() { Box::type; def<Missing<int>> value = null }", DiagnosticCode.RAW_GENERIC_TYPE)
		assertEquals(2, error.sourceLabels.first().span.startLine)
		assertContains(error.summary, "Box")
	}

	@Test
	fun `nullable array nominal enum wide and target sized arguments retain their syntax identities`() {
		val ast = unit("""
			typedef<class> Box<T> {}
			typedef<class> Item {}
			typedef<struct> Point { int x }
			typedef<enum> Mode { One }
			fn take(Box<int?> a, Box<int[][2]> b, Box<Item> c, Box<Point> d, Box<Mode> e, Box<int<129>?> f, Box<usize> g, Box<isize> h, Box<typeinfo> i, Box<Box<string>> j) {}
		""")
		val model = GenericAnalyzer(target).analyze(ast)
		val parameters = (ast.declarations.last() as FunctionDeclaration).parameters
		assertEquals(listOf("int?", "int[][2]", "Item", "Point", "Mode", "int<129>?", "usize", "isize", "typeinfo", "Box<string>"), parameters.map { it.type.typeArguments.single().spelling })
		assertIs<NominalTypeName>(model.nameOf(parameters[2].type.typeArguments.single()))
		assertEquals(BuiltinTypeName(cloth.token.Keywords.USIZE), model.nameOf(parameters[6].type.typeArguments.single()))
		failure("typedef<class> Box<T> {}\nfn take(Box<void> value) {}", DiagnosticCode.INVALID_GENERIC_ARGUMENT)
	}

	@Test
	fun `generic base arguments bind the derived owner without constructing closed layouts`() {
		val ast = unit("typedef<class> Base<A> {}\ntypedef<class> Derived<T> : Base<T> {}")
		val model = GenericAnalyzer(target).analyze(ast)
		val derived = ast.declarations[1] as ClassDeclaration
		val base = derived.parents.single().type
		assertSame(model.parametersOf(derived).single(), model.nameOf(base.typeArguments.single()))
		assertSame(ast.declarations[0], assertIs<cloth.data.CClass>(assertIs<NominalTypeName>(model.nameOf(base)).type).declaration)
	}

	@Test
	fun `bounds resolve the original nominal and owner parameter identities`() {
		val ast = unit("""
			typedef<class> Entity<T: Entity<T>> {}
			typedef<class> Player : Entity<Player> {}
			typedef<interface> IHealth<N: Number> {}
			typedef<struct> Meter<T: IHealth<N>, N: Number> { T value }
			fn observe<T: Object>(T value) {}
		""")
		val model = GenericAnalyzer(target).analyze(ast)
		val entity = model.parametersOf(ast.declarations[0]).single()
		val entityBound = assertNotNull(entity.declaration.bound)
		val entityName = assertIs<NominalTypeName>(model.nameOf(entityBound))
		assertSame(ast.declarations[0], entityName.type.declaration)
		assertSame(entity, model.nameOf(entityBound.typeArguments.single()))
		val playerBase = assertIs<ClassDeclaration>(ast.declarations[1]).parents.single().type
		assertEquals(entityName, model.nameOf(playerBase))

		val (meter, number) = model.parametersOf(ast.declarations[3])
		val meterBound = assertNotNull(meter.declaration.bound)
		assertSame(ast.declarations[2], assertIs<NominalTypeName>(model.nameOf(meterBound)).type.declaration)
		assertSame(number, model.nameOf(meterBound.typeArguments.single()))
		assertSame(NumberBoundName, model.nameOf(assertNotNull(number.declaration.bound)))
		val objectBound = assertNotNull(model.parametersOf(ast.declarations[4]).single().declaration.bound)
		assertEquals("Object", assertIs<NominalTypeName>(model.nameOf(objectBound)).type.spelling)
	}

	@Test
	fun `plain Number is contextual and qualified and imported types use ordinary lookup`(@TempDir root: Path) {
		val graph = graph(root, mapOf(
			"Main.co" to "using Bounds: Number, Family\nfn observe<T: Number, U: Family.Number>(Number value) {}",
			"Bounds.co" to "pub typedef<class> Number {}\npub typedef<class> Family { pub typedef<interface> Number {} }",
		))
		val model = GenericAnalyzer(target).analyze(graph)
		val imported = graph.sources.single { it.path.fileName.toString() == "Bounds.co" }.unit.declarations
		val function = graph.sources.single { it.path.fileName.toString() == "Main.co" }.unit.declarations.single() as FunctionDeclaration
		val (numeric, nominal) = model.parametersOf(function)
		assertSame(NumberBoundName, model.nameOf(assertNotNull(numeric.declaration.bound)))
		val nested = assertIs<ClassDeclaration>(imported[1]).nestedTypes.single()
		assertSame(nested, assertIs<NominalTypeName>(model.nameOf(assertNotNull(nominal.declaration.bound))).type.declaration)
		assertSame(imported[0], assertIs<NominalTypeName>(model.nameOf(function.parameters.single().type)).type.declaration)
	}

	@Test
	fun `bounds keep name and arity errors and reject other bound types`() {
		val unknown = failure("fn read<T: Missing>(Other value) {}", DiagnosticCode.UNSUPPORTED_TYPE)
		assertContains(unknown.summary, "'Missing'")
		failure("fn read<T: Number>(Number value) {}", DiagnosticCode.UNSUPPORTED_TYPE)
		val prefix = "typedef<interface> IHealth<N> {}\n"
		failure(prefix + "fn read<T: IHealth>() {}", DiagnosticCode.RAW_GENERIC_TYPE)
		failure(prefix + "fn read<T: IHealth<int, float>>() {}", DiagnosticCode.GENERIC_ARITY)
		for (bound in listOf("int", "Object?", "Object[]", "unsigned Object", "T", "Slot")) {
			failure("typedef<struct> Slot { int value }\nfn read<T: $bound>() {}", DiagnosticCode.INVALID_GENERIC_BOUND)
		}
	}

	@Test
	fun `unused bound declarations reach both semantic entry points`() {
		val ast = unit("typedef<interface> IHealth<T: Number> {}")
		assertNotNull(DeclarationAnalyzer(target).analyze(ast).genericViews)
		assertTrue(ClosedTypeAnalyzer(target).analyze(ast).instances.isEmpty())
	}

	@Test
	fun `documented imported List binds private slot names in the defining file`(@TempDir root: Path) {
		val fixture = Files.readString(Path.of(".agents/skills/cloth-generics/references/acceptance-fixtures.md"))
		val blocks = Regex("```cloth\\r?\\n([\\s\\S]*?)\\r?\\n```").findAll(fixture).map { it.groupValues[1] }.toList()
		val graph = graph(root, mapOf("Main.co" to blocks[1], "collections/List.co" to blocks[0]))
		val model = GenericAnalyzer(target).analyze(graph)
		assertTrue(model.requiresGenericCompilation)
		val library = graph.sources.single { it.path.fileName.toString() == "List.co" }.unit
		val slot = library.declarations.first() as StructDeclaration
		val list = library.declarations[1] as ClassDeclaration
		val field = list.members.filterIsInstance<ClassFieldDeclaration>().single().type
		assertSame(slot, assertIs<CStruct>(assertIs<NominalTypeName>(model.nameOf(field)).type).declaration)
		assertSame(model.parametersOf(list).single(), model.nameOf(field.typeArguments.single()))
		assertNotSame(model.parametersOf(slot).single(), model.parametersOf(list).single())
	}

	@Test
	fun `circular imports and repeated routes retain original generic parameter owners`(@TempDir root: Path) {
		val graph = graph(root, mapOf(
			"Main.co" to "using A: Box\nusing B: use\nfn main() { Box<int>::new; use() }",
			"A.co" to "using B: use\npub typedef<class> Box<T> { pub mt<T> identity(T value) = value }",
			"B.co" to "using A: Box\npub fn use() { Box<string>::new }"
		))
		val model = GenericAnalyzer(target).analyze(graph)
		val owner = graph.sources.single { it.path.fileName.toString() == "A.co" }.unit.declarations.single() as ClassDeclaration
		assertSame(owner, model.parametersOf(owner).single().owner)
		assertEquals(1, model.owners.size)
		val types = model.names.values.filterIsInstance<NominalTypeName>().map { it.type }.distinct()
		assertEquals(1, types.size)
	}

	@Test
	fun `same spelled private templates and caller private actual types retain separate identities`(@TempDir root: Path) {
		val graph = graph(root, mapOf(
			"Main.co" to "using A: Public\nusing B: work\ntypedef<class> Hidden {}\nfn main() { Public<Hidden>::new; work() }",
			"A.co" to "typedef<struct> Slot<T> { T value }\npub typedef<class> Public<T> { def<Slot<T>[]> values = [] }",
			"B.co" to "typedef<struct> Slot<T> { T value }\npub fn work() { def<Slot<int>> value = { 1 } }"
		))
		val model = GenericAnalyzer(target).analyze(graph)
		val slots = model.names.values.filterIsInstance<NominalTypeName>().map { it.type }.filter { it.spelling == "Slot" }.distinct()
		assertEquals(2, slots.size)
		val hidden = model.names.values.filterIsInstance<NominalTypeName>().single { it.type.spelling == "Hidden" }
		assertEquals("Main.co", Path.of(assertNotNull(hidden.type.name.span.source).name).fileName.toString())
	}

	@Test
	fun `generic parameters are not imported as names and private templates stay private`(@TempDir root: Path) {
		for ((i, selection) in listOf("T" to DiagnosticCode.IMPORT_NAME_NOT_FOUND, "Hidden" to DiagnosticCode.IMPORT_PRIVATE_NAME).withIndex()) {
			val error = assertFailsWith<ClothDiagnostic> { GenericAnalyzer(target).analyze(graph(root.resolve("case_$i"), mapOf("Main.co" to "using Lib: ${selection.first}\nfn main() {}", "Lib.co" to "pub typedef<class> Box<T> {}\ntypedef<class> Hidden<T> {}"))) }
			assertEquals(selection.second, error.diagnosticCode)
			assertLocated(error)
		}
	}

	@Test
	fun `ordinary programs retain the non generic declaration path`() {
		assertFalse(GenericAnalyzer(target).analyze(unit("fn<int> main() = 1 -> int<8> -> int")).requiresGenericCompilation)
	}
}
