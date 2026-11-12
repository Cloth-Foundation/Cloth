package cloth

import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.ExpressionStatement
import cloth.parser.type.ConstructorBound
import cloth.token.TokenSpan
import org.junit.jupiter.api.Test
import kotlin.test.*

class GenericParsingTest {

	private fun unit(source: String) = Parser(Lexer(SourceFile("generic-syntax.co", source.trimIndent())).scanTokens()).parse()
	private fun type(source: String) = (unit("fn<$source> value() = null").declarations.single() as FunctionDeclaration).returnTypes.single()
	private fun expression(source: String) = ((unit("fn main() { $source }").declarations.single() as FunctionDeclaration).body as BlockBody).statements.single().let { (it as ExpressionStatement).expression }
	private fun text(span: TokenSpan): String {
		val lines = assertNotNull(span.source).text.split('\n')
		fun offset(line: Int, column: Int) = lines.take(line - 1).sumOf { it.length + 1 } + column - 1
		return assertNotNull(span.source).text.substring(offset(span.startLine, span.startColumn), offset(span.endLine, span.endColumn))
	}

	@Test
	fun `class and struct owner parameters retain original names and delimiter spans`() {
		val declarations = unit("""
			pub final typedef<class> Box<A, B> (def<A> first) {}
			typedef<struct> Pair<A, B> { A first, B second }
		""").declarations
		val box = assertIs<ClassDeclaration>(declarations[0])
		val pair = assertIs<StructDeclaration>(declarations[1])
		for (parameters in listOf(assertNotNull(box.typeParameters), assertNotNull(pair.typeParameters))) {
			assertEquals(listOf("A", "B"), parameters.parameters.map { it.name.lexeme })
			assertEquals("<A, B>", text(parameters.span))
			assertEquals("<", text(parameters.opening.span))
			assertEquals(">", text(parameters.closing.span))
			assertEquals("B", text(parameters.parameters[1].span))
		}
		assertEquals("A", box.parameters.single().parameter.type.spelling)
	}

	@Test
	fun `nested arguments retain numeric widths nullable positions and fixed growable extents`() {
		val syntax = "Map<unsigned int<16>, Box<int[]?[]?>>[2]?"
		val annotation = type(syntax)
		assertEquals(syntax, annotation.spelling)
		assertNull(annotation.width)
		assertEquals(2, annotation.typeArguments.size)
		val integer = annotation.typeArguments[0]
		assertTrue(integer.unsigned)
		assertEquals("16", integer.width?.lexeme)
		assertNull(integer.application)
		assertEquals("int[]?[]?", annotation.typeArguments[1].typeArguments.single().spelling)
		assertEquals(listOf("2"), annotation.arraySizes.map { it?.lexeme })
		assertEquals(syntax, text(annotation.span))
		assertEquals("<unsigned int<16>, Box<int[]?[]?>>", text(assertNotNull(annotation.application).span))
	}

	@Test
	fun `multiline owner and argument lists retain complete UTF16 source spans`() {
		val declaration = assertIs<StructDeclaration>(unit("""
			// 😀 source prefix
			typedef<struct> Holder<
			    T,
			    U
			> { Pair<
			    T,
			    U[]?
			> value }
		""").declarations.single())
		assertContains(text(assertNotNull(declaration.typeParameters).span), "\n")
		val field = declaration.fields.single().type
		assertEquals("Pair<T, U[]?>", field.spelling)
		assertEquals("U[]?", text(field.typeArguments[1].span))
		assertSame(declaration.name.span.source, field.typeArguments[1].name.span.source)
	}

	@Test
	fun `type receivers retain generic applications through construction providers and postfix access`() {
		val read = assertIs<FieldAccessExpression>(expression("Box<List<int<129>[]>>::new(1).value"))
		val creation = assertIs<ClassCreationExpression>(read.receiver)
		assertEquals("Box<List<int<129>[]>>", assertIs<TypeReceiverExpression>(creation.receiver).annotation.spelling)
		assertEquals(1, creation.arguments.size)
		val metadata = assertIs<ProviderAccessExpression>(expression("Box<int[]>[2]?::type::rank"))
		val type = assertIs<ProviderAccessExpression>(metadata.receiver)
		assertEquals("Box<int[]>[2]?", assertIs<TypeReceiverExpression>(type.receiver).annotation.spelling)
	}

