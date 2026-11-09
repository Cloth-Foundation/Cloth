package cloth.semantic.generics

import cloth.ast.SourceUnit
import cloth.data.*
import cloth.diagnostics.DiagnosticCode
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.*
import cloth.parser.type.TypeReference
import cloth.parser.type.maximumTypeArgumentNesting
import cloth.semantic.declarations.EnumModel
import cloth.semantic.declarations.FileBindings
import cloth.semantic.declarations.TypeResolver
import cloth.semantic.interfaces.InterfaceConformanceAnalyzer
import cloth.semantic.interfaces.interfaceParentOrder
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
		if (names.interfaceSpan != null && names.owners.isNotEmpty()) {
			val templates = files.fork()
			symbolic(templates, GenericAnalyzer(target).analyze(templates))
		}
		return closed(files, names)
	}

	internal fun closed(files: FileBindings, names: GenericNameModel): ClosedTypeModel = Types(files, names, target).analyze()
	internal fun symbolic(files: FileBindings, names: GenericNameModel): ClosedTypeModel = Types(files, names, target, symbolic = true).analyze()

	private class Types(private val files: FileBindings, private val names: GenericNameModel, private val target: TargetLayout, private val symbolic: Boolean = false) {
		private data class Key(val definition: CMemberType, val arguments: List<ValueType>)
		private val model = ClosedTypeModel(names, target, symbolic)
		private val instances = linkedMapOf<Key, ClosedTypeInstance>()
		private val work = ArrayDeque<ClosedTypeInstance>()
		private val ownerUses = IdentityHashMap<Declaration, MutableList<GenericTypeUse>>()
		private var current: ClosedTypeInstance? = null
		private var closedCount = 0
		private var symbolicCount = 0

		fun analyze(): ClosedTypeModel {
			val interfaceOrder = interfaceParentOrder(files.typeOrder.filterIsInstance<CInterface>(), names)
			for (use in names.uses) use.owner?.let { ownerUses.getOrPut(it) { mutableListOf() }.add(use) }
			for (definition in files.typeOrder) if (names.parametersOf(definition.declaration).isEmpty()) register(definition, definition, emptyList(), emptyList(), 0)
			if (symbolic) for (definition in files.typeOrder) {
				val parameters = names.parametersOf(definition.declaration)
				if (parameters.isNotEmpty()) instantiate(definition, parameters.map { CTypeParameter(it.owner, it.declaration, it.index) }, definition.name.span)
			}
			for (use in names.uses) if (use.owner == null) bind(use)
			for (file in files.files) for (function in file.functions) model.functionList.add(signature(function.declaration))
			while (work.isNotEmpty()) {
				val instance = work.removeFirst()
				current = instance
				instance.state = ClosedTypeState.RESOLVING
				when (val type = instance.type) {
					is CClass -> defineClass(type)
					is CStruct -> defineStruct(type)
					is CEnum -> {
						EnumModel.defineFields(type, resolver())
						type.declaration.operators.forEach { instance.signatureList.add(signature(it)) }
					}
					is CInterface -> type.declaration.methods.forEach { instance.signatureList.add(signature(it)) }
				}
				for (use in ownerUses[instance.definition.declaration].orEmpty()) bind(use)
				current = null
			}
			val baseOrder = validateInheritance()
			validateStructs()
			if (names.interfaceSpan != null) model.interfaces = InterfaceConformanceAnalyzer(model).analyze(interfaceOrder, baseOrder)
			model.catalog.values.forEach { it.state = ClosedTypeState.COMPLETE }
			return model
		}

		private fun facts() = current?.facts ?: model.roots
		private fun bind(use: GenericTypeUse) {
			val pattern = pattern(use.type)
			if (pattern is ClosedTypePattern.Exact) use.receiver?.let { (current?.receivers ?: model.receivers)[it] = pattern.type }
		}
		private fun pattern(annotation: TypeReference): ClosedTypePattern = facts().getOrPut(annotation) {
			if (annotation.capturesWidth) ClosedTypePattern.OperatorWidth(annotation) else ClosedTypePattern.Exact(resolve(annotation))
		}
		private fun resolve(annotation: TypeReference): ValueType {
			(facts()[annotation] as? ClosedTypePattern.Exact)?.let { return it.type }
			return resolver().resolve(annotation).also { facts()[annotation] = ClosedTypePattern.Exact(it) }
		}
		private fun resolver() = TypeResolver(target, substitutedType = { annotation ->
			when (val identity = names.names[annotation]) {
				is TypeParameterSymbol -> current?.environment?.get(identity) ?: throw InternalErrors.invariant("Unsolved owner parameter '${identity.name.lexeme}' in a closed type")
				else -> {
					val definition = (identity as? NominalTypeName)?.type ?: files.nominal(annotation.name)
					definition?.let { if (annotation.application == null) it else instantiate(it, annotation.typeArguments.map(::resolve), annotation.span) }
				}
			}
		})

		private fun register(type: CMemberType, definition: CMemberType, arguments: List<ValueType>, chain: List<Pair<TokenSpan, String>>, depth: Int): ClosedTypeInstance {
			val parameters = names.parametersOf(definition.declaration)
			return ClosedTypeInstance(type, definition, arguments, parameters.zip(arguments).toMap(), chain, depth).also {
				model.catalog[type] = it
				work.addLast(it)
			}
		}

		private fun argumentDepth(type: ValueType): Int {
			var value = type
			while (true) value = when (value) {
				is CArray -> value.element
				is CNullable -> value.payload
				else -> return (value as? CMemberType)?.let { model.catalog[it]?.argumentDepth } ?: 0
			}
		}

		private fun instantiate(definition: CMemberType, arguments: List<ValueType>, use: TokenSpan): CMemberType {
			val key = Key(definition, arguments.toList())
			// Reuse provisional identities before charging depth or instance budgets.
			instances[key]?.let { return it.type }
			val chain = current?.chain.orEmpty()
			val open = arguments.any { it.hasTypeParameters() }
			val depth = 1 + (arguments.maxOfOrNull(::argumentDepth) ?: 0)
			fun limit(code: DiagnosticCode, summary: String): Nothing = throw GenericErrors.limit(code, summary, use, definition.name, chain)
			if (depth > maximumTypeArgumentNesting) limit(DiagnosticCode.GENERIC_SUBSTITUTION_NESTING, "Generic type argument nesting limit exceeded ($maximumTypeArgumentNesting)")
			if (chain.size >= maximumGenericDependencyDepth) limit(if (open) DiagnosticCode.GENERIC_TEMPLATE_DEPENDENCY_LIMIT else DiagnosticCode.GENERIC_DEPENDENCY_LIMIT, if (open) "Generic template analysis dependency limit exceeded ($maximumGenericDependencyDepth)" else "Generic instantiation dependency limit exceeded ($maximumGenericDependencyDepth)")
			if (open && symbolicCount >= maximumGenericInstances) limit(DiagnosticCode.GENERIC_TEMPLATE_INSTANCE_LIMIT, "Too many symbolic generic instances ($maximumGenericInstances)")
			if (!open && closedCount >= maximumGenericInstances) limit(DiagnosticCode.GENERIC_INSTANCE_LIMIT, "Too many distinct generic instances ($maximumGenericInstances)")
			val type = when (definition) {
				is CClass -> CClass(definition.declaration, definition, key.arguments)
				is CStruct -> CStruct(definition.declaration, genericDefinition = definition, typeArguments = key.arguments)
				is CEnum -> throw GenericErrors.arity(definition.name, arguments.size, 0, definition.name)
				is CInterface -> CInterface(definition.declaration, definition, key.arguments)
			}
			if (open) symbolicCount++ else closedCount++
			return register(type, definition, key.arguments, chain + (use to definition.name.lexeme), depth).also { instances[key] = it }.type
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
			current!!.signatureList.add(signature(PrimaryConstructorDeclaration(declaration)))
			declaration.members.filterIsInstance<CallableDeclaration>().forEach { current!!.signatureList.add(signature(it)) }
		}

		private fun defineStruct(owner: CStruct) {
			val seen = mutableMapOf<String, Token>()
			owner.defineFields(owner.declaration.fields.mapIndexed { index, field ->
				seen.putIfAbsent(field.name.lexeme, field.name)?.let { throw SemanticErrors.duplicate(field.name, it) }
				val type = resolve(field.type)
				if (type == ValueType.VOID) throw SemanticErrors.voidType(field.type.span)
				StructField(field, index, type)
			})
			owner.declaration.methods.forEach { current!!.signatureList.add(signature(it)) }
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
