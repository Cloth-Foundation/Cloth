package cloth.semantic.generics

import cloth.data.*
import cloth.diagnostics.GenericErrors
import cloth.parser.declaration.*
import cloth.parser.type.TypeReference
import cloth.semantic.declarations.DeclarationAnalyzer
import cloth.semantic.declarations.DeclarationModel
import cloth.semantic.declarations.FileBindings
import cloth.semantic.symbols.MethodSymbol
import cloth.semantic.typing.FunctionAnalyzer
import cloth.semantic.typing.FunctionSemanticModel
import cloth.target.TargetLayout
import cloth.token.Keywords

/** Declaration checking and closed checking have independent catalogs, symbols and facts. */
internal class GenericSemanticAnalysis(private val target: TargetLayout) {
	fun declarations(files: FileBindings, names: GenericNameModel): DeclarationModel {
		visibility(files, names)
		val analyzer = ClosedTypeAnalyzer(target)
		if (!names.requiresGenericCompilation) {
			val closed = analyzer.closed(files, names)
			interfaceVisibility(closed, files.objectRoot)
			return DeclarationAnalyzer(target).prepared(files, GenericBodyViews(closed))
		}
		val templateFiles = files.fork()
		val templateNames = GenericAnalyzer(target).analyze(templateFiles)
		val symbolicTypes = analyzer.symbolic(templateFiles, templateNames)
		interfaceVisibility(symbolicTypes, templateFiles.objectRoot)
		val templateDeclarations = DeclarationAnalyzer(target).prepared(templateFiles, GenericBodyViews(symbolicTypes))
		val templates = FunctionAnalyzer(target).analyze(templateDeclarations)
		// Width templates retain ordinary call-time range checks. Declaration checking
		// uses the widest admitted format, without granting any capability to owner T.
		for (method in templateDeclarations.widthTemplates) if (method.owner.hasTypeParameters()) {
			templates.widestSpecialization(method)
		}
		for (method in templates.callableBodies.filterIsInstance<MethodSymbol>()) {
			val declaration = method.owner.declaration
			val canonical = method.owner.typeArguments.isNotEmpty() && method.owner.isCanonicalApplication()
			val public = when (declaration) {
				is ClassDeclaration -> declaration.visibility != null && method.declaration.visibility != null
				is StructDeclaration -> declaration.visibility != null
				else -> false
			}
			if (canonical && public && method.declaration.operator != null && method.declaration.returnTypes.isEmpty()) {
				checkExposedTypes(method.name, templates.typeOf(method).results.values, templateFiles.objectRoot)
			}
		}
		inheritedClassVisibility(templates)
		val closed = analyzer.closed(files, names)
		return DeclarationAnalyzer(target).prepared(files, GenericBodyViews(closed, templates))
	}

	private fun interfaceVisibility(types: ClosedTypeModel, objectRoot: CClass) {
		for ((owner, contract) in types.interfaces.contracts) {
			if (owner.declaration.visibility == null || !owner.isCanonicalApplication()) continue
			for (method in contract.methods.values) {
				val exposed = method.signature.parameterTypes.map { checkNotNull(it.exact) } + method.results.values
				checkExposedTypes(method.declaration.name, exposed, objectRoot)
			}
		}
	}

