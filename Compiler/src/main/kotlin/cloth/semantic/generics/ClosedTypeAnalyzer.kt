package cloth.semantic.generics

import cloth.ast.SourceUnit
import cloth.data.*
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.*
import cloth.parser.expression.CallExpression
import cloth.parser.type.TypeReference
import cloth.parser.type.maximumTypeArgumentNesting
import cloth.semantic.declarations.EnumModel
import cloth.semantic.declarations.FileBindings
import cloth.semantic.declarations.TypeResolver
import cloth.semantic.declarations.VirtualMethods
import cloth.semantic.interfaces.InterfaceConformanceAnalyzer
import cloth.semantic.interfaces.interfaceParentOrder
import cloth.semantic.symbols.FunctionGroupSymbol
import cloth.source.SourceGraph
import cloth.target.TargetLayout
import cloth.token.Token
import cloth.token.TokenSpan
import java.util.IdentityHashMap

internal const val maximumGenericDependencyDepth = 64
internal const val maximumGenericInstances = 4096

/** A newly available type or committed call contract requires fresh semantic facts. */
internal class GenericAnalysisRestart : RuntimeException(null, null, false, false)

/** Discover closed requirements with a worklist, then validate complete layout graphs. */
class ClosedTypeAnalyzer(private val target: TargetLayout) {
	fun analyze(unit: SourceUnit): ClosedTypeModel = analyze(FileBindings.single(unit))
	internal fun analyze(graph: SourceGraph): ClosedTypeModel = analyze(FileBindings.graph(graph))
	internal fun analyze(files: FileBindings, names: GenericNameModel = GenericAnalyzer(target).analyze(files)): ClosedTypeModel {
		if ((names.interfaceSpan != null || names.hasVirtualMethods || names.hasBounds) && names.owners.isNotEmpty()) {
			val templates = files.fork()
			worklist(templates, GenericAnalyzer(target).analyze(templates), symbolic = true, checkCallBounds = true)
		}
		return worklist(files, names, checkCallBounds = true).model
	}

	internal fun worklist(files: FileBindings, names: GenericNameModel, symbolic: Boolean = false, checkCallBounds: Boolean = false): Worklist =
		Worklist(files, names, target, symbolic, checkCallBounds).also { it.initialize() }

