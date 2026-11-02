package cloth.diagnostics

import cloth.semantic.symbols.FunctionSymbol
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.TokenSpan

object EntryErrors {

	fun missing(end: TokenSpan?) = ClothDiagnostic(DiagnosticCode.MISSING_MAIN, "Executable program requires a main function", end?.let { listOf(SourceLabel(it, "no main was declared in this source")) } ?: emptyList(), "Declare main${Operators.PAREN_OPEN}${Operators.PAREN_CLOSE}, or compile declarations without an entry point using '--emit object'.")
	fun duplicate(mains: List<FunctionSymbol>) = ClothDiagnostic(DiagnosticCode.DUPLICATE_MAIN, "Executable program must have exactly one main function", mains.map { SourceLabel(it.name.span, "main declared here") }, "Keep one entry point; main${Operators.PAREN_OPEN}${Operators.PAREN_CLOSE} and main${Operators.PAREN_OPEN}${Keywords.STRING}${Operators.BRACKET_OPEN}${Operators.BRACKET_CLOSE} args${Operators.PAREN_CLOSE} cannot coexist in one executable.")
	fun parameters(main: FunctionSymbol) = ClothDiagnostic(DiagnosticCode.INVALID_MAIN_PARAMETERS, "Main must take no parameters or one ${Keywords.STRING}${Operators.BRACKET_OPEN}${Operators.BRACKET_CLOSE} parameter", listOf(SourceLabel(main.name.span, "invalid entry-point parameter list")), "Use main${Operators.PAREN_OPEN}${Operators.PAREN_CLOSE} or main${Operators.PAREN_OPEN}${Keywords.STRING}${Operators.BRACKET_OPEN}${Operators.BRACKET_CLOSE} args${Operators.PAREN_CLOSE}.")
	fun returns(main: FunctionSymbol) = ClothDiagnostic(DiagnosticCode.INVALID_MAIN_RETURN, "Main must return ${Keywords.VOID}, ${Keywords.ISIZE} or a signed ${Keywords.INTEGER} at a supported width", listOf(SourceLabel(main.declaration.returnTypes.firstOrNull()?.span?.through(main.declaration.returnTypes.last().span)
			?: main.name.span, "invalid entry-point return type")), "Use ${Keywords.VOID}, ${Keywords.ISIZE}, ${Keywords.INTEGER}, ${Keywords.INTEGER}${Operators.LESS}8${Operators.GREATER}, ${Keywords.INTEGER}${Operators.LESS}16${Operators.GREATER}, ${Keywords.INTEGER}${Operators.LESS}32${Operators.GREATER} or ${Keywords.INTEGER}${Operators.LESS}64${Operators.GREATER}; omission means ${Keywords.VOID}.")
}
