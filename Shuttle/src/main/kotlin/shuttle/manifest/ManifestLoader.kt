package shuttle.manifest

import cloth.diagnostics.SourceFile
import cloth.token.Keywords
import cloth.token.Operators
import shuttle.diagnostics.ShuttleErrors
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal class ManifestDocument(val path: Path, val source: SourceFile)
internal data class EntrySelection(val module: String, val fileParts: List<String>, val function: String)
internal data class ProjectManifest(val document: ManifestDocument, val name: String, val version: String?, val entry: EntrySelection, val moduleDirectories: Map<String, String>, val outputDirectory: String)
internal data class ModuleManifest(val document: ManifestDocument, val name: String, val version: String?)

/** Standard TOML parsing followed by Shuttle's schema; no Cloth source is read here. */
internal object ManifestLoader {
	fun project(path: Path): ProjectManifest {
		val (document, root) = read(path)
		val project = table(document, root, "project", "project")
		val name = string(document, project, "name", "project.name")
		if (!artifactName(name)) throw ShuttleErrors.schema(document, "project.name", "expected a legal single filename")
		val version = optionalString(document, project, "version", "project.version")
		val entryText = string(document, project, "entryPoint", "project.entryPoint")
		val parts = entryText.split(Operators.DOT.toChar())
		if (parts.size < 3 || parts.any { !identifier(it) } || parts.last() != "main") {
			throw ShuttleErrors.entry(document, "expected a module, a dotted source-file path, and main")
		}
		unknown(document, project, "project", setOf("name", "version", "entryPoint"))
		val modules = table(document, root, "modules", "modules")
		val ids = keys(modules)
		if (ids.isEmpty()) throw ShuttleErrors.schema(document, "modules", "expected at least one module")
		val directories = ids.associateWith { id ->
			if (!identifier(id)) throw ShuttleErrors.schema(document, "modules.$id", "module name '$id' must be one Cloth identifier")
			string(document, modules, id, "modules.$id")
		}
		val output = optionalTable(document, root, "output", "output")
		val outputDirectory = output?.let { optionalString(document, it, "directory", "output.directory") } ?: "build"
		if (output != null) unknown(document, output, "output", setOf("directory"))
		dependencies(document, root)
		unknown(document, root, "", setOf("project", "modules", "output", "dependencies"))
		return ProjectManifest(document, name, version, EntrySelection(parts.first(), parts.drop(1).dropLast(1), parts.last()), directories, outputDirectory)
	}

	fun module(path: Path): ModuleManifest {
		val (document, root) = read(path)
		val module = table(document, root, "module", "module")
		val name = string(document, module, "name", "module.name")
		if (!identifier(name)) throw ShuttleErrors.schema(document, "module.name", "expected one Cloth identifier")
		val version = optionalString(document, module, "version", "module.version")
		unknown(document, module, "module", setOf("name", "version"))
		dependencies(document, root)
		unknown(document, root, "", setOf("module", "dependencies"))
		return ModuleManifest(document, name, version)
	}

	private fun read(path: Path): Pair<ManifestDocument, ManifestTable> {
		val text = try {
			Files.readString(path, StandardCharsets.UTF_8)
		} catch (failure: CharacterCodingException) {
			throw ShuttleErrors.encoding(path, failure)
		} catch (failure: IOException) {
			throw ShuttleErrors.read(path, failure)
		}
		val document = ManifestDocument(path, SourceFile(path.toString(), text))
		val root = try {
			parseManifestToml(text)
		} catch (failure: ManifestTomlException) {
			throw ShuttleErrors.syntax(document, failure.line, failure.column, failure.message ?: "Malformed input", failure.cause ?: failure)
		}
		return document to root
	}

	private fun table(document: ManifestDocument, parent: ManifestTable, key: String, path: String): ManifestTable = optionalTable(document, parent, key, path)
		?: throw ShuttleErrors.schema(document, path, "required table is missing")

	private fun optionalTable(document: ManifestDocument, parent: ManifestTable, key: String, path: String): ManifestTable? {
		val value = parent.entries[key] ?: return null
		return value as? ManifestTable ?: throw ShuttleErrors.schema(document, path, "expected a table")
	}

	private fun string(document: ManifestDocument, parent: ManifestTable, key: String, path: String): String = optionalString(document, parent, key, path)
		?: throw ShuttleErrors.schema(document, path, "required string is missing")

	private fun optionalString(document: ManifestDocument, parent: ManifestTable, key: String, path: String): String? {
		val value = parent.entries[key] ?: return null
		val text = (value as? ManifestText)?.text
		if (text.isNullOrBlank()) throw ShuttleErrors.schema(document, path, "expected a nonblank string")
		return text
	}

	private fun dependencies(document: ManifestDocument, root: ManifestTable) {
		val dependencies = optionalTable(document, root, "dependencies", "dependencies") ?: return
		if (dependencies.entries.isNotEmpty()) throw ShuttleErrors.dependencies(document)
	}

	private fun unknown(document: ManifestDocument, table: ManifestTable, prefix: String, allowed: Set<String>) {
		val unknown = keys(table).firstOrNull { it !in allowed } ?: return
		throw ShuttleErrors.schema(document, if (prefix.isEmpty()) unknown else "$prefix.$unknown", "unknown field")
	}

	private fun keys(table: ManifestTable): List<String> = table.entries.keys.sorted()

	private fun identifier(value: String): Boolean = value.isNotEmpty() &&
		(value.first().isLetter() || value.first() == Operators.UNDERSCORE.toChar()) &&
		value.all { it.isLetterOrDigit() || it == Operators.UNDERSCORE.toChar() } &&
		value != Operators.UNDERSCORE.toString() && Keywords.entries.none { it != Keywords.AUTO && it.toString() == value }

	private fun artifactName(name: String): Boolean {
		if (name in setOf(".", "..") || name.last().let { it == '.' || it == ' ' } || name.any { it.code < 32 || it in "/\\<>:\"|?*" }) return false
		val stem = name.substringBefore('.').uppercase(Locale.ROOT)
		return stem !in setOf("CON", "PRN", "AUX", "NUL") && !Regex("(?:COM|LPT)[1-9]").matches(stem)
	}
}
