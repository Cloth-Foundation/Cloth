package cloth.diagnostics

import cloth.ast.ImportDeclaration
import cloth.ast.SourceUnit
import cloth.parser.exception.SyntaxException
import cloth.token.Keywords
import cloth.token.Token
import java.nio.file.Path

/** Facts and original tokens only; import checks never render or accumulate errors. */
object ImportErrors {

	fun outsideHeader(keyword: Token) = SyntaxException(DiagnosticCode.IMPORT_OUTSIDE_HEADER, "'${Keywords.USING}' imports must appear in the file header before ordinary declarations", keyword.span)

	fun duplicateSelection(first: Token, repeated: Token) = SyntaxException(DiagnosticCode.DUPLICATE_IMPORT_SELECTION, "Import selection '${repeated.lexeme}' is repeated", repeated.span, related = listOf(SourceLabel(first.span, "First selection here")))

	fun missingContext(declaration: ImportDeclaration) = ClothDiagnostic(DiagnosticCode.MISSING_IMPORT_CONTEXT, "Source import '${declaration.path.spelling}' requires explicitly supplied module roots", listOf(SourceLabel(declaration.path.span, "No module context")), guidance = "Build the project through Shuttle, or supply its module roots in the compiler request.")

	fun missingSource(declaration: ImportDeclaration) = ClothDiagnostic(DiagnosticCode.IMPORTED_SOURCE_NOT_FOUND, "No source file matches import '${declaration.path.spelling}' in the supplied module roots", listOf(SourceLabel(declaration.path.span, "Source not found with this exact case")))

	fun ambiguousSource(declaration: ImportDeclaration, candidates: List<Pair<String, Path>>) = ClothDiagnostic(DiagnosticCode.AMBIGUOUS_IMPORT_SOURCE, "Source import '${declaration.path.spelling}' matches more than one module", listOf(SourceLabel(declaration.path.span, "Ambiguous source path")), guidance = candidates.joinToString("\n") { (module, path) -> "$module: $path" })

	fun sourceAccess(declaration: ImportDeclaration, path: Path, cause: Throwable) = ClothDiagnostic(DiagnosticCode.IMPORT_SOURCE_ACCESS, "Unable to inspect source import '${declaration.path.spelling}' at '$path'", listOf(SourceLabel(declaration.path.span, "Source access failed")), guidance = cause.message, cause = cause)

	fun sourceRead(declaration: ImportDeclaration, path: Path, cause: Throwable) = ClothDiagnostic(DiagnosticCode.SOURCE_READ, "Unable to read source file '$path'", listOf(SourceLabel(declaration.path.span, "Imported here")), guidance = cause.message, cause = cause)

	fun missingName(name: Token, declaration: ImportDeclaration) = ClothDiagnostic(DiagnosticCode.IMPORT_NAME_NOT_FOUND, "Source '${declaration.path.spelling}' does not export '${name.lexeme}'", listOf(SourceLabel(name.span, "Name is not exported")))

	fun privateName(name: Token, definition: Token) = ClothDiagnostic(DiagnosticCode.IMPORT_PRIVATE_NAME, "Cannot import private declaration '${name.lexeme}'", listOf(SourceLabel(name.span, "Private import"), SourceLabel(definition.span, "Private declaration here")))

	fun conflict(name: String, selection: Token, previous: Token, definition: Token, previousImport: Token?) = ClothDiagnostic(DiagnosticCode.IMPORT_NAME_CONFLICT, "Imported name '$name' conflicts with a different declaration", buildList {
		add(SourceLabel(selection.span, "Conflicting import"))
		add(SourceLabel(previous.span, "Existing declaration"))
		add(SourceLabel(definition.span, "Imported declaration"))
		previousImport?.let { add(SourceLabel(it.span, "Earlier import here")) }
	})

	/** A one-file semantic adapter cannot discard imports that have not been resolved. */
	fun requireResolved(unit: SourceUnit) {
		val first = unit.imports.firstOrNull() ?: return
		throw ClothDiagnostic(DiagnosticCode.IMPORTS_REQUIRE_SOURCE_GRAPH, "This single-file analysis requires a resolved source graph to process imports", listOf(SourceLabel(first.span, "Unresolved import")))
	}
}
