package cloth.file

import cloth.diagnostics.CliErrors
import java.io.File
import java.io.IOException
import java.nio.file.Path

class ClothObject(val name: String, val ext: FileExtension = FileExtension.CO, val path: Path) {

	constructor(file: File) : this(file.nameWithoutExtension, FileExtension.entries.firstOrNull { it.name.equals(file.extension, ignoreCase = true) } ?: throw CliErrors.extension(file.extension), file.toPath())

	private val file: File = File(path.toString())

	fun getContents(): String {
		return try {
			getFile().readText()
		} catch (failure: IOException) {
			throw CliErrors.read(path, failure)
		}
	}

	fun getFile(): File {
		return file
	}

	fun getExtension(): FileExtension {
		return ext
	}

	override fun toString(): String {
		return "$name.${getExtension()}"
	}
}

enum class FileExtension { CO, CLIB;

	override fun toString(): String {
		return name.lowercase()
	}
}
