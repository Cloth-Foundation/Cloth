package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.SourceFile
import cloth.lexer.Lexer
import cloth.semantic.typing.FunctionAnalyzer
import cloth.settings.OutputKind
import cloth.source.SourceGraphLoader
import cloth.token.Keywords
import cloth.token.Operators
import cloth.toolchain.StandardLibrary
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compile the Cloth scanner once per available native profile, then use the Kotlin lexer as its oracle. */
class BootstrapLexerTest {
	private val frontend = Path.of("Bootstrap/Frontend").toAbsolutePath()

	private fun request(source: Path, output: Path, target: String, kind: OutputKind = OutputKind.EXECUTABLE) =
		StandardLibrary.default().attachTo(CompilationRequest(
			source, output, listOf(SourceModule("Frontend", frontend), SourceModule("Driver", source.parent)),
			outputKind = kind, targetTriple = target,
		))

	@Test
	fun `Bootstrap scans the same tokens spans and first errors as the Kotlin lexer`(@TempDir directory: Path) {
		val main = directory.resolve("Main.co")
		Files.writeString(main, """
			using lexer.Lexer: Lexer
			using source.SourceFile: SourceFile
			using token.TokenList: TokenList

			fn<isize> main(string[] args) {
			    // Native argv cannot contain NUL; reconstruct it only in this test driver.
			    def input := if (args::size > 1) { args[0].replace("<NUL>", "\0") } else { args[0] }
			    def source := SourceFile::new("<string>", input)
			    def lexer := Lexer::new(input, source)
			    def<TokenList?> tokens := lexer.scanTokens()
			    if (tokens == null) {
			        if (lexer.failure == null) return 90
			        if (!(lexer.failure.span.source === source)) return 91
			        println(lexer.failure)
			        lexer::delete
			        if (source.text != input) return 92
			        source::delete
			        return 1
			    }
			    // Success transfers ownership: the tokens must outlive their lexer.
			    lexer::delete
			    for (def index <- 0..tokens.size()) {
			        def token := tokens.get(index)
			        if (!(token.span.source === source)) return 93
			        println(token)
			    }
			    tokens::delete
			    if (source.text != input) return 95
			    source::delete
			    return 0
			}
		""".trimIndent())

		val cases = cases()
		val expected = cases.map { oracle(it.second) }
		for (abi in structRunners()) {
			val executable = ClothCompiler.compile(request(main, directory.resolve("BootstrapLexer_${abi.layout.sizeBits}"), abi.targetTriple)).artifact
			for ((index, case) in cases.withIndex()) {
				val (name, input) = case
				val arguments = if ('\u0000' in input) listOf(input.replace("\u0000", "<NUL>"), "nul") else listOf(input)
				val output = directory.resolve("process-output.txt")
				val process = ProcessBuilder(listOf(executable.toString()) + arguments.map { launchArgument(it, abi.windows) })
					.redirectErrorStream(true).redirectOutput(output.toFile()).start()
				try {
					assertTrue(process.waitFor(30, TimeUnit.SECONDS), "$name on ${abi.targetTriple} timed out")
					val actual = Files.readString(output).replace("\r\n", "\n")
					assertEquals(expected[index].first, process.exitValue(), "$name on ${abi.targetTriple}:\n$actual")
					assertEquals(expected[index].second, actual, "$name on ${abi.targetTriple}")
				} finally {
					if (process.isAlive) process.destroyForcibly()
				}
			}
		}
		println("Verified ${cases.size} inputs on ${structRunners().size} native profiles, including ${cases.count { it.first.startsWith("Bootstrap source ") }} Bootstrap source files.")
	}

