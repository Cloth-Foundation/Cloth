package cloth.source

import cloth.ast.ImportDeclaration
import cloth.ast.SourceUnit
import cloth.diagnostics.CliErrors
import cloth.diagnostics.CompilationErrors
import cloth.diagnostics.SourceFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.*

/** Identities are local to one compilation, allocated in depth-first discovery order. */
internal data class SourceId(val index: Int)

internal class SourceNode internal constructor(val id: SourceId, val path: Path, val unit: SourceUnit, internal val fileKey: Any?) {

	val source: SourceFile get() = requireNotNull(unit.source)
}

/** An edge keeps its written import and selected root/path even when the target was cached. */
internal data class ImportEdge(val from: SourceId, val declaration: ImportDeclaration, val to: SourceId, val moduleId: String, val selectedPath: Path, val backEdge: Boolean)

/** Completed discovery only: no merged declarations, symbol lookup or target facts. */
internal class SourceGraph internal constructor(val primary: SourceNode, sources: List<SourceNode>, edges: List<ImportEdge>) {

	val sources: List<SourceNode> = Collections.unmodifiableList(ArrayList(sources))
	val edges: List<ImportEdge> = Collections.unmodifiableList(ArrayList(edges))
	private val outgoing = this.edges.groupBy { it.from }

	fun source(id: SourceId): SourceNode = sources[id.index]
	fun importsOf(id: SourceId): List<ImportEdge> = Collections.unmodifiableList(outgoing[id].orEmpty())

	/** Check the actual artifact path, or a possible final name during pre-ABI validation. */
	fun checkOutput(destination: Path) {
		if (!destination.isAbsolute) throw CompilationErrors.invalid("Output alias checking requires an absolute path: $destination")
		val output = destination.normalize()
		try {
			val attributes = try {
				Files.readAttributes(output, BasicFileAttributes::class.java)
			} catch (_: NoSuchFileException) {
				null
			}
			for (input in sources) {
				val sameKey = attributes?.fileKey()?.let { it == input.fileKey && output.fileSystem == input.path.fileSystem } == true
				if (output == input.path || sameKey || attributes != null && output.fileSystem == input.path.fileSystem && Files.isSameFile(output, input.path)) {
					throw CliErrors.outputOverwritesSource(output)
				}
			}
		} catch (failure: IOException) {
			throw CliErrors.output(failure)
		}
	}
}
