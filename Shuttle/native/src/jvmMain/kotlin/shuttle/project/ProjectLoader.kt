package shuttle.project

import shuttle.diagnostics.ShuttleErrors
import shuttle.manifest.ManifestDocument
import shuttle.manifest.ManifestLoader
import shuttle.manifest.ProjectManifest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Resolves configuration only. Compilation and command execution are separate stages. */
internal object ProjectLoader {
	fun load(invocationDirectory: Path = Path.of("").toAbsolutePath(), project: Path? = null): ResolvedProject {
		val selected = select(invocationDirectory, project)
		val root = try {
			selected.parent.toRealPath()
		} catch (failure: IOException) {
			throw ShuttleErrors.selection(selected, "the containing directory is inaccessible", failure)
		}
		// A manifest symlink does not move relative configuration to its target's directory.
		val manifest = ManifestLoader.project(root.resolve("build.toml"))
		val modules = mutableListOf<ResolvedModule>()
		for ((id, configured) in manifest.moduleDirectories) {
			val selectedRoot = configuredPath(manifest.document, root, configured, "modules.$id")
			val sourceRoot = try {
				selectedRoot.toRealPath().also { resolved ->
					if (!Files.isDirectory(resolved)) throw ShuttleErrors.moduleRoot(manifest.document, id, selectedRoot)
					for (previous in modules) {
						if (Files.isSameFile(resolved, previous.sourceRoot)) throw ShuttleErrors.duplicateRoot(manifest.document, id, previous.name, resolved)
					}
				}
			} catch (failure: IOException) {
				throw ShuttleErrors.moduleRoot(manifest.document, id, selectedRoot, failure)
			}
			val module = ManifestLoader.module(sourceRoot.resolve("module.toml"))
			if (module.name != id) throw ShuttleErrors.moduleName(module.document, id, module.name)
			modules.add(ResolvedModule(id, module.version, sourceRoot, module.document))
		}
		val output = outputDirectory(manifest.document, configuredPath(manifest.document, root, manifest.outputDirectory, "output.directory"))
		val source = entryFile(manifest, modules)
		return ResolvedProject(manifest.name, manifest.version, root, manifest.document, modules, source, manifest.entry.function, output)
	}

	private fun select(invocationDirectory: Path, project: Path?): Path {
		if (project != null) {
			val selected = if (project.isAbsolute) project else invocationDirectory.toAbsolutePath().resolve(project)
			if (Files.isDirectory(selected)) return selected.resolve("build.toml")
			if (selected.fileName?.toString() == "build.toml") return selected
			throw ShuttleErrors.selection(selected, "expected a project directory or build.toml file")
		}
		var directory = try {
			invocationDirectory.toAbsolutePath().toRealPath().also {
				if (!Files.isDirectory(it)) throw ShuttleErrors.selection(it, "expected an invocation directory")
			}
		} catch (failure: IOException) {
			throw ShuttleErrors.selection(invocationDirectory, "the invocation directory is inaccessible", failure)
		}
		while (true) {
			val candidate = directory.resolve("build.toml")
			try {
				if (exists(candidate)) return candidate
			} catch (failure: IOException) {
				throw ShuttleErrors.read(candidate, failure)
			}
			directory = directory.parent ?: throw ShuttleErrors.projectNotFound(invocationDirectory)
		}
	}

	private fun entryFile(project: ProjectManifest, modules: List<ResolvedModule>): Path {
		val module = modules.singleOrNull { it.name == project.entry.module }
			?: throw ShuttleErrors.entry(project.document, "module '${project.entry.module}' is not declared")
		var path = module.sourceRoot
		for ((index, part) in project.entry.fileParts.withIndex()) {
			val name = part + if (index == project.entry.fileParts.lastIndex) ".co" else ""
			path = try {
				Files.newDirectoryStream(path).use { entries -> entries.firstOrNull { it.fileName.toString() == name } }
					?: throw ShuttleErrors.entry(project.document, "source path component '$name' does not exist with that exact case under '$path'")
			} catch (failure: IOException) {
				throw ShuttleErrors.entry(project.document, "unable to access source path '$path'", failure)
			}
		}
		return try {
			if (!Files.isRegularFile(path)) throw ShuttleErrors.entry(project.document, "source is not a regular file: $path")
			path.toRealPath()
		} catch (failure: IOException) {
			throw ShuttleErrors.entry(project.document, "source is inaccessible: $path", failure)
		}
	}
}

private fun configuredPath(document: ManifestDocument, root: Path, value: String, key: String): Path = try {
	val path = Path.of(value)
	if (path.isAbsolute) path else root.resolve(path)
} catch (failure: InvalidPathException) {
	throw ShuttleErrors.schema(document, key, "invalid directory path", failure)
}

/** Preserve existing filesystem aliases before normalizing a not-yet-created suffix. */
private fun outputDirectory(document: ManifestDocument, requested: Path): Path {
	try {
		var prefix = requested
		val suffix = ArrayDeque<Path>()
		while (!exists(prefix)) {
			suffix.addFirst(prefix.fileName ?: throw ShuttleErrors.output(document, "directory has no existing parent: $requested"))
			prefix = prefix.parent ?: throw ShuttleErrors.output(document, "directory has no existing parent: $requested")
		}
		var result = prefix.toRealPath()
		if (!Files.isDirectory(result)) throw ShuttleErrors.output(document, "not a directory: $prefix")
		for (part in suffix) result = result.resolve(part)
		result = result.normalize()
		if (exists(result) && !Files.isDirectory(result)) throw ShuttleErrors.output(document, "not a directory: $result")
		return result
	} catch (failure: IOException) {
		throw ShuttleErrors.output(document, "directory is inaccessible: $requested", failure)
	}
}

private fun exists(path: Path): Boolean = try {
	Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
	true
} catch (_: NoSuchFileException) {
	false
}
