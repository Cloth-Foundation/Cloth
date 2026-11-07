package cloth.semantic

import cloth.ast.SourceUnit
import cloth.data.*
import cloth.diagnostics.EntryErrors
import cloth.semantic.declarations.FileBindings
import cloth.semantic.symbols.FunctionSymbol
import cloth.semantic.typing.FunctionAnalyzer
import cloth.semantic.typing.FunctionSemanticModel
import cloth.target.TargetLayout

enum class ProgramKind { EXECUTABLE,
	LIBRARY
}

data class ProgramModel(val kind: ProgramKind, val functions: FunctionSemanticModel, val entryPoint: FunctionSymbol?)

/** Entry-point rules apply to executables, independently of function-body validation. */
class ProgramAnalyzer(private val target: TargetLayout) {

	fun analyze(unit: SourceUnit, kind: ProgramKind = ProgramKind.EXECUTABLE): ProgramModel {
		val functions = FunctionAnalyzer(target).analyze(unit)
		return program(functions, kind)
	}

	internal fun analyze(files: FileBindings, kind: ProgramKind = ProgramKind.EXECUTABLE): ProgramModel = program(FunctionAnalyzer(target).analyze(files), kind)

	private fun program(functions: FunctionSemanticModel, kind: ProgramKind): ProgramModel {
		if (kind == ProgramKind.LIBRARY) return ProgramModel(kind, functions, null)
		val primary = functions.declarations.files.primary
		val mains = primary.functions.filter { it.name.lexeme == "main" }
		if (mains.isEmpty()) throw EntryErrors.missing(primary.unit.end)
		if (mains.size > 1) throw EntryErrors.duplicate(mains)
		val main = mains.single()
		val type = functions.typeOf(main)
		if (type.results is ResultShape.Multiple) throw EntryErrors.returns(main)
		if (type.parameters.isNotEmpty() && type.parameters != listOf(CArray(ValueType.STRING)) && type.parameters != listOf(CNullable(CArray(ValueType.STRING)))) throw EntryErrors.parameters(main)
		// The accepted integer entry types are signed fixed-width int and target-sized isize.
		if (type.returns != ValueType.VOID && type.returns !is CISize && (type.returns !is CInt || type.returns.unsigned)) throw EntryErrors.returns(main)
		return ProgramModel(kind, functions, main)
	}
}
