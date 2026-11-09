package shuttle

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.api.SourceModule
import cloth.settings.OutputKind
import cloth.toolchain.StandardLibrary
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shuttle.packaging.prepareToolchainBundle
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.*

class ToolchainBundleTest {
	@TempDir lateinit var temporary: Path

	@Test fun `bundle exports pinned sources and reproducible provenance without checkout artifacts`() {
		val (root, library, revision) = fixture()
		Files.createDirectories(library.resolve("build"))
		Files.writeString(library.resolve("build/ignored.o"), "scratch")
		val output = root.resolve("build/bundle")
		prepareToolchainBundle(root, output, "compiler test:1=2")
		val metadataText = Files.readString(output.resolve("toolchain.properties"))
		val metadata = Properties().apply { metadataText.reader().use(::load) }
		assertEquals("1", metadata.getProperty("format"))
		assertEquals("compiler test:1=2", metadata.getProperty("compiler.version"))
		assertEquals("0.0.1", metadata.getProperty("stdlib.version"))
		assertEquals(revision, metadata.getProperty("stdlib.revision"))
		assertEquals("// library root\n", Files.readString(output.resolve("stdlib/cloth/Library.co")))
		assertTrue(Files.isRegularFile(output.resolve("stdlib/cloth/collections/Probe.co")))
		assertFalse(Files.exists(output.resolve("stdlib/.git")))
		assertFalse(Files.exists(output.resolve("stdlib/build")))
		assertFalse(Files.exists(output.resolve("stdlib/README.md")))
		Files.writeString(output.resolve("stdlib/stale.co"), "old")
		prepareToolchainBundle(root, output, "compiler test:1=2")
		assertEquals(metadataText, Files.readString(output.resolve("toolchain.properties")))
		assertFalse(Files.exists(output.resolve("stdlib/stale.co")))
	}

	@Test fun `uncommitted library sources fail before replacing an earlier bundle`() {
		val (root, library) = fixture()
		val output = root.resolve("build/bundle")
		Files.createDirectories(output)
		Files.writeString(output.resolve("keep"), "previous")
		Files.writeString(library.resolve("cloth/Library.co"), "// uncommitted\n")
		val failure = assertFailsWith<IllegalStateException> { prepareToolchainBundle(root, output, "1") }
		assertTrue(failure.message.orEmpty().contains("uncommitted"))
		assertEquals("previous", Files.readString(output.resolve("keep")))
	}

	@Test fun `a different checked out library commit cannot masquerade as the recorded pin`() {
		val (root, library) = fixture()
		Files.writeString(library.resolve("cloth/Library.co"), "// new commit\n")
		commit(library)
		val failure = assertFailsWith<IllegalStateException> { prepareToolchainBundle(root, root.resolve("build/bundle"), "1") }
		assertTrue(failure.message.orEmpty().contains("differs from the compiler pin"))
	}

	@Test fun `incorrect source filename case fails even on case insensitive hosts`() {
		val (root, library) = fixture()
		git(library, "mv", "cloth/Library.co", "cloth/wrong.co")
		val revision = commit(library)
		pin(root, revision)
		val failure = assertFailsWith<IllegalStateException> { prepareToolchainBundle(root, root.resolve("build/bundle"), "1") }
		assertTrue(failure.message.orEmpty().contains("exact filename case"))
	}

	@Test fun `exporter cannot replace the repository or standard library`() {
		val (root, library) = fixture()
		assertFailsWith<IllegalArgumentException> { prepareToolchainBundle(root, root, "1") }
		assertFailsWith<IllegalArgumentException> { prepareToolchainBundle(root, library, "1") }
		assertTrue(Files.exists(library.resolve("cloth/Library.co")))
	}

	@Test fun `exporter packages the public collections and their pinned snapshot compiles through the public API`() {
		val (root, library) = fixture()
		val actual = Path.of("Standard-Library").toAbsolutePath()
		val collections = listOf("List", "Deque", "Queue", "Stack").map { "cloth/collections/$it.co" }
		for (relative in listOf("module.toml", "cloth/Library.co") + collections) {
			Files.copy(actual.resolve(relative), library.resolve(relative), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
		}
		val revision = commit(library)
		pin(root, revision)
		val output = root.resolve("build/public-list-bundle")
		prepareToolchainBundle(root, output, "generic-list-test")
		for (relative in collections) {
			assertContentEquals(Files.readAllBytes(actual.resolve(relative)), Files.readAllBytes(output.resolve("stdlib").resolve(relative)))
		}
		assertFalse(Files.exists(output.resolve("stdlib/.git")))
		val app = Files.createDirectory(root.resolve("app"))
		val source = Files.writeString(app.resolve("Main.co"), """
		    using cloth.collections.List : List
		    using cloth.collections.Deque : Deque
		    using cloth.collections.Queue : Queue
		    using cloth.collections.Stack : Stack
		    fn main() {
		        def values = List<int[]>::new
		        values.append([1]); values.append([2, 3])
		        def copied = values.copy()
		        values::delete; copied::delete

		        def ends = Deque<int>::new
		        ends.pushFront(1); ends.pushBack(2)
		        println(ends.popBack())
		        ends::delete
		        def queue = Queue<string>::new
		        queue.enqueue("first")
		        println(queue.dequeue())
		        queue::delete
		        def stack = Stack<int>::new
		        stack.push(3)
		        println(stack.pop())
		        stack::delete
		    }
		""".trimIndent())
		val selected = StandardLibrary.fromContext(output.toString(), "installed")
		val request = selected.attachTo(CompilationRequest(source, root.resolve("build/public-list.o"), listOf(SourceModule("App", app)), OutputKind.OBJECT, targetTriple = "aarch64-unknown-linux-gnu"))
		val bytes = Files.readAllBytes(ClothCompiler.compile(request).artifact)
		assertEquals(listOf(0x7f, 0x45, 0x4c, 0x46), bytes.take(4).map { it.toInt() and 255 })
		assertEquals(0xb7, bytes[18].toInt() and 255)
	}

	private data class Fixture(val root: Path, val library: Path, val revision: String)

	private fun fixture(): Fixture {
		val root = Files.createDirectory(temporary.resolve("compiler"))
		val library = Files.createDirectory(root.resolve("Standard-Library"))
		git(root, "init", "--quiet")
		git(library, "init", "--quiet")
		Files.writeString(library.resolve(".gitignore"), "build/\n")
		Files.writeString(library.resolve("module.toml"), "[module]\nname = \"StandardLibrary\"\nversion = \"0.0.1\"\n")
		Files.writeString(library.resolve("README.md"), "development documentation\n")
		Files.createDirectories(library.resolve("cloth/collections"))
		Files.writeString(library.resolve("cloth/Library.co"), "// library root\n")
		Files.writeString(library.resolve("cloth/collections/Probe.co"), "pub fn<int> answer() = 42\n")
		val revision = commit(library)
		pin(root, revision)
		return Fixture(root, library, revision)
	}

	private fun pin(root: Path, revision: String) { git(root, "update-index", "--add", "--cacheinfo", "160000,$revision,Standard-Library") }
	private fun commit(library: Path): String {
		git(library, "add", "--all")
		git(library, "-c", "user.name=Cloth test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "fixture")
		return git(library, "rev-parse", "HEAD").trim()
	}
	private fun git(directory: Path, vararg args: String): String {
		val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + args).redirectErrorStream(true).start()
		process.outputStream.close()
		val text = process.inputStream.bufferedReader().readText()
		check(process.waitFor() == 0) { text }
		return text
	}
}
