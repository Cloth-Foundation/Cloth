package cloth.diagnostics

import cloth.semantic.symbols.FunctionSymbol
import cloth.token.TokenSpan

object EntryErrors {

	fun missing(end: TokenSpan?) = ClothDiagnostic(DiagnosticCode.MISSING_MAIN, "Executable program requires a main function", end?.let { listOf(SourceLabel(it, "no main was declared in this source")) } ?: emptyList(), "Declare main(), or compile declarations without an entry point using '--emit object'.")
	fun duplicate(mains: List<FunctionSymbol>) = ClothDiagnostic(DiagnosticCode.DUPLICATE_MAIN, "Executable program must have exactly one main function", mains.map { SourceLabel(it.name.span, "main declared here") }, "Keep one entry point; main() and main(string[] args) cannot coexist in one executable.")
	fun parameters(main: FunctionSymbol) = ClothDiagnostic(DiagnosticCode.INVALID_MAIN_PARAMETERS, "Main must take no parameters or one string[] parameter", listOf(SourceLabel(main.name.span, "invalid entry-point parameter list")), "Use main() or main(string[] args).")
	fun returns(main: FunctionSymbol) = ClothDiagnostic(DiagnosticCode.INVALID_MAIN_RETURN, "Main must return void or a signed int at a supported width", listOf(SourceLabel(main.declaration.returnType?.span ?: main.name.span, "invalid entry-point return type")), "Use void, int, int<8>, int<16>, int<32> or int<64>; omission means void.")
}
