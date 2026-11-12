package cloth.semantic.typing

import cloth.data.ValueType
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.ExpressionBody
import cloth.parser.declaration.MethodDeclaration
import cloth.parser.expression.CallSite
import cloth.semantic.ResultShape
import cloth.semantic.flow.ClassLifetimeAnalysis
import cloth.semantic.flow.NullFlowAnalysis
import cloth.semantic.flow.checkCallableMetadata
import cloth.semantic.flow.checkArrayShapes
import cloth.semantic.symbols.MethodSymbol
import java.util.IdentityHashMap

internal class CallResultFlowRestart : RuntimeException(null, null, false, false)

/** Checks operator bodies in the requesting analysis and guards active recursion. */
internal class OperatorBodies {

	private val inferring = mutableSetOf<MethodSymbol>()
	private val specializing = mutableSetOf<Pair<MethodSymbol, Int>>()

	fun infer(facts: FunctionSemanticModel, method: MethodSymbol, declared: FunctionType): FunctionType {
		val declaration = method.declaration
		if (!inferring.add(method)) throw SemanticErrors.operatorDeclaration("A recursive operator requires an explicit result type", declaration.span)
		return try {
			facts.canonicalBoundMethod(method)?.let { canonical ->
				facts.typeOf(canonical)
				NullFlowAnalysis(facts).analyze(declaration)
			}
			withCallResults(facts, declaration) { body ->
				val signature = FunctionType(declared.parameters, inferResult(body, declaration))
				if (facts.declarations.genericViews?.isCanonicalBoundBody(method) == true) facts.inferredBoundBodies[method] = body
				signature
			}
		} finally {
			inferring.remove(method)
		}
	}

	fun specialize(facts: FunctionSemanticModel, template: MethodSymbol, width: Int): MethodSymbol {
		val key = template to width
		val declaration = template.declaration
		if (!specializing.add(key)) throw SemanticErrors.operatorDeclaration("A recursive operand context requires an explicit result type", declaration.span)
		return try {
			facts.canonicalBoundMethod(template)?.let { canonical ->
				facts.specialize(canonical, width)
				NullFlowAnalysis(facts).analyze(declaration)
			}
			val annotated = declaration.returnTypes.singleOrNull()?.let(facts.declarations.typeResolver(width)::resolve)
			val result = annotated ?: withCallResults(facts, declaration, width) { inferResult(it, declaration) }
			if (result == ValueType.VOID) throw SemanticErrors.operatorDeclaration("An operand context must produce a value", declaration.span)
			val signature = FunctionType(emptyList(), result)
			withCallResults(facts, declaration, width) { body ->
				// Annotated recursion can see the signature inside this body while it is checked.
				val method = body.reserveSpecialization(template, width, signature)
				BodyTyping(body, declaration, signature.results).checkBody()
				checkArrayShapes(method, body)
				checkCallableMetadata(method, body)
				if (body.declarations.classDefinitions.isNotEmpty()) ClassLifetimeAnalysis(body).analyze(listOf(method))
				facts.publishSpecialization(method, body)
				method
			}
		} finally {
			specializing.remove(key)
		}
	}

	private fun <T> withCallResults(
		facts: FunctionSemanticModel,
		declaration: MethodDeclaration,
		width: Int? = null,
		check: (FunctionSemanticModel) -> T,
	): T {
		val results = if (facts.declarations.genericViews == null) null else IdentityHashMap<CallSite, ResultShape>()
		while (true) {
			val body = facts.forCallableBody(width, results)
			if (results != null) NullFlowAnalysis(body).analyze(declaration)
			try {
				return check(body)
			} catch (_: CallResultFlowRestart) {
				// The chosen return type supplies a new flow fact for this body and width.
			}
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
