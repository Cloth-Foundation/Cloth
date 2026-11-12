package cloth.semantic.declarations

import cloth.data.CClass
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.MethodDeclaration
import cloth.semantic.ResultShape
import cloth.semantic.generics.ClosedTypeModel
import cloth.semantic.generics.fixedResults
import cloth.semantic.generics.fixedSignature
import cloth.semantic.symbols.FunctionSignature
import cloth.token.Keywords
import cloth.token.Operators
import java.util.*

internal class VirtualMethodBody(val owner: CClass, val declaration: MethodDeclaration)

internal class VirtualMethodFamily(val base: VirtualMethodBody, val results: ResultShape) {

	private val targets = linkedMapOf<CClass, VirtualMethodBody>()
	val implementations: Map<CClass, VirtualMethodBody> get() = targets

	fun addTarget(owner: CClass, body: VirtualMethodBody) {
		targets[owner] = body
	}
}

/** Match each override against its closed base signature and retain one family per overload. */
internal class VirtualMethods private constructor(private val families: Map<CClass, Map<MethodDeclaration, VirtualMethodFamily>>) {

	fun familyOf(owner: CClass, declaration: MethodDeclaration) = families[owner]?.get(declaration)

	private data class ActiveMethod(val family: VirtualMethodFamily, val body: VirtualMethodBody)

	companion object {

		val Empty = VirtualMethods(emptyMap())

		fun analyze(types: ClosedTypeModel, baseOrder: List<CClass>): VirtualMethods {
			val effective = mutableMapOf<CClass, Map<FunctionSignature, ActiveMethod>>()
			val families = mutableMapOf<CClass, Map<MethodDeclaration, VirtualMethodFamily>>()
			for (owner in baseOrder) {
				val inherited = effective[owner.base].orEmpty()
				val active = inherited.toMutableMap()
				val own = IdentityHashMap<MethodDeclaration, VirtualMethodFamily>()
				for (closed in types.instanceOf(owner).signatures) {
					val method = closed.declaration as? MethodDeclaration ?: continue
					if (method.operator != null || (inherited.isEmpty() && method.virtualPrefix == null)) continue
					val signature = closed.fixedSignature()
					val base = inherited[signature]?.family
					if (base == null && method.virtualPrefix == null) continue
					val declaration = base?.base?.declaration?.name
					if (base != null && method.overridePrefix == null) {
						throw SemanticErrors.virtualMethod("'${signature.spelling}' requires '${Keywords.OVERRIDE}${Operators.COLON}'", method.name.span, declaration)
					}
					if (method.visibility == null) {
						throw SemanticErrors.virtualMethod("Virtual method '${signature.spelling}' requires '${Keywords.PUBLIC}'", method.name.span, declaration)
					}
					if (base != null) method.parameters.firstOrNull { it.optional }?.let { parameter ->
						throw SemanticErrors.virtualMethod("Declare optional parameters and defaults on the virtual base method", parameter.span, declaration)
					}
					val results = closed.fixedResults()
					if (base != null && results != base.results) {
						throw SemanticErrors.virtualMethod("'${signature.spelling}' returns '${results.spelling}'. The virtual base method requires '${base.results.spelling}'.", method.name.span, declaration)
					}
					val body = VirtualMethodBody(owner, method)
					val family = base ?: VirtualMethodFamily(body, results)
					own[method] = family
					active[signature] = ActiveMethod(family, body)
				}
				for ((family, body) in active.values) family.addTarget(owner, body)
				effective[owner] = active
				if (own.isNotEmpty()) families[owner] = own
			}
			return VirtualMethods(families)
		}
	}
}
