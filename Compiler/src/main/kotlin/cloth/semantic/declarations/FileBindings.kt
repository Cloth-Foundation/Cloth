package cloth.semantic.declarations

import cloth.ast.ImportSelection
import cloth.ast.SourceUnit
import cloth.data.CClass
import cloth.data.CEnum
import cloth.data.CInterface
import cloth.data.CMemberType
import cloth.data.CStruct
import cloth.diagnostics.ImportErrors
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.diagnostics.SourceFile
import cloth.diagnostics.StateErrors
import cloth.parser.declaration.ClassDeclaration
import cloth.parser.declaration.Declaration
import cloth.parser.declaration.EnumDeclaration
import cloth.parser.declaration.FunctionDeclaration
import cloth.parser.declaration.InterfaceDeclaration
import cloth.parser.declaration.StateClassMember
import cloth.parser.declaration.StructDeclaration
import cloth.parser.declaration.TopLevelStateDeclaration
import cloth.parser.declaration.TypeDeclaration
import cloth.semantic.symbols.FunctionGroupSymbol
import cloth.semantic.symbols.FunctionSymbol
import cloth.semantic.symbols.LexicalScope
import cloth.semantic.checkIntrinsicDeclarations
import cloth.source.SourceGraph
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenSpan
import cloth.token.TokenType
import java.util.*

internal class FileContext(val unit: SourceUnit, val standardLibraryPaths: Set<String> = emptySet()) {

	val scope = LexicalScope()
	val types = linkedMapOf<String, CMemberType>()
	val localTypes = linkedMapOf<String, CMemberType>()
	val states = linkedMapOf<String, TopLevelStateDeclaration>()
	val localStates = linkedMapOf<String, TopLevelStateDeclaration>()
	val functions = mutableListOf<FunctionSymbol>()
	val exports = linkedMapOf<String, FileExport>()
	val typeImports = mutableMapOf<String, Token>()
	val functionImports = mutableMapOf<String, Token>()
	val stateImports = mutableMapOf<String, Token>()
}

internal class FileExport(val type: CMemberType?, val functions: FunctionGroupSymbol?, val state: TopLevelStateDeclaration?)

private class NestedTypeScope(val declaration: Declaration, val qualifiedName: String) {
	val types = linkedMapOf<String, CMemberType>()

	fun contains(use: TokenSpan): Boolean {
		val owner = declaration.span
		return use.source === owner.source &&
			(use.startLine > owner.startLine || use.startLine == owner.startLine && use.startColumn >= owner.startColumn) &&
			(use.endLine < owner.endLine || use.endLine == owner.endLine && use.endColumn <= owner.endColumn)
	}
}

/** Register original identities across all files before binding any import or resolving a type. */
internal class FileBindings private constructor(val files: List<FileContext>, private val graph: SourceGraph? = null) {

	val primary get() = files.first()
	val intrinsicBodies = checkIntrinsicDeclarations(files)
	val topLevelStates = files.flatMap { it.unit.declarations.filterIsInstance<TopLevelStateDeclaration>() }
	val firstStateSyntax: TokenSpan?
	val structs = IdentityHashMap<StructDeclaration, CStruct>()
	val classes = IdentityHashMap<ClassDeclaration, CClass>()
	val enums = IdentityHashMap<EnumDeclaration, CEnum>()
	val typeOrder = mutableListOf<CMemberType>()
	private val sources = IdentityHashMap<SourceFile, FileContext>()
	private val nestedScopes = IdentityHashMap<Declaration, NestedTypeScope>()
	private val enclosingScopes = IdentityHashMap<TypeDeclaration, NestedTypeScope>()
	private val scopesByFile = IdentityHashMap<FileContext, MutableList<NestedTypeScope>>()
	private val orderedDeclarations = mutableListOf<Declaration>()
	val declarations: List<Declaration> get() = orderedDeclarations
	val objectRoot: CClass = TokenSpan(1, 1, 1, 1).let { span ->
		val keyword = Token(TokenType.Keyword, span, Keywords.CLASS.toString())
		CClass(ClassDeclaration(keyword, Token(TokenType.Identifier, span, "Object"), null, null, emptyList(), emptyList(), span), objectRoot = true).also { it.defineFields(emptyList()) }
	}

	init {
		for (file in files) {
			file.unit.source?.let { sources[it] = file }
			val groups = linkedMapOf<String, MutableList<FunctionSymbol>>()
			for (declaration in file.unit.declarations) {
				if (declaration is TypeDeclaration) {
					registerType(file, declaration)
					continue
				}
				orderedDeclarations.add(declaration)
				if (declaration is FunctionDeclaration) {
					val symbol = FunctionSymbol(declaration) { nominal(it.name) }
					val overloads = groups.getOrPut(symbol.name.lexeme) { mutableListOf() }
					file.functions.add(symbol)
					overloads.add(symbol)
				}
				if (declaration is TopLevelStateDeclaration) {
					val name = declaration.name
					file.states.putIfAbsent(name.lexeme, declaration)?.let { prior ->
						throw StateErrors.declaration("Top-level state '${name.lexeme}' is already declared", name, prior.name)
					}
					file.localStates[name.lexeme] = declaration
					registerNestedTypes(file, declaration, name.lexeme, declaration.nestedTypes)
				}
			}
			for (overloads in groups.values) file.scope.declare(FunctionGroupSymbol(overloads.toList()))
			// Export only this file's public declarations, never received imports.
			for (declaration in file.unit.declarations) {
				val name = when (declaration) {
					is TypeDeclaration -> declaration.name.takeIf { declaration.visibility != null }
					is FunctionDeclaration -> declaration.name.takeIf { declaration.visibility != null }
					is TopLevelStateDeclaration -> declaration.name.takeIf { declaration.visibility != null }
					else                   -> null
				} ?: continue
				val publicFunctions = groups[name.lexeme].orEmpty().filter { it.declaration.visibility != null }
				val publicType = file.types[name.lexeme]?.takeIf { it.declaration.visibility != null }
				val publicState = file.states[name.lexeme]?.takeIf { it.visibility != null }
				file.exports[name.lexeme] = FileExport(publicType, publicFunctions.takeIf { it.isNotEmpty() }?.let(::FunctionGroupSymbol), publicState)
			}
		}
		firstStateSyntax = topLevelStates.firstOrNull()?.keyword?.span ?: typeOrder.asSequence().filterIsInstance<CClass>().firstNotNullOfOrNull { type ->
			type.declaration.members.firstNotNullOfOrNull { (it as? StateClassMember)?.keyword?.span }
		}
	}

