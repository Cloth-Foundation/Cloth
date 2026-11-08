package cloth.settings

import cloth.diagnostics.CliErrors
import java.io.File

enum class OutputKind { EXECUTABLE,
	OBJECT
}

/** Standalone compiler CLI adapter; project selection belongs to Shuttle. */
class CompilerSettings(val args: Array<String>) {

	private val options = CompilerOptions(args.toList(), usage).also {
		if (it.positional.size > 2) throw CliErrors.arguments(usage)
	}
	val linkOptions get() = options.linkOptions
	val outputKind get() = options.outputKind
	val targetTriple get() = options.targetTriple
	val standardLibrary get() = options.standardLibrary
	val debug get() = options.debug

	fun getSourceFile(): File = options.positional.getOrNull(0)?.let(::File) ?: File("Bootstrap/Frontend/Main.co")
	fun getOutputFile(): File? = options.positional.getOrNull(1)?.let(::File)

	companion object {

		const val usage = "Usage: cloth [source.co] [output] [--stdlib PATH | --no-stdlib] [--target TRIPLE] [--emit executable|object] [--debug] [--linker PATH] [--linker-driver lld|msvc|cc] " + "[--runtime default|static|dynamic] [--subsystem console|windows] [-L DIR] [-l NAME] " + "[--sysroot DIR] [--msvc-tools DIR] [--windows-sdk DIR] [--windows-sdk-version VERSION]\n" + "       cloth run [source.co] [output] [build options] [-- program arguments]"
	}
}
