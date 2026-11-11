package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.semantic.generics.*
import cloth.semantic.typing.FunctionAnalyzer
import cloth.token.Keywords
import cloth.token.Operators
import org.junit.jupiter.api.Test
import kotlin.test.*

class GenericCallableTypingTest {
	private fun analyze(source: String) = FunctionAnalyzer(genericTarget()).analyze(genericUnit(source))
	private fun failure(source: String, code: DiagnosticCode) = assertFailsWith<ClothDiagnostic> { analyze(source) }.also { assertEquals(code, it.diagnosticCode, it.summary); assertLocated(it) }

	@Test
	fun `two closed owners get exact method signatures and independent expression facts`() {
		val ast = genericUnit("""
			typedef<class> Box<T> (def<T> value) { pub mt<T> get() = value }
			fn main() {
			    def number = Box<int>::new(1)
			    def word = Box<string>::new("hello")
			    def first = number.get()
			    def second = word.get()
			    number::delete
			    word::delete
			}
		""")
		val before = ast.toString()
		val model = FunctionAnalyzer(genericTarget()).analyze(ast)
		val views = model.declarations.genericViews!!
		val boxes = views.types.instances.map { it.type }.filterIsInstance<CClass>()
		val originalMethod = (ast.declarations.first() as ClassDeclaration).members.single() as MethodDeclaration
		val originalExpression = (originalMethod.body as ExpressionBody).expression
		val expressions = boxes.map { views.viewOf(originalExpression, it) as Expression }
		assertNotSame(expressions[0], expressions[1])
		assertSame(originalExpression, views.originalOf(expressions[0]))
		assertEquals(listOf(ValueType.INT, ValueType.STRING), expressions.map(model::typeOf))
		assertNotNull(views.templates)
		assertEquals(before, ast.toString())
	}

	@Test
	fun `unused generic bodies reject names arithmetic equality members output and class capabilities`() {
		failure("typedef<class> Box<T> { mt<T> read(T value) = missing }\nfn main() {}", DiagnosticCode.UNKNOWN_NAME)
		failure("typedef<class> Box<T> { mt<T> read(T value) = value + 1 }\nfn main() {}", DiagnosticCode.BINARY_OPERANDS)
		failure("typedef<class> Box<T> { mt<bool> same(T a, T b) = a == b }\nfn main() {}", DiagnosticCode.INCOMPARABLE_TYPES)
		failure("typedef<class> Box<T> { mt read(T value) = value.missing() }\nfn main() {}", DiagnosticCode.UNKNOWN_MEMBER)
		failure("typedef<class> Box<T> { mt read(T value) = println(value) }\nfn main() {}", DiagnosticCode.INVALID_BUILTIN_ARGUMENT)
		failure("typedef<class> Box<T> { mt read(T value) = value::delete }\nfn main() {}", DiagnosticCode.INVALID_CLASS_LIFECYCLE)
		failure("typedef<class> Box<T> { mt read() = T::new }\nfn main() {}", DiagnosticCode.INVALID_CLASS_LIFECYCLE)
	}

	@Test
	fun `Number bounds grant numeric operations in unused templates`() {
		analyze("""
			fn<T> convert<T: Number, U: Number>(U value) = value -> T
			fn<T> add<T: Number>(T value) {
			    def<T?> one = 1 -> T
			    def total = value + one
			    total++
			    return total
			}
			fn<bool> same<T: Number>(T? left, T? right) = left == right
			fn<string> describe<T: Number>(T value) {
			    println(value)
			    return #"{value::str}: {value}"
			}
		""")
	}