	@Test
	fun `generic base applications retain arguments separately from constructor arguments`() {
		val declaration = assertIs<ClassDeclaration>(unit("typedef<class> Derived<T> : Base<List<T>>(1) {}").declarations.single())
		val base = declaration.parents.single()
		assertEquals("Base<List<T>>", base.type.spelling)
		assertEquals("Base", base.type.name.lexeme)
		assertEquals(1, assertNotNull(base.constructorArguments).size)
		assertEquals(": Base<List<T>>(1)", text(base.span))
	}

	@Test
	fun `return lists optional wrappers selectors casts and checks preserve type applications`() {
		val declaration = assertIs<FunctionDeclaration>(unit("fn<Box<int>, Pair<string, int[]>> f(opt<Box<int>?> value ?: null) = null, null").declarations.single())
		assertEquals(listOf("Box<int>", "Pair<string, int[]>"), declaration.returnTypes.map { it.spelling })
		assertEquals("Box<int>?", declaration.parameters.single().type.spelling)
		assertTrue(declaration.parameters.single().optional)
		val cast = assertIs<CastExpression>(expression("value -> Box<List<int>>"))
		assertEquals("Box<List<int>>", cast.target.spelling)
		val check = assertIs<TypeCheckExpression>(assertIs<BinaryExpression>(expression("value is Box<int> && flag")).left)
		assertEquals("Box<int>", check.target.spelling)
		val box = assertIs<ClassDeclaration>(unit("typedef<class> Box<T> { operator<int<auto>> +() = 1 }").declarations.single())
		assertEquals("int<auto>", assertIs<MethodDeclaration>(box.members.single()).operator?.selector?.spelling)
	}

	@Test
	fun `ordinary comparisons and numeric casts do not become generic applications`() {
		val chain = assertIs<BinaryExpression>(expression("left < right > limit"))
		assertEquals(">", chain.operator.lexeme)
		assertEquals("<", assertIs<BinaryExpression>(chain.left).operator.lexeme)
		val numeric = assertIs<BinaryExpression>(expression("value -> int<8> < limit"))
		assertEquals("8", assertIs<CastExpression>(numeric.left).target.width?.lexeme)
		val named = assertIs<BinaryExpression>(expression("value -> Named < low > high"))
		assertEquals(">", named.operator.lexeme)
		assertNull(assertIs<CastExpression>(assertIs<BinaryExpression>(named.left).left).target.application)
		val parenthesized = assertIs<BinaryExpression>(expression("left < right > (limit)"))
		assertEquals(">", parenthesized.operator.lexeme)
		assertEquals("<", assertIs<BinaryExpression>(parenthesized.left).operator.lexeme)
	}

	@Test
	fun `function parameters and explicit call arguments retain their separate lists`() {
		val declaration = assertIs<FunctionDeclaration>(unit("pub fn<T> choose<T, U>(T value, U unused) = value").declarations.single())
		assertEquals("<T, U>", text(assertNotNull(declaration.typeParameters).span))
		assertEquals("T", declaration.returnType?.spelling)
		val call = assertIs<CallExpression>(expression("choose<Slot<unsigned int<16>>, int[]?>(value, null)"))
		val types = assertNotNull(call.typeArguments)
		assertEquals(listOf("Slot<unsigned int<16>>", "int[]?"), types.arguments.map { it.spelling })
		assertEquals(2, call.arguments.size)
	}

	@Test
	fun `ordinary methods and member calls preserve their type lists`() {
		val declarations = unit("""
			typedef<class> Box<T> (def<T> item) {
			    pub mt<T, U> pair<U: View<T>>(U value) = self.item, value
			}
			typedef<struct> Slot<T> {
			    T item;
			    mt<U> echo<U>(U value) = value
			}
		""").declarations
		val pair = assertIs<MethodDeclaration>(assertIs<ClassDeclaration>(declarations[0]).members.single())
		assertEquals("<U: View<T>>", text(assertNotNull(pair.typeParameters).span))
		assertEquals(listOf("T", "U"), pair.returnTypes.map { it.spelling })
		val echo = assertIs<StructDeclaration>(declarations[1]).methods.single()
		assertEquals("<U>", text(assertNotNull(echo.typeParameters).span))
		val call = assertIs<MemberCallExpression>(expression("box.pair<Slot<unsigned int<16>>, int[]?>(value=item)"))
		val types = assertNotNull(call.typeArguments)
		assertEquals("<Slot<unsigned int<16>>, int[]?>", text(types.span))
		assertEquals(listOf("Slot<unsigned int<16>>", "int[]?"), types.arguments.map { it.spelling })
		assertEquals("value", call.suppliedArguments.single().label?.lexeme)
	}

