package loom.compiler.diagnostics;

import loom.compiler.token.TokenSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects and prints errors, warnings, and notes with proper highlighting.
 */
public class ErrorReporter {

	public enum Severity {
		ERROR, WARNING, NOTE
	}

	private record Message(Severity severity, TokenSpan span, String message, String sourceCode) {
	}

	private final List<Message> messages = new ArrayList<>();
	private int errorCount = 0;
	private int warningCount = 0;
	private String globalSourceCode = null;

	public void reportError (TokenSpan span, String message, String sourceCode) {
		messages.add(new Message(Severity.ERROR, span, message, sourceCode));
		errorCount++;
	}

	public void reportWarning (TokenSpan span, String message, String sourceCode) {
		messages.add(new Message(Severity.WARNING, span, message, sourceCode));
		warningCount++;
	}

	public void reportNote (TokenSpan span, String message, String sourceCode) {
		messages.add(new Message(Severity.NOTE, span, message, sourceCode));
	}
	
	public void setGlobalSourceCode(String sourceCode) {
		this.globalSourceCode = sourceCode;
	}

	public boolean hasErrors () {
		return errorCount > 0;
	}

	public boolean hasWarnings () {
		return warningCount > 0;
	}

	public void printAll () {
		for (Message msg : messages) {
			print(msg);
		}

		if (!messages.isEmpty()) {
			System.out.printf(
					"\nSummary: %d error(s), %d warning(s), %d note(s)\n",
					errorCount,
					warningCount,
					messages.size() - errorCount - warningCount
			);
		}
	}

	private void print (Message msg) {
		String prefix = switch (msg.severity) {
			case ERROR -> "Error";
			case WARNING -> "Warning";
			case NOTE -> "Note";
		};

		String position = msg.span.getPositionString();
		System.out.printf("%s at %s: %s\n", prefix, position, msg.message);
		
		// Try to highlight the source code, but handle cases where sourceCode might not be the actual file content
		String highlighted = msg.span.highlightSourceLine(msg.sourceCode);
		if (highlighted.contains("(Token starts beyond the end of the source code)")) {
			// If the source code doesn't match, try using the global source code
			if (globalSourceCode != null) {
				highlighted = msg.span.highlightSourceLine(globalSourceCode);
				if (highlighted.contains("(Token starts beyond the end of the source code)")) {
					System.out.println("(Unable to highlight source code - span mismatch)");
				} else {
					System.out.println(highlighted);
				}
			} else {
				System.out.println("(Unable to highlight source code - span mismatch)");
			}
		} else {
			System.out.println(highlighted);
		}
	}

	public void clear () {
		messages.clear();
		errorCount = 0;
		warningCount = 0;
	}
}

