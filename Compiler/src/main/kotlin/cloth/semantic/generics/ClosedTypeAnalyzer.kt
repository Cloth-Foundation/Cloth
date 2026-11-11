package cloth.semantic.generics

import cloth.ast.SourceUnit
import cloth.data.*
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.*
import cloth.parser.expression.CallExpression
import cloth.parser.type.TypeArgumentList
import cloth.parser.type.TypeReference
import cloth.parser.type.maximumTypeArgumentNesting
import cloth.semantic.declarations.EnumModel
import cloth.semantic.declarations.FileBindings
import cloth.semantic.declarations.TypeResolver
import cloth.semantic.declarations.VirtualMethods
import cloth.semantic.interfaces.InterfaceConformanceAnalyzer
import cloth.semantic.interfaces.interfaceParentOrder
import cloth.semantic.symbols.FunctionGroupSymbol
import cloth.semantic.symbols.FunctionSymbol
import cloth.source.SourceGraph
import cloth.target.TargetLayout
import cloth.token.Token
import cloth.token.TokenSpan
import java.util.IdentityHashMap

internal const val maximumGenericDependencyDepth = 64
internal const val maximumGenericInstances = 4096

/** Discover closed requirements with a worklist, then validate complete layout graphs. */
class ClosedTypeAnalyzer(private val target: TargetLayout) {
	fun analyze(unit: SourceUnit): ClosedTypeModel = analyze(FileBindings.single(unit))
	internal fun analyze(graph: SourceGraph): ClosedTypeModel = analyze(FileBindings.graph(graph))
	internal fun analyze(files: FileBindings, names: GenericNameModel = GenericAnalyzer(target).analyze(files)): ClosedTypeModel {
		if ((names.interfaceSpan != null || names.hasVirtualMethods || names.hasBounds) && names.owners.isNotEmpty()) {
			val templates = files.fork()
			symbolic(templates, GenericAnalyzer(target).analyze(templates))
		}
		return closed(files, names)
	}

	internal fun closed(files: FileBindings, names: GenericNameModel): ClosedTypeModel = Types(files, names, target).analyze()
	internal fun symbolic(files: FileBindings, names: GenericNameModel): ClosedTypeModel = Types(files, names, target, symbolic = true).analyze()

	private class Types(private val files: FileBindings, private val names: GenericNameModel, private val target: TargetLayout, private val symbolic: Boolean = false) {
		private data class Key(val definition: CMemberType, val arguments: List<ValueType>)
		private sealed interface Bound {
			val spelling: String
			data object Number : Bound { override val spelling get() = NumberBoundName.spelling }
			data class Nominal(val type: CMemberType) : Bound { override val spelling get() = type.spelling }
		}
		private data class BoundCheck(val parameter: TypeParameterSymbol, val argument: ValueType, val bound: Bound, val use: TokenSpan)

		private val model = ClosedTypeModel(names, target, symbolic)
		private val instances = linkedMapOf<Key, ClosedTypeInstance>()
		private val work = ArrayDeque<() -> Unit>()
		private val ownerUses = IdentityHashMap<Declaration?, MutableList<GenericTypeUse>>()
		private val ownerCalls = IdentityHashMap<Declaration?, MutableList<CallExpression>>()
		private val boundChecks = mutableListOf<BoundCheck>()
		private var bindings = model.rootBindings
		private var closedCount = 0
		private var symbolicCount = 0

