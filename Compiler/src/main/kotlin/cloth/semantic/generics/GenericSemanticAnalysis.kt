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
			val closed = analyzer.worklist(files, names).model
			interfaceVisibility(closed, files)
			return DeclarationAnalyzer(target).prepared(files, GenericBodyViews(closed))
		}
		val templateFiles = files.fork()
		val templateNames = GenericAnalyzer(target).analyze(templateFiles)
		val symbolic = analyzer.worklist(templateFiles, templateNames, symbolic = true)
		val templates = checkFunctions(templateFiles, symbolic)
		for (method in templates.callableBodies.filterIsInstance<MethodSymbol>()) {
			val declaration = method.owner.declaration
			val canonical = method.owner.typeArguments.isNotEmpty() && symbolic.model.isCanonicalApplication(method.owner)
			val public = when (declaration) {
				is ClassDeclaration  -> templateFiles.privateTypeName(method.owner) == null && method.declaration.visibility != null
				is StructDeclaration -> templateFiles.privateTypeName(method.owner) == null
				else                 -> false
			}
			if (canonical && public && method.declaration.operator != null && method.declaration.returnTypes.isEmpty()) {
				checkExposedTypes(method.name, templates.typeOf(method).results.values, templateFiles)
			}
		}
		val closed = analyzer.worklist(files, names)
		if (!names.hasGenericFunctions && !names.hasGenericMethodSyntax && names.inferredCreations.isEmpty() && closed.model.callableCandidates.isEmpty()) {
			return DeclarationAnalyzer(target).prepared(files, GenericBodyViews(closed.model, templates))
		}
		return checkFunctions(files, closed, templates).declarations
	}

	private fun checkFunctions(files: FileBindings, worklist: ClosedTypeAnalyzer.Worklist, templates: FunctionSemanticModel? = null): FunctionSemanticModel {
		val types = worklist.model
		while (true) {
			if (types.symbolic) interfaceVisibility(types, files)
			val views = GenericBodyViews(types, templates, worklist)
			try {
				val declarations = DeclarationAnalyzer(target).prepared(files, views)
				val semantics = FunctionAnalyzer(target).analyze(declarations)
				if (types.symbolic) {
					// Declaration checking uses the widest admitted operator format.
					for (method in declarations.widthTemplates) if (method.owner.hasTypeParameters() || method.owner.typeArguments.isNotEmpty() && types.isCanonicalApplication(method.owner)) {
						semantics.widestSpecialization(method)
					}
					inheritedClassVisibility(semantics, files)
				}
				if (!worklist.expand(semantics.genericCallableRequests())) {
					views.finishDiscovery()
					return semantics
				}
			} catch (_: GenericAnalysisRestart) {
				// New types or call contracts are available to the next flow and typing pass.
			}
		}
	}

	private fun interfaceVisibility(types: ClosedTypeModel, files: FileBindings) {
		for ((owner, contract) in types.interfaces.contracts) {
			if (files.privateTypeName(owner) != null || !types.isCanonicalApplication(owner)) continue
			for (method in contract.methods.values) {
				val exposed = method.signature.parameterTypes.map { checkNotNull(it.exact) } + method.results.values
				checkExposedTypes(method.declaration.name, exposed, files)
			}
		}
	}

	private fun inheritedClassVisibility(templates: FunctionSemanticModel, files: FileBindings) {
		val declarations = templates.declarations
		val methodsByOwner = declarations.methods.groupBy { it.owner }
		val types = checkNotNull(declarations.genericViews).types
		val signatureKeys = GenericSignatureKeys(types.names)
		for (owner in declarations.classDefinitions.keys) {
			if (files.privateTypeName(owner) != null || owner.typeArguments.isEmpty() || !types.isCanonicalApplication(owner)) continue
			for (base in owner.ancestry().drop(1)) {
				for (field in base.fields) {
					if (field.visibility != null && owner.field(field.name.lexeme) === field) {
						checkExposedTypes(field.name, listOf(field.type), files)
					}
				}
				for (method in methodsByOwner[base].orEmpty()) {
					if (method.declaration.typeParameters != null) continue
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
					checkExposedTypes(method.name, exposed, files)
				}
				for ((name, methods) in types.methodTemplates[base].orEmpty()) {
					val effective = declarations.methodGroup(owner, name)?.templates.orEmpty()
					for (method in methods) {
						val declaration = method.declaration
						if (declaration.visibility == null || method !in effective) continue
						val exposed = signatureKeys.exposedTypes(declaration, types.instanceOf(base).bindings.environment)
						checkExposedTypes(declaration.name, exposed, files)
					}
				}
			}
			declarations.states[owner]?.let { states ->
				for (state in states.supported.values) if (state.declaration.visibility != null) {
					checkExposedTypes(state.name, listOf(state.payloadType), files)
				}
				for (contract in states.contracts.values) if (contract.public) {
					val signature = templates.typeOf(contract.callable)
					checkExposedTypes(contract.callable.name, signature.parameters + signature.results.values, files)
				}
			}
		}
	}

	private fun FunctionSemanticModel.widestSpecialization(method: MethodSymbol): MethodSymbol {
		val selector = checkNotNull(method.declaration.operator?.selector)
		val width = if (selector.name.lexeme == Keywords.INTEGER.toString()) ValueType.MAX_INTEGER_BITS else ValueType.FLOAT_WIDTHS.max()
		return specialize(method, width)
	}

	private fun checkExposedTypes(use: cloth.token.Token, exposed: List<ValueType>, files: FileBindings) {
		val pending = ArrayDeque(exposed)
		val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ValueType, Boolean>())
		while (pending.isNotEmpty()) {
			val type = pending.removeLast()
			if (!seen.add(type)) continue
			when (type) {
				is CNullable   -> pending.add(type.payload)
				is CArray      -> pending.add(type.element)
				is CMemberType -> {
					files.privateTypeName(type)?.let { throw GenericErrors.privateSignature(use, it) }
					pending.addAll(type.typeArguments)
				}

				else           -> Unit
			}
		}
	}

	private fun visibility(files: FileBindings, names: GenericNameModel) {
		fun surface(type: TypeReference) {
			val nominal = (names.names[type] as? NominalTypeName)?.type
			nominal?.let(files::privateTypeName)?.let { throw GenericErrors.privateSignature(type.name, it) }
			type.typeArguments.forEach(::surface)
		}

		fun callable(member: CallableDeclaration) {
			names.parametersOf(member).forEach {
				it.declaration.bounds.flatMap { bound -> bound.signatureTypes() }.forEach(::surface)
				it.declaration.defaultType?.let(::surface)
			}
			member.parameters.forEach { surface(it.type) }
			member.returnTypes.forEach(::surface)
			(member as? MethodDeclaration)?.operator?.selector?.let(::surface)
		}

		val publicTypes = files.typeOrder.filter { files.privateTypeName(it) == null }.mapTo(java.util.Collections.newSetFromMap(java.util.IdentityHashMap())) { it.declaration }
		for (declaration in files.declarations) {
			val public = when (declaration) {
				is FunctionDeclaration -> declaration.visibility != null
				is TypeDeclaration     -> declaration in publicTypes
				else                   -> false
			}
			val parameters = names.parametersOf(declaration)
			if (!public) continue
			val genericOwner = parameters.isNotEmpty()
			if (declaration is TypeDeclaration) parameters.forEach {
				it.declaration.bounds.flatMap { bound -> bound.signatureTypes() }.forEach(::surface)
				it.declaration.defaultType?.let(::surface)
			}
			when (declaration) {
				is FunctionDeclaration -> if (genericOwner) callable(declaration)
				is ClassDeclaration    -> {
					if (genericOwner) declaration.parameters.forEach { surface(it.parameter.type) }
					for (member in declaration.members) when (member) {
						is ClassFieldDeclaration  -> if (genericOwner && member.visibility != null) surface(member.type)
						is MethodDeclaration      -> if (member.visibility != null && (genericOwner || member.typeParameters != null)) callable(member)
						is ConstructorDeclaration -> if (genericOwner) callable(member)
						else                      -> Unit
					}
				}

				is StructDeclaration   -> {
					if (genericOwner) declaration.fields.forEach { surface(it.type) }
					declaration.methods.filter { genericOwner || it.typeParameters != null }.forEach(::callable)
				}

				else                   -> Unit
			}
		}
	}
}
