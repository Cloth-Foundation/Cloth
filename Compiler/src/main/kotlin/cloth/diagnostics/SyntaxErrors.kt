package cloth.diagnostics

import cloth.parser.exception.SyntaxException
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType

object SyntaxErrors {

	private fun found(token: Token) = when (token.type) {
		TokenType.EOF     -> "end of file"
		TokenType.NewLine -> "a newline"
		else              -> "'${token.lexeme}'"
	}

	fun expected(description: String, token: Token) = SyntaxException(DiagnosticCode.EXPECTED_TOKEN, "Expected $description, found ${found(token)}", token.span)
	fun newline(token: Token) = SyntaxException(DiagnosticCode.EXPECTED_NEWLINE, "Expected a newline or '${Operators.SEMICOLON}' after statement, found ${found(token)}", token.span, "Start the next statement on a new line or separate it with '${Operators.SEMICOLON}'.")
	fun reassignment(token: Token) = SyntaxException(DiagnosticCode.INVALID_REASSIGNMENT, "'${Operators.FINAL_ASSIGN}' is only allowed in a binding declaration; use '${Operators.ASSIGN}' for reassignment", token.span)
	fun readOnlyProvider(name: Token) = SyntaxException(DiagnosticCode.READ_ONLY_PROVIDER, "Compiler data accessor '${name.lexeme}' is read-only", name.span, "Copy the result into a mutable binding before changing that independent value.")
	fun functionInType(keyword: Token) = SyntaxException(DiagnosticCode.FUNCTION_IN_TYPE, "A '${Keywords.FUNCTION}' declaration is not allowed inside '${Keywords.TYPEDEF}'", keyword.span, "Declare a method with '${Keywords.METHOD}' in the type body.")
	fun methodOutsideType(keyword: Token) = SyntaxException(DiagnosticCode.METHOD_OUTSIDE_TYPE, "A '${Keywords.METHOD}' declaration requires an enclosing '${Keywords.TYPEDEF}'", keyword.span)
	fun operator(token: Token) = SyntaxException(DiagnosticCode.UNSUPPORTED_OPERATOR, "Unsupported operator '${token.lexeme}'", token.span)
	fun number(token: Token, floating: Boolean) = SyntaxException(if (floating) DiagnosticCode.INVALID_FLOAT else DiagnosticCode.INVALID_INTEGER, "Invalid or out-of-range ${if (floating) Keywords.FLOAT.toString() else Keywords.INTEGER.toString()} '${token.lexeme}'", token.span)
	fun expression(token: Token) = SyntaxException(DiagnosticCode.EXPECTED_EXPRESSION, "Expected a number, boolean, character, string, '${Keywords.NULL}', binding name, '${Keywords.IF}', or '${Operators.PAREN_OPEN}', got ${found(token)}", token.span)
}
