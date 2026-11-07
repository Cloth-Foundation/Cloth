package cloth

import cloth.data.CString
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

class EnumNameTest {

	private val source = """
		typedef<enum> Plain { MiXeD, OTHER }
		typedef<enum> Styled (pub def<string> name) {
		    FIRST("friendly"), LAST("later");
		    operator ::str() {
		        print("!")
		        return self.name
		    }
		}
		fn<Styled> next() {
		    print(">")
		    return Styled.LAST
		}
		fn<string> defaultName(Styled value, opt<string> label ?: value::name) = label
		fn<Styled?> maybe(bool present) = if (present) { Styled.FIRST } else { null }
		fn<int> main() {
		    println(Plain.MiXeD::name)
		    println(Plain.OTHER::str)
		    println(Styled.FIRST.name)
		    println(Styled.FIRST::name)
		    println(Styled.FIRST::str)
		    println(next()::name)
		    println(defaultName(Styled.LAST))
		    def<Styled?> present = maybe(true)
		    if (present != null) {
		        println(#"{present::name}")
		    } else {
		        return 1
		    }
		    def current = Styled.FIRST
		    def saved = current::name
		    current = Styled.LAST
		    if (current::name != "LAST") return 2
		    println(saved)
		    return 0
		}
	"""

	@Test
	fun `name binds a string value recipe with no user formatter effects`() {
		val facts = FunctionAnalyzer(nativeTestLayout).analyze(structUnit("""
			typedef<enum> E { A; operator ::str() = "custom" }
			fn<string> name(E value) = value::name
		"""))
		val recipe = assertIs<ProviderResult.EnumName>(facts.types.providers.values.single().result)
		assertSame(CString, recipe.type)
		assertEquals(setOf(ProviderReceiverForm.VALUE), recipe.receiverForms)
		assertFalse(recipe.callsFormatter)
	}

	@Test
	fun `name preserves case spelling receiver effects and independent returned text`(@TempDir directory: Path) {
		val expected = "MiXeD\nOTHER\nfriendly\nFIRST\n!friendly\n>LAST\nLAST\nFIRST\nFIRST\n" + processCompletion().replace("\r\n", "\n")
		for (abi in structRunners()) {
			assertEquals(expected, runStructProgram(abi, source, directory.resolve("enum_name_${abi.layout.sizeBits}")))
		}
	}

	@Test
	fun `name verifies and emits objects across supported targets`(@TempDir directory: Path) {
		val unit = structUnit(source)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(unit), "target triple = \"$triple\"")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("enum_name_$index.o"))) > 0)
		}
	}

	@Test
	fun `name requires an enum value and ordinary nullable presence proofs`() {
		val prefix = "typedef<enum> E { A }\n"
		val cases = listOf(
			"fn<string> bad() = E::name" to DiagnosticCode.INVALID_PROVIDER_RECEIVER,
			"fn<string> bad(E? value) = value::name" to DiagnosticCode.NULLABLE_PAYLOAD_ACCESS,
			"fn<string> bad(E value) = value::Name" to DiagnosticCode.UNKNOWN_PROVIDER,
			"fn<string> bad() = 1::name" to DiagnosticCode.UNKNOWN_PROVIDER,
			"fn<string> bad(E value) = value::name()" to DiagnosticCode.UNSUPPORTED_STATE_CALL,
			"typedef<enum> Invalid { A; operator ::name() = \"custom\" }" to DiagnosticCode.INVALID_OPERATOR_DECLARATION,
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
