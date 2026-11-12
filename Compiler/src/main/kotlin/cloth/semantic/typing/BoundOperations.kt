package cloth.semantic.typing

import cloth.data.CClass
import cloth.data.CInterface
import cloth.data.CMemberType
import cloth.data.CNullable
import cloth.data.CTypeParameter
import cloth.diagnostics.InternalErrors
import cloth.parser.declaration.MethodDeclaration
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.Expression
import cloth.parser.expression.MemberCallExpression
import cloth.semantic.ClassComparisonPlan
import cloth.semantic.generics.sourceSpans
import cloth.semantic.symbols.CallableContractSymbol
import cloth.semantic.symbols.MethodSymbol

/** The canonical body's facts select the bound view and the source contract. */
private fun <T : Any> FunctionSemanticModel.checkedFact(
	site: Expression,
	read: (FunctionSemanticModel, Expression) -> T?,
): T? {
	val views = declarations.genericViews ?: return null
	val checked = views.checkedExpression(site) ?: return null
	return boundDeclarationModels(views.templates ?: this).firstNotNullOfOrNull { read(it, checked) }
}

internal fun FunctionSemanticModel.boundView(value: Expression): CMemberType? {
	val declared = checkedFact(value) { body, checked -> body.types.scalarTypeOrNull(checked) }
	val parameter = ((declared as? CNullable)?.payload ?: declared) as? CTypeParameter ?: return null
	return declarations.genericViews?.specializedBound(value, parameter.parameter)
}

internal fun FunctionSemanticModel.checkedBoundMethod(call: MemberCallExpression, owner: CMemberType, report: Boolean = true): CallableContractSymbol? {
	if (boundView(call.receiver) == null) return null
	return checkedFact(call) { body, checked ->
		when (val selected = body.selectedBoundMemberCall(checked as MemberCallExpression)) {
			is ResolvedCall.Source -> specializeMethod(owner, selected.callable as MethodSymbol, body, call, report)
			is ResolvedCall.Interface -> {
				val bound = owner as CInterface
				val source = selected.contract.requirement
				declarations.interfaceMethods.values.firstOrNull { method ->
					method.requirement.declaration === source.declaration && (method.owner === bound || method.owner in bound.ancestors)
				} ?: throw InternalErrors.invariant("Missing bound interface requirement '${source.declaration.name.lexeme}'")
			}
			null -> null
		}
	}
}

internal fun FunctionSemanticModel.checkedIdentity(expression: BinaryExpression, left: CMemberType, right: CMemberType): ClassComparisonPlan? {
	if (boundView(expression.left) == null && boundView(expression.right) == null) return null
	return checkedFact(expression) { body, checked ->
		body.types.classComparisons[checked as BinaryExpression]?.let { plan ->
			plan.copy(identityOverride = plan.identityOverride?.let { selected ->
				val owner = if (selected.receiverIndex == 0) left else right
				val method = checkNotNull(specializeMethod(owner, selected.call.callable as MethodSymbol, body))
				selected.copy(call = selected.call.copy(callable = method, type = typeOf(method)))
			})
		}
	}
}

private fun FunctionSemanticModel.specializeMethod(
	owner: CMemberType,
	selected: MethodSymbol,
	body: FunctionSemanticModel,
	call: MemberCallExpression? = null,
	report: Boolean = true,
): MethodSymbol? {
	val canonical = checkNotNull(body.declarations.genericViews)
	val source = canonical.originalOf(selected.declaration) as MethodDeclaration
	val declaring = (owner as CClass).ancestry().firstOrNull { it.declaration === selected.owner.declaration }
		?: throw InternalErrors.invariant("Missing bound class member '${source.name.lexeme}'")
	val views = checkNotNull(declarations.genericViews)
	if (source.typeParameters == null) return declarations.symbolOf(views.viewOf(source, declaring) as MethodDeclaration)
	val site = checkNotNull(call)
	val template = declarations.methodTemplate(declaring, source)
		?: throw InternalErrors.invariant("Missing bound generic method '${source.name.lexeme}'")
	val supplied = site.typeArguments ?: return views.inferredBoundMethod(site, template, selected, canonical, report)
	val arguments = methodTypeArguments(supplied, report) ?: return null
	return views.methodCandidates(listOf(template), arguments, supplied.sourceSpans(), site, report).singleOrNull()
}
