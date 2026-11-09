package cloth.semantic.generics

import cloth.data.*
import cloth.diagnostics.GenericErrors
import cloth.parser.declaration.*
import cloth.parser.type.TypeReference
import cloth.semantic.declarations.DeclarationAnalyzer
import cloth.semantic.declarations.DeclarationModel
import cloth.semantic.declarations.FileBindings
import cloth.semantic.typing.FunctionAnalyzer
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
			val selector = checkNotNull(method.declaration.operator?.selector)
			templates.specialize(method, if (selector.name.lexeme == Keywords.INTEGER.toString()) ValueType.MAX_INTEGER_BITS else ValueType.FLOAT_WIDTHS.max())
		}
		for (method in templates.callableBodies.filterIsInstance<cloth.semantic.symbols.MethodSymbol>()) {
			val declaration = method.owner.declaration
			val parameters = templateNames.parametersOf(declaration)
			val canonical = parameters.isNotEmpty() && method.owner.typeArguments.withIndex().all { (index, type) -> type is CTypeParameter && type.owner === declaration && type.index == index }
			val public = when (declaration) {
				is ClassDeclaration -> declaration.visibility != null && method.declaration.visibility != null
				is StructDeclaration -> declaration.visibility != null
				else -> false
			}
			if (canonical && public && method.declaration.operator != null && method.declaration.returnTypes.isEmpty()) {
				checkExposedTypes(method.name, templates.typeOf(method).results.values, templateFiles.objectRoot)
			}
		}
		val closed = analyzer.closed(files, names)
		return DeclarationAnalyzer(target).prepared(files, GenericBodyViews(closed, templates))
	}

	private fun interfaceVisibility(types: ClosedTypeModel, objectRoot: CClass) {
		for ((owner, contract) in types.interfaces.contracts) {
			if (owner.declaration.visibility == null || !owner.typeArguments.withIndex().all { (index, type) ->
				type is CTypeParameter && type.owner === owner.declaration && type.index == index
			}) continue
			for (method in contract.methods.values) {
				val exposed = method.signature.parameterTypes.map { checkNotNull(it.exact) } + method.results.values
				checkExposedTypes(method.declaration.name, exposed, objectRoot)
			}
		}
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
