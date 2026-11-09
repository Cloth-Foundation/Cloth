package cloth.semantic.generics

import cloth.ast.SourceUnit
import cloth.data.*
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.InterfaceErrors
import cloth.diagnostics.SemanticErrors
import cloth.diagnostics.SourceLabel
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.parser.type.TypeParameterDeclaration
import cloth.parser.type.TypeReference
import cloth.parser.type.maximumTypeArgumentNesting
import cloth.semantic.declarations.FileBindings
import cloth.semantic.flow.eagerOperands
import cloth.source.SourceGraph
import cloth.target.TargetLayout
import cloth.token.Keywords
import cloth.token.Token
import cloth.token.TokenSpan
import java.util.IdentityHashMap

/** Name identities only. These do not describe closed types or native layouts. */
sealed interface TypeNameIdentity
data class BuiltinTypeName(val keyword: Keywords) : TypeNameIdentity
data class NominalTypeName(val type: CMemberType) : TypeNameIdentity

/** Parameters with identical spelling in different owners remain different symbols. */
class TypeParameterSymbol internal constructor(val owner: Declaration, val declaration: TypeParameterDeclaration, val index: Int) : TypeNameIdentity {
	val name get() = declaration.name
}

internal data class GenericTypeUse(val type: TypeReference, val parameters: Map<String, TypeParameterSymbol>, val receiver: NameExpression? = null) {
	val owner get() = parameters.values.firstOrNull()?.owner
}

class GenericNameModel internal constructor(val target: TargetLayout) {
	internal val owners = IdentityHashMap<Declaration, List<TypeParameterSymbol>>()
	internal val names = IdentityHashMap<TypeReference, TypeNameIdentity>()
	internal val receivers = IdentityHashMap<NameExpression, TypeNameIdentity>()
	internal val uses = mutableListOf<GenericTypeUse>()
	internal var genericSpan: TokenSpan? = null
	internal var interfaceSpan: TokenSpan? = null
	val requiresGenericCompilation get() = genericSpan != null
	fun parametersOf(owner: Declaration): List<TypeParameterSymbol> = owners[owner].orEmpty()
	fun nameOf(reference: TypeReference): TypeNameIdentity = names.getValue(reference)
	fun receiverName(expression: NameExpression): TypeNameIdentity? = receivers[expression]
	internal fun requireImplemented() { genericSpan?.let { throw GenericErrors.compilation(it) } }
	internal fun requireInterfaceSemantics() { interfaceSpan?.let { throw InterfaceErrors.compilation(it) } }
}

/** Bind generic syntax in original source contexts before any closed layouts exist. */
class GenericAnalyzer(private val target: TargetLayout) {
	fun analyze(unit: SourceUnit): GenericNameModel = analyze(FileBindings.single(unit))
	internal fun analyze(graph: SourceGraph): GenericNameModel = analyze(FileBindings.graph(graph))
	internal fun analyze(files: FileBindings): GenericNameModel = Names(files, target).analyze()

	private class Names(private val files: FileBindings, target: TargetLayout) {
		private val model = GenericNameModel(target)
		private val uses get() = model.uses
		private var hasMultipleClassParents = false

		fun analyze(): GenericNameModel {
			for (file in files.files) for (declaration in file.unit.declarations) {
				val owner = declaration as? TypeDeclaration ?: continue
				val name = owner.name
				val list = owner.typeParameters ?: continue
				if (model.genericSpan == null) model.genericSpan = list.span
				val seen = mutableMapOf<String, Token>()
				model.owners[declaration] = list.parameters.mapIndexed { index, parameter ->
					if (parameter.name.lexeme == name.lexeme) throw GenericErrors.parameter("A type parameter cannot have its owner's name '${name.lexeme}'", parameter.name, name)
					seen.putIfAbsent(parameter.name.lexeme, parameter.name)?.let { throw GenericErrors.parameter("Type parameter '${parameter.name.lexeme}' is already declared", parameter.name, it) }
					TypeParameterSymbol(declaration, parameter, index)
				}
			}
			for (file in files.files) file.unit.declarations.forEach(::declaration)
			// Leave the established non-generic checking order untouched.
			val hasInterfaceHeaders = model.interfaceSpan != null || hasMultipleClassParents
			if (!model.requiresGenericCompilation && !hasInterfaceHeaders) return model
			for (use in uses) {
				val identity = resolve(use.type, use.parameters)
				use.receiver?.let { model.receivers[it] = identity }
			}
			if (hasInterfaceHeaders) checkParents()
			return model
		}

		private fun checkParents() {
			for (file in files.files) for (declaration in file.unit.declarations) when (declaration) {
				is InterfaceDeclaration -> for (parent in declaration.parents) {
					if ((model.nameOf(parent) as? NominalTypeName)?.type !is CInterface) {
						throw InterfaceErrors.header("'${parent.name.lexeme}' is not an interface parent", parent.span)
					}
				}
				is ClassDeclaration -> {
					var classBase: ClassParentClause? = null
					for (parent in declaration.parents) when ((model.nameOf(parent.type) as? NominalTypeName)?.type) {
						is CClass -> {
							classBase?.let {
								throw InterfaceErrors.header("A class can declare only one class base", parent.span, SourceLabel(it.span, "first class base here"))
							}
							classBase = parent
						}
						is CInterface -> if (parent.constructorArguments != null) {
							throw InterfaceErrors.header("An interface clause takes no constructor arguments", parent.span)
						}
						else -> throw InterfaceErrors.header("'${parent.type.name.lexeme}' is not a class or interface parent", parent.type.span)
					}
				}
				else -> Unit
			}
		}

