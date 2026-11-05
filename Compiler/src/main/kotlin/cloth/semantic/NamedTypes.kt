package cloth.semantic

import cloth.data.*
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.*
import cloth.parser.type.TypeReference
import cloth.target.TargetLayout
import cloth.token.Token

/** Resolve inline struct dependencies and stop layout traversal at class identity. */
internal class NamedTypes(declarations: List<Declaration>, target: TargetLayout) {

	private val names = linkedMapOf<String, StructDeclaration>()
	private val resolved = mutableMapOf<String, CStruct>()
	private val classNames = linkedMapOf<String, CClass>()
	val classes: Map<String, CClass> get() = classNames
	private val active = linkedMapOf<String, StructFieldDeclaration?>()
	private val resolver = TypeResolver(target) { name -> classNames[name] ?: names[name]?.let(::resolve) }

	init {
		for (declaration in declarations) {
			when (declaration) {
				is StructDeclaration -> {
					val name = declaration.name
					classNames[name.lexeme]?.let { throw SemanticErrors.duplicateClassType(name, it.name) }
					names.putIfAbsent(name.lexeme, declaration)?.let { throw SemanticErrors.duplicateStruct(name, it.name) }
				}

				is ClassDeclaration  -> {
					val name = declaration.name
					val previous = names[name.lexeme]?.name ?: classNames[name.lexeme]?.name
					if (previous != null) throw SemanticErrors.duplicateClassType(name, previous)
					classNames[name.lexeme] = CClass(declaration)
				}

				else                 -> Unit
			}
		}
	}

	fun resolveStructs(): Map<String, CStruct> = names.mapValues { resolve(it.value) }

	fun resolveClassFields() {
		for (owner in classes.values) {
			val fields = mutableListOf<ClassField>()
			val names = mutableMapOf<String, Token>()
			fun add(name: Token, annotation: TypeReference, visibility: Token?) {
				names.putIfAbsent(name.lexeme, name)?.let { throw SemanticErrors.duplicate(name, it) }
				val type = resolver.resolve(annotation, declarations = true)
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
		val name = declaration.name.lexeme
		resolved[name]?.let { return it }
		if (name in active) throw SemanticErrors.structCycle(declaration.name, active.entries.dropWhile { it.key != name }.mapNotNull { it.value })
		active[name] = null
		val seen = mutableMapOf<String, StructFieldDeclaration>()
		val fields = declaration.fields.mapIndexed { index, field ->
			seen.putIfAbsent(field.name.lexeme, field)?.let { throw SemanticErrors.duplicate(field.name, it.name) }
			field.type.arraySizes.indexOfFirst { it == null }.takeIf { it >= 0 }?.let {
				throw SemanticErrors.structField("A struct array field requires an explicit extent at every dimension", field.type.arraySuffixes[it])
			}
			active[name] = field
			val type = resolver.resolve(field.type, declarations = true)
			if (type == ValueType.VOID) throw SemanticErrors.voidType(field.type.span)
			StructField(field, index, type)
		}
		active.remove(name)
		return CStruct(declaration, fields).also { resolved[name] = it }
	}
}