	@Test
	fun `Bootstrap releases successful and partial token lists and syntax trees`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("Ownership.co"), """
			using lexer.Lexer: Lexer
			using parser.Ast: SourceUnit
			using parser.Parser: Parser
			using source.SourceFile: SourceFile
			using token.TokenList: TokenList

			fn<bool> scanAndDelete(string input, bool succeeds) {
			    def source := SourceFile::new("<string>", input)
			    def lexer := Lexer::new(input, source)
			    def<TokenList?> tokens := lexer.scanTokens()
			    def valid := if (tokens == null) { !succeeds && lexer.failure != null } else { succeeds && tokens.size() == 3 }
			    lexer::delete
			    if (tokens != null) tokens::delete
			    def sourceSurvived := source.text == input
			    source::delete
			    return valid && sourceSurvived
			}

			fn<bool> parseAndDelete(string input, bool succeeds) {
			    def source := SourceFile::new("<string>", input)
			    def lexer := Lexer::new(input, source)
			    def<TokenList?> tokens := lexer.scanTokens()
			    lexer::delete
			    if (tokens == null) {
			        source::delete
			        return false
			    }
			    def parser := Parser::new(tokens)
			    def<SourceUnit?> tree := parser.parse()
			    def valid = tree == null && !succeeds && parser.failure != null
			    parser::delete
			    if (tree != null) {
			        valid = succeeds && tree.functions.size() == 1 && tree.functions.get(0).bindings.size() == 2
			        tree::delete
			    }
			    tokens::delete
			    source::delete
			    return valid
			}

			fn<bool> sample() {
			    if (!scanAndDelete("first second", true) || !scanAndDelete("first second @", false)) return false
			    if (!parseAndDelete("fn main() { def x = 2; def y := -(x + 1) ** 3 }", true)) return false
			    return parseAndDelete("fn done() {}\nfn main() { def x = 2; def y := (x * 3 + (4 ** 5 * )) }", false)
			}
		""".trimIndent())
		val harness = GrowableArrayStorageTest()
		for (abi in structRunners()) {
			val output = directory.resolve("BootstrapOwnership_${abi.layout.sizeBits}")
			val graph = SourceGraphLoader.load(request(source, output, abi.targetTriple, OutputKind.OBJECT))
			val model = FunctionAnalyzer(abi.layout).analyze(graph)
			harness.probe(abi, output, model = model) {
				expect(checkNotNull(sourceCall("sample", null)), "Bootstrap ownership probe failed")
			}
		}
	}

	private fun cases(): List<Pair<String, String>> = buildList {
		add("empty" to "")
		add("whitespace and positions" to " \t\r\n\r\nx\ry\n")
		add("keywords and aliases" to Keywords.entries.joinToString(" "))
		add("identifier boundaries" to "auto module definition method function constructor public boolean integer character _ __ _x _1 x_1 IF foo123 café λ 中文 \u0661\u0662")
		// Quotes are literal delimiters, tested separately below.
		add("operators" to Operators.entries.filter { it !in listOf(Operators.QUOTATION, Operators.DOUBLE_QUOTATION) }.joinToString(" "))
		add("default state prefix" to "default: ({ name })\npub state<Info> Alive {}")
		add("longest operators and nullable receivers" to "=== == = := ++ += + -- -= - ** *= * /= / %= % ..= .. . ?:: ?. ?: ? :: -> <- >= <= && || _a __ _")
		add("comments" to "// no token\r\n/* two\nlines */x/**/y/* first /* close */z // eof")
		add("numeric forms" to "0 123 1_000 0xff 0xAf_F0 0b10_01 0o7_0 12f 12F 12i 12I 12.f 12.F 12.50f 12.5 12..14 12..=14 12.foo 12.iValue")
		add("quoted strings" to "\"\" \"plain café 😀\" \"\\n\\r\\t\\0\\\\\\'\\\"\"")
		add("raw string" to "\"\"\"raw\n\\not-an-escape\r\n\"quote\" 😀\tend\"\"\"")
		add("characters" to "'a' 'λ' '😀' '\\n' '\\r' '\\t' '\\0' '\\\\' '\\'' '\\\"'")
		add("control JSON" to "\"a\u0000\b\u000c\u0001\u001fb\"")
		add("NUL outside literal" to "\u0000 @")
		add("NUL in comments" to "/*\u0000*/ //\u0000\nx")
		add("interpolation segments" to "#\"\" #\"hello {{world}}\" #\"{x}{y}\" #\"a{1 + 2}z\"")
		add("nested interpolation" to "#\"x{ #\"nested {value}\" }y\"")
		add("braces in holes" to "#\"{ {x} /* } ignored */ + \"}\" + '}' + \"\"\"}\"\"\" }\"")
		add("newline in a hole" to "#\"{ // } ignored\nx }\"")
		add("Unicode columns" to "\"😀\" '𐐀' #\"😀{value}終\" /*😀\n*/next")
		add("many tokens" to "name ".repeat(2_048))
		add("long lexemes" to ("\"\"\"" + "aé😀".repeat(1_024) + "\"\"\""))
		add("nested holes" to ("#\"{".repeat(24) + "value" + "}\"".repeat(24)))
		val invalid = listOf(
			"@", "x @ 1__", "@Initial state<void> Ready {}", "/*", "/*\nmissing", "1_", "1__2", "0x_1", "0b1_", "1._0",
			"0x", "0b2", "0o8", "0Xff", "0x1.2", "0b10f", "0o7i", "0x1.f", "123.i", "123.0i",
			"123.0Ifoo", "12foo", "12fSuffix", "12_i", "0x1_foo", "0x１",
			"\"", "\"missing", "\"a\nb\"", "\"a\rb\"", "\"\\", "\"\\q\"", "\"\\\n",
			"\"\"\"", "\"\"\"missing\n", "'", "'\n", "''", "'ab'", "'a", "'\\", "'\\q'", "'\\\n'",
			"#\"", "#\"missing", "#\"a\nb\"", "#\"\\q\"", "#\"\\", "#\"}\"", "#\"{", "#\"x{ { y }",
			"#\"{ \"missing", "#\"{ /* missing", "#\"{ @ }\"", "#\"{ #\"}\" }\"",
		)
		invalid.forEachIndexed { index, text -> add("first error $index" to text) }

		// Deterministic mixtures exercise state transitions without compiling one executable per input.
		val atoms = listOf("name", "_x", "12", "0xf", "12.f", ":", "?", "::", "===", "+", "\n", "/*c*/", "\"text\"", "'😀'", "#\"{x}\"", "@", "1__2", "#\"}\"")
		val random = Random(20261107)
		repeat(40) { index -> add("mixture $index" to List(12) { atoms[random.nextInt(atoms.size)] }.joinToString(" ")) }
		Files.walk(frontend).use { paths ->
			paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".co") }.sorted().forEach {
				add("Bootstrap source ${frontend.relativize(it)}" to Files.readString(it))
			}
		}
	}

	private fun oracle(input: String): Pair<Int, String> = try {
		0 to (Lexer(SourceFile("<string>", input)).scanTokens().joinToString("\n", postfix = "\n") + "\nPEC: 0\n")
	} catch (failure: ClothDiagnostic) {
		val json = "{\n  \"code\": ${quoted(failure.code())},\n  \"span\": ${quoted(failure.sourceLabels.single().span.toString())},\n  \"message\": ${quoted(failure.summary)}\n}\n"
		1 to (json + "\nPEC: 1\n")
	}

	private fun quoted(text: String) = buildString {
		append('"')
		for (character in text) when (character) {
			'"' -> append("\\\"")
			'\\' -> append("\\\\")
			'\b' -> append("\\b")
			'\u000c' -> append("\\f")
			'\n' -> append("\\n")
			'\r' -> append("\\r")
			'\t' -> append("\\t")
			else -> if (character < ' ') append("\\u${character.code.toString(16).padStart(4, '0')}") else append(character)
		}
		append('"')
	}

	/** Java's Windows launcher requires embedded quotes to be escaped at this test boundary. */
	private fun launchArgument(argument: String, windows: Boolean): String {
		if (!windows || '"' !in argument) return argument
		val escaped = Regex("""(\\*)("|\z)""").replace(argument) { match ->
			val (slashes, quote) = match.destructured
			slashes.repeat(2) + if (quote.isEmpty()) "" else "\\\""
		}
		return "\"$escaped\""
	}
}