	private fun inheritedClassVisibility(templates: FunctionSemanticModel) {
		val declarations = templates.declarations
		val methodsByOwner = declarations.methods.groupBy { it.owner }
		for (owner in declarations.classDefinitions.keys) {
			if (owner.declaration.visibility == null || owner.typeArguments.isEmpty() || !owner.isCanonicalApplication()) continue
			for (base in owner.ancestry().drop(1)) {
				for (field in base.fields) {
					if (field.visibility != null && owner.field(field.name.lexeme) === field) {
						checkExposedTypes(field.name, listOf(field.type), declarations.objectRoot)
					}
				}
				for (method in methodsByOwner[base].orEmpty()) {
					if (method.declaration.visibility == null) continue
					val name = method.name.lexeme
					val operator = method.declaration.operator
					val effective = when {
						operator == null          -> declarations.methodGroup(owner, name)?.overloads.orEmpty()
						operator.selector != null -> declarations.contextOperators(owner, name)
						else                      -> declarations.operators(owner, name, operator.rightReceiver, method.parameters.size)
					}
					if (method !in effective) continue
					val checked = if (method in declarations.widthTemplates) templates.widestSpecialization(method) else method
					val signature = templates.typeOf(checked)
					val exposed = signature.parameters + signature.results.values + listOfNotNull(templates.contextSelectorType(method))
					checkExposedTypes(method.name, exposed, declarations.objectRoot)
				}
			}
			declarations.states[owner]?.let { states ->
				for (state in states.supported.values) if (state.declaration.visibility != null) {
					checkExposedTypes(state.name, listOf(state.payloadType), declarations.objectRoot)
				}
				for (contract in states.contracts.values) if (contract.public) {
					val signature = templates.typeOf(contract.callable)
					checkExposedTypes(contract.callable.name, signature.parameters + signature.results.values, declarations.objectRoot)
				}
			}
		}
	}

	private fun CMemberType.isCanonicalApplication() = typeArguments.withIndex().all { (index, type) ->
		type is CTypeParameter && type.owner === declaration && type.index == index
	}

	private fun FunctionSemanticModel.widestSpecialization(method: MethodSymbol): MethodSymbol {
		val selector = checkNotNull(method.declaration.operator?.selector)
		val width = if (selector.name.lexeme == Keywords.INTEGER.toString()) ValueType.MAX_INTEGER_BITS else ValueType.FLOAT_WIDTHS.max()
		return specialize(method, width)
	}

	private fun checkExposedTypes(use: cloth.token.Token, exposed: List<ValueType>, objectRoot: CClass) {
		val pending = ArrayDeque(exposed)
		val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ValueType, Boolean>())
		while (pending.isNotEmpty()) {
			val type = pending.removeLast()
			if (!seen.add(type)) continue
			when (type) {
				is CNullable -> pending.add(type.payload)
				is CArray -> pending.add(type.element)
				is CMemberType -> {
					if (type.declaration.visibility == null && type !== objectRoot) throw GenericErrors.privateSignature(use, type.name)
					pending.addAll(type.typeArguments)
				}
				else -> Unit
			}
		}
	}

	private fun visibility(files: FileBindings, names: GenericNameModel) {
		fun surface(type: TypeReference) {
			val nominal = (names.names[type] as? NominalTypeName)?.type
			if (nominal != null && nominal !== files.objectRoot) {
				if (nominal.declaration.visibility == null) throw GenericErrors.privateSignature(type.name, nominal.name)
			}
			type.typeArguments.forEach(::surface)
		}
		fun callable(member: CallableDeclaration) {
			member.parameters.forEach { surface(it.type) }
			member.returnTypes.forEach(::surface)
			(member as? MethodDeclaration)?.operator?.selector?.let(::surface)
		}
		for (file in files.files) for (declaration in file.unit.declarations) when (declaration) {
			is FunctionDeclaration -> if (declaration.visibility != null && declaration.typeParameters != null) callable(declaration)
			is ClassDeclaration -> if (declaration.visibility != null && declaration.typeParameters != null) {
				declaration.parameters.forEach { surface(it.parameter.type) }
				for (member in declaration.members) when (member) {
					is ClassFieldDeclaration -> if (member.visibility != null) surface(member.type)
					is MethodDeclaration -> if (member.visibility != null) callable(member)
					is ConstructorDeclaration -> callable(member)
					else -> Unit
				}
			}
			is StructDeclaration -> if (declaration.visibility != null && declaration.typeParameters != null) {
				declaration.fields.forEach { surface(it.type) }
				declaration.methods.forEach(::callable)
			}
			else -> Unit
		}
	}
}
