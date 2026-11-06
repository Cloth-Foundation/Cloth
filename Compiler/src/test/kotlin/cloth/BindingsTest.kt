package cloth

import cloth.emitter.LLVMEmitter
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.exception.SyntaxException
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.BooleanLiteral
import cloth.parser.expression.UnaryExpression
import cloth.parser.statement.AssignmentStatement
import cloth.parser.statement.BindingDeclaration
import cloth.parser.statement.ExpressionStatement
import cloth.semantic.SemanticException
import cloth.semantic.typing.FunctionAnalyzer
import cloth.token.Operators
import cloth.token.TokenType
import cloth.util.ExecutableFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class BindingsTest {

	@Test
	fun `declarations preserve explicit types and binding mutability`() {
		val program = parseMain("""
			def<int> x = 10
			def<int> original := x
			x = x + 5
		""")
		val statements = program.mainStatements
		val mutable = assertIs<BindingDeclaration>(statements[0])
		val immutable = assertIs<BindingDeclaration>(statements[1])
		assertEquals("int", assertNotNull(mutable.type).name.lexeme)
		assertEquals("x", mutable.name.lexeme)
		assertTrue(mutable.mutable)
		assertFalse(immutable.mutable)
		assertIs<AssignmentStatement>(statements[2])
		FunctionAnalyzer(nativeTestLayout).analyze(program)
	}

	@Test
	fun `inferred declarations omit the annotation and preserve mutability`() {
		val program = parseMain("""
			def x = 10
			def y := x + 2
			x = y + 5
		""")
		val mutable = assertIs<BindingDeclaration>(program.mainStatements[0])
		val immutable = assertIs<BindingDeclaration>(program.mainStatements[1])
		assertNull(mutable.type)
		assertNull(immutable.type)
		assertTrue(mutable.mutable)
		assertFalse(immutable.mutable)
		FunctionAnalyzer(nativeTestLayout).analyze(program)
	}

	@Test
	fun `angle parser supports caller defined contents and nesting`() {
		val parser = Parser(Lexer("<outer<inner>>").scanTokens())
		fun parseName(): String {
			val name = parser.expect(TokenType.Identifier, "a name").lexeme
			return if (parser.peek().lexeme == Operators.LESS.toString()) {
				val nested = parser.delimited.angles { parseName() }
				"$name($nested)"
			} else name
		}
		assertEquals("outer(inner)", parser.delimited.angles { parseName() })
		assertEquals(TokenType.EOF, parser.peek().type)
	}

	@Test
	fun `malformed binding syntax is rejected`() {
		val cases = listOf("def x", "def x =", "def y :=", "def = 10", "def<> x = 10", "def<int x = 10", "def<int>> x = 10", "def<int> = 10", "def<int> x 10", "def<int> x =", "def<int> x = (1 + 2", "def<int> x = 1 def<int> y = 2", "x := 10")
		for (source in cases) {
			assertFailsWith<SyntaxException>(source) { parseMain(source) }
		}
	}

	@Test
	fun `inferred bindings keep their initializer type and ordinary binding rules`() {
		val cases = listOf("def x = 10\nx = 1.5" to "Cannot assign 'float' to 'int'", "def x = 10\ndef y := x + 2\nx = y / 2.0" to "Cannot assign 'float' to 'int'", "def x = 10\nx = x + 1\nx = 1.5" to "Cannot assign 'float' to 'int'", "def x = 1.5\nx = 10" to "Cannot assign 'int' to 'float'", "def x = 1 + 0.5\ndef y = -x\ny = 2" to "Cannot assign 'int' to 'float'", "def x := 10\nx = 20" to "Cannot reassign immutable binding 'x'", "def x := 1.5\nx = 2.5" to "Cannot reassign immutable binding 'x'", "def x = x + 1" to "Unknown name 'x'", "def x := missing + 2" to "Unknown name 'missing'", "def x = 1\ndef<int> x = 2" to "already declared", "def<int> x = 1\ndef x := 2" to "already declared", "def x = 1.5\ndef<int> y = x" to "Cannot assign 'float' to 'int'")
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) {
				// The public emission path must reject mismatches before generating LLVM.
				LLVMEmitter().emit(parseMain(source))
			}
			assertContains(failure.message.orEmpty(), diagnostic)
			assertLocated(failure)
		}
	}

	@Test
	fun `invalid binding uses report semantic errors with locations`() {
		val cases = listOf("missing + 1" to "Unknown name 'missing'", "missing = 1" to "Unknown name 'missing'", "def<int> x = x + 1" to "Unknown name 'x'", "def<int> x = later\ndef<int> later = 1" to "Unknown name 'later'", "def<int> x = 1\ndef<int> x = 2" to "already declared", "def<int> x := 1\nx = 2" to "Cannot reassign immutable binding 'x'", "def<int> x = 1.5" to "Cannot assign 'float' to 'int'", "def<int> x = 1\nx = x + 0.5" to "Cannot assign 'float' to 'int'", "def<bool> x = 1" to "Cannot assign 'int' to 'bool'", "def<Widget> x = 1" to "not supported yet")
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) {
				FunctionAnalyzer(nativeTestLayout).analyze(parseMain(source))
			}
			assertContains(failure.message.orEmpty(), diagnostic)
			assertLocated(failure)
		}
	}

	@Test
	fun `emitter checks bindings before emitting LLVM`() {
		assertFailsWith<SemanticException> {
			LLVMEmitter().emit(parseMain("def<int> x := 1\nx = 2"))
		}
	}

	@Test
	fun `analysis state belongs to one program`() {
		val analyzer = FunctionAnalyzer(nativeTestLayout)
		analyzer.analyze(parseMain("def<int> x = 1"))
		analyzer.analyze(parseMain("def<int> x := 2"))
		assertFailsWith<SemanticException> { analyzer.analyze(parseMain("x")) }
	}

	@Test
	fun `native bindings read current values and preserve integer copies`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("bindings"), """
			def<int> x = 10
			def<int> original := x
			def<int> y = 3
			x = (x + y) * 2
			y = -x + original % y
			x = x / 2
			println(original * 100 + x * 10 + y)
		""", "1105\n")
	}

	@Test
	fun `bindings and existing math examples compile through the CLI`(@TempDir directory: Path) {
		for (file in listOf("Bindings.co", "Math.co", "Booleans.co", "Unsigned.co", "Casts.co", "Characters.co", "Strings.co", "Functions.co", "StringFunctions.co")) {
			val output = directory.resolve(file.removeSuffix(".co"))
			// Absolute paths exercise source selection and the separate output argument.
			assertEquals(0, runCompiler(arrayOf(Path.of("examples", file).toAbsolutePath().toString(), output.toString())), file)
			assertEquals(processCompletion(), runExecutable(ExecutableFormat.current.executablePath(output)))
		}
	}

	@Test
	fun `integer bindings can participate in existing floating point arithmetic`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("mixed"), "def<int> x = 9\nprintln(x / 2.0)", "4.5\n")
	}

	@Test
	fun `inferred integer expressions support reassignment and copy values`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("inferred_int"), """
			def x = 10
			def y := x + 2
			def half = y / 5
			x = -(-x) + half
			half = half * 3
			println(x * 100 + y * 10 + half)
		""", "1326\n")
	}

	@Test
	fun `inferred floats preserve storage types across reads and reassignment`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("inferred_float"), """
			def scale = 1.5
			def original := scale
			def<int> count := 3
			def mixed := count + scale
			scale = (scale + 0.75)
			println(original + mixed + scale)
		""", "8.25\n")
	}

	@Test
	fun `explicit floats enforce declared types and immutability`() {
		val cases = listOf("def<float> x = 1" to "Cannot assign 'int' to 'float'", "def<float> x := 1" to "Cannot assign 'int' to 'float'", "def x = 10\ndef<float> y = x" to "Cannot assign 'int' to 'float'", "def<float> x = 1.5\nx = 2" to "Cannot assign 'int' to 'float'", "def<float> x = 1.5\nx = 2.0\nx = 2 / 2" to "Cannot assign 'int' to 'float'", "def<float> x := 1.5\nx = 2.0" to "Cannot reassign immutable binding 'x'", "def<float> x = 1.5\ndef<int> y = x" to "Cannot assign 'float' to 'int'", "def<float> x = 1.5\ndef y = 1\ny = x" to "Cannot assign 'float' to 'int'", "def<float> x = x + 1.0" to "Unknown name 'x'", "def x = 1.5\ndef<float> x = 2.0" to "already declared")
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) {
				FunctionAnalyzer(nativeTestLayout).analyze(parseMain(source))
			}
			assertContains(failure.message.orEmpty(), diagnostic)
			assertLocated(failure)
		}
	}

	@Test
	fun `explicit and inferred floats share arithmetic and preserve copied values`(@TempDir directory: Path) {
		assertMainOutput(directory.resolve("explicit_float"), """
			def<float> scale = 1.5
			def offset = 0.25
			def<float> snapshot := scale
			def mixed := scale + offset
			def<float> fromInferred := mixed
			scale = mixed * 2
			offset = scale / 2
			println(snapshot + fromInferred + scale + offset)
		""", "8.5\n")
	}

	@Test
	fun `boolean literals support explicit and inferred bindings`() {
		val program = parseMain("""
			def<bool> alive = true
			def stopped := (false)
			alive = stopped
		""")
		val explicit = assertIs<BindingDeclaration>(program.mainStatements[0])
		val inferred = assertIs<BindingDeclaration>(program.mainStatements[1])
		assertEquals("bool", assertNotNull(explicit.type).name.lexeme)
		assertEquals(true, assertIs<BooleanLiteral>(explicit.initializer).value)
		assertTrue(explicit.mutable)
		assertNull(inferred.type)
		assertEquals(false, assertIs<BooleanLiteral>(inferred.initializer).value)
		assertFalse(inferred.mutable)
		FunctionAnalyzer(nativeTestLayout).analyze(program)
	}

	@Test
	fun `booleans reject numeric conversions arithmetic and immutable assignment`() {
		val cases = listOf("def<bool> x = 0" to "Cannot assign 'int' to 'bool'", "def<bool> x = 1.0" to "Cannot assign 'float' to 'bool'", "def<int> x = true" to "Cannot assign 'bool' to 'int'", "def<float> x = false" to "Cannot assign 'bool' to 'float'", "def x = true\nx = 1" to "Cannot assign 'int' to 'bool'", "def x = false\nx = 1.0" to "Cannot assign 'float' to 'bool'", "def x = 1\nx = false" to "Cannot assign 'bool' to 'int'", "def x = 1.0\nx = true" to "Cannot assign 'bool' to 'float'", "def<bool> x := true\nx = false" to "Cannot reassign immutable binding 'x'", "def x := false\nx = true" to "Cannot reassign immutable binding 'x'", "def x = true\ndef x = false" to "already declared", "def x = x" to "Unknown name 'x'", "def x = later\ndef later = true" to "Unknown name 'later'") + listOf("+true", "-false").map { it to "requires a numeric operand" } + listOf("+", "-", "*", "/", "%").flatMap { operator ->
			listOf("true $operator false", "true $operator 1", "1.0 $operator false", "1 $operator true").map { it to "requires numeric operands" }
		}
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) { FunctionAnalyzer(nativeTestLayout).analyze(parseMain(source)) }
			assertContains(failure.message.orEmpty(), diagnostic)
			assertLocated(failure)
		}
	}

	@Test
	fun `each boolean binding owns a byte even with interleaved numeric bindings`() {
		for (count in listOf(1, 2, 8, 9, 16, 17)) {
			val source = (0 until count).joinToString("\n") {
				"def flag$it = true\ndef number$it = $it\ndef scale$it = 1.5"
			} + "\nflag0 = flag${count - 1}\nflag0"
			val ir = LLVMEmitter().emit(parseMain(source))
			assertEquals(count, Regex("alloca i8\\b").findAll(ir).count(), ir)
			assertFalse(ir.contains("alloca i1,"), ir)
			assertContains(ir, "to i1")
			assertContains(ir, "zext i1")
			assertFalse(Regex("\\b(lshr|shl|and|or) i8\\b").containsMatchIn(ir), ir)
		}
	}

	@Test
	fun `boolean updates preserve independent bindings and copied values`(@TempDir directory: Path) {
		val source = """
			def<bool> a = true
			def<int> count = 7
			def b = false
			def<float> scale = 1.5
			def c = true
			def d = false
			def e = true
			def f = false
			def g = true
			def h = false
			def i = true
			def snapshot := a
			a = false
			b = true
			c = b
			d = a
			e = false
			f = true
			g = false
			h = true
			i = false
			h = h
		""".trimIndent()
		val expected = listOf(false, true, true, false, false, true, false, true, false, true)
		for ((index, name) in listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "snapshot").withIndex()) {
			assertMainOutput(directory.resolve("independent_$name"), "$source\nprintln($name)", "${expected[index]}\n")
		}
	}

	@Test
	fun `boolean literals and reassigned bindings print as boolean values`(@TempDir directory: Path) {
		val cases = listOf("println(true)" to true, "println(false)" to false, "def x = true\nx = false\nprintln(x)" to false, "def<bool> x = false\nx = true\nprintln(x)" to true)
		for ((index, case) in cases.withIndex()) {
			val (source, expected) = case
			assertMainOutput(directory.resolve("bool_$index"), source, "$expected\n")
		}
	}

	@Test
	fun `boolean negation parses nested prefix operands and requires an operand`() {
		val declaration = assertIs<BindingDeclaration>(parseMain("def inverse := !!(false)").mainStatements.single())
		val outer = assertIs<UnaryExpression>(declaration.initializer)
		assertEquals("!", outer.operator.lexeme)
		val inner = assertIs<UnaryExpression>(outer.operand)
		assertEquals("!", inner.operator.lexeme)
		assertFalse(assertIs<BooleanLiteral>(inner.operand).value)
		for (source in listOf("!", "!!", "!()", "def inverse = !", "true!")) {
			assertFailsWith<SyntaxException>(source) { parseMain(source) }
		}
	}

	@Test
	fun `boolean negation rejects numeric operands and preserves type rules`() {
		val cases = listOf("!0" to "requires a boolean operand, got 'int'", "!!1" to "requires a boolean operand, got 'int'", "!-1" to "requires a boolean operand, got 'int'", "!1.5" to "requires a boolean operand, got 'float'", "!(1 + 2)" to "requires a boolean operand, got 'int'", "def number = 1.0\n!number" to "requires a boolean operand, got 'float'", "!missing" to "Unknown name 'missing'", "def<int> x = !true" to "Cannot assign 'bool' to 'int'", "def<float> x = !false" to "Cannot assign 'bool' to 'float'", "def x = !true\nx = 1" to "Cannot assign 'int' to 'bool'", "def x := !true\nx = !x" to "Cannot reassign immutable binding 'x'", "-!true" to "requires a numeric operand", "!+false" to "requires a numeric operand", "!true + 1" to "requires numeric operands")
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) { FunctionAnalyzer(nativeTestLayout).analyze(parseMain(source)) }
			assertContains(failure.message.orEmpty(), diagnostic)
			assertLocated(failure)
		}
	}

	@Test
	fun `native boolean negation handles literals nesting and current binding values`(@TempDir directory: Path) {
		val cases = listOf("println(!true)" to false, "println(!false)" to true, "println(!!false)" to false, "println(!!!false)" to true, "println(!(!(true)))" to true, "def<bool> alive = true\ndef inverse := !alive\nprintln(inverse)" to false, "def alive = false\ndef<bool> inverse := !alive\nprintln(inverse)" to true, "def alive = false\nalive = !alive\nprintln(alive)" to true, "def alive = true\nalive = !alive\nalive = !alive\nprintln(alive)" to true, "def alive = true\ndef snapshot := !alive\nalive = !alive\nprintln(!snapshot)" to true)
		for ((index, case) in cases.withIndex()) {
			val (source, expected) = case
			assertMainOutput(directory.resolve("negation_$index"), source, "$expected\n")
		}
	}

	@Test
	fun `logical and equality tokens remain distinct from assignment and negation`() {
		val operators = Lexer("!true != false && !false || true == false = true").scanTokens().filter { it.type == TokenType.Operator }.map { it.lexeme }
		assertEquals(listOf("!", "!=", "&&", "!", "||", "==", "="), operators)
		val ordering = Lexer("1 < 2 > 0 <= 3 >= 1").scanTokens().filter { it.type == TokenType.Operator }.map { it.lexeme }
		assertEquals(listOf("<", ">", "<=", ">="), ordering)
	}

	@Test
	fun `logical operators respect precedence parentheses and left associativity`() {
		fun expression(source: String) = assertIs<ExpressionStatement>(parseMain(source).mainStatements.single()).expression
		val mixed = assertIs<BinaryExpression>(expression("!true || false && true"))
		assertEquals("||", mixed.operator.lexeme)
		assertIs<UnaryExpression>(mixed.left)
		assertEquals("&&", assertIs<BinaryExpression>(mixed.right).operator.lexeme)
		val grouped = assertIs<BinaryExpression>(expression("(true || false) && true"))
		assertEquals("&&", grouped.operator.lexeme)
		assertEquals("||", assertIs<BinaryExpression>(grouped.left).operator.lexeme)
		for (operator in listOf("&&", "||")) {
			val chain = assertIs<BinaryExpression>(expression("true $operator false $operator true"))
			assertEquals(operator, assertIs<BinaryExpression>(chain.left).operator.lexeme)
			assertIs<BooleanLiteral>(chain.right)
		}
		for (source in listOf("true &&", "|| false", "true || || false", "true && ()")) {
			assertFailsWith<SyntaxException>(source) { parseMain(source) }
		}
	}

	@Test
	fun `logical operators type check both operands even when short circuited`() {
		val cases = listOf("false && 1" to "requires boolean operands", "true || 0.0" to "requires boolean operands", "1 && true" to "requires boolean operands", "1.5 || false" to "requires boolean operands", "1 && 2.0" to "requires boolean operands", "false && missing" to "Unknown name 'missing'", "true || missing" to "Unknown name 'missing'", "def<int> x = true && false" to "Cannot assign 'bool' to 'int'", "def<float> x = false || true" to "Cannot assign 'bool' to 'float'", "def x = true && false\nx = 1" to "Cannot assign 'int' to 'bool'", "def x := false || true\nx = false" to "Cannot reassign immutable binding 'x'", "true || false + 1" to "requires numeric operands", "-(true && false)" to "requires a numeric operand")
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) { FunctionAnalyzer(nativeTestLayout).analyze(parseMain(source)) }
			assertContains(failure.message.orEmpty(), diagnostic)
			assertLocated(failure)
		}
	}

	@Test
	fun `short circuit control flow branches around the right operand load`() {
		for (operator in listOf("&&", "||")) {
			val ir = LLVMEmitter().emit(parseMain("def left = true\ndef right = false\nleft $operator right"))
			val branch = assertNotNull(Regex("br i1 %left_bool, label %([^,\\s]+), label %([^\\s]+)").find(ir), ir)
			val rhsLabel = branch.groupValues[if (operator == "&&") 1 else 2]
			val endLabel = branch.groupValues[if (operator == "&&") 2 else 1]
			val rhsStart = ir.indexOf("$rhsLabel:")
			val endStart = ir.indexOf("$endLabel:")
			val rightLoad = ir.indexOf("%right_value = load i8")
			assertTrue(rhsStart > branch.range.last && rightLoad > rhsStart && rightLoad < endStart, ir)
			val skippedValue = if (operator == "&&") "false" else "true"
			assertContains(ir, "phi i1 [ $skippedValue, %entry ], [ %right_bool, %$rhsLabel ]")
			assertFalse(Regex("\\b(and|or) i1\\b").containsMatchIn(ir), ir)
		}
	}

	@Test
	fun `native logical operators implement truth tables with boolean bindings`(@TempDir directory: Path) {
		for (operator in listOf("&&", "||")) {
			for (left in listOf(false, true)) {
				for (right in listOf(false, true)) {
					val source = "def left = $left\ndef<bool> right := $right\nprintln(left $operator right)"
					val expected = if (operator == "&&") left && right else left || right
					assertMainOutput(directory.resolve("logic_${operator == "&&"}_${left}_$right"), source, "$expected\n")
				}
			}
		}
	}

	@Test
	fun `nested short circuit expressions compose with storage negation and later statements`(@TempDir directory: Path) {
		val cases = listOf("println(true || false && false)" to true, "println((true || false) && false)" to false, "println(false && true || true)" to true, "println(false || (true && !false))" to true, "println((true && false) || (false || true))" to true, "println((true || false) && (false || true))" to true, "println(true && (false || (true && false)))" to false, "println(!((false || true) && (true || false)))" to false, "def a = true\ndef snapshot := a && false\na = a && !snapshot\na = !a || snapshot\nprintln(a)" to false, "def<bool> a = false || true\ndef b := a && true\na = false\nprintln(b)" to true)
		for ((index, case) in cases.withIndex()) {
			val (source, expected) = case
			assertMainOutput(directory.resolve("nested_logic_$index"), source, "$expected\n")
		}
		assertMainOutput(directory.resolve("after_logic"), "def flag = true && false\ndef x = 7\nprintln(x + 2)", "9\n")
	}

	@Test
	fun `boolean equality precedence lies between negation and short circuit logic`() {
		fun expression(source: String) = assertIs<ExpressionStatement>(parseMain(source).mainStatements.single()).expression
		val mixed = assertIs<BinaryExpression>(expression("!true == false && true != false || false"))
		assertEquals("||", mixed.operator.lexeme)
		val conjunction = assertIs<BinaryExpression>(mixed.left)
		assertEquals("&&", conjunction.operator.lexeme)
		val equal = assertIs<BinaryExpression>(conjunction.left)
		assertEquals("==", equal.operator.lexeme)
		assertIs<UnaryExpression>(equal.left)
		assertEquals("!=", assertIs<BinaryExpression>(conjunction.right).operator.lexeme)
		val grouped = assertIs<BinaryExpression>(expression("(true || false) == (false && true)"))
		assertEquals("==", grouped.operator.lexeme)
		assertEquals("||", assertIs<BinaryExpression>(grouped.left).operator.lexeme)
		assertEquals("&&", assertIs<BinaryExpression>(grouped.right).operator.lexeme)
		val chain = assertIs<BinaryExpression>(expression("true == false != false"))
		assertEquals("!=", chain.operator.lexeme)
		assertEquals("==", assertIs<BinaryExpression>(chain.left).operator.lexeme)
		for (source in listOf("true ==", "!= false", "true != != false", "true == ()")) {
			assertFailsWith<SyntaxException>(source) { parseMain(source) }
		}
	}

	@Test
	fun `equality rejects boolean numeric mixing and preserves binding rules`() {
		val cases = listOf("true == 1" to "Cannot compare 'bool' and 'int'", "0 != false" to "Cannot compare 'int' and 'bool'", "true != 1.0" to "Cannot compare 'bool' and 'float'", "0.0 == false" to "Cannot compare 'float' and 'bool'", "true || (false != 1)" to "Cannot compare 'bool' and 'int'", "true == missing" to "Unknown name 'missing'", "missing != false" to "Unknown name 'missing'", "def<int> same = true == false" to "Cannot assign 'bool' to 'int'", "def<float> different = true != false" to "Cannot assign 'bool' to 'float'", "def same = true == false\nsame = 1" to "Cannot assign 'int' to 'bool'", "def same := true == false\nsame = true" to "Cannot reassign immutable binding 'same'", "(true == false) + 1" to "requires numeric operands")
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) { FunctionAnalyzer(nativeTestLayout).analyze(parseMain(source)) }
			assertContains(failure.message.orEmpty(), diagnostic)
			assertLocated(failure)
		}
	}

	@Test
	fun `native boolean equality and inequality implement both truth tables`(@TempDir directory: Path) {
		for (operator in listOf("==", "!=")) {
			for (left in listOf(false, true)) {
				for (right in listOf(false, true)) {
					val expected = if (operator == "==") left == right else left != right
					for (bindings in listOf(false, true)) {
						val source = if (bindings) "def left = $left\ndef<bool> right := $right\nprintln(left $operator right)" else "println($left $operator $right)"
						assertMainOutput(directory.resolve("equality_${operator == "=="}_${left}_${right}_$bindings"), source, "$expected\n")
					}
				}
			}
		}
	}

	@Test
	fun `boolean comparisons compose with short circuits negation and storage`(@TempDir directory: Path) {
		val cases = listOf("println(!true == false)" to true, "println(!(true == false))" to true, "println(true == false && false)" to false, "println(true || false == false)" to true, "println(true && true != false || false)" to true, "println((true && false) == (false || false))" to true, "println((true || false) != (true && false))" to true, "println(true == (false || (true && false)))" to false, "println((true && false) != true && !false)" to true, "println(false && (true != false))" to false, "println(true || (true == false))" to true, "println(true == false != false)" to false, "def a = true\ndef<bool> same = a == false\ndef copy := same\na = false\nsame = a != true\nprintln(copy == !same)" to true, "def a = true\ndef same := a == true\na = false\nprintln(same)" to true)
		for ((index, case) in cases.withIndex()) {
			val (source, expected) = case
			assertMainOutput(directory.resolve("comparison_composition_$index"), source, "$expected\n")
		}
	}

	@Test
	fun `native integer equality preserves exact values signs and boundaries`(@TempDir directory: Path) {
		val cases = listOf(Triple("0", "0", true), Triple("7", "7", true), Triple("7", "8", false), Triple("-7", "-7", true), Triple("-7", "7", false), Triple("2147483647", "2147483646", false), Triple("(-2147483647 - 1)", "(-2147483647 - 1)", true), Triple("16777217", "16777216", false))
		for ((index, case) in cases.withIndex()) {
			val (left, right, same) = case
			for (equal in listOf(false, true)) {
				val operator = if (equal) "==" else "!="
				val source = "def<int> left = $left\ndef right := $right\nprintln(left $operator right)"
				assertMainOutput(directory.resolve("int_equality_${index}_$equal"), source, "${if (equal) same else !same}\n")
			}
		}
	}

	@Test
	fun `native float equality handles signed zero infinity and NaN`(@TempDir directory: Path) {
		val cases = listOf(Triple("1.25", "1.25", true), Triple("1.25", "1.5", false), Triple("-1.25", "-1.25", true), Triple("-1.25", "1.25", false), Triple("zero", "-zero", true), Triple("1.0 / zero", "1.0 / zero", true), Triple("1.0 / zero", "-1.0 / zero", false), Triple("zero / zero", "zero / zero", false), Triple("zero / zero", "1.0", false), Triple("1.0", "zero / zero", false), Triple("1.0 / zero", "zero / zero", false))
		for ((index, case) in cases.withIndex()) {
			val (left, right, same) = case
			for (equal in listOf(false, true)) {
				val operator = if (equal) "==" else "!="
				val source = "def<float> zero = 0.0\ndef left = $left\ndef<float> right := $right\nprintln(left $operator right)"
				assertMainOutput(directory.resolve("float_equality_${index}_$equal"), source, "${if (equal) same else !same}\n")
			}
		}
	}

	@Test
	fun `mixed numeric equality follows existing float32 promotion in either order`(@TempDir directory: Path) {
		val cases = listOf(Triple("7", "7.0", true), Triple("7.0", "7", true), Triple("-7", "-7.0", true), Triple("-7.0", "7", false), Triple("7", "7.5", false), Triple("7.5", "7", false), Triple("0", "-0.0", true), Triple("16777217", "16777216.0", true), Triple("16777216.0", "16777217", true))
		for ((index, case) in cases.withIndex()) {
			val (left, right, same) = case
			for (equal in listOf(false, true)) {
				val operator = if (equal) "==" else "!="
				val source = "def left = $left\ndef right := $right\nprintln(left $operator right)"
				assertMainOutput(directory.resolve("mixed_equality_${index}_$equal"), source, "${if (equal) same else !same}\n")
			}
		}
	}

	@Test
	fun `numeric comparisons compose with arithmetic bindings and guarded division`(@TempDir directory: Path) {
		val cases = listOf("println(1 + 2 * 3 == 7 && 4.5 / 1.5 != 2.0)" to true, "println(!(3 == 4) && (1.5 != 2.0))" to true, "println((1 == 1) == (2.0 != 3.0))" to true, "def<int> count = 7\ndef snapshot := count == 7.0\ncount = 8\nprintln(snapshot && count != 7)" to true, "def<float> scale = 1.5\ndef<bool> same = scale == 1.5\nscale = 2.0\nsame = scale == 1.5\nprintln(same)" to false, "def zero = 0\ndef guard = false\nprintln(guard && (10 / zero == 1))" to false, "def zero = 0\ndef guard = true\nprintln(guard || (10 / zero != 1))" to true)
		for ((index, case) in cases.withIndex()) {
			val (source, expected) = case
			assertMainOutput(directory.resolve("numeric_composition_$index"), source, "$expected\n")
		}
	}

	@Test
	fun `numeric equality results retain bool typing and strict assignment checks`() {
		val cases = listOf("def<int> same = 1 == 1" to "Cannot assign 'bool' to 'int'", "def<float> different = 1.0 != 2.0" to "Cannot assign 'bool' to 'float'", "def same = 1 == 1.0\nsame = 1" to "Cannot assign 'int' to 'bool'", "def same := 1.0 == 1\nsame = false" to "Cannot reassign immutable binding 'same'", "def x = 1\nx = 1.0" to "Cannot assign 'float' to 'int'", "def<float> x = 1" to "Cannot assign 'int' to 'float'", "1 == 1 == 1" to "Cannot compare 'bool' and 'int'", "true == 1.0" to "Cannot compare 'bool' and 'float'", "1.0 != false" to "Cannot compare 'float' and 'bool'", "1 == missing" to "Unknown name 'missing'", "(1 == 1) + 2" to "requires numeric operands")
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) { FunctionAnalyzer(nativeTestLayout).analyze(parseMain(source)) }
			assertContains(failure.message.orEmpty(), diagnostic)
			assertLocated(failure)
		}
	}

	@Test
	fun `ordering precedence lies between arithmetic and equality`() {
		val expression = assertIs<ExpressionStatement>(parseMain("1 + 2 * 3 < 8 == true && 4 >= 3").mainStatements.single()).expression
		val conjunction = assertIs<BinaryExpression>(expression)
		assertEquals("&&", conjunction.operator.lexeme)
		val equality = assertIs<BinaryExpression>(conjunction.left)
		assertEquals("==", equality.operator.lexeme)
		val less = assertIs<BinaryExpression>(equality.left)
		assertEquals("<", less.operator.lexeme)
		val addition = assertIs<BinaryExpression>(less.left)
		assertEquals("+", addition.operator.lexeme)
		assertEquals("*", assertIs<BinaryExpression>(addition.right).operator.lexeme)
		assertEquals(">=", assertIs<BinaryExpression>(conjunction.right).operator.lexeme)
		val grouped = assertIs<ExpressionStatement>(parseMain("(1 + 2) <= (3 * 4)").mainStatements.single()).expression
		assertEquals("<=", assertIs<BinaryExpression>(grouped).operator.lexeme)
		for (source in listOf("1 <", "> 1", "1 <= <= 2", "1 >= ()")) {
			assertFailsWith<SyntaxException>(source) { parseMain(source) }
		}
	}

	@Test
	fun `ordering requires numeric operands and returns a bool`() {
		val cases = listOf("true < false" to "requires numeric operands", "true > 1" to "requires numeric operands", "1.0 <= false" to "requires numeric operands", "true >= 1.5" to "requires numeric operands", "false && (true < 1)" to "requires numeric operands", "1 < 2 < 3" to "requires numeric operands", "1 <= missing" to "Unknown name 'missing'", "def<int> x = 1 < 2" to "Cannot assign 'bool' to 'int'", "def<float> x = 1.0 >= 2.0" to "Cannot assign 'bool' to 'float'", "def x = 1 < 2.0\nx = 1" to "Cannot assign 'int' to 'bool'", "def x := 1.0 > 2\nx = false" to "Cannot reassign immutable binding 'x'", "(1 < 2) + 3" to "requires numeric operands")
		for ((source, diagnostic) in cases) {
			val failure = assertFailsWith<SemanticException>(source) { FunctionAnalyzer(nativeTestLayout).analyze(parseMain(source)) }
			assertContains(failure.message.orEmpty(), diagnostic)
			assertLocated(failure)
		}
	}

	@Test
	fun `native integer ordering uses signed exact comparisons`(@TempDir directory: Path) {
		assertOrdering(directory, "int", listOf(Triple("1", "2", -1), Triple("2", "1", 1), Triple("2", "2", 0), Triple("-1", "0", -1), Triple("0", "-1", 1), Triple("-3", "-2", -1), Triple("(-2147483647 - 1)", "2147483647", -1), Triple("2147483647", "(-2147483647 - 1)", 1), Triple("16777217", "16777216", 1)))
	}

	@Test
	fun `native float ordering handles zero infinity and unordered NaN`(@TempDir directory: Path) {
		assertOrdering(directory, "float", listOf(Triple("1.25", "1.5", -1), Triple("1.5", "1.25", 1), Triple("1.25", "1.25", 0), Triple("-1.5", "-1.25", -1), Triple("zero", "-zero", 0), Triple("1.0 / zero", "1.0 / zero", 0), Triple("-1.0 / zero", "1.0", -1), Triple("1.0 / zero", "1.0", 1), Triple("zero / zero", "1.0", null), Triple("1.0", "zero / zero", null), Triple("zero / zero", "zero / zero", null)))
	}

	@Test
	fun `mixed ordering follows float32 promotion in both operand directions`(@TempDir directory: Path) {
		assertOrdering(directory, "mixed", listOf(Triple("7", "7.5", -1), Triple("7.5", "7", 1), Triple("7", "7.0", 0), Triple("7.0", "7", 0), Triple("-7", "-6.5", -1), Triple("-6.5", "-7", 1), Triple("0", "-0.0", 0), Triple("16777217", "16777216.0", 0), Triple("16777216.0", "16777217", 0)))
	}

	@Test
	fun `ordering composes with arithmetic equality storage and short circuit guards`(@TempDir directory: Path) {
		val cases = listOf("println(1 + 2 * 3 < 8 == true && 4 >= 3)" to true, "println(!(3 < 2) && 1.5 <= 2.0)" to true, "println(2 > 1 != 4 <= 3)" to true, "println(1 < 2 && 3.0 > 2 || 4 >= 5)" to true, "println((1 > 2 || 3 <= 3) == (4.0 >= 4 && 5 != 6))" to true, "def x = 7\ndef snapshot := x < 8.0\nx = 9\nprintln(snapshot && x >= 8)" to true, "def<float> x = 1.5\ndef<bool> inRange = x > 1.0\nx = 0.5\ninRange = x > 1.0\nprintln(inRange)" to false, "def zero = 0\nprintln(zero != 0 && 10 / zero > 1)" to false, "def zero = 0\nprintln(zero == 0 || 10 / zero <= 1)" to true, "def x = 2 < 3\ndef y = 9\nprintln(y + 1)" to "10")
		for ((index, case) in cases.withIndex()) {
			val (source, expected) = case
			assertMainOutput(directory.resolve("ordering_composition_$index"), source, "$expected\n")
		}
	}

	private fun assertOrdering(directory: Path, name: String, cases: List<Triple<String, String, Int?>>) {
		for ((index, case) in cases.withIndex()) {
			val (left, right, relation) = case
			for ((opIndex, operator) in listOf("<", ">", "<=", ">=").withIndex()) {
				val expected = relation != null && when (operator) {
					"<"  -> relation < 0
					">"  -> relation > 0
					"<=" -> relation <= 0
					else -> relation >= 0
				}
				val source = "def<float> zero = 0.0\ndef left = $left\ndef right := $right\nprintln(left $operator right)"
				assertMainOutput(directory.resolve("ordering_${name}_${index}_$opIndex"), source, "$expected\n")
			}
		}
	}

}
