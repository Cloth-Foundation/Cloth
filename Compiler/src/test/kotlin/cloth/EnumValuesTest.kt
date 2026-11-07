package cloth

import cloth.data.CArray
import cloth.data.CEnum
import cloth.data.ProviderReceiverForm
import cloth.data.ProviderResult
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class EnumValuesTest {

	private val source = """
		typedef<enum> Season (pub def<int> values = 7) {
		    FALL, SPRING, WINTER;
		    operator ==(Season other) = self.values == other.values
		    operator ::str() {
		        print("!")
		        return self::name
		    }
		}
		typedef<enum> Solo { ONLY }
		fn<Season[]> returned() = Season::values
		fn<Season[]> defaulted(opt<Season[]> entries ?: Season::values) = entries
		fn<Season[]> pass(Season[] entries) = entries
		fn<Season[], Solo[]> pair() = Season::values, Solo::values
		fn<Season[]> choose(bool all) = if (all) { Season::values } else { [Season.WINTER] }
		fn<int> main() {
		    def entries = Season::values
		    if (entries::size != 3 || !entries::type::isarray) return 1
		    if (entries::type::element != Season::type || entries::type::extent != 3) return 2
		    println(entries::size)
		    def index = 0
		    for (def entry <- entries) {
		        if (entry -> int != index || entry.values != 7) return 3
		        println(entry::name)
		        entries[1] = Season.FALL
		        entry = Season.WINTER
		        index++
		    }
		    if (index != 3 || entries[0] -> int != 0 || entries[1] -> int != 0) return 4
		    def original = Season::values
		    def copy = original
		    copy[0] = Season.WINTER
		    def later = Season::values
		    if (original[0] -> int != 0 || later[0] -> int != 0 || copy[0] -> int != 2) return 5
		    println(Season::values[1]::name)
		    println(returned()[2]::name)
		    println(pass(defaulted())[0]::name)
		    if (defaulted([Season.WINTER])::size != 1) return 6
		    def both, only = pair()
		    both[0] = Season.WINTER
		    println(only)
		    if (Season::values[0] -> int != 0 || only::size != 1) return 7
		    if (choose(true)::size != 3 || choose(false)::size != 1) return 8
		    def values = 9
		    if (values != 9 || Season.FALL.values != 7 || !(Season.FALL == Season.SPRING)) return 9
		    println(Season::values)
		    return 0
		}
	"""

	@Test
	fun `values binds a type-only array of the exact enum and declared case count`() {
		val facts = FunctionAnalyzer(nativeTestLayout).analyze(structUnit("""
			typedef<enum> E { LAST, FIRST, MiXeD }
			fn<E[3]> all() = E::values
		"""))
		val access = facts.types.providers.values.single()
		val recipe = assertIs<ProviderResult.EnumValues>(access.result)
		val owner = assertIs<CEnum>(recipe.receiverType)
		assertEquals(CArray(owner, 3), recipe.type)
		assertEquals(ProviderReceiverForm.TYPE, access.receiverForm)
		assertEquals(setOf(ProviderReceiverForm.TYPE), recipe.receiverForms)
		assertFalse(recipe.callsFormatter)
	}

	@Test
	fun `values preserves order snapshots independent arrays returns defaults and formatting`(@TempDir directory: Path) {
		val expected = "3\nFALL\nSPRING\nWINTER\nSPRING\nWINTER\nFALL\n[ONLY]\n!!![FALL, SPRING, WINTER]\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			assertEquals(expected, runStructProgram(abi, source, directory.resolve("enum_values_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `values verifies and emits objects across supported targets`(@TempDir directory: Path) {
		val unit = structUnit(source)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(unit), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("enum_values_$index.o"))) > 0)
		}
	}

	@Test
	fun `values requires an enum type and preserves provider and array boundaries`() {
		val prefix = "typedef<enum> E { A, B }\ntypedef<struct> S {}\ntypedef<class> C {}\n"
		val cases = listOf(
			"fn bad() { E.A::values }" to DiagnosticCode.INVALID_PROVIDER_RECEIVER,
			"fn bad(E value) { value::values }" to DiagnosticCode.INVALID_PROVIDER_RECEIVER,
			"fn bad(E? value) { value::values }" to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS,
			"fn bad(E? value) { if (value != null) { value::values } }" to DiagnosticCode.INVALID_PROVIDER_RECEIVER,
			"fn bad(E E) { E::values }" to DiagnosticCode.INVALID_PROVIDER_RECEIVER,
			"fn bad() { E::Values }" to DiagnosticCode.UNKNOWN_PROVIDER,
			"fn bad() { int::values }" to DiagnosticCode.UNKNOWN_PROVIDER,
			"fn bad() { string::values }" to DiagnosticCode.UNKNOWN_PROVIDER,
			"fn bad() { S::values }" to DiagnosticCode.UNKNOWN_PROVIDER,
			"fn bad() { C::values }" to DiagnosticCode.UNKNOWN_PROVIDER,
			"fn bad() { def entries = E::values; entries::values }" to DiagnosticCode.UNKNOWN_PROVIDER,
			"fn bad() { E::type::values }" to DiagnosticCode.UNKNOWN_PROVIDER,
			"fn bad() { def<E[1]> entries = E::values }" to DiagnosticCode.TYPE_MISMATCH,
			"fn<string[]> bad() = E::values" to DiagnosticCode.RETURN_TYPE,
			"fn bad() { E::values = [E.B, E.A] }" to DiagnosticCode.READ_ONLY_PROVIDER,
			"fn bad() { E::values++ }" to DiagnosticCode.READ_ONLY_PROVIDER,
			"fn bad(E value) { value::values() }" to DiagnosticCode.UNSUPPORTED_STATE_CALL,
			"typedef<enum> Invalid { A; operator ::values() = [Invalid.A] }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
		)
		for ((body, code) in cases) {
			val error = assertFailsWith<ClothDiagnostic>(body) {
				FunctionAnalyzer(nativeTestLayout).analyze(structUnit(prefix + body))
			}
			assertEquals(code, error.diagnosticCode, body)
			assertLocated(error)
		}
	}
}