		private fun interfaceSyntax(span: TokenSpan) {
			if (model.interfaceSpan == null) model.interfaceSpan = span
		}

		private fun parameters(owner: Declaration) = model.parametersOf(owner).associateBy { it.name.lexeme }
		private fun use(type: TypeReference?, parameters: Map<String, TypeParameterSymbol>) {
			if (type == null) return
			if (type.application != null && model.genericSpan == null) model.genericSpan = type.application.span
			uses.add(GenericTypeUse(type, parameters))
		}

		private fun resolve(type: TypeReference, parameters: Map<String, TypeParameterSymbol>, argument: Boolean = false, depth: Int = 0): TypeNameIdentity {
			if (depth > maximumTypeArgumentNesting) throw GenericErrors.nesting(type.span, maximumTypeArgumentNesting)
			if (argument && type.name.lexeme == Keywords.VOID.toString()) throw GenericErrors.argument("'${Keywords.VOID}' is not a value type argument", type.span)
			val parameter = parameters[type.name.lexeme]
			val identity = parameter ?: builtinTypes.firstOrNull { it.toString() == type.name.lexeme }?.let(::BuiltinTypeName)
				?: files.nominal(type.name)?.let(::NominalTypeName) ?: throw SemanticErrors.type(type)
			if (identity is TypeParameterSymbol && type.application != null) throw GenericErrors.argument("Type parameter '${type.name.lexeme}' is not a generic declaration", type.span)
			if (identity is NominalTypeName) {
				val expected = model.parametersOf(identity.type.declaration).size
				if (expected != 0 && type.application == null) throw GenericErrors.raw(type.name, identity.type.name)
				if (type.typeArguments.size != expected) throw GenericErrors.arity(type.name, type.typeArguments.size, expected, identity.type.name)
			}
			type.typeArguments.forEach { resolve(it, parameters, argument = true, depth = depth + 1) }
			model.names[type] = identity
			return identity
		}

		private fun declaration(declaration: Declaration) {
			val parameters = parameters(declaration)
			when (declaration) {
				is FunctionDeclaration -> callable(declaration, parameters, emptySet())
				is StructDeclaration -> {
					declaration.fields.forEach { use(it.type, parameters) }
					val fields = declaration.fields.map { it.name.lexeme }.toSet()
					declaration.methods.forEach { callable(it, parameters, fields) }
				}
				is InterfaceDeclaration -> {
					interfaceSyntax(declaration.kind.span)
					declaration.parents.forEach { use(it, parameters) }
					declaration.methods.forEach { callable(it, parameters, emptySet()) }
				}
				is ClassDeclaration -> {
					if (declaration.parents.size > 1) hasMultipleClassParents = true
					val fields = classFields(declaration)
					var header = emptySet<String>()
					for (parameter in declaration.parameters) {
						use(parameter.parameter.type, parameters)
						parameter.parameter.defaultValue?.let { expression(it, parameters, header) }
						header = header + parameter.parameter.name.lexeme
					}
					for (parent in declaration.parents) {
						use(parent.type, parameters)
						parent.constructorArguments.orEmpty().forEach { expression(it.expression, parameters, header) }
					}
					for (member in declaration.members) when (member) {
						is ClassFieldDeclaration -> { use(member.type, parameters); expression(member.initializer, parameters, fields + header) }
						is CallableDeclaration -> {
							if (member is ConstructorDeclaration) member.delegation.arguments.forEach { expression(it, parameters, member.parameters.map { it.name.lexeme }.toSet()) }
							callable(member, parameters, fields)
						}
					}
				}
				is EnumDeclaration -> {
					for (field in declaration.fields) { use(field.type, parameters); field.default?.let { expression(it, parameters, emptySet()) } }
					declaration.cases.forEach { case -> case.values.forEach { expression(it, parameters, emptySet()) } }
					declaration.operators.forEach { callable(it, parameters, declaration.fields.map { it.name.lexeme }.toSet()) }
				}
				else -> Unit
			}
		}

		private fun classFields(declaration: ClassDeclaration, seen: MutableSet<ClassDeclaration> = java.util.Collections.newSetFromMap(IdentityHashMap())): Set<String> {
			if (!seen.add(declaration)) return emptySet()
			val own = declaration.parameters.filter { it.fieldKeyword != null }.map { it.parameter.name.lexeme } + declaration.members.filterIsInstance<ClassFieldDeclaration>().map { it.name.lexeme }
			val base = declaration.parents.firstNotNullOfOrNull { (files.nominal(it.type.name) as? CClass)?.declaration }
			return own.toSet() + (base?.let { classFields(it, seen) } ?: emptySet())
		}

