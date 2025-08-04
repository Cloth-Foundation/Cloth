package loom.compiler.token;

import java.util.Objects;

/**
 * Represents a source location span (range of lines/columns) in a source file.
 */
public final class TokenSpan {

	private final int startLine;
	private final int startColumn;
	private final int endLine;
	private final int endColumn;
	private final String sourceFile;

	public TokenSpan (int startLine, int startColumn, int endLine, int endColumn, String sourceFile) {
		this.startLine = startLine;
		this.startColumn = startColumn;
		this.endLine = endLine;
		this.endColumn = endColumn;
		this.sourceFile = sourceFile;
	}

	public static TokenSpan singleLine (int line, int startColumn, int endColumn, String sourceFile) {
		return new TokenSpan(line, startColumn, line, endColumn, sourceFile);
	}

	public int startLine () {
		return startLine;
	}

	public int startColumn () {
		return startColumn;
	}

	public int endLine () {
		return endLine;
	}

	public int endColumn () {
		return endColumn;
	}

	public String sourceFile () {
		return sourceFile;
	}

	public boolean isSingleLine () {
		return startLine == endLine;
	}

	public int length () {
		if (isSingleLine()) {
			return endColumn - startColumn;
		}
		return -1;
	}

	public String getPositionString () {
		if (sourceFile != null) {
			return String.format("%s:%d:%d", sourceFile, startLine, startColumn);
		}
		return String.format("line %d, column %d", startLine, startColumn);
	}

	/**
	 * Merges this span with another to create a span that covers both.
	 * Assumes both spans are from the same source file.
	 */
	public TokenSpan merge (TokenSpan other) {
		if (this.sourceFile != null && other.sourceFile != null && !this.sourceFile.equals(other.sourceFile)) {
			throw new IllegalArgumentException("Cannot merge spans from different files.");
		}

		int newStartLine = Math.min(this.startLine, other.startLine);
		int newStartCol = (this.startLine < other.startLine) ? this.startColumn
				: Math.min(this.startColumn, other.startColumn);

		int newEndLine = Math.max(this.endLine, other.endLine);
		int newEndCol = (this.endLine > other.endLine) ? this.endColumn
				: Math.max(this.endColumn, other.endColumn);

		String file = this.sourceFile != null ? this.sourceFile : other.sourceFile;

		return new TokenSpan(newStartLine, newStartCol, newEndLine, newEndCol, file);
	}

	/**
	 * Highlights the span across one or more lines of source code.
	 */
	public String highlightSourceLine (String sourceCode) {
		String[] lines = sourceCode.split("\n", -1);
		StringBuilder sb = new StringBuilder();

		int safeStartLine = Math.max(1, startLine);
		int safeEndLine = Math.min(endLine, lines.length);

		// If the span starts beyond the source code, show what we can
		if (safeStartLine > lines.length) {
			sb.append("(Token starts beyond the end of the source code)\n");
			// Still try to show the source code if we have any lines
			if (lines.length > 0) {
				for (int i = 1; i <= Math.min(lines.length, 3); i++) {
					String line = lines[i - 1];
					sb.append(line).append("\n");
				}
			}
			return sb.toString();
		}

		for (int i = safeStartLine; i <= safeEndLine; i++) {
			String line = lines[i - 1];
			sb.append(line).append("\n");

			StringBuilder caretLine = createCaretLine(line, i);

			sb.append(caretLine).append("\n");
		}

		if (endLine > lines.length) {
			sb.append("(Span extends past end of file)\n");
		}

		return sb.toString();
	}

	private StringBuilder createCaretLine (String line, int i) {
		StringBuilder caretLine = new StringBuilder();
		int lineLength = line.length();

		int caretStart = (i == startLine) ? startColumn : 0;
		int caretEnd = (i == endLine) ? endColumn : lineLength;

		caretStart = Math.max(0, Math.min(caretStart, lineLength));
		caretEnd = Math.max(caretStart, Math.min(caretEnd, lineLength));

		// Pad up to caretStart
		for (int j = 0; j < caretStart; j++) {
			caretLine.append(line.charAt(j) == '\t' ? '\t' : ' ');
		}

		// Draw carets
		caretLine.append("^".repeat(Math.max(0, caretEnd - caretStart)));
		return caretLine;
	}

	/**
	 * Formats a full error message showing file, position, and a highlighted line.
	 */
	public String formatError (String sourceCode, String message) {
		return "Error at " + getPositionString() + ": " + message + "\n" +
				highlightSourceLine(sourceCode);
	}

	@Override
	public String toString () {
		return String.format("Span [%d:%d -> %d:%d%s]",
				startLine, startColumn, endLine, endColumn,
				sourceFile != null ? ", file='" + sourceFile + "'" : ""
		);
	}

	@Override
	public boolean equals (Object o) {
		if (this == o) return true;
		if (!(o instanceof TokenSpan other)) return false;
		return startLine == other.startLine &&
				startColumn == other.startColumn &&
				endLine == other.endLine &&
				endColumn == other.endColumn &&
				Objects.equals(sourceFile, other.sourceFile);
	}

	@Override
	public int hashCode () {
		return Objects.hash(startLine, startColumn, endLine, endColumn, sourceFile);
	}
}
