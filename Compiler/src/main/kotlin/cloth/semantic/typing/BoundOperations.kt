package cloth.semantic.typing

import cloth.data.*
import cloth.diagnostics.InternalErrors
import cloth.parser.declaration.MethodDeclaration
import cloth.parser.expression.BinaryExpression
import cloth.parser.expression.ClassCreationExpression
import cloth.parser.expression.Expression
import cloth.parser.expression.MemberCallExpression
import cloth.semantic.ClassComparisonPlan
import cloth.semantic.declarations.DeclarationModel
import cloth.semantic.generics.sourceSpans
import cloth.semantic.symbols.CallableContractSymbol
import cloth.semantic.symbols.ConstructorGuaranteeSymbol
import cloth.semantic.symbols.InterfaceMethodSymbol
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

internal fun FunctionSemanticModel.boundViews(value: Expression, actual: ValueType? = null): List<CMemberType> {
	val parameter = checkedFact(value) { body, checked -> body.types.scalarTypeOrNull(checked)?.parameterPayload() }
	return parameter?.let { declarations.genericViews?.specializedBounds(value, it.parameter) } ?: actual?.parameterPayload()?.nominalBounds.orEmpty()
}

internal fun DeclarationModel.boundMemberGroups(bounds: List<CMemberType>, name: String) = bounds.distinct().mapNotNull { memberGroup(it, name) }

internal fun FunctionSemanticModel.checkedConstructorGuarantee(
	call: ClassCreationExpression,
	candidates: List<ConstructorGuaranteeSymbol>,
): ConstructorGuaranteeSymbol? = checkedFact(call) { body, checked ->
	val contract = body.guaranteedConstructions[checked as ClassCreationExpression]?.contract ?: return@checkedFact null
	val source = checkNotNull(body.declarations.genericViews).originalOf(contract.declaration)
	val views = checkNotNull(declarations.genericViews)
	candidates.single { views.originalOf(it.declaration) === source }
}

internal data class BoundMethod(val view: CMemberType, val contract: CallableContractSymbol)

internal fun FunctionSemanticModel.checkedBoundMethod(call: MemberCallExpression, report: Boolean = true): BoundMethod? {
	return checkedFact(call) { body, checked ->
		val original = checked as MemberCallExpression
		val parameter = body.types.scalarTypeOrNull(original.receiver)?.parameterPayload() ?: return@checkedFact null
		val bounds = declarations.genericViews?.specializedBounds(call.receiver, parameter.parameter) ?: return@checkedFact null
		when (val selected = body.selectedBoundMemberCall(original)) {
			is ResolvedCall.Source    -> {
				val bound = bounds.filterIsInstance<CClass>().single()
				specializeMethod(bound, selected.callable as MethodSymbol, body, call, report)?.let { BoundMethod(bound, it) }
			}

			is ResolvedCall.Interface -> {
				val index = parameter.nominalBounds.indexOf(selected.owner)
				val bound = bounds[index] as CInterface
				rebindInterfaceMethod(bound, selected.contract, body, call, report)
			}

			null                      -> null
		}
	}
}

private fun FunctionSemanticModel.rebindInterfaceMethod(
	bound: CInterface,
	selected: InterfaceMethodSymbol,
	body: FunctionSemanticModel,
	call: MemberCallExpression,
	report: Boolean,
): BoundMethod? {
	val source = selected.requirement.declaration
	val contracts = declarations.interfaces.contracts
	val contract = contracts.getValue(bound)
	val current = contract.requirements.values.firstOrNull { it.declaration === source }
	val requirement = current ?: bound.ancestors.asSequence().flatMap { contracts.getValue(it).requirements.values.asSequence() }.firstOrNull { it.declaration === source } ?: throw InternalErrors.invariant("Missing bound interface requirement '${source.name.lexeme}'")
	val method = if (source.typeParameters == null) declarations.interfaceMethods.getValue(requirement) else {
		val canonical = checkNotNull(body.declarations.genericViews)
		checkNotNull(declarations.genericViews).inferredBoundInterfaceMethod(call, requirement, selected, canonical, report) ?: return null
	}
	// Substitution may merge inherited slots in the bound's interface view.
	val view = if (current != null) bound else method.owner
	return BoundMethod(view, method)
}

internal fun FunctionSemanticModel.checkedIdentity(expression: BinaryExpression, left: CMemberType, right: CMemberType): ClassComparisonPlan? {
	if (boundViews(expression.left).isEmpty() && boundViews(expression.right).isEmpty()) return null
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
	val declaring = (owner as CClass).ancestry().firstOrNull { it.declaration === selected.owner.declaration } ?: throw InternalErrors.invariant("Missing bound class member '${source.name.lexeme}'")
	val views = checkNotNull(declarations.genericViews)
	if (source.typeParameters == null) return declarations.symbolOf(views.viewOf(source, declaring) as MethodDeclaration)
	val site = checkNotNull(call)
	val template = declarations.methodTemplate(declaring, source) ?: throw InternalErrors.invariant("Missing bound generic method '${source.name.lexeme}'")
	val supplied = site.typeArguments
	if (supplied == null || supplied.hasPlaceholders || supplied.arguments.size != template.signature.typeParameterCount) {
		return views.inferredBoundMethod(site, template, selected, canonical, report)
	}
	val arguments = methodTypeArguments(supplied, report) ?: return null
	return views.methodCandidates(listOf(template), arguments, supplied.sourceSpans(), site, report, constructorAccessChecked = true).singleOrNull()
}