	@Test
	fun `generic member calls keep the comparison and postfix grammar`() {
		for (source in listOf("box.value < high > (low)", "box.value<high> (low)")) {
			val comparison = assertIs<BinaryExpression>(expression(source))
			assertEquals(">", comparison.operator.lexeme)
			val less = assertIs<BinaryExpression>(comparison.left)
			assertEquals("<", less.operator.lexeme)
			assertIs<FieldAccessExpression>(less.left)
		}
		val valueCheck = assertIs<BinaryExpression>(expression("value is outer.box.read<int>()"))
		assertIs<MemberCallExpression>(valueCheck.right)
		val typeCheck = assertIs<TypeCheckExpression>(expression("value is Outer.Box<int>"))
		assertEquals("Outer.Box<int>", typeCheck.target.spelling)
		val guarded = assertIs<GuardedAccessExpression>(expression("box?.read<int>().value"))
		val access = assertIs<FieldAccessExpression>(guarded.continuation)
		val call = assertIs<MemberCallExpression>(access.receiver)
		assertEquals("<int>", text(assertNotNull(call.typeArguments).span))
	}

	@Test
	fun `empty parameter lists and malformed argument lists fail at the first syntax error`() {
		for (source in listOf("typedef<class> Box<> {}", "typedef<struct> Box<T,> {}", "typedef<class> Box<T U> {}", "fn<Box<int,>> f() = null", "fn<Box<int> f() = null", "fn main() { Box<int,>::type }", "fn work<>() {}", "fn main() { work<int,>(1) }", "typedef<class> Box { mt work<>() {} }", "fn main() { box.work<int,>(1) }")) {
			val error = assertFailsWith<ClothDiagnostic>(source) { unit(source) }
			assertEquals(DiagnosticCode.EXPECTED_TOKEN, error.diagnosticCode, source)
			assertLocated(error)
		}
	}

	@Test
	fun `all generic declaration forms retain optional bound syntax and source spans`() {
		val declarations = unit("""
			typedef<class> Entity<T: Entity<T>> {}
			typedef<struct> Slot<T: Object, U> { T value, U other }
			typedef<interface> IHealth<T:
			    Number> {}
			fn read<T: IHealth<float>>(T value) {}
		""").declarations
		val entity = assertNotNull(assertIs<ClassDeclaration>(declarations[0]).typeParameters).parameters.single()
		val slot = assertNotNull(assertIs<StructDeclaration>(declarations[1]).typeParameters).parameters
		val health = assertNotNull(assertIs<InterfaceDeclaration>(declarations[2]).typeParameters).parameters.single()
		val read = assertNotNull(assertIs<FunctionDeclaration>(declarations[3]).typeParameters).parameters.single()
		assertEquals("T: Entity<T>", text(entity.span))
		assertEquals("U", text(slot[1].span))
		assertTrue(slot[1].bounds.isEmpty())
		assertEquals(listOf("Entity<T>", "Object", "Number", "IHealth<float>"), listOf(entity, slot[0], health, read).map { text(it.bounds.single().span) })
	}

	@Test
	fun `combined bounds preserve order and spans before a default and the next parameter`() {
		val declarations = unit("""
			typedef<struct> Pair<T: Entity<T> &
			    INamed = Player, N: Number> { T value, N count }
			typedef<class> Box {
			    mt inspect<U: INamed&Entity<U>>(U value) {}
			}
		""").declarations
		val (value, count) = assertNotNull(assertIs<StructDeclaration>(declarations[0]).typeParameters).parameters
		assertEquals(listOf("Entity<T>", "INamed"), value.bounds.map { text(it.span) })
		assertEquals("Player", text(assertNotNull(value.defaultType).span))
		assertEquals("T: Entity<T> &\n    INamed = Player", text(value.span))
		assertEquals("Number", text(count.bounds.single().span))

		val method = assertIs<MethodDeclaration>(assertIs<ClassDeclaration>(declarations[1]).members.single())
		val parameter = assertNotNull(method.typeParameters).parameters.single()
		assertEquals(listOf("INamed", "Entity<U>"), parameter.bounds.map { text(it.span) })
		assertEquals("U: INamed&Entity<U>", text(parameter.span))
		assertEquals("&&", assertIs<BinaryExpression>(expression("true && false")).operator.lexeme)
	}

