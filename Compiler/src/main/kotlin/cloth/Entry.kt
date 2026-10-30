package cloth

import cloth.diagnostics.*
import cloth.emitter.LLVMEmitter
import cloth.file.ClothObject
import cloth.lexer.Lexer
import cloth.parser.Parser
import cloth.settings.CompilerSettings
import cloth.settings.OutputKind
import java.io.IOException
import java.nio.file.InvalidPathException
import kotlin.system.exitProcess

var settings: CompilerSettings? = null

fun main(args: Array<String>) {
	val status = runCompiler(args)
	if (status != 0) exitProcess(status)
}

/** Testable CLI boundary: expected failures never escape as JVM stack traces. */
fun runCompiler(args: Array<String>, reporter: DiagnosticReporter = DiagnosticReporter()): Int {
	val debug = "--debug" in args.takeWhile { it != "--" }
	try {
		val compilerSettings = CompilerSettings(args)
		settings = compilerSettings
		val clothObject = ClothObject(compilerSettings.getSourceFile())
		val source = SourceFile(clothObject.path.toString(), clothObject.getContents())
		val output = compilerSettings.getOutputFile()?.toPath() ?: clothObject.path.resolveSibling(clothObject.name + if (compilerSettings.outputKind == OutputKind.OBJECT) ".o" else "")
		val unit = Parser(Lexer(source).scanTokens()).parse()
		val emitter = LLVMEmitter(compilerSettings.linkOptions)
		when (compilerSettings.outputKind) {
			OutputKind.EXECUTABLE -> emitter.compile(unit, output)
			OutputKind.OBJECT -> emitter.compileObject(unit, output)
		}
		return 0
	} catch (failure: ClothDiagnostic) {
		reporter.report(failure, debug)
		return if (failure.code().startsWith("C9")) 2 else 1
	} catch (failure: InvalidPathException) {
		reporter.report(CliErrors.path(failure.input, failure), debug)
		return 1
	} catch (failure: IOException) {
		reporter.report(CliErrors.output(failure), debug)
		return 1
	} catch (failure: Exception) {
		reporter.report(InternalErrors.failure(failure), debug)
		return 2
	} catch (failure: LinkageError) {
		reporter.report(InternalErrors.failure(failure), debug)
		return 2
	}
}
