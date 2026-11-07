package cloth.semantic.declarations

import cloth.ast.ImportSelection
import cloth.ast.SourceUnit
import cloth.data.CClass
import cloth.data.CEnum
import cloth.data.CMemberType
import cloth.data.CStruct
import cloth.diagnostics.ImportErrors
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.diagnostics.SourceFile
import cloth.parser.declaration.ClassDeclaration
import cloth.parser.declaration.EnumDeclaration
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.declaration.StructDeclaration
import cloth.semantic.symbols.FunctionGroupSymbol
import cloth.semantic.symbols.FunctionSymbol
import cloth.semantic.symbols.LexicalScope
import cloth.source.SourceGraph
import cloth.token.Keywords
import cloth.token.Token
import cloth.token.TokenSpan
import cloth.token.TokenType
import java.util.*

internal class FileContext(val unit: SourceUnit) {

	val scope = LexicalScope()
	val types = linkedMapOf<String, CMemberType>()
	val localTypes = linkedMapOf<String, CMemberType>()
	val functions = mutableListOf<FunctionSymbol>()
	val exports = linkedMapOf<String, FileExport>()
	val typeImports = mutableMapOf<String, Token>()
	val functionImports = mutableMapOf<String, Token>()
}

internal class FileExport(val type: CMemberType?, val functions: FunctionGroupSymbol?)

/** Register original identities across all files before binding any import or resolving a type. */
internal class FileBindings private constructor(val files: List<FileContext>) {

	val primary get() = files.first()
	val structs = IdentityHashMap<StructDeclaration, CStruct>()
	val classes = IdentityHashMap<ClassDeclaration, CClass>()
	val enums = IdentityHashMap<EnumDeclaration, CEnum>()
	val typeOrder = mutableListOf<CMemberType>()
	private val sources = IdentityHashMap<SourceFile, FileContext>()
	val objectRoot: CClass = TokenSpan(1, 1, 1, 1).let { span ->
		val keyword = Token(TokenType.Keyword, span, Keywords.CLASS.toString())
		CClass(ClassDeclaration(keyword, Token(TokenType.Identifier, span, "Object"), null, null, emptyList(), emptyList(), span)).also { it.defineFields(emptyList()) }
	}

	init {
		for (file in files) {
			file.unit.source?.let { sources[it] = file }
			val groups = linkedMapOf<String, MutableList<FunctionSymbol>>()
			for (declaration in file.unit.declarations) {
				val type = when (declaration) {
					is StructDeclaration -> CStruct(declaration).also { structs[declaration] = it }
					is ClassDeclaration  -> CClass(declaration).also { classes[declaration] = it }
					is EnumDeclaration   -> CEnum(declaration).also { enums[declaration] = it }
					else                 -> null
				}
				if (type != null) {
					typeOrder.add(type)
					file.localTypes[type.spelling] = type
					if (type.spelling == objectRoot.spelling) throw SemanticErrors.classMember("'${objectRoot.spelling}' is the built-in class root", type.name.span)
					file.types.putIfAbsent(type.spelling, type)?.let { previous ->
						if (type is CStruct && previous is CStruct) throw SemanticErrors.duplicateStruct(type.name, previous.name)
						throw SemanticErrors.duplicateClassType(type.name, previous.name)
					}
				}
				if (declaration is FunctionDeclaration) {
					val symbol = FunctionSymbol(declaration) { nominal(it.name) }
					file.functions.add(symbol)
					groups.getOrPut(symbol.name.lexeme) { mutableListOf() }.add(symbol)
				}
			}
			for (overloads in groups.values) file.scope.declare(FunctionGroupSymbol(overloads.toList()))
			// Export only this file's public declarations, never received imports.
			for (declaration in file.unit.declarations) {
				val name = when (declaration) {
					is StructDeclaration   -> declaration.name.takeIf { declaration.visibility != null }
					is ClassDeclaration    -> declaration.name.takeIf { declaration.visibility != null }
					is EnumDeclaration     -> declaration.name.takeIf { declaration.visibility != null }
					is FunctionDeclaration -> declaration.name.takeIf { declaration.visibility != null }
					else                   -> null
				} ?: continue
				val publicFunctions = groups[name.lexeme].orEmpty().filter { it.declaration.visibility != null }
				val publicType = file.types[name.lexeme]?.takeIf { owner ->
					when (owner) {
						is CStruct -> owner.declaration.visibility != null
						is CClass  -> owner.declaration.visibility != null
						is CEnum   -> owner.declaration.visibility != null
					}
				}
				file.exports[name.lexeme] = FileExport(publicType, publicFunctions.takeIf { it.isNotEmpty() }?.let(::FunctionGroupSymbol))
			}
		}
	}

	fun context(name: Token): FileContext = name.span.source?.let { sources[it] } ?: files.singleOrNull() ?: throw InternalErrors.invariant("No defining file context for '${name.lexeme}'")

	fun nominal(name: Token): CMemberType? = if (name.lexeme == objectRoot.spelling) objectRoot else context(name).types[name.lexeme]

	private fun bind(graph: SourceGraph) {
		for ((index, file) in files.withIndex()) for (edge in graph.importsOf(graph.sources[index].id)) {
			val source = files[edge.to.index]
			val selection = edge.declaration.selection
			val names = when (selection) {
				is ImportSelection.Names    -> selection.names.map { it.lexeme to it }
				is ImportSelection.Wildcard -> source.exports.keys.map { it to selection.star }
			}
			for ((name, token) in names) {
				val exported = source.exports[name] ?: run {
					val privateName = source.localTypes[name]?.name ?: source.functions.firstOrNull { it.name.lexeme == name }?.name
					if (privateName != null) throw ImportErrors.privateName(token, privateName)
					throw ImportErrors.missingName(token, edge.declaration)
				}
				exported.type?.let { type ->
					val previous = file.types[name]
					if (previous != null && previous !== type) throw ImportErrors.conflict(name, token, previous.name, type.name, file.typeImports[name])
					if (previous == null) {
						file.types[name] = type
						file.typeImports[name] = token
					}
				}
				exported.functions?.let { functions ->
					val previous = file.scope.lookup(name) as? FunctionGroupSymbol
					if (previous != null && !previous.overloads.containsAll(functions.overloads)) throw ImportErrors.conflict(name, token, previous.name, functions.name, file.functionImports[name])
					if (previous == null) {
						file.scope.declare(functions)
						file.functionImports[name] = token
					}
				}
			}
		}
	}

	companion object {

		fun single(unit: SourceUnit): FileBindings {
			ImportErrors.requireResolved(unit)
			return FileBindings(listOf(FileContext(unit)))
		}

		fun graph(graph: SourceGraph): FileBindings = FileBindings(graph.sources.map { FileContext(it.unit) }).also { it.bind(graph) }
	}
}
