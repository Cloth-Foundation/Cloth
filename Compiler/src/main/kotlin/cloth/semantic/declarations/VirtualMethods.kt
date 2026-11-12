package cloth.semantic.declarations

import cloth.data.CClass
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.MethodDeclaration
import cloth.semantic.ResultShape
import cloth.semantic.generics.*
import cloth.semantic.symbols.FunctionSignature
import cloth.token.Keywords
import cloth.token.Operators
import java.util.*

internal class VirtualMethodBody(val owner: CClass, val declaration: MethodDeclaration, val results: ResultShape)

internal class VirtualMethodFamily<T>(val base: T) {

	private val targets = linkedMapOf<CClass, T>()
	val implementations: Map<CClass, T> get() = targets

	fun addTarget(owner: CClass, body: T) {
		targets[owner] = body
	}
}

/** Match each override against its base signature and keep the original call contract. */
internal class VirtualMethods private constructor(
	private val families: Map<CClass, Map<MethodDeclaration, VirtualMethodFamily<VirtualMethodBody>>>,
	private val genericFamilies: Map<GenericMethodTemplate, VirtualMethodFamily<GenericMethodTemplate>>,
) {

	fun familyOf(owner: CClass, declaration: MethodDeclaration) = families[owner]?.get(declaration)
	fun genericFamilyOf(template: GenericMethodTemplate) = genericFamilies[template]

	private data class ActiveMethod(val family: VirtualMethodFamily<VirtualMethodBody>, val body: VirtualMethodBody)

	companion object {

		val Empty = VirtualMethods(emptyMap(), emptyMap())

		fun analyze(types: ClosedTypeModel, baseOrder: List<CClass>, keys: GenericSignatureKeys): VirtualMethods {
			val effective = mutableMapOf<CClass, Map<FunctionSignature, ActiveMethod>>()
			val families = mutableMapOf<CClass, Map<MethodDeclaration, VirtualMethodFamily<VirtualMethodBody>>>()
			for (owner in baseOrder) {
				val inherited = effective[owner.base].orEmpty()
				val active = inherited.toMutableMap()
				val own = IdentityHashMap<MethodDeclaration, VirtualMethodFamily<VirtualMethodBody>>()
				for (closed in types.instanceOf(owner).signatures) {
					val method = closed.declaration as? MethodDeclaration ?: continue
					if (method.operator != null || (inherited.isEmpty() && method.virtualPrefix == null)) continue
					val signature = closed.fixedSignature()
					val base = inherited[signature]?.family
					if (base == null && method.virtualPrefix == null) continue
					checkDeclaration(method, signature, base?.base?.declaration)
					val results = closed.fixedResults()
					if (base != null) checkResults(method, signature, results, base.base.declaration, base.base.results)
					val body = VirtualMethodBody(owner, method, results)
					val family = base ?: VirtualMethodFamily(body)
					own[method] = family
					active[signature] = ActiveMethod(family, body)
				}
				for ((family, body) in active.values) family.addTarget(owner, body)
				effective[owner] = active
				if (own.isNotEmpty()) families[owner] = own
			}
			return VirtualMethods(families, genericFamilies(types, baseOrder, keys))
		}

		private fun genericFamilies(types: ClosedTypeModel, baseOrder: List<CClass>, keys: GenericSignatureKeys): Map<GenericMethodTemplate, VirtualMethodFamily<GenericMethodTemplate>> {
			val effective = mutableMapOf<CClass, Map<GenericMethodSignature, List<VirtualMethodFamily<GenericMethodTemplate>>>>()
			return buildMap {
				for (owner in baseOrder) {
					val inherited = effective[owner.base].orEmpty()
					val introduced = linkedMapOf<GenericMethodSignature, MutableList<VirtualMethodFamily<GenericMethodTemplate>>>()
					val replacements = mutableMapOf<VirtualMethodFamily<GenericMethodTemplate>, GenericMethodTemplate>()
					for (template in types.methodTemplates[owner].orEmpty().values.flatten()) {
						val method = template.declaration
						val signature = template.signature.valueParameters
						val bases = inherited[template.signature].orEmpty()
						val family = bases.firstOrNull()
						if (family == null && method.virtualPrefix == null) continue
						checkDeclaration(method, signature, family?.base?.declaration)
						if (family == null) {
							val declared = VirtualMethodFamily(template)
							declared.addTarget(owner, template)
							introduced.getOrPut(template.signature) { mutableListOf() }.add(declared)
							put(template, declared)
							continue
						}
						val base = family.base
						if (bases.size > 1) throw SemanticErrors.virtualMethod(
							"'${signature.spelling}' matches more than one generic virtual base method", method.name.span, base.declaration.name,
						)
						if (replacements.putIfAbsent(family, template) != null) throw SemanticErrors.virtualMethod(
							"'${signature.spelling}' already overrides this virtual base method in '${owner.spelling}'", method.name.span, base.declaration.name,
						)
						for (parameter in checkNotNull(method.typeParameters).parameters) {
							parameter.bounds.firstOrNull()?.let {
								throw SemanticErrors.virtualMethod("Generic overrides inherit bounds from the virtual base method", it.span, base.declaration.name)
							}
							parameter.defaultType?.let {
								throw SemanticErrors.virtualMethod("Declare type argument defaults on the virtual base method", it.span, base.declaration.name)
							}
						}
						val results = keys.results(method, types.instanceOf(owner).bindings.environment)
						val expected = keys.results(base.declaration, types.instanceOf(base.owner).bindings.environment)
						checkResults(method, signature, results, base.declaration, expected)
						put(template, family)
					}
					for (group in inherited.values) for (family in group) {
						val body = replacements[family] ?: family.implementations.getValue(checkNotNull(owner.base))
						family.addTarget(owner, body)
					}
					effective[owner] = inherited + introduced
				}
			}
		}

		private fun checkDeclaration(method: MethodDeclaration, signature: FunctionSignature, base: MethodDeclaration?) {
			val declaration = base?.name
			if (base != null && method.overridePrefix == null) {
				throw SemanticErrors.virtualMethod("'${signature.spelling}' requires '${Keywords.OVERRIDE}${Operators.COLON}'", method.name.span, declaration)
			}
			if (method.visibility == null) {
				throw SemanticErrors.virtualMethod("Virtual method '${signature.spelling}' requires '${Keywords.PUBLIC}'", method.name.span, declaration)
			}
			if (base != null) method.parameters.firstOrNull { it.optional }?.let { parameter ->
				throw SemanticErrors.virtualMethod("Declare optional parameters and defaults on the virtual base method", parameter.span, declaration)
			}
		}

		private fun checkResults(method: MethodDeclaration, signature: FunctionSignature, results: ResultShape, base: MethodDeclaration, expected: ResultShape) {
			if (results != expected) {
				throw SemanticErrors.virtualMethod("'${signature.spelling}' returns '${results.spelling}'. The virtual base method requires '${expected.spelling}'.", method.name.span, base.name)
			}
		}
	}
}
