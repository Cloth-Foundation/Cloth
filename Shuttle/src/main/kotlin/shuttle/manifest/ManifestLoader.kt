package shuttle.manifest

import cloth.diagnostics.SourceFile
import cloth.token.Keywords
import cloth.token.Operators
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.toml.TomlFactory
import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.fasterxml.jackson.dataformat.toml.TomlReadFeature
import shuttle.diagnostics.ShuttleErrors
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.DateTimeException
import java.util.Locale

internal class ManifestDocument(val path: Path, val source: SourceFile)
internal data class EntrySelection(val module: String, val fileParts: List<String>, val function: String)
internal data class ProjectManifest(val document: ManifestDocument, val name: String, val version: String?, val entry: EntrySelection, val moduleDirectories: Map<String, String>, val outputDirectory: String)
internal data class ModuleManifest(val document: ManifestDocument, val name: String, val version: String?)

/** Standard TOML parsing followed by Shuttle's schema; no Cloth source is read here. */
internal object ManifestLoader {
	private val mapper = TomlMapper(TomlFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(TomlReadFeature.PARSE_JAVA_TIME)
		.build())

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

	private fun read(path: Path): Pair<ManifestDocument, JsonNode> {
		val text = try {
			Files.readString(path, StandardCharsets.UTF_8)
		} catch (failure: CharacterCodingException) {
			throw ShuttleErrors.encoding(path, failure)
		} catch (failure: IOException) {
			throw ShuttleErrors.read(path, failure)
		}
		val document = ManifestDocument(path, SourceFile(path.toString(), text))
		val root = try {
			mapper.readTree(text) ?: mapper.createObjectNode()
		} catch (failure: JsonProcessingException) {
			val location = failure.location
			throw ShuttleErrors.syntax(document, location?.lineNr, location?.columnNr, failure.originalMessage, failure)
		} catch (failure: DateTimeException) {
			throw ShuttleErrors.syntax(document, null, null, "Invalid TOML date/time: ${failure.message}", failure)
		}
		return document to root
	}

	private fun table(document: ManifestDocument, parent: JsonNode, key: String, path: String): JsonNode = optionalTable(document, parent, key, path)
		?: throw ShuttleErrors.schema(document, path, "required table is missing")

	private fun optionalTable(document: ManifestDocument, parent: JsonNode, key: String, path: String): JsonNode? {
		val value = parent.get(key) ?: return null
		if (!value.isObject) throw ShuttleErrors.schema(document, path, "expected a table")
		return value
	}

	private fun string(document: ManifestDocument, parent: JsonNode, key: String, path: String): String = optionalString(document, parent, key, path)
		?: throw ShuttleErrors.schema(document, path, "required string is missing")

	private fun optionalString(document: ManifestDocument, parent: JsonNode, key: String, path: String): String? {
		val value = parent.get(key) ?: return null
		if (!value.isTextual || value.textValue().isBlank()) throw ShuttleErrors.schema(document, path, "expected a nonblank string")
		return value.textValue()
	}

	private fun dependencies(document: ManifestDocument, root: JsonNode) {
		val dependencies = optionalTable(document, root, "dependencies", "dependencies") ?: return
		if (!dependencies.isEmpty) throw ShuttleErrors.dependencies(document)
	}

	private fun unknown(document: ManifestDocument, table: JsonNode, prefix: String, allowed: Set<String>) {
		val unknown = keys(table).firstOrNull { it !in allowed } ?: return
		throw ShuttleErrors.schema(document, if (prefix.isEmpty()) unknown else "$prefix.$unknown", "unknown field")
	}

	private fun keys(table: JsonNode): List<String> = table.properties().map { it.key }.sorted()

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