	@Test
	fun `constructor guarantees retain parameter syntax and bound spans`() {
		val function = assertIs<FunctionDeclaration>(unit("""
			fn keep<E, T: Object & new() & new(E seed, opt<E> other ?: seed) = Player>(T value) {}
		""").declarations.single())
		val parameter = assertNotNull(function.typeParameters).parameters.last()
		assertEquals(listOf("Object", "new()", "new(E seed, opt<E> other ?: seed)"), parameter.bounds.map { text(it.span) })
		assertEquals("T: Object & new() & new(E seed, opt<E> other ?: seed) = Player", text(parameter.span))
		val empty = assertIs<ConstructorBound>(parameter.bounds[1]).declaration
		assertTrue(empty.parameters.isEmpty())
		val guarantee = assertIs<ConstructorBound>(parameter.bounds.last()).declaration
		assertEquals(listOf("seed", "other"), guarantee.parameters.map { it.name.lexeme })
		assertEquals(listOf("E", "E"), guarantee.parameters.map { it.type.spelling })
		assertTrue(guarantee.parameters.last().optional)
		assertEquals("seed", text(assertNotNull(guarantee.parameters.last().defaultValue).span))
	}

	@Test
	fun `a bound requires a type and ends before the next parameter`() {
		for ((source, token) in listOf(
			"typedef<class> Box<T:> {}" to ">",
			"typedef<class> Box<T: Number: Object> {}" to ":",
			"typedef<class> Box<T: Object &> {}" to ">",
			"typedef<class> Box<T: Object && INamed> {}" to "&&",
		)) {
			val error = assertFailsWith<ClothDiagnostic>(source) { unit(source) }
			assertEquals(DiagnosticCode.EXPECTED_TOKEN, error.diagnosticCode, source)
			assertEquals(token, text(error.sourceLabels.single().span))
		}
	}

	@Test
	fun `alternate default syntax enums and dispatched generic methods retain a syntax boundary`() {
		for (source in listOf(
			"typedef<struct> Box<T ?: int> {}", "fn work<T: Number ?: int>() {}",
			"typedef<enum> Mode<T> { One }",
			"typedef<class> Box { virtual: pub mt work<T>() {} }",
			"typedef<class> Box : Base { override: pub mt work<T>() {} }",
			"typedef<class> Box { state<void> Alive { mt work<T>() {} } }",
			"typedef<interface> Box { mt work<T>() }",
		)) {
			val error = assertFailsWith<ClothDiagnostic>(source) { unit(source) }
			assertEquals(DiagnosticCode.UNSUPPORTED_GENERIC_SYNTAX, error.diagnosticCode, source)
			assertLocated(error)
		}
	}

	@Test
	fun `nullable argument spelling is retained without admitting duplicate markers or opt arguments`() {
		assertEquals("Maybe<int?>?", type("Maybe<int?>?").spelling)
		val duplicate = assertFailsWith<ClothDiagnostic> { type("Maybe<int??>") }
		assertEquals(DiagnosticCode.EXPECTED_TOKEN, duplicate.diagnosticCode)
		val optional = assertFailsWith<ClothDiagnostic> { type("Box<opt<int>>") }
		assertEquals(DiagnosticCode.INVALID_GENERIC_ARGUMENT, optional.diagnosticCode)
		assertLocated(optional)
	}

	@Test
	fun `generic nesting permits 256 layers and rejects 257 before host recursion fails`() {
		val allowed = "Box<".repeat(256) + "int" + ">".repeat(256)
		assertEquals(allowed, type(allowed).spelling)
		for (source in listOf("fn<${"Box<".repeat(257)}int${">".repeat(257)}> f() = null", "fn main() { ${"Box<".repeat(257)}int${">".repeat(257)}::type }")) {
			val error = assertFailsWith<ClothDiagnostic> { unit(source) }
			assertEquals(DiagnosticCode.GENERIC_ARGUMENT_NESTING, error.diagnosticCode)
			assertLocated(error)
		}
	}
}
