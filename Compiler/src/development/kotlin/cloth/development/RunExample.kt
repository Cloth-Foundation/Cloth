package cloth.development

import cloth.emitter.NativeCABI
import cloth.runCompiler
import cloth.settings.CompilerSettings
import cloth.settings.OutputKind
import java.io.IOException
import kotlin.system.exitProcess

/** Development launcher only; the regular compiler never executes its output. */
fun main(args: Array<String>) {
	val compilationStatus = runCompiler(args)
	if (compilationStatus != 0) exitProcess(compilationStatus)

	val settings = CompilerSettings(args)
	if (settings.outputKind != OutputKind.EXECUTABLE) {
		System.err.println("runExample requires executable output.")
		exitProcess(1)
	}
	val source = settings.getSourceFile()
	val output = settings.getOutputFile()?.toPath() ?: source.toPath().resolveSibling(source.nameWithoutExtension)
	val abi = settings.targetTriple?.let(NativeCABI::forTarget) ?: NativeCABI.native()
	val executable = abi.executableFormat.executablePath(output).toAbsolutePath().normalize()
	val status = try {
		ProcessBuilder(executable.toString()).inheritIO().start().waitFor()
	} catch (failure: IOException) {
		System.err.println("Could not run example '$executable': ${failure.message}")
		1
	}
	exitProcess(status)
}
