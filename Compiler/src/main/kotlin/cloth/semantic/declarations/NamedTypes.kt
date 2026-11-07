package cloth.semantic.declarations

import cloth.data.*
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.ClassFieldDeclaration
import cloth.parser.declaration.StructDeclaration
import cloth.parser.declaration.StructFieldDeclaration
import cloth.parser.type.TypeReference
import cloth.target.TargetLayout
import cloth.token.Token

/** Resolve inline struct dependencies and stop layout traversal at class identity. */
internal class NamedTypes(private val bindings: FileBindings, target: TargetLayout) {

	private val resolved = mutableSetOf<CStruct>()
	val root get() = bindings.objectRoot
	val classes get() = bindings.typeOrder.filterIsInstance<CClass>()
	val enums get() = bindings.typeOrder.filterIsInstance<CEnum>()
	private val active = linkedMapOf<CStruct, StructFieldDeclaration?>()
	private val resolver = TypeResolver(target, contextualNamedType = { token -> bindings.nominal(token)?.also { if (it is CStruct) resolve(it.declaration) } })
	fun nominal(name: Token) = bindings.nominal(name)

	init {
		for (owner in classes) {
			val clause = owner.declaration.base
			val base = if (clause == null) root else bindings.nominal(clause.name) as? CClass ?: throw SemanticErrors.classMember("'${clause.name.lexeme}' is not a class base", clause.name.span)
			if (base.declaration.finalKeyword != null) throw SemanticErrors.classMember("Cannot inherit from final class '${base.spelling}'", checkNotNull(clause).name.span, base.name)
			if (base == root && clause?.arguments?.isNotEmpty() == true) throw SemanticErrors.classMember("'${root.spelling}' takes no base arguments", clause.span)
			owner.base = base
		}
		for (owner in classes) {
			val seen = mutableSetOf<CClass>()
			for (base in owner.ancestry()) {
				if (!seen.add(base)) throw SemanticErrors.classMember("Inheritance cycle involving '${base.spelling}'", checkNotNull(base.declaration.base).name.span)
			}
		}
	}

	fun resolveStructs(): List<CStruct> = bindings.typeOrder.filterIsInstance<CStruct>().map { resolve(it.declaration) }

	fun resolveEnumFields() = enums.forEach { EnumModel.defineFields(it, resolver) }

	fun resolveClassFields() {
		for (owner in classes) {
			val fields = mutableListOf<ClassField>()
			val names = mutableMapOf<String, Token>()
			fun add(name: Token, annotation: TypeReference, visibility: Token?) {
				names.putIfAbsent(name.lexeme, name)?.let { throw SemanticErrors.duplicate(name, it) }
				val type = resolver.resolve(annotation)
				if (type == ValueType.VOID) throw SemanticErrors.voidType(annotation.span)
				fields.add(ClassField(owner, name, annotation, fields.size, type, visibility))
			}
			for (header in owner.declaration.parameters) {
				if (header.fieldKeyword != null) add(header.parameter.name, header.parameter.type, header.visibility)
			}
			for (field in owner.declaration.members.filterIsInstance<ClassFieldDeclaration>()) add(field.name, field.type, field.visibility)
			owner.defineFields(fields)
		}
	}

	private fun resolve(declaration: StructDeclaration): CStruct {
		val owner = bindings.structs.getValue(declaration)
		if (owner in resolved) return owner
		if (owner in active) throw SemanticErrors.structCycle(declaration.name, active.entries.dropWhile { it.key !== owner }.mapNotNull { it.value })
		active[owner] = null
		val seen = mutableMapOf<String, StructFieldDeclaration>()
		val fields = declaration.fields.mapIndexed { index, field ->
			seen.putIfAbsent(field.name.lexeme, field)?.let { throw SemanticErrors.duplicate(field.name, it.name) }
			field.type.arraySizes.indexOfFirst { it == null }.takeIf { it >= 0 }?.let {
				throw SemanticErrors.structField("A struct array field requires an explicit extent at every dimension", field.type.arraySuffixes[it])
			}
			active[owner] = field
			val type = resolver.resolve(field.type)
			if (type == ValueType.VOID) throw SemanticErrors.voidType(field.type.span)
			StructField(field, index, type)
		}
		active.remove(owner)
		owner.defineFields(fields)
		resolved.add(owner)
		return owner
	}
}