		private fun callable(declaration: CallableSignature, parameters: Map<String, TypeParameterSymbol>, outer: Set<String>) {
			declaration.returnTypes.forEach { use(it, parameters) }
			(declaration as? MethodDeclaration)?.let { method ->
				method.overridePrefix?.let(::interfaceSyntax)
				method.operator?.selector?.let { use(it, parameters) }
			}
			var values = outer
			for (parameter in declaration.parameters) {
				use(parameter.type, parameters)
				parameter.defaultValue?.let { expression(it, parameters, values) }
				values = values + parameter.name.lexeme
			}
			val body = when (declaration) {
				is CallableDeclaration -> declaration.body
				is InterfaceMethodDeclaration -> declaration.fallback?.body
			}
			when (body) {
				is BlockBody -> statements(body.statements, parameters, values)
				is ExpressionBody -> expression(body.expression, parameters, values)
				null -> Unit
			}
		}

		private fun statements(statements: List<Statement>, parameters: Map<String, TypeParameterSymbol>, outer: Set<String>) {
			val values = outer.toMutableSet()
			for (statement in statements) when (statement) {
				is BindingDeclaration -> { use(statement.type, parameters); expression(statement.initializer, parameters, values); values.add(statement.name.lexeme) }
				is BindingGroupDeclaration -> {
					statement.types.forEach { use(it, parameters) }; expression(statement.initializer, parameters, values)
					statement.targets.filterIsInstance<ReceivingTarget.Named>().forEach { values.add(it.token.lexeme) }
				}
				is ExpressionStatement -> expression(statement.expression, parameters, values)
				is AssignmentStatement -> expression(statement.value, parameters, values)
				is MultiAssignmentStatement -> expression(statement.value, parameters, values)
				is ElementAssignmentStatement -> { expression(statement.target, parameters, values); expression(statement.value, parameters, values) }
				is FieldWriteStatement -> { expression(statement.target, parameters, values); statement.value?.let { expression(it, parameters, values) } }
				is ReturnStatement -> statement.value?.let { expression(it, parameters, values) }
				is ForStatement -> {
					use(statement.binding.type, parameters)
					when (val source = statement.source) {
						is ForSource.Elements -> expression(source.expression, parameters, values)
						is ForSource.Range -> { expression(source.start, parameters, values); expression(source.end, parameters, values) }
					}
					val child = values + statement.binding.name.lexeme
					statement.stepper?.value?.let { expression(it, parameters, child) }
					statements(statement.body.statements, parameters, child)
				}
				is WhileStatement -> { expression(statement.condition, parameters, values); statements(statement.body.statements, parameters, values) }
				is DoWhileStatement -> { statements(statement.body.statements, parameters, values); expression(statement.condition, parameters, values) }
				is UpdateStatement, is LoopJumpStatement -> Unit
				else -> Unit
			}
		}

		private fun receiver(expression: Expression, parameters: Map<String, TypeParameterSymbol>, values: Set<String>, alwaysType: Boolean = false) {
			if (expression is NameExpression && (alwaysType || expression.name.lexeme !in values) && (expression.name.lexeme in parameters || files.nominal(expression.name) != null)) uses.add(GenericTypeUse(TypeReference(expression.name), parameters, expression))
			else expression(expression, parameters, values)
		}

		private fun expression(expression: Expression, parameters: Map<String, TypeParameterSymbol>, values: Set<String>) {
			when (expression) {
				is TypeReceiverExpression -> use(expression.annotation, parameters)
				is CastExpression -> { expression(expression.operand, parameters, values); use(expression.target, parameters) }
				is TypeCheckExpression -> {
					expression(expression.operand, parameters, values)
					val target = expression.target
					if (target.application != null || target.hasNullability || target.width != null || target.name.lexeme in parameters || target.name.lexeme !in values || files.nominal(target.name) != null) use(target, parameters)
				}
				is FunctionBlockExpression -> callable(expression.declaration, parameters, values)
				is IfExpression -> {
					for (branch in expression.branches) { expression(branch.condition, parameters, values); statements(branch.statements, parameters, values) }
					expression.otherwise?.let { statements(it, parameters, values) }
				}
				is ElvisExpression -> { expression(expression.left, parameters, values); expression(expression.fallback, parameters, values) }
				is ProviderAccessExpression -> receiver(expression.receiver, parameters, values)
				is ClassCreationExpression -> { receiver(expression.receiver, parameters, values, alwaysType = true); expression.arguments.forEach { expression(it, parameters, values) } }
				is StateCallExpression -> { receiver(expression.receiver, parameters, values); expression.arguments.forEach { expression(it, parameters, values) } }
				else -> expression.eagerOperands().forEach { expression(it, parameters, values) }
			}
		}

		companion object {
			private val builtinTypes = setOf(Keywords.INTEGER, Keywords.FLOAT, Keywords.BOOLEAN, Keywords.STRING, Keywords.CHARACTER, Keywords.VOID, Keywords.TYPEINFO, Keywords.USIZE, Keywords.ISIZE)
		}
	}
}
