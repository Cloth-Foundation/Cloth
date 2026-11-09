package cloth

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.parser.declaration.ClassDeclaration
import cloth.semantic.typing.FunctionAnalyzer
import cloth.token.Keywords
import cloth.token.Operators
import cloth.token.Token
import cloth.token.TokenType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GenericNativeLimitsTest {
	@Test
	fun `symbolic checking models remain ineligible for native output`(@TempDir directory: Path) {
		val abi = NativeCABI.native()
		val model = FunctionAnalyzer(abi.layout).analyze(structUnit("typedef<class> Box<T> (def<T> value)\nfn main() {}"))
		val output = directory.resolve("absent/out.o")
		val templates = model.declarations.genericViews!!.templates!!
		val error = assertFailsWith<ClothDiagnostic> { LLVMEmitter(abi).compileObject(templates, output) }
		assertEquals(DiagnosticCode.UNSUPPORTED_GENERIC_TYPES, error.diagnosticCode)
		assertLocated(error)
		assertFalse(Files.exists(output.parent))
	}

	@Test
	fun `canonical metadata measures shared DAGs and checks exact UTF8 boundary before expansion`() {
		val declaration = genericUnit("typedef<class> Pair<A, B> {}").declarations.single() as ClassDeclaration
		var type: ValueType = ValueType.INT
		repeat(40) { type = CClass(declaration, typeArguments = listOf(type, type)) }
		val failure = assertFailsWith<ClothDiagnostic> { type.metadataSpelling }
		assertEquals(DiagnosticCode.GENERIC_METADATA_SIZE, failure.diagnosticCode)
		assertLocated(failure)
		val suffix = "${Operators.LESS}${ValueType.INT.metadataSpelling}${Operators.GREATER}"
		val remaining = GENERIC_METADATA_BYTES - suffix.toByteArray(Charsets.UTF_8).size
		val name = "𐐀".repeat(remaining / 4) + "a".repeat(remaining % 4)
		fun renamed(text: String) = CClass(declaration.copy(name = Token(TokenType.Identifier, declaration.name.span, text)), typeArguments = listOf(ValueType.INT))
		assertEquals(GENERIC_METADATA_BYTES, renamed(name).metadataSpelling.toByteArray(Charsets.UTF_8).size)
		assertEquals(DiagnosticCode.GENERIC_METADATA_SIZE, assertFailsWith<ClothDiagnostic> { renamed(name + "a").metadataSpelling }.diagnosticCode)
	}

	@Test
	fun `expanded metadata failure stops before object creation on every target`(@TempDir directory: Path) {
		val owners = (0..24).joinToString("\n") { i ->
			val field = if (i == 24) "${Keywords.METHOD}${Operators.LESS}${Keywords.TYPEINFO}${Operators.GREATER} info() = T${Operators.COLON_COLON}type" else
				"${Keywords.DEFINITION}${Operators.LESS}G${i + 1}${Operators.LESS}Pair${Operators.LESS}T${Operators.COMMA} T${Operators.GREATER}${Operators.GREATER}${Operators.QUESTION}${Operators.GREATER} next = ${Keywords.NULL}"
			"${Keywords.TYPEDEF}${Operators.LESS}${Keywords.CLASS}${Operators.GREATER} G$i${Operators.LESS}T${Operators.GREATER} ${Operators.BRACE_OPEN} $field ${Operators.BRACE_CLOSE}"
		}
		val ast = structUnit("typedef<class> Pair<A, B> {}\n$owners\nfn main() { def value = G0<int>::new; value::delete }")
		for ((i, triple) in structTargetTriples.withIndex()) {
			val output = directory.resolve("absent_$i/out.o")
			val error = assertFailsWith<ClothDiagnostic> { LLVMEmitter(NativeCABI.forTarget(triple)).compileObject(ast, output) }
			assertEquals(DiagnosticCode.GENERIC_METADATA_SIZE, error.diagnosticCode, error.summary)
			assertLocated(error)
			assertFalse(Files.exists(output.parent))
		}
	}

	@Test
	fun `generic inline field DAG size is checked exactly before LLVM layout`(@TempDir directory: Path) {
		for (triple in listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc")) {
			val abi = NativeCABI.forTarget(triple)
			val argument = (1..abi.layout.pointerBits).fold("int") { text, _ -> "Pair${Operators.LESS}$text${Operators.GREATER}" }
			val ast = structUnit("typedef<struct> Pair<T> { T first, T second }\nfn take($argument value) {}")
			val output = directory.resolve("absent_${abi.layout.pointerBits}/out.o")
			val error = assertFailsWith<ClothDiagnostic> { LLVMEmitter(abi).compileObject(ast, output) }
			assertEquals(DiagnosticCode.BUFFER_SIZE, error.diagnosticCode, error.summary)
			assertLocated(error)
			assertFalse(Files.exists(output.parent))
		}
	}
}
