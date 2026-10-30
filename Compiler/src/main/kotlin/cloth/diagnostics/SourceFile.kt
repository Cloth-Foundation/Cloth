package cloth.diagnostics

import cloth.token.TokenSpan
import com.opencastsoftware.yvette.*

/** The exact text compiled, independent of subsequent changes on disk. */
class SourceFile(val name: String, val text: String) : SourceCode {

	// Preserve empty files and the empty final line: EOF may be labeled there.
	private val lines = text.split('\n').map { it.removeSuffix("\r") }
	private val displayedLines by lazy { lines.map(::expandTabs) }

	fun range(span: TokenSpan): Range = Range(span.startLine - 1, displayColumn(span.startLine - 1, span.startColumn - 1), span.endLine - 1, displayColumn(span.endLine - 1, span.endColumn - 1))

	private fun displayColumn(line: Int, column: Int): Int = expandTabs(lines[line].take(column)).length

	private fun expandTabs(line: String): String = buildString {
		for (character in line) {
			if (character == '\t') repeat(4 - length % 4) { append(' ') } else append(character)
		}
	}

	override fun readRange(range: Range, linesBefore: Int, linesAfter: Int): RangeContents {
		val first = (range.start().line() - linesBefore).coerceAtLeast(0)
		val last = (range.end().line() + linesAfter).coerceAtMost(lines.lastIndex)
		return StringRangeContents(name, Range(first, 0, last, displayedLines[last].length), (first..last).map { Line(it, displayedLines[it]) })
	}
}
