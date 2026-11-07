package cloth.api

import cloth.compileArtifact
import cloth.diagnostics.CliErrors
import java.io.IOException

/** Compiler entry point for build tools. Reporting and program launch belong to the caller. */
object ClothCompiler {

	fun compile(request: CompilationRequest): CompilationResult {
		return try {
			CompilationResult(compileArtifact(request))
		} catch (failure: IOException) {
			throw CliErrors.output(failure)
		}
	}
}
