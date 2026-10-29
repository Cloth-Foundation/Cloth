package cloth.util

data class CursorPosition(val line: Int, val column: Int) {

	override fun toString(): String = "$line:$column"

}