	internal class Worklist(
		private val files: FileBindings,
		private val names: GenericNameModel,
		private val target: TargetLayout,
		private val symbolic: Boolean,
		checkCallBounds: Boolean,
	) {
		private sealed interface Bound {
			val spelling: String
			data object Number : Bound { override val spelling get() = NumberBoundName.spelling }
			data class Nominal(val type: CMemberType) : Bound { override val spelling get() = type.spelling }

			fun accepts(argument: ValueType): Boolean = when (this) {
				Number -> argument.supportsNumericOperations
				is Nominal -> type.accepts(argument)
			}
		}
		private data class BoundCheck(val parameter: TypeParameterSymbol, val argument: ValueType, val bound: Bound, val use: TokenSpan)

		val model = ClosedTypeModel(names, target, symbolic, files)
		private val work = ArrayDeque<() -> Unit>()
		private val ownerUses = IdentityHashMap<Declaration?, MutableList<GenericTypeUse>>()
		private val ownerCalls = IdentityHashMap<Declaration?, MutableList<CallExpression>>()
		private val pendingFunctions = linkedMapOf<ClosedFunctionKey, GenericCallableRequest>()
		private val callBounds = if (checkCallBounds) mutableListOf<Pair<TypeArgumentSpans, List<ClosedFunctionKey>>>() else null
		private val boundChecks = mutableListOf<BoundCheck>()
		private val signatureKeys = GenericSignatureKeys(names)
		private lateinit var interfaceOrder: Map<CInterface, Int>
		private var bindings = model.rootBindings
		private var closedCount = 0
		private var symbolicCount = 0

		fun initialize() {
			// Check source interface cycles before any application can expand them.
			interfaceOrder = interfaceParentOrder(files.typeOrder.filterIsInstance<CInterface>(), names)
			for (use in names.uses) if (!use.bound) ownerUses.getOrPut(use.owner) { mutableListOf() }.add(use)
			for (use in names.functionUses) ownerCalls.getOrPut(use.owner) { mutableListOf() }.add(use.call)
			for (definition in files.typeOrder) if (names.parametersOf(definition.declaration).isEmpty()) register(definition, definition, emptyList(), GenericBindings())
			if (symbolic) {
				for (definition in files.typeOrder) {
					val arguments = symbolicArguments(definition.declaration)
					if (arguments.isNotEmpty()) instantiate(definition, arguments, definition.name.span)
				}
				for (file in files.files) for (function in file.functions) {
					val arguments = symbolicArguments(function.declaration)
					if (arguments.isNotEmpty()) instantiate(ClosedFunctionKey(function, arguments), function.name.span)
				}
			}
			bindOwner(null)
			for (file in files.files) for (function in file.functions) {
				if (function.typeParameterCount == 0) model.functionList.add(signature(function.declaration))
			}
			complete()
			for ((supplied, candidates) in callBounds.orEmpty()) {
				if (candidates.all { it in model.rejectedCallables }) {
					val failure = model.rejectedCallables.getValue(candidates.first())
					throw failure.diagnostic(supplied)
				}
			}
		}

		fun expand(requests: Sequence<GenericCallableRequest>): Boolean {
			for ((key, supplied, caller) in requests) {
				if (key in model.callableInstances) continue
				withBindings(caller) { instantiate(key, supplied.span, supplied) }
			}
			if (work.isEmpty()) return false
			complete()
			return true
		}

		fun prepareCallables(requests: List<GenericCallableRequest>) {
			for (request in requests) if (!hasContract(request.key)) prepareCandidate(request)
			if (work.isNotEmpty()) {
				complete()
				throw GenericAnalysisRestart()
			}
		}

		fun prepareApplication(key: NominalApplicationKey, caller: GenericBindings, supplied: TypeArgumentSpans): Nothing {
			withBindings(caller) { instantiate(key.definition, key.arguments, supplied.span, supplied) }
			complete()
			throw GenericAnalysisRestart()
		}

		private fun hasContract(key: ClosedCallableKey) =
			key in model.callableInstances || key in model.callableCandidates || key in model.rejectedCallables

		private fun complete() {
			do {
				while (work.isNotEmpty()) work.removeFirst()()
				val baseOrder = validateInheritance()
				validateStructs()
				if (names.hasVirtualMethods) model.virtualMethods = VirtualMethods.analyze(model, baseOrder)
				if (names.interfaceSpan != null) model.interfaces = InterfaceConformanceAnalyzer(model).analyze(interfaceOrder, baseOrder)
				checkBounds()
				val requests = pendingFunctions.values.toList()
				pendingFunctions.clear()
				requests.forEach(::prepareCandidate)
			} while (work.isNotEmpty())
			model.catalog.values.forEach { it.state = ClosedTypeState.COMPLETE }
		}

		private fun symbolicArguments(declaration: Declaration) = names.parametersOf(declaration).map { parameter ->
			val bound = parameter.declaration.bound?.let(names::nameOf)
			CTypeParameter(parameter.owner, parameter.declaration, parameter.index, numberBound = bound === NumberBoundName)
		}

		private fun bindOwner(owner: Declaration?) {
			for (use in ownerUses[owner].orEmpty()) bind(use)
			for (call in ownerCalls[owner].orEmpty()) {
				// Publish candidate contracts from the file group. The lexical call
				// resolver checks which group is actually visible at this site.
				val supplied = checkNotNull(call.typeArguments)
				val group = files.context(call.name).scope.lookup(call.name.lexeme) as? FunctionGroupSymbol ?: continue
				val templates = group.overloads.filter { it.typeParameterCount == supplied.arguments.size }
				if (templates.isEmpty()) continue
				val arguments = supplied.arguments.map(::resolve)
				val spans = supplied.sourceSpans()
				val keys = templates.map { ClosedFunctionKey(it, arguments) }
				for (key in keys) {
					if (hasContract(key)) continue
					pendingFunctions.putIfAbsent(key, GenericCallableRequest(key, spans, bindings))
				}
				callBounds?.add(spans to keys)
			}
		}

		private fun bind(use: GenericTypeUse) {
			val pattern = pattern(use.type)
			if (pattern is ClosedTypePattern.Exact) use.receiver?.let { bindings.receivers[it] = pattern.type }
		}
		private fun pattern(annotation: TypeReference): ClosedTypePattern = bindings.facts.getOrPut(annotation) {
			if (annotation.capturesWidth) ClosedTypePattern.OperatorWidth(annotation) else ClosedTypePattern.Exact(resolve(annotation))
		}
		private fun resolve(annotation: TypeReference, instantiateApplications: Boolean = true): ValueType {
			(bindings.facts[annotation] as? ClosedTypePattern.Exact)?.let { return it.type }
			return resolver(instantiateApplications).resolve(annotation).also { bindings.facts[annotation] = ClosedTypePattern.Exact(it) }
		}
		private fun resolver(instantiateApplications: Boolean = true) = TypeResolver(target, substitutedType = { annotation ->
			when (val identity = names.names[annotation]) {
				is TypeParameterSymbol -> bindings.environment[identity] ?: throw InternalErrors.invariant("Unsolved owner parameter '${identity.name.lexeme}' in a closed type")
				else -> {
					val definition = (identity as? NominalTypeName)?.type ?: files.nominal(annotation.name)
					definition?.let {
						val application = annotation.application ?: return@let it
						val arguments = application.arguments.map { argument -> resolve(argument, instantiateApplications) }
						if (instantiateApplications) instantiate(it, arguments, annotation.span, application.sourceSpans())
						else model.applications[NominalApplicationKey(it, arguments)]?.type ?: it.application(arguments)
					}
				}
			}
		})

		private fun register(type: CMemberType, definition: CMemberType, arguments: List<ValueType>, bindings: GenericBindings): ClosedTypeInstance =
			ClosedTypeInstance(type, definition, arguments, bindings).also {
				model.catalog[type] = it
				work.addLast { define(it) }
			}

		private fun withBindings(instance: GenericBindings, define: () -> Unit) {
			val previous = bindings
			bindings = instance
			try {
				define()
			} finally {
				bindings = previous
			}
		}

		private fun define(instance: ClosedTypeInstance) = withBindings(instance.bindings) {
			instance.state = ClosedTypeState.RESOLVING
			bindBounds(instance.definition.declaration)
			val signatures = instance.signatureList
			when (val type = instance.type) {
				is CClass -> {
					defineClass(type)
					signatures.add(signature(PrimaryConstructorDeclaration(type.declaration)))
					val members = type.declaration.members.filterIsInstance<CallableDeclaration>()
					members.filterNot { it is MethodDeclaration && it.typeParameters != null }.mapTo(signatures, ::signature)
					if (symbolic && type.typeArguments.isNotEmpty() && type.isCanonicalApplication()) {
						registerConstructorTemplates(type, signatures)
					}
					registerMethods(type, members.filterIsInstance<MethodDeclaration>())
				}
				is CStruct -> {
					defineStruct(type)
					type.declaration.methods.filter { it.typeParameters == null }.mapTo(signatures, ::signature)
					registerMethods(type, type.declaration.methods)
				}
				is CEnum -> {
					EnumModel.defineFields(type, resolver())
					type.declaration.operators.mapTo(signatures, ::signature)
				}
				is CInterface -> type.declaration.methods.mapTo(signatures, ::signature)
			}
			bindOwner(instance.definition.declaration)
		}

		private fun registerConstructorTemplates(owner: CClass, signatures: List<ClosedCallableSignature>) {
			val slots = owner.typeArguments.map { it as CTypeParameter }
			model.constructorTemplates[owner.declaration] = signatures
				.filter { it.declaration is PrimaryConstructorDeclaration || it.declaration is ConstructorDeclaration }
				.map { signature ->
					val parameters = signature.parameters.map {
						when (it) {
							is ClosedTypePattern.Exact -> it.type
							is ClosedTypePattern.OperatorWidth -> throw SemanticErrors.type(it.annotation)
						}
					}
					GenericConstructorTemplate(signature.declaration as CallableDeclaration, GenericParameterPatterns(parameters, slots))
				}
		}

		private fun registerMethods(owner: CMemberType, declarations: List<MethodDeclaration>) {
			val methods = declarations.filter { it.typeParameters != null }
			if (methods.isEmpty()) return
			val canonical = owner.isCanonicalApplication()
			val seen = mutableMapOf<GenericMethodSignature, MethodDeclaration>()
			model.methodTemplates[owner] = methods.map { declaration ->
				val count = names.parametersOf(declaration).size
				val key = GenericMethodSignature(count, signatureKeys.signature(declaration, bindings.environment))
				if (canonical) seen.putIfAbsent(key, declaration)?.let { prior ->
					throw SemanticErrors.duplicateMethod(owner.name, key.valueParameters.spelling, prior.name, declaration.name)
				}
				GenericMethodTemplate(owner, declaration, key, signatureKeys.parameterSlots(declaration)).also {
					if (symbolic && canonical) instantiate(ClosedMethodKey(it, symbolicArguments(declaration)), declaration.name.span)
				}
			}.groupBy { it.declaration.name.lexeme }
		}

		private fun argumentDepth(type: ValueType): Int {
			var value = type
			while (true) value = when (value) {
				is CArray -> value.element
				is CNullable -> value.payload
				else -> return (value as? CMemberType)?.let { model.catalog[it]?.bindings?.argumentDepth } ?: 0
			}
		}

		private fun instantiate(definition: CMemberType, arguments: List<ValueType>, use: TokenSpan, suppliedArguments: TypeArgumentSpans? = null): CMemberType {
			val key = NominalApplicationKey(definition, arguments.toList())
			// Reuse provisional identities before charging depth or instance budgets.
			model.applications[key]?.let { return it.type }
			val context = newBindings(definition.declaration, definition.name, key.arguments, use, suppliedArguments)
			val type = definition.application(key.arguments)
			return register(type, definition, key.arguments, context).also { model.applications[key] = it }.type
		}

		private fun instantiate(key: ClosedCallableKey, use: TokenSpan, suppliedArguments: TypeArgumentSpans? = null) {
			model.callableInstances.getOrPut(key) {
				val declaration = key.declaration
				val context = newBindings(declaration, declaration.name, key.arguments, use, suppliedArguments, key.owner)
				model.callableCandidates.remove(key)
				work.addLast {
					withBindings(context) {
						bindBounds(declaration)
						val signature = signature(declaration)
						if (key is ClosedFunctionKey) {
							model.functionList.add(signature)
							if (suppliedArguments == null) {
								model.functionPatterns[key.declaration] = GenericParameterPatterns(
									signature.parameters.map { (it as ClosedTypePattern.Exact).type },
									key.arguments.map { it as CTypeParameter },
								)
							}
						}
						bindOwner(declaration)
					}
				}
				context
			}
		}

		private fun prepareCandidate(request: GenericCallableRequest) {
			val (key, supplied, caller) = request
			val declaration = key.declaration
			val parameterOwner = if (key is ClosedConstructorKey) key.definition.declaration else declaration
			val local = names.parametersOf(parameterOwner).zip(key.arguments).toMap()
			val enclosing = key.owner?.let(model::instanceOf)?.bindings
			val context = GenericBindings(enclosing?.environment.orEmpty() + local, caller.chain + (supplied.span to declaration.name.lexeme), suppliedArguments = supplied)
			withBindings(context) {
				for ((parameter, argument) in local) {
					// Any satisfied nominal bound is already present in the argument's
					// ancestry. Resolve other applications for diagnostic spelling only.
					val bound = boundOf(parameter, instantiateApplications = false) ?: continue
					if (!bound.accepts(argument)) {
						model.rejectedCallables[key] = GenericBoundFailure(parameter, argument, bound.spelling)
						return@withBindings
					}
				}
				model.callableCandidates[key] = GenericCallableCandidate(context, signature(declaration))
			}
		}

		private fun newBindings(declaration: Declaration, name: Token, arguments: List<ValueType>, use: TokenSpan, suppliedArguments: TypeArgumentSpans?, owner: CMemberType? = null): GenericBindings {
			val chain = bindings.chain
			val exactArguments = owner?.typeArguments.orEmpty() + arguments
			val open = exactArguments.any { it.hasTypeParameters() }
			val depth = 1 + (exactArguments.maxOfOrNull(::argumentDepth) ?: 0)
			fun limit(code: DiagnosticCode, summary: String): Nothing = throw GenericErrors.limit(code, summary, use, name, chain)
			if (depth > maximumTypeArgumentNesting) limit(DiagnosticCode.GENERIC_SUBSTITUTION_NESTING, "Generic type argument nesting limit exceeded ($maximumTypeArgumentNesting)")
			if (chain.size >= maximumGenericDependencyDepth) limit(if (open) DiagnosticCode.GENERIC_TEMPLATE_DEPENDENCY_LIMIT else DiagnosticCode.GENERIC_DEPENDENCY_LIMIT, if (open) "Generic template analysis dependency limit exceeded ($maximumGenericDependencyDepth)" else "Generic instantiation dependency limit exceeded ($maximumGenericDependencyDepth)")
			if (open && symbolicCount >= maximumGenericInstances) limit(DiagnosticCode.GENERIC_TEMPLATE_INSTANCE_LIMIT, "Too many symbolic generic instances ($maximumGenericInstances)")
			if (!open && closedCount >= maximumGenericInstances) limit(DiagnosticCode.GENERIC_INSTANCE_LIMIT, "Too many distinct generic instances ($maximumGenericInstances)")
			if (open) symbolicCount++ else closedCount++
			val enclosing = owner?.let(model::instanceOf)?.bindings
			val environment = enclosing?.environment.orEmpty() + names.parametersOf(declaration).zip(arguments).toMap()
			return GenericBindings(environment, chain + (use to name.lexeme), depth, suppliedArguments).also {
				if (enclosing != null) it.nominalBounds.putAll(enclosing.nominalBounds)
			}
		}

		private fun bindBounds(declaration: Declaration) {
			val supplied = bindings.suppliedArguments
			for (parameter in names.parametersOf(declaration)) {
				val argument = bindings.environment.getValue(parameter)
				val bound = boundOf(parameter) ?: continue
				if (bound is Bound.Nominal) bindings.nominalBounds[parameter.declaration] = bound.type
				if (supplied == null) {
					// Only the canonical declaration instance supplies facts about its parameters.
					if (bound is Bound.Nominal) (argument as CTypeParameter).nominalBound = bound.type
				} else {
					boundChecks.add(BoundCheck(parameter, argument, bound, supplied.arguments[parameter.index]))
				}
			}
		}

		private fun boundOf(parameter: TypeParameterSymbol, instantiateApplications: Boolean = true): Bound? {
			val reference = parameter.declaration.bound ?: return null
			return if (names.nameOf(reference) === NumberBoundName) Bound.Number
			else Bound.Nominal(resolve(reference, instantiateApplications) as CMemberType)
		}

		private fun checkBounds() {
			// Parent edges and interface conformance are complete before proofs run.
			for ((parameter, argument, bound, use) in boundChecks) {
				if (!bound.accepts(argument)) throw GenericBoundFailure(parameter, argument, bound.spelling).diagnostic(use)
			}
			boundChecks.clear()
		}

		private fun signature(declaration: CallableSignature): ClosedCallableSignature = ClosedCallableSignature(declaration,
			declaration.parameters.map { pattern(it.type) }, declaration.returnTypes.map(::pattern),
			(declaration as? MethodDeclaration)?.operator?.selector?.let(::pattern))

		private fun defineClass(owner: CClass) {
			val declaration = owner.declaration
			var base = files.objectRoot
			var clause: ClassParentClause? = null
			for (parent in declaration.parents) when (val type = resolve(parent.type)) {
				is CClass -> { base = type; clause = parent }
				is CInterface -> Unit
				else -> throw SemanticErrors.classMember("'${parent.type.name.lexeme}' is not a class base", parent.type.name.span)
			}
			if (base.declaration.finalKeyword != null) throw SemanticErrors.classMember("Cannot inherit from final class '${base.name.lexeme}'", checkNotNull(clause).type.name.span, base.name)
			if (base === files.objectRoot && clause?.constructorArguments?.isNotEmpty() == true) throw SemanticErrors.classMember("'${base.spelling}' takes no base arguments", clause.span)
			owner.base = base
			val seen = mutableMapOf<String, Token>()
			val fields = mutableListOf<ClassField>()
			fun add(name: Token, annotation: TypeReference, visibility: Token?) {
				seen.putIfAbsent(name.lexeme, name)?.let { throw SemanticErrors.duplicate(name, it) }
				val type = resolve(annotation)
				if (type == ValueType.VOID) throw SemanticErrors.voidType(annotation.span)
				fields.add(ClassField(owner, name, annotation, fields.size, type, visibility))
			}
			for (parameter in declaration.parameters) if (parameter.fieldKeyword != null) add(parameter.parameter.name, parameter.parameter.type, parameter.visibility)
			for (field in declaration.members.filterIsInstance<ClassFieldDeclaration>()) add(field.name, field.type, field.visibility)
			owner.defineFields(fields)
		}

		private fun defineStruct(owner: CStruct) {
			val seen = mutableMapOf<String, Token>()
			owner.defineFields(owner.declaration.fields.mapIndexed { index, field ->
				seen.putIfAbsent(field.name.lexeme, field.name)?.let { throw SemanticErrors.duplicate(field.name, it) }
				val type = resolve(field.type)
				if (type == ValueType.VOID) throw SemanticErrors.voidType(field.type.span)
				StructField(field, index, type)
			})
		}

		private fun validateInheritance(): List<CClass> {
			val complete = linkedSetOf(files.objectRoot)
			for (owner in model.nominalTypes.filterIsInstance<CClass>()) {
				val path = mutableSetOf<CClass>()
				var next: CClass? = owner
				while (next != null && next !in complete) {
					if (!path.add(next)) throw SemanticErrors.classMember("Inheritance cycle involving '${next.name.lexeme}'", checkNotNull(model.baseClauseOf(next)).type.name.span)
					next = next.base
				}
				complete.addAll(path.reversed())
			}
			return complete.drop(1)
		}

		private fun validateStructs() {
			class Frame(val owner: CStruct) {
				val fields = owner.fields.iterator()
				var field: StructField? = null
			}
			val complete = mutableSetOf<CStruct>()
			for (owner in model.nominalTypes.filterIsInstance<CStruct>()) {
				if (owner in complete) continue
				val stack = mutableListOf(Frame(owner))
				val active = mutableMapOf(owner to 0)
				while (stack.isNotEmpty()) {
					val frame = stack.last()
					if (!frame.fields.hasNext()) {
						complete.add(frame.owner); active.remove(frame.owner); stack.removeLast(); continue
					}
					val field = frame.fields.next().also { frame.field = it }
					var type = field.type
					while (type is CArray || type is CNullable) type = if (type is CArray) type.element else (type as CNullable).payload
					if (type !is CStruct || type in complete) continue
					active[type]?.let { index -> throw SemanticErrors.structCycle(type.name, stack.drop(index).mapNotNull { it.field?.declaration }) }
					active[type] = stack.size
					stack.add(Frame(type))
				}
			}
		}
	}
}
