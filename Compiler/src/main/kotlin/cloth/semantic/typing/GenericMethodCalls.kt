package cloth.semantic.typing

import cloth.data.CClass
import cloth.data.CMemberType
import cloth.data.sameDeclaration
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.CallSite
import cloth.parser.type.TypeArgumentList
import cloth.semantic.generics.sourceSpans
import cloth.semantic.symbols.MethodGroupSymbol
import cloth.semantic.symbols.MethodSymbol

internal fun methodVisible(owner: CMemberType, caller: CClass?, isPublic: Boolean) =
	owner !is CClass || owner.sameDeclaration(caller) || isPublic

internal fun MethodGroupSymbol.typeArgumentError(call: CallSite, supplied: TypeArgumentList?) =
	GenericErrors.callArity(call.name, supplied?.span ?: call.name.span, supplied?.arguments?.size ?: 0, buildList {
		if (overloads.isNotEmpty()) add(0)
		templates.forEach { add(it.signature.typeParameterCount) }
	}.distinct().sorted(), name)

internal fun FunctionSemanticModel.genericMethodCandidates(
	call: CallSite,
	supplied: TypeArgumentList,
	group: MethodGroupSymbol,
	classOwner: CClass?,
	report: Boolean = true,
): List<MethodSymbol> {
	val matching = group.templates.filter { it.signature.typeParameterCount == supplied.arguments.size }
	if (matching.isEmpty()) {
		if (report) throw group.typeArgumentError(call, supplied)
		return emptyList()
	}
	val visible = matching.filter { methodVisible(it.owner, classOwner, it.declaration.visibility != null) }
	if (visible.isEmpty()) {
		if (report) throw SemanticErrors.privateClassMember(call.name, matching.first().declaration.name)
		return emptyList()
	}
	val arguments = methodTypeArguments(supplied, report) ?: return emptyList()
	return checkNotNull(declarations.genericViews).methodCandidates(visible, arguments, supplied.sourceSpans(), call, report)
}
