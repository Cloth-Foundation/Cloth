package shuttle

import java.nio.file.Files
import java.nio.file.Path

internal data class ProjectFixture(val root: Path, val build: Path, val module: Path, val moduleManifest: Path, val source: Path)

internal fun projectFixture(root: Path): ProjectFixture {
	Files.createDirectories(root)
	val module = Files.createDirectories(root.resolve("Frontend"))
	val moduleManifest = Files.writeString(module.resolve("module.toml"), "[module]\nname = 'Frontend'\n")
	val source = Files.writeString(module.resolve("Main.co"), "fn main() {}")
	val build = Files.writeString(root.resolve("build.toml"), projectText())
	return ProjectFixture(root.toRealPath(), build, module, moduleManifest, source)
}

internal fun projectText(
	name: String = "Demo",
	entry: String = "Frontend.Main.main",
	modules: String = "Frontend = 'Frontend'",
	extra: String = "",
): String = "[project]\nname = ${tomlString(name)}\nentryPoint = ${tomlString(entry)}\n[modules]\n$modules\n$extra"

internal fun tomlString(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

internal fun importedProject(root: Path): ProjectFixture {
	val fixture = projectFixture(root)
	val library = Files.createDirectories(root.resolve("Library/support"))
	Files.writeString(root.resolve("Library/module.toml"), "[module]\nname = 'Library'\n")
	Files.writeString(library.resolve("Worker.co"), "pub fn<int> answer() { print(\"shuttle λ 🚀\"); return 7 }")
	Files.writeString(fixture.source, "using support.Worker: answer\nfn<int> main() = answer()")
	Files.writeString(fixture.build, projectText(modules = "Frontend = 'Frontend'\nLibrary = 'Library'"))
	return fixture
}