	@Test
	fun `Number arithmetic requires exact operands and explicit numeric casts`() {
		failure("fn<T> add<T: Number>(T value) = value + 1", DiagnosticCode.BINARY_OPERANDS)
		failure("fn<T> add<T: Number, U: Number>(T left, U right) = left + right", DiagnosticCode.BINARY_OPERANDS)
		failure("fn<bool> same<T: Number>(T value) = value == 1", DiagnosticCode.INCOMPARABLE_TYPES)
		failure("fn<T> cast<T: Number>(bool value) = value -> T", DiagnosticCode.INVALID_CAST)
		failure("fn<int> cast<T>(T value) = value -> int", DiagnosticCode.INVALID_CAST)
	}

	@Test
	fun `nullable substitution preserves explicit receptions lazy fallback and proofs`() {
		analyze("""
			typedef<class> Maybe<T> (def<T?> value ?: null) {
			    pub mt<T?> get() = value
			    pub mt<T> identity(T input) { def<T> copied = input; return copied }
			    pub mt<T> fallback(T input) = value ?: input
			}
			fn main() {
			    def a = Maybe<int>::new
			    def b = Maybe<int?>::new
			    def<int?> first = a.get()
			    def<int?> second = b.identity(7)
			    def<int?> third = b.fallback(9)
			    a::delete
			    b::delete
			}
		""")
		failure("typedef<class> Box<T> { mt<T> read(T value) { def copy = value; return copy } }\nfn main() {}", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
	}

	@Test
	fun `public generic surfaces reject private helpers but private storage is allowed`() {
		analyze("typedef<struct> Helper<T> { T value }\npub typedef<class> Box<T> { def<Helper<T>?> value = null }\nfn main() {}")
		for (body in listOf("pub def<Helper<T>?> value = null", "pub mt<Helper<T>?> read() = null", "pub mt read(Helper<T> value) {}")) {
			failure("typedef<struct> Helper<T> { T value }\npub typedef<class> Box<T> { $body }\nfn main() {}", DiagnosticCode.PRIVATE_GENERIC_SIGNATURE)
		}
	}

	@Test
	fun `nullable wrappers do not grant unknown T storage or equality capabilities`() {
		failure("typedef<class> Box<T> { mt<usize> read(T? value) = value::size }\nfn main() {}", DiagnosticCode.UNKNOWN_PROVIDER)
		failure("typedef<class> Box<T> { mt<bool> same(T? a, T? b) = a == b }\nfn main() {}", DiagnosticCode.INCOMPARABLE_TYPES)
	}

	@Test
	fun `public primary secondary and struct signatures reject private helpers even unused`() {
		val helper = "typedef<struct> Helper<T> { T value }\n"
		for (declaration in listOf(
			"pub typedef<class> Box<T> (def<Helper<T>?> value ?: null)",
			"pub typedef<class> Box<T> { Box(Helper<T> value) : () {} }",
			"pub typedef<struct> Box<T> { Helper<T>? value }",
			"pub typedef<struct> Box<T> { T value; mt<Helper<T>?> read() = null }"
		)) failure(helper + declaration + "\nfn main() {}", DiagnosticCode.PRIVATE_GENERIC_SIGNATURE)
	}

	@Test
	fun `public inferred operator results cannot expose private helper storage`() {
		failure("""
			typedef<struct> Helper<T> { T value }
			pub typedef<class> Box<T> (T value) {
			    def<Helper<T>> item = { value }
			    pub operator +(Box<T> other) = item
			}
			fn main() {}
		""", DiagnosticCode.PRIVATE_GENERIC_SIGNATURE)
	}

	@Test
	fun `type parameters take precedence over same spelled value names in is targets`() {
		analyze("""
			typedef<class> Box<T> { pub mt<bool> check(T value, int T) = value is T }
			fn main() { def b = Box<int>::new; b.check(1, 2); b::delete }
		""")
	}

	@Test
	fun `symbolic expanding declarations stop before unbounded work without a closed use`() {
		failure("typedef<class> Expands<T> (def<Expands<T[]>?> next ?: null)\nfn main() {}", DiagnosticCode.GENERIC_TEMPLATE_DEPENDENCY_LIMIT)
	}

	@Test
	fun `intrinsic template failures retain the first source error through overload candidates`() {
		val error = failure("""
			typedef<class> Box<T> {
			    mt take(int value) {}
			    mt take(string value) {}
			    mt bad(T value) { take(1 / 0); missing }
			}
			fn main() {}
		""", DiagnosticCode.UNKNOWN_NAME)
		assertContains(error.summary, "missing") // Name binding stops before later expression typing.
		failure("""
			typedef<class> Box<T> {
			    mt take(int value) {}
			    mt take(string value) {}
			    mt bad(T value) { take(1 / 0) }
			}
			fn main() {}
		""", DiagnosticCode.INTEGER_ZERO_DIVISOR)
	}

	@Test
	fun `shared argument trees have bounded display without becoming signature keys`() {
		val declaration = genericUnit("typedef<class> Pair<A, B> {}").declarations.single() as ClassDeclaration
		var argument: ValueType = ValueType.INT
		repeat(40) { argument = CClass(declaration, typeArguments = listOf(argument, argument)) }
		val display = argument.spelling
		assertTrue(display.length <= 4096)
		assertTrue(display.endsWith('…'))
		assertFalse(argument.hasTypeParameters())
		val sameDisplay = CClass(declaration, typeArguments = (argument as CClass).typeArguments)
		assertEquals(argument.spelling, sameDisplay.spelling)
		assertNotEquals(cloth.semantic.symbols.SignatureType.resolved(argument), cloth.semantic.symbols.SignatureType.resolved(sameDisplay))
		val unicode = declaration.copy(name = cloth.token.Token(cloth.token.TokenType.Identifier, declaration.name.span, "𐐀".repeat(3000)))
		val shortened = CClass(unicode, typeArguments = listOf(ValueType.INT)).spelling
		assertTrue(shortened.length <= 4096)
		assertTrue(shortened.endsWith('…'))
		assertFalse(shortened[shortened.lastIndex - 1].isHighSurrogate())
	}

	@Test
	fun `symbolic and closed instance quotas are independent at the exact boundary`() {
		fun owners(count: Int) = (0 until count).joinToString("\n") { index -> "${Keywords.TYPEDEF}${Operators.LESS}${Keywords.CLASS}${Operators.GREATER} G$index${Operators.LESS}T${Operators.GREATER} ${Operators.BRACE_OPEN}${Operators.BRACE_CLOSE}" } + "\nfn main() {}"
		assertEquals(0, analyze(owners(4096)).declarations.genericViews!!.types.instances.size)
		failure(owners(4097), DiagnosticCode.GENERIC_TEMPLATE_INSTANCE_LIMIT)
		val uses = (1..4096).joinToString("\n") { bits -> "Box${Operators.LESS}${Keywords.INTEGER}${Operators.LESS}$bits${Operators.GREATER}${Operators.GREATER}${Operators.COLON_COLON}type" }
		assertEquals(4096, analyze("typedef<class> Box<T> {}\nfn main() {\n$uses\n}").declarations.genericViews!!.types.instances.size)
	}

	@Test
	fun `64 symbolic dependencies reuse identities before charging another step`() {
		val owners = (0 until 64).joinToString("\n") { index ->
			val next = (index + 1) % 64
			"${Keywords.TYPEDEF}${Operators.LESS}${Keywords.CLASS}${Operators.GREATER} N$index${Operators.LESS}T${Operators.GREATER} (${Keywords.DEFINITION}${Operators.LESS}N$next${Operators.LESS}T${Operators.GREATER}${Operators.QUESTION}${Operators.GREATER} next ${Operators.ELVIS} ${Keywords.NULL})"
		}
		val model = analyze(owners + "\nfn main() {}")
		val templates = model.declarations.genericViews!!.templates!!.declarations.genericViews!!.types
		assertEquals(4096, templates.instances.size)
		assertTrue(templates.instances.all { it.type.hasTypeParameters() })
	}
}
