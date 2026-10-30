package cloth.semantic

import cloth.ast.SourceUnit
import cloth.diagnostics.EntryErrors
import cloth.semantic.symbols.FunctionSymbol

enum class ProgramKind { EXECUTABLE, LIBRARY }

data class ProgramModel(val kind: ProgramKind, val functions: FunctionSemanticModel, val entryPoint: FunctionSymbol?)

/** Entry-point rules apply to executables, independently of function-body validation. */
class ProgramAnalyzer {

	fun analyze(unit: SourceUnit, kind: ProgramKind = ProgramKind.EXECUTABLE): ProgramModel {
		val functions = FunctionAnalyzer().analyze(unit)
		if (kind == ProgramKind.LIBRARY) return ProgramModel(kind, functions, null)
		val mains = functions.declarations.functions.filter { it.name.lexeme == "main" }
		if (mains.isEmpty()) throw EntryErrors.missing(unit.end)
		if (mains.size > 1) throw EntryErrors.duplicate(mains)
		val main = mains.single()
		val type = functions.typeOf(main)
		if (type.parameters.isNotEmpty() && type.parameters != listOf(ValueType.ArrayValue(ValueType.STRING))) throw EntryErrors.parameters(main)
		if (type.returns != ValueType.VOID && (type.returns !is ValueType.Integer || type.returns.unsigned)) throw EntryErrors.returns(main)
		return ProgramModel(kind, functions, main)
	}
}