	private fun registerType(file: FileContext, declaration: TypeDeclaration, owner: NestedTypeScope? = null) {
		val enclosingName = owner?.qualifiedName
		val type = when (declaration) {
			is StructDeclaration -> CStruct(declaration, enclosingName = enclosingName).also { structs[declaration] = it }
			is ClassDeclaration -> CClass(declaration, enclosingName = enclosingName).also { classes[declaration] = it }
			is EnumDeclaration -> CEnum(declaration, enclosingName).also { enums[declaration] = it }
			is InterfaceDeclaration -> CInterface(declaration, enclosingName = enclosingName)
		}
		val name = type.name.lexeme
		if (name == objectRoot.spelling) throw SemanticErrors.classMember("'${objectRoot.spelling}' is the built-in class root", type.name.span)
		val scope = owner?.types ?: file.types
		scope.putIfAbsent(name, type)?.let { previous ->
			if (type is CStruct && previous is CStruct) throw SemanticErrors.duplicateStruct(type.name, previous.name)
			throw SemanticErrors.duplicateClassType(type.name, previous.name)
		}
		if (owner == null) file.localTypes[name] = type
		else enclosingScopes[declaration] = owner
		orderedDeclarations.add(declaration)
		typeOrder.add(type)
		registerNestedTypes(file, declaration, type.qualifiedName, declaration.nestedTypes)
	}

	private fun registerNestedTypes(file: FileContext, owner: Declaration, qualifiedName: String, types: List<TypeDeclaration>) {
		if (types.isEmpty()) return
		val scope = NestedTypeScope(owner, qualifiedName)
		nestedScopes[owner] = scope
		scopesByFile.getOrPut(file) { mutableListOf() }.add(scope)
		for (type in types) registerType(file, type, scope)
	}

	fun context(name: Token): FileContext = name.span.source?.let { sources[it] } ?: files.singleOrNull() ?: throw InternalErrors.invariant("No defining file context for '${name.lexeme}'")

	private fun lexicalType(file: FileContext, use: Token, name: String): CMemberType? = scopesByFile[file]?.asReversed()
		?.firstNotNullOfOrNull { scope -> scope.types[name]?.takeIf { scope.contains(use.span) } } ?: file.types[name]

	fun nominal(name: Token): CMemberType? {
		if (name.lexeme == objectRoot.spelling) return objectRoot
		val file = context(name)
		val separator = Operators.DOT.toString()
		if (separator !in name.lexeme) return lexicalType(file, name, name.lexeme)

		val path = name.lexeme.split(separator)
		var type = lexicalType(file, name, path.first())
		var owner = type?.declaration ?: file.states[path.first()]
		for (index in 1 until path.size) {
			val scope = owner?.let(nestedScopes::get) ?: return null
			type = scope.types[path[index]] ?: return null
			if (type.declaration.visibility == null && !scope.contains(name.span)) throw SemanticErrors.privateNestedType(name, type)
			owner = type.declaration
		}
		return type
	}

	internal fun privateTypeName(type: CMemberType): Token? {
		if (type === objectRoot) return null
		var declaration: Declaration? = type.declaration
		while (declaration != null) when (declaration) {
			is TypeDeclaration -> {
				if (declaration.visibility == null) return declaration.name
				declaration = enclosingScopes[declaration]?.declaration
			}
			is TopLevelStateDeclaration -> return if (declaration.visibility == null) declaration.name else null
			else -> break
		}
		return null
	}
	fun state(name: Token): TopLevelStateDeclaration? = context(name).states[name.lexeme]
	internal fun fork(): FileBindings = graph?.let { graph(it) } ?: single(primary.unit)

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
					val privateName = source.localTypes[name]?.name ?: source.localStates[name]?.name ?: source.functions.firstOrNull { it.name.lexeme == name }?.name
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
				exported.state?.let { state ->
					val previous = file.states[name]
					if (previous != null && previous !== state) throw ImportErrors.conflict(name, token, previous.name, state.name, file.stateImports[name])
					if (previous == null) {
						file.states[name] = state
						file.stateImports[name] = token
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

		fun graph(graph: SourceGraph): FileBindings = FileBindings(
			graph.sources.map { FileContext(it.unit, graph.standardLibraryPaths(it.id)) }, graph,
		).also { it.bind(graph) }
	}
}
