package cloth.semantic

import cloth.ast.SourceUnit
import cloth.target.TargetLayout

/** Complete implemented function semantics: declarations, calls, returns and flow. */
class FunctionAnalyzer(private val target: TargetLayout? = null) {

	fun analyze(unit: SourceUnit): FunctionSemanticModel = CallAnalyzer(validateReturns = true, target = target).analyze(unit)
	fun analyze(declarations: DeclarationModel): FunctionSemanticModel = CallAnalyzer(validateReturns = true, target = target).analyze(declarations)
}
