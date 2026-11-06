package cloth

import cloth.data.CNullable
import cloth.data.ValueType
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.expression.CallExpression
import cloth.parser.expression.IfExpression
import cloth.parser.expression.ProviderAccessExpression
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class NullabilityTypingTest {

	private fun parse(source: String) = Parser(Lexer(SourceFile("nullable-typing.co", source.trimIndent())).scanTokens()).parse()
	private fun valid(source: String) = FunctionAnalyzer(nativeTestLayout).analyze(parse(source))
	private fun failure(source: String, code: DiagnosticCode) {
		val error = assertFailsWith<ClothDiagnostic> { valid(source) }
		assertEquals(code, error.diagnosticCode, error.summary)
		assertLocated(error)
	}

	@Test
	fun `nullable bindings require explicit identity and keep strict payload types`() {
		valid("""
			fn<int<64>?> maybe(bool found) = if (found) { 25 } else { null }
			fn test(int<64>? input) {
				def<int<64>?> value = 25
				value = null
				value = input
				def<int<64>?> returned = maybe(true)
				def<int<64>?> copy = value
				def<bool?> flag := null
				println(flag)
			}
		""")
		failure("fn test(int? input) { def copy = input }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
		failure("fn<int?> maybe() = null\nfn test() { def result = maybe() }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
		failure("fn test() { def result = null }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
		failure("fn test(int input) { def<int<64>?> result = input }", DiagnosticCode.TYPE_MISMATCH)
		failure("fn test(int<64> input) { def<usize?> result = input }", DiagnosticCode.TYPE_MISMATCH)
		failure("fn test() { def<int> result = null }", DiagnosticCode.TYPE_MISMATCH)
		failure("fn<int> result() = null", DiagnosticCode.RETURN_TYPE)
		failure("fn test() { def<int?> result := null\nresult = 1 }", DiagnosticCode.IMMUTABLE_BINDING)
	}

	@Test
	fun `nullable conditionals require declared destinations even with typed branches`() {
		valid("""
			fn<int?> pick(bool flag, int? existing) = if (flag) { existing } else { 25 }
			fn consume(int? input) {}
			fn test(bool flag) {
				def<int?> first = if (flag) { 5 } else { null }
				def<int?> second = if (flag) { null } else { first }
				consume(if (flag) { null } else { 5 })
			}
		""")
		for (branches in listOf("{ 5 } else { null }", "{ null } else { 5 }", "{ null } else { null }", "{ existing } else { 5 }", "{ existing } else { existing }")) {
			failure("fn test(bool flag, int? existing) { def inferred = if (flag) $branches }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
		}
		failure("fn test() { def inferred = if (true) { 5 } else { null } }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
		failure("fn choose(int? value) {}\nfn choose(bool? value) {}\nfn test(bool flag) { choose(if (flag) { null } else { null }) }", DiagnosticCode.AMBIGUOUS_CALL)
	}

	@Test
	fun `proofs narrow individual reads and copies but preserve source metadata`() {
		val ast = parse("""
			fn test(int? input) {
				if (input == null) { return }
				def copy = input
				println(input::type)
				println(input::str)
				input = null
				println(copy + 1)
				println(input)
			}
		""")
		val model = FunctionAnalyzer(nativeTestLayout).analyze(ast)
		val body = assertIs<BlockBody>(assertIs<FunctionDeclaration>(ast.declarations.single()).body).statements
		assertEquals(ValueType.INT, model.typeOf(body[1]))
		val metadata = assertIs<ProviderAccessExpression>(assertIs<CallExpression>(assertIs<ExpressionStatement>(body[2]).expression).arguments.single())
		assertEquals(CNullable(ValueType.INT), model.typeOf(metadata.receiver))
		assertEquals(ValueType.TYPEINFO, model.typeOf(metadata))
		val formatted = assertIs<ProviderAccessExpression>(assertIs<CallExpression>(assertIs<ExpressionStatement>(body[3]).expression).arguments.single())
		assertEquals(ValueType.INT, model.typeOf(formatted.receiver))
		failure("fn test(int? input) { println(input + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		failure("fn test(int? input) { if (input != null) { input = null\nprintln(input::str) } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		failure("fn test(bool? flag) { if (flag) {} }", DiagnosticCode.NON_BOOLEAN_CONDITION)
	}

	@Test
	fun `negation short circuits joins and early returns preserve sound proofs`() {
		valid("""
			fn<int> consume(int? input, bool flag) {
				if (!(input != null)) { return 0 }
				if (input != null && input > 0) { println(input::str) }
				if (input == null || input == 0) { return 0 }
				if (flag) { input = 1 } else { input = 2 }
				input++
				return input + 1
			}
		""")
		failure("fn test(int? input, bool flag) { if (flag) { input = 1 }\nprintln(input + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		failure("fn test(int? input) { input++ }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		failure("fn test(int? input) { if (input != null || true) { println(input + 1) } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `deferred declarations do not execute effects or inherit mutable capture proofs`() {
		valid("""
			fn test(int? input) {
				if (input == null) { return }
				def saved = input
				def<int> later = { return saved + 1 }
				def<int> reset = { input = null
					return 0 }
				println(input + 1)
				println(input + reset)
				println(later)
				println(input)
			}
		""")
		failure("fn test(int? input) { if (input != null) { def<int> later = { return input + 1 }\ninput = null\nprintln(later) } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		failure("fn test(int? input) { if (input == null) { return }\ndef<int> reset = { input = null\nreturn 0 }\nprintln(reset + input) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		valid("fn test(int? input) { def<int> later = { if (input == null) { return 0 }\nreturn input + 1 }\nprintln(later) }")
		failure("fn test() { def<int?> changing = { return null }\nif (changing != null) { println(changing + 1) } }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
	}

	@Test
	fun `equality accepts all nullable presence combinations but rejects nonnullable null tests`() {
		valid("""
			fn test(int? first, int? second, bool? flag, typeinfo? info) {
				println(first == second)
				println(first != second)
				println(first == 5)
				println(5 != first)
				println(null == first)
				println(first != null)
				println(flag == false)
				println(info == int::type)
				if (first != null) { println(first == null) }
			}
		""")
		for (test in listOf("5 == null", "null != 5", "null == null")) failure("fn test() { println($test) }", DiagnosticCode.INVALID_NULL_COMPARISON)
		failure("fn test(int? value, int<64> other) { println(value == other) }", DiagnosticCode.INCOMPARABLE_TYPES)
	}

	@Test
	fun `Elvis has strict result typing checks skipped source and does not create implicit nullable bindings`() {
		val ast = parse("""
			fn test(int? input, int? other) {
				def payload = input ?: 0
				def<int?> maybe = input ?: other
				def<int?> absent = input ?: null
				if (input != null) { println(input ?: 1) }
			}
		""")
		val model = FunctionAnalyzer(nativeTestLayout).analyze(ast)
		val body = assertIs<BlockBody>(assertIs<FunctionDeclaration>(ast.declarations.single()).body).statements
		assertEquals(ValueType.INT, model.typeOf(body[0]))
		assertEquals(CNullable(ValueType.INT), model.typeOf(body[1]))
		assertEquals(CNullable(ValueType.INT), model.typeOf(body[2]))
		failure("fn test(int? value, int? other) { def inferred = value ?: other }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
		failure("fn test() { println(5 ?: 0) }", DiagnosticCode.INVALID_ELVIS)
		failure("fn test(int? value) { println(value ?: true) }", DiagnosticCode.INVALID_ELVIS)
		failure("fn test(int? value) { println(value ?: missing) }", DiagnosticCode.UNKNOWN_NAME)
		valid("fn test(bool flag, int? value) { def<int?> result = (if (flag) { value } else { null }) ?: 0 }")
	}

	@Test
	fun `overloads preserve default literal preference and change only proven argument occurrences`() {
		val ast = parse("""
			fn<int> pick(int? value) = 1
			fn<int> pick(int<64> value) = 2
			fn<int> narrowed(int value) = 3
			fn<int> narrowed(int? value) = 4
			fn test(int? value) {
				pick(25)
				narrowed(value)
				if (value != null) { narrowed(value) }
			}
		""")
		val model = FunctionAnalyzer(nativeTestLayout).analyze(ast)
		val body = assertIs<BlockBody>(assertIs<FunctionDeclaration>(ast.declarations.last()).body).statements
		val literal = assertIs<CallExpression>(assertIs<ExpressionStatement>(body[0]).expression)
		val before = assertIs<CallExpression>(assertIs<ExpressionStatement>(body[1]).expression)
		val guard = assertIs<IfExpression>(assertIs<ExpressionStatement>(body[2]).expression)
		val after = assertIs<CallExpression>(assertIs<ExpressionStatement>(guard.branches.single().statements.single()).expression)
		assertEquals(listOf(CNullable(ValueType.INT)), model.typeOf(model.selectedFunction(literal)).parameters)
		assertEquals(listOf(CNullable(ValueType.INT)), model.typeOf(model.selectedFunction(before)).parameters)
		assertEquals(listOf(ValueType.INT), model.typeOf(model.selectedFunction(after)).parameters)
		failure("fn pick(int? value) {}\nfn pick(bool? value) {}\nfn test() { pick(null) }", DiagnosticCode.AMBIGUOUS_CALL)
	}

	@Test
	fun `formatting is absence aware while explicit str and casts still require proof`() {
		valid("fn test(int? value) { print(null)\nprintln(value)\nprintln(#\"value={value}; literal={null}\") }")
		failure("fn test(int? value) { println(value::str) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		failure("fn test(int? value) { println(value -> int) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		valid("fn test(int? value) { value -> void\nif (value != null) { println(value -> int<64>) }\nprintln((value ?: 0) -> int<64>) }")
		valid("fn test() { 5 -> int?\nnull -> int?\nprintln(int?::type::isnumeric) }")
		valid("fn test(int? value) { println(value is int?) }")
		failure("fn take(int value) {}\nfn test(int? value) { take(value + 1) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		failure("fn take(int value) {}\nfn take(bool value) {}\nfn test(int? value) { take(value -> int) }", DiagnosticCode.NULLABLE_PAYLOAD_ACCESS)
		failure("fn take(bool value) {}\nfn test() { take(5 == null) }", DiagnosticCode.INVALID_NULL_COMPARISON)
	}

	@Test
	fun `named nullable result positions require annotations but inferred discards create no bindings`() {
		valid("fn<int?, bool> pair() = null, true\nfn test() { def<int?, bool> value, ready = pair()\ndef _, otherReady = pair() }")
		failure("fn<int?, bool> pair() = null, true\nfn test() { def value, ready = pair() }", DiagnosticCode.EXPLICIT_NULLABLE_TYPE)
	}
}
