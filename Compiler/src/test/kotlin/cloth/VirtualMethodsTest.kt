package cloth

import cloth.api.ClothCompiler
import cloth.api.CompilationRequest
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.semantic.ProgramAnalyzer
import cloth.settings.OutputKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class VirtualMethodsTest {

	@Test
	fun `virtual calls follow construction and destruction levels and use closed overloads`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("Main.co"), """
			fn<string> defaultLabel(int id) {
			    println("default label")
			    return #"id {id}"
			}
			typedef<interface> Described { mt<string> describe() }
			typedef<class> Entity (pub def<int> id) {
			    Entity(bool log) : (3) { if (log) println(self.describe()) }
			    ~Entity {
			        println(self.describe())
			        println(self is Player)
			    }
			    virtual: pub mt<string> describe() = #"entity {self.id}"
			    virtual: pub mt<string, int> details() = "entity details", self.id
			    virtual: pub mt<string> labeled(opt<string> label ?: defaultLabel(self.id), opt<string> suffix ?: label) = #"{label}:{suffix}"
			    pub mt<string> throughSelf() = describe()
			    pub mt<string> staticText() = "entity static"
			}
			typedef<class> Player (def<int> offset) : Entity(true), Described {
			    Player(bool log) : (7) { if (log) println(self.describe()) }
			    ~Player { println(self.throughSelf()) }
			    override: pub mt<string> describe() = #"player {self.offset}"
			    override: pub mt<string, int> details() = "player details", self.id + self.offset
			    override: pub mt<string> labeled(string text, string ending) = #"player {text}:{ending}"
			    pub mt<string> staticText() = "player static"
			}
			typedef<class> Box<T> (def<T> value) {
			    virtual: pub mt<T> select(opt<T> fallback ?: self.value) = fallback
			}
			typedef<class> Fallback<T> (T value) : Box<T>(value) {
			    override: pub mt<T> select(T selected) = selected
			}
			fn main() {
			    def player = Player::new(true)
			    def<Entity> entity = player
			    def<Described> protocol = player
			    println(entity.describe())
			    println(entity.throughSelf())
			    println(protocol.describe())
			    println(entity.labeled())
			    println(player.labeled())
			    println(player.labeled(label="named"))
			    println(entity.staticText())
			    println(player.staticText())
			    def label, count = entity.details()
			    println(label)
			    println(count)
			    def derived = Fallback<string>::new("original")
			    def<Box<string>> box = derived
			    println(box.select("fallback"))
			    println(derived.select())
			    println(derived.select(fallback="generic label"))
			    box::delete
			    entity::delete
			}
		""".trimIndent())
		val expected = """
			entity 3
			player 7
			player 7
			player 7
			player 7
			default label
			player id 3:id 3
			default label
			player id 3:id 3
			player named:named
			entity static
			player static
			player details
			10
			fallback
			original
			generic label
			player 7
			entity 3
			true
		""".trimIndent() + "\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			val request = CompilationRequest(source, directory.resolve("virtual_${abi.layout.sizeBits}"), targetTriple = abi.targetTriple)
			assertEquals(expected, runExecutable(ClothCompiler.compile(request).artifact).replace("\r\n", "\n"), abi.targetTriple)
		}
		for ((index, triple) in structTargetTriples.withIndex()) {
			val request = CompilationRequest(source, directory.resolve("virtual_$index.o"), outputKind = OutputKind.OBJECT, targetTriple = triple)
			assertTrue(Files.size(ClothCompiler.compile(request).artifact) > 0, triple)
		}
	}

	@Test
	fun `virtual dispatch checks receiver liveness again after a base default`(@TempDir directory: Path) {
		val source = Files.writeString(directory.resolve("Main.co"), """
			typedef<class> Entity {
			    virtual: pub mt<int> read(Entity alias, opt<int> value ?: discard(alias)) = value
			}
			typedef<class> Player : Entity {
			    override: pub mt<int> read(Entity alias, int value) {
			        println("override ran")
			        return value
			    }
			}
			fn<int> discard(Entity value) { value::delete; return 4 }
			fn main() {
			    def player = Player::new
			    println(player.read(player))
			}
		""".trimIndent())
		for (abi in structRunners()) {
			val request = CompilationRequest(source, directory.resolve("stale_virtual_${abi.layout.sizeBits}"), targetTriple = abi.targetTriple)
			val output = runExecutable(ClothCompiler.compile(request).artifact, status = 1)
			assertContains(output, "Class object is no longer live")
			assertFalse(output.contains("override ran"), output)
		}
	}

	@Test
	fun `virtual declarations and overrides require public exact marked signatures`() {
		val base = "typedef<class> Entity { virtual: pub mt<int, bool> read(int value) = value, true }"
		val optionalBase = "typedef<class> Entity { virtual: pub mt<int> read(opt<int> value ?: 1) = value }"
		val cases = listOf(
			"typedef<class> Entity { virtual: mt read() {} }" to DiagnosticCode.INVALID_VIRTUAL_METHOD,
			"$base\ntypedef<class> Player : Entity { override: mt<int, bool> read(int value) = value, true }" to DiagnosticCode.INVALID_VIRTUAL_METHOD,
			"$base\ntypedef<class> Player : Entity { pub mt<int, bool> read(int value) = value, true }" to DiagnosticCode.INVALID_VIRTUAL_METHOD,
			"$base\ntypedef<class> Player : Entity { override: pub mt<bool, int> read(int value) = true, value }" to DiagnosticCode.INVALID_VIRTUAL_METHOD,
			"$base\ntypedef<class> Player : Entity { override: pub mt<int, bool> read(bool value) = 0, value }" to DiagnosticCode.INVALID_INTERFACE_OVERRIDE,
			"$optionalBase\ntypedef<class> Player : Entity { override: pub mt<int> read(opt<int> renamed ?: 2) = renamed }" to DiagnosticCode.INVALID_VIRTUAL_METHOD,
			"typedef<class> Entity { virtual: def<int> value = 0 }" to DiagnosticCode.EXPECTED_TOKEN,
		)
		for ((source, code) in cases) {
			val failure = assertFailsWith<ClothDiagnostic>(source) { ProgramAnalyzer(nativeTestLayout).analyze(structUnit("$source\nfn main() {}")) }
			assertEquals(code, failure.diagnosticCode, source)
			assertLocated(failure)
		}
	}
}
