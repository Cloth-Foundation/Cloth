package cloth.semantic

import cloth.ast.SourceUnit

/** Complete implemented function semantics: declarations, calls, returns and flow. */
class FunctionAnalyzer {

	fun analyze(unit: SourceUnit): FunctionSemanticModel = CallAnalyzer(validateReturns = true).analyze(unit)
	fun analyze(declarations: DeclarationModel): FunctionSemanticModel = CallAnalyzer(validateReturns = true).analyze(declarations)
}
