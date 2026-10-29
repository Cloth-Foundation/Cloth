package cloth.file

import java.io.File
import java.nio.file.Path

class ClothObject(val name: String, val ext: FileExtension = FileExtension.CO, val path: Path) {

	constructor(file: File) : this(file.nameWithoutExtension, FileExtension.valueOf(file.extension.uppercase()), file.toPath())

	private val file: File = File(path.toString())

	fun getContents(): String {
		return file.readText()
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

enum class FileExtension {
	CO,
	CLIB;

	override fun toString(): String {
		return name.lowercase()
	}
}