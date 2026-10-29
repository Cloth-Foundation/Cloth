package cloth.settings

import java.io.File

class CompilerSettings(val args: Array<String>) {

	init {
		require(args.size <= 2) { "Usage: cloth [source.co] [output]" }
	}

	fun getOutputFile(): File? {
		return args.getOrNull(0)?.let(::File)
	}

}