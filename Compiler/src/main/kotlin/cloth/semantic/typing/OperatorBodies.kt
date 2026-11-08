package cloth.semantic.typing

import cloth.data.ValueType
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.MethodDeclaration
import cloth.semantic.ResultShape
import cloth.semantic.flow.ClassLifetimeAnalysis
import cloth.semantic.flow.checkCallableMetadata
import cloth.semantic.flow.checkArrayShapes
import cloth.semantic.symbols.MethodSymbol

/** Checks operator bodies in the requesting analysis and guards active recursion. */
internal class OperatorBodies {

	private val inferring = mutableSetOf<MethodSymbol>()
	private val specializing = mutableSetOf<Pair<MethodSymbol, Int>>()

	fun infer(facts: FunctionSemanticModel, method: MethodSymbol, declared: FunctionType): FunctionType {
		val declaration = method.declaration
		if (!inferring.add(method)) throw SemanticErrors.operatorDeclaration("A recursive operator requires an explicit result type", declaration.span)
		return try {
			FunctionType(declared.parameters, inferResult(facts.forCallableBody(), declaration))
		} finally {
			inferring.remove(method)
		}
	}

	fun specialize(facts: FunctionSemanticModel, template: MethodSymbol, width: Int): MethodSymbol {
		val key = template to width
		val declaration = template.declaration
		if (!specializing.add(key)) throw SemanticErrors.operatorDeclaration("A recursive operand context requires an explicit result type", declaration.span)
		return try {
			val body = facts.forCallableBody(width)
			val annotated = declaration.returnTypes.singleOrNull()?.let(body.types.typeResolver::resolve)
			val result = annotated ?: inferResult(body.trial().model, declaration)
			if (result == ValueType.VOID) throw SemanticErrors.operatorDeclaration("An operand context must produce a value", declaration.span)
			val signature = FunctionType(emptyList(), result)
			// Annotated recursion can see the signature inside this body while it is checked.
			val method = body.reserveSpecialization(template, width, signature)
			BodyTyping(body, declaration, signature.results).checkBody()
			checkArrayShapes(method, body)
			checkCallableMetadata(method, body)
			if (body.declarations.classDefinitions.isNotEmpty()) ClassLifetimeAnalysis(body).analyze(listOf(method))
			facts.publishSpecialization(method, body)
			method
		} finally {
			specializing.remove(key)
		}
	}

	private fun inferResult(facts: FunctionSemanticModel, declaration: MethodDeclaration): ValueType {
		val body = (declaration.body as ExpressionBody).expression
		val result = BodyTyping(facts, declaration, ResultShape.Empty, hasReturnContract = false).resultOf(body).scalarOrNull()
		if (result == null || result == ValueType.VOID || result == ValueType.NEVER || result == ValueType.Null) {
			throw SemanticErrors.operatorDeclaration("An operator must produce one inferable value", body.span)
		}
		return result
	}
}
