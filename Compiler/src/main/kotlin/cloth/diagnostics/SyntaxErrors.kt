package cloth.diagnostics

import cloth.parser.exception.SyntaxException
import cloth.token.Token
import cloth.token.TokenType

object SyntaxErrors {

	private fun found(token: Token) = when (token.type) {
		TokenType.EOF     -> "end of file"
		TokenType.NewLine -> "a newline"
		else              -> "'${token.lexeme}'"
	}

	fun expected(description: String, token: Token) = SyntaxException(DiagnosticCode.EXPECTED_TOKEN, "Expected $description, found ${found(token)}", token.span)
	fun newline(token: Token) = SyntaxException(DiagnosticCode.EXPECTED_NEWLINE, "Expected a newline after statement, found ${found(token)}", token.span, "Start the next statement on a new line.")
	fun reassignment(token: Token) = SyntaxException(DiagnosticCode.INVALID_REASSIGNMENT, "':=' is only allowed in a binding declaration; use '=' for reassignment", token.span)
	fun operator(token: Token) = SyntaxException(DiagnosticCode.UNSUPPORTED_OPERATOR, "Unsupported operator '${token.lexeme}'", token.span)
	fun number(token: Token, floating: Boolean) = SyntaxException(if (floating) DiagnosticCode.INVALID_FLOAT else DiagnosticCode.INVALID_INTEGER, "Invalid or out-of-range ${if (floating) "float" else "int"} '${token.lexeme}'", token.span)
	fun expression(token: Token) = SyntaxException(DiagnosticCode.EXPECTED_EXPRESSION, "Expected a number, boolean, character, string, binding name, or '(', got ${found(token)}", token.span)
}
