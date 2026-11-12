package cloth.semantic

import cloth.data.*
import cloth.diagnostics.IntrinsicErrors
import cloth.parser.declaration.*
import cloth.semantic.declarations.DeclarationModel
import cloth.semantic.declarations.FileContext
import cloth.semantic.symbols.CallableContractSymbol
import cloth.semantic.typing.FunctionType
import cloth.token.Keywords

internal enum class LibraryIntrinsic(val id: String, val source: String, val owner: String? = null) {
	DECODE_UTF8("text.utf8.decode", "cloth/text/Utf8.co"),
	FILE_OPEN("io.file.open", "cloth/io/fs/File.co", "File"),
	FILE_READ("io.file.read", "cloth/io/fs/File.co", "File");

	companion object {
		val bytes = CArray(CInt(8, unsigned = true))
	}
}

internal enum class FileIoError(val spelling: String) {
	INVALID_PATH("InvalidPath"), NOT_FOUND("NotFound"), PERMISSION_DENIED("PermissionDenied"),
	OPEN_FAILED("OpenFailed"), ALREADY_OPEN("AlreadyOpen"), NOT_OPEN("NotOpen"), READ_FAILED("ReadFailed"),
}

/** Check the original declarations, including generic templates with no instantiations. */
internal fun checkIntrinsicDeclarations(files: List<FileContext>): Map<IntrinsicBody, LibraryIntrinsic> {
	val operations = LibraryIntrinsic.entries.associateBy(LibraryIntrinsic::id)
	val declarations = linkedMapOf<LibraryIntrinsic, IntrinsicBody>()
	fun register(callable: CallableDeclaration, file: FileContext, owner: ClassDeclaration?, topLevel: Boolean) {
		val body = callable.body as? IntrinsicBody ?: return
		if (file.standardLibraryPaths.isEmpty()) throw IntrinsicErrors.invalid(body, "requires a declaration in the selected standard library")
		val operation = operations[body.id] ?: throw IntrinsicErrors.invalid(body, "unknown operation")
		if (operation.source !in file.standardLibraryPaths || operation.owner != owner?.name?.lexeme || !topLevel) {
			throw IntrinsicErrors.invalid(body, "requires the registered declaration in ${operation.source}" + (operation.owner?.let { " on class '$it'" } ?: ""))
		}
		if (callable is FunctionDeclaration && callable.typeParameters != null ||
			owner != null && (owner.typeParameters != null || owner.finalKeyword == null) ||
			callable is MethodDeclaration && (callable.virtualPrefix != null || callable.overridePrefix != null) ||
			callable.parameters.any { it.optional }) {
			throw IntrinsicErrors.invalid(body, "requires a concrete function or ordinary method on a '${Keywords.FINAL}' class with required parameters")
		}
		declarations.putIfAbsent(operation, body)?.let { throw IntrinsicErrors.duplicate(body, it) }
	}
	fun visit(declaration: Declaration, file: FileContext, topLevel: Boolean) {
		when (declaration) {
			is FunctionDeclaration -> register(declaration, file, null, topLevel)
			is ClassDeclaration -> declaration.members.filterIsInstance<MethodDeclaration>().forEach { register(it, file, declaration, topLevel) }
			else -> Unit
		}
		if (declaration is TypeDeclaration) declaration.nestedTypes.forEach { visit(it, file, false) }
	}
	for (file in files) file.unit.declarations.forEach { visit(it, file, true) }
	return declarations.entries.associate { (operation, body) -> body to operation }
}

internal fun bindIntrinsics(declarations: DeclarationModel, signatures: Map<CallableContractSymbol, FunctionType>): Map<CallableContractSymbol, LibraryIntrinsic> =
	buildMap {
		for ((callable, signature) in signatures) {
			val body = callable.declaration.executableBody as? IntrinsicBody ?: continue
			val operation = declarations.files.intrinsicBodies.getValue(body)
			val expected = when (operation) {
				LibraryIntrinsic.DECODE_UTF8 -> FunctionType(listOf(LibraryIntrinsic.bytes), CNullable(ValueType.STRING))
				LibraryIntrinsic.FILE_OPEN, LibraryIntrinsic.FILE_READ -> {
					val errors = declarations.files.context(callable.name).localTypes["FileError"] as? CEnum
					if (errors == null || FileIoError.entries.any { it.spelling !in errors.cases }) {
						throw IntrinsicErrors.invalid(body, "requires the library FileError enum with its registered cases")
					}
					val error = CNullable(errors)
					if (operation == LibraryIntrinsic.FILE_OPEN) FunctionType(listOf(ValueType.STRING), error)
					else FunctionType(listOf(CUSize(declarations.target.sizeBits)), ResultShape.of(listOf(CNullable(LibraryIntrinsic.bytes), error)))
				}
			}
			if (signature != expected) {
				val parameters = expected.parameters.joinToString { it.spelling }
				val results = expected.results.values.joinToString { it.spelling }
				throw IntrinsicErrors.invalid(body, "expected parameters ($parameters) and results ($results)")
			}
			put(callable, operation)
		}
	}
