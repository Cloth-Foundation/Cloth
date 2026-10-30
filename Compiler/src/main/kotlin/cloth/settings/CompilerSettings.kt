package cloth.settings

import java.io.File

class CompilerSettings(val args: Array<String>) {

	init {
		require(args.size <= 2) { "Usage: cloth [source.co] [output]" }
	}

	fun getSourceFile(): File = args.getOrNull(0)?.let(::File) ?: File("Unsigned.co")

	fun getOutputFile(): File? = args.getOrNull(1)?.let(::File)

}