		fun analyze(): ClosedTypeModel {
			val interfaceOrder = interfaceParentOrder(files.typeOrder.filterIsInstance<CInterface>(), names)
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
					if (arguments.isNotEmpty()) instantiate(function, arguments, function.name.span)
				}
			}
			bindOwner(null)
			for (file in files.files) for (function in file.functions) {
				if (function.typeParameterCount == 0) model.functionList.add(signature(function.declaration))
			}
			while (work.isNotEmpty()) work.removeFirst()()
			val baseOrder = validateInheritance()
			validateStructs()
			if (names.hasVirtualMethods) model.virtualMethods = VirtualMethods.analyze(model, baseOrder)
			if (names.interfaceSpan != null) model.interfaces = InterfaceConformanceAnalyzer(model).analyze(interfaceOrder, baseOrder)
			checkBounds()
			model.catalog.values.forEach { it.state = ClosedTypeState.COMPLETE }
			return model
		}

		private fun symbolicArguments(declaration: Declaration) = names.parametersOf(declaration).map { parameter ->
			val bound = parameter.declaration.bound?.let(names::nameOf)
			CTypeParameter(parameter.owner, parameter.declaration, parameter.index, numberBound = bound === NumberBoundName)
		}

		private fun bindOwner(owner: Declaration?) {
			for (use in ownerUses[owner].orEmpty()) bind(use)
			for (call in ownerCalls[owner].orEmpty()) {
				// Discover the needed views from the file group. Body name resolution
				// still checks the call against its lexical scope.
				val arguments = checkNotNull(call.typeArguments)
				val group = files.context(call.name).scope.lookup(call.name.lexeme) as? FunctionGroupSymbol ?: continue
				val template = group.overloads.firstOrNull { it.typeParameterCount == arguments.arguments.size } ?: continue
				instantiate(template, arguments.arguments.map(::resolve), arguments.span, arguments)
			}
		}

		private fun bind(use: GenericTypeUse) {
			val pattern = pattern(use.type)
			if (pattern is ClosedTypePattern.Exact) use.receiver?.let { bindings.receivers[it] = pattern.type }
		}
		private fun pattern(annotation: TypeReference): ClosedTypePattern = bindings.facts.getOrPut(annotation) {
			if (annotation.capturesWidth) ClosedTypePattern.OperatorWidth(annotation) else ClosedTypePattern.Exact(resolve(annotation))
		}
		private fun resolve(annotation: TypeReference): ValueType {
			(bindings.facts[annotation] as? ClosedTypePattern.Exact)?.let { return it.type }
			return resolver().resolve(annotation).also { bindings.facts[annotation] = ClosedTypePattern.Exact(it) }
		}
		private fun resolver() = TypeResolver(target, substitutedType = { annotation ->
			when (val identity = names.names[annotation]) {
				is TypeParameterSymbol -> bindings.environment[identity] ?: throw InternalErrors.invariant("Unsolved owner parameter '${identity.name.lexeme}' in a closed type")
				else -> {
					val definition = (identity as? NominalTypeName)?.type ?: files.nominal(annotation.name)
					definition?.let {
						val application = annotation.application
						if (application == null) it else instantiate(it, application.arguments.map(::resolve), annotation.span, application)
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
			bindBounds()
			val signatures = instance.signatureList
			when (val type = instance.type) {
				is CClass -> {
					defineClass(type)
					signatures.add(signature(PrimaryConstructorDeclaration(type.declaration)))
					type.declaration.members.filterIsInstance<CallableDeclaration>().mapTo(signatures, ::signature)
				}
				is CStruct -> {
					defineStruct(type)
					type.declaration.methods.mapTo(signatures, ::signature)
				}
				is CEnum -> {
					EnumModel.defineFields(type, resolver())
					type.declaration.operators.mapTo(signatures, ::signature)
				}
				is CInterface -> type.declaration.methods.mapTo(signatures, ::signature)
			}
			bindOwner(instance.definition.declaration)
		}

		private fun argumentDepth(type: ValueType): Int {
			var value = type
			while (true) value = when (value) {
				is CArray -> value.element
				is CNullable -> value.payload
				else -> return (value as? CMemberType)?.let { model.catalog[it]?.bindings?.argumentDepth } ?: 0
			}
		}

		private fun instantiate(definition: CMemberType, arguments: List<ValueType>, use: TokenSpan, suppliedArguments: TypeArgumentList? = null): CMemberType {
			val key = Key(definition, arguments.toList())
			// Reuse provisional identities before charging depth or instance budgets.
			instances[key]?.let { return it.type }
			val context = newBindings(definition.declaration, definition.name, key.arguments, use, suppliedArguments)
			val type = when (definition) {
				is CClass -> CClass(definition.declaration, definition, key.arguments)
				is CStruct -> CStruct(definition.declaration, genericDefinition = definition, typeArguments = key.arguments)
				is CEnum -> throw GenericErrors.arity(definition.name, arguments.size, 0, definition.name)
				is CInterface -> CInterface(definition.declaration, definition, key.arguments)
			}
			return register(type, definition, key.arguments, context).also { instances[key] = it }.type
		}

		private fun instantiate(template: FunctionSymbol, arguments: List<ValueType>, use: TokenSpan, suppliedArguments: TypeArgumentList? = null) {
			val key = ClosedFunctionKey(template, arguments)
			model.functionInstances.getOrPut(key) {
				val context = newBindings(template.declaration, template.name, arguments, use, suppliedArguments)
				work.addLast {
					withBindings(context) {
						bindBounds()
						model.functionList.add(signature(template.declaration))
						bindOwner(template.declaration)
					}
				}
				context
			}
		}

		private fun newBindings(declaration: Declaration, name: Token, arguments: List<ValueType>, use: TokenSpan, suppliedArguments: TypeArgumentList?): GenericBindings {
			val chain = bindings.chain
			val open = arguments.any { it.hasTypeParameters() }
			val depth = 1 + (arguments.maxOfOrNull(::argumentDepth) ?: 0)
			fun limit(code: DiagnosticCode, summary: String): Nothing = throw GenericErrors.limit(code, summary, use, name, chain)
			if (depth > maximumTypeArgumentNesting) limit(DiagnosticCode.GENERIC_SUBSTITUTION_NESTING, "Generic type argument nesting limit exceeded ($maximumTypeArgumentNesting)")
			if (chain.size >= maximumGenericDependencyDepth) limit(if (open) DiagnosticCode.GENERIC_TEMPLATE_DEPENDENCY_LIMIT else DiagnosticCode.GENERIC_DEPENDENCY_LIMIT, if (open) "Generic template analysis dependency limit exceeded ($maximumGenericDependencyDepth)" else "Generic instantiation dependency limit exceeded ($maximumGenericDependencyDepth)")
			if (open && symbolicCount >= maximumGenericInstances) limit(DiagnosticCode.GENERIC_TEMPLATE_INSTANCE_LIMIT, "Too many symbolic generic instances ($maximumGenericInstances)")
			if (!open && closedCount >= maximumGenericInstances) limit(DiagnosticCode.GENERIC_INSTANCE_LIMIT, "Too many distinct generic instances ($maximumGenericInstances)")
			if (open) symbolicCount++ else closedCount++
			return GenericBindings(names.parametersOf(declaration).zip(arguments).toMap(), chain + (use to name.lexeme), depth, suppliedArguments)
		}

		private fun bindBounds() {
			val supplied = bindings.suppliedArguments
			for ((parameter, argument) in bindings.environment) {
				val reference = parameter.declaration.bound ?: continue
				val bound = if (names.nameOf(reference) === NumberBoundName) Bound.Number else Bound.Nominal(resolve(reference) as CMemberType)
				if (bound is Bound.Nominal) bindings.nominalBounds[parameter.declaration] = bound.type
				if (supplied == null) {
					// Only the canonical declaration instance supplies facts about its parameters.
					if (bound is Bound.Nominal) (argument as CTypeParameter).nominalBound = bound.type
				} else {
					boundChecks.add(BoundCheck(parameter, argument, bound, supplied.arguments[parameter.index].span))
				}
			}
		}

		private fun checkBounds() {
			// Parent edges and interface conformance are complete before proofs run.
			for ((parameter, argument, bound, use) in boundChecks) {
				val satisfied = when (bound) {
					Bound.Number -> argument.supportsNumericOperations
					is Bound.Nominal -> bound.type.accepts(argument)
				}
				if (!satisfied) throw GenericErrors.unsatisfiedBound(parameter.name, argument.spelling, bound.spelling, use, checkNotNull(parameter.declaration.bound).span)
			}
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
