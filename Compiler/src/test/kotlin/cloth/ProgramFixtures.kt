package cloth

import cloth.ast.SourceUnit
import cloth.diagnostics.SourceFile
import cloth.emitter.NativeCABI
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.parser.declaration.BlockBody
import cloth.parser.declaration.FunctionDeclaration
import cloth.settings.CompilerSettings
import cloth.token.Keywords
import cloth.token.Operators
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal val nativeTestLayout by lazy { NativeCABI.native().layout }

private fun mainSource(body: String): String = "${Keywords.FUNCTION} main${Operators.PAREN_OPEN}${Operators.PAREN_CLOSE} ${Operators.BRACE_OPEN}\n$body\n${Operators.BRACE_CLOSE}"

internal fun parseMain(body: String, name: String = "<input>"): SourceUnit = parseMain(SourceFile(name, body.trimIndent()))

internal fun parseMain(source: SourceFile): SourceUnit = Parser(Lexer(SourceFile(source.name, mainSource(source.text))).scanTokens()).parse()

internal val SourceUnit.mainStatements get() = ((declarations.single() as FunctionDeclaration).body as BlockBody).statements

internal fun compileMain(output: Path, body: String, vararg options: String): Path {
	val source = Files.writeString(output.resolveSibling("${output.fileName}.co"), mainSource(body.trimIndent()))
	return compileArtifact(CompilerSettings(arrayOf(source.toString(), output.toString(), *options)))
}

internal fun assertMainOutput(output: Path, body: String, text: String = "", vararg options: String) {
	val expected = text.replace("\n", System.lineSeparator()) + processCompletion()
	assertEquals(expected, runExecutable(compileMain(output, body, *options)))
}

internal fun runExecutable(executable: Path, status: Int = 0): String {
	val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
	try {
		assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Executable timed out: $executable")
		val output = process.inputStream.use { it.readBytes() }.decodeToString(throwOnInvalidSequence = true)
		assertEquals(status, process.exitValue(), output)
		return output
	} finally {
		if (process.isAlive) process.destroyForcibly()
	}
}
