package cloth.source

import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.api.SourceModuleRole
import cloth.api.validateCompilationRequest
import cloth.ast.ImportDeclaration
import cloth.diagnostics.CliErrors
import cloth.diagnostics.ImportErrors
import cloth.diagnostics.SourceFile
import cloth.file.ClothObject
import cloth.file.FileExtension
import cloth.lexer.Lexer
import cloth.parser.Parser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** Compiler-owned, manifest-independent discovery. A failed load never returns a partial graph. */
internal object SourceGraphLoader {

	fun load(request: CompilationRequest): SourceGraph {
		val modules = validateCompilationRequest(request).sortedBy { it.id }
		val primary = ClothObject(request.sourceFile.toFile()).path
		return Discovery(modules).load(primary)
	}
}

private enum class LoadState { REGISTERED,
	LOADING,
	LOADED
}

private class Slot(val node: SourceNode, var state: LoadState = LoadState.REGISTERED)
private class Frame(val slot: Slot, var nextImport: Int = 0)
private data class FileIdentity(val fileSystem: FileSystem, val key: Any)
private data class Candidate(val module: SourceModule, val path: Path)

private class Discovery(private val modules: List<SourceModule>) {

	private val slots = mutableListOf<Slot>()
	private val byPath = mutableMapOf<Path, Slot>()
	private val byIdentity = mutableMapOf<FileIdentity, Slot>()
	private val edges = mutableListOf<ImportEdge>()

	fun load(path: Path): SourceGraph {
		val primary = acquire(path, null)
		primary.state = LoadState.LOADING
		val frames = ArrayDeque<Frame>()
		frames.addLast(Frame(primary))
		while (frames.isNotEmpty()) {
			val frame = frames.last()
			val imports = frame.slot.node.unit.imports
			if (frame.nextImport == imports.size) {
				frame.slot.state = LoadState.LOADED
				frames.removeLast()
				continue
			}
			val declaration = imports[frame.nextImport++]
			// Match every root before consulting identity caches or inspecting declarations.
			val candidate = resolve(declaration)
			val target = acquire(candidate.path, declaration)
			edges.add(ImportEdge(frame.slot.node.id, declaration, target.node.id, candidate.module.id, candidate.path, target.state == LoadState.LOADING))
			if (target.state == LoadState.REGISTERED) {
				target.state = LoadState.LOADING
				frames.addLast(Frame(target))
			}
			// Existing LOADING targets keep back-edges; existing LOADED targets are also reused.
		}
		val library = modules.singleOrNull { it.role == SourceModuleRole.STANDARD_LIBRARY }
		val libraryPaths = if (library == null) emptyMap() else edges.filter { it.moduleId == library.id }.groupBy({ it.to }, { library.sourceRoot.relativize(it.selectedPath).joinToString("/") }).mapValues { (_, paths) -> paths.toSet() }
		return SourceGraph(primary.node, slots.map { it.node }, edges, libraryPaths)
	}

	private fun resolve(declaration: ImportDeclaration): Candidate {
		if (modules.isEmpty()) throw ImportErrors.missingContext(declaration)
		val candidates = mutableListOf<Candidate>()
		for (module in modules) {
			val path = exactPath(module.sourceRoot, declaration) ?: continue
			candidates.add(Candidate(module, path))
		}
		val standardLibrary = modules.singleOrNull { it.role == SourceModuleRole.STANDARD_LIBRARY }
		if (standardLibrary != null && declaration.path.components.first().lexeme == "cloth") {
			val conflicts = candidates.filter { it.module.role != SourceModuleRole.STANDARD_LIBRARY }
			if (conflicts.isNotEmpty()) throw ImportErrors.reservedStandardLibraryPath(declaration, standardLibrary.sourceRoot, conflicts.map { it.module.id to it.path })
		}
		if (candidates.isEmpty()) throw ImportErrors.missingSource(declaration)
		if (candidates.size > 1) throw ImportErrors.ambiguousSource(declaration, candidates.map { it.module.id to it.path })
		return candidates.single()
	}

	private fun exactPath(root: Path, declaration: ImportDeclaration): Path? {
		var directory = root
		try {
			for ((index, component) in declaration.path.components.withIndex()) {
				val last = index == declaration.path.components.lastIndex
				val name = component.lexeme + if (last) ".${FileExtension.CO}" else ""
				val matched = Files.newDirectoryStream(directory).use { entries -> entries.firstOrNull { it.fileName.toString() == name } } ?: return null
				val attributes = try {
					Files.readAttributes(matched, BasicFileAttributes::class.java)
				} catch (_: NoSuchFileException) {
					return null
				}
				if (last && !attributes.isRegularFile || !last && !attributes.isDirectory) return null
				directory = matched
			}
			return directory
		} catch (failure: DirectoryIteratorException) {
			throw ImportErrors.sourceAccess(declaration, directory, failure.cause ?: failure)
		} catch (failure: IOException) {
			throw ImportErrors.sourceAccess(declaration, directory, failure)
		}
	}

	private fun acquire(path: Path, origin: ImportDeclaration?): Slot {
		val canonical: Path
		val attributes: BasicFileAttributes
		try {
			canonical = path.toRealPath()
			byPath[canonical]?.let { return it }
			attributes = Files.readAttributes(canonical, BasicFileAttributes::class.java)
			if (!attributes.isRegularFile) throw IOException("Source is not a regular file")
			val identity = attributes.fileKey()?.let { FileIdentity(canonical.fileSystem, it) }
			val cached = if (identity != null) byIdentity[identity] else slots.firstOrNull { canonical.fileSystem == it.node.path.fileSystem && Files.isSameFile(canonical, it.node.path) }
			if (cached != null) {
				byPath[canonical] = cached
				return cached
			}
		} catch (failure: IOException) {
			throw if (origin == null) CliErrors.read(path, failure) else ImportErrors.sourceAccess(origin, path, failure)
		}
		val text = try {
			Files.readString(path, StandardCharsets.UTF_8)
		} catch (failure: IOException) {
			throw if (origin == null) CliErrors.read(path, failure) else ImportErrors.sourceRead(origin, path, failure)
		}
		val unit = Parser(Lexer(SourceFile(path.toString(), text)).scanTokens()).parse()
		val slot = Slot(SourceNode(SourceId(slots.size), canonical, unit, attributes.fileKey()))
		slots.add(slot)
		byPath[canonical] = slot
		attributes.fileKey()?.let { byIdentity[FileIdentity(canonical.fileSystem, it)] = slot }
		return slot
	}
}
