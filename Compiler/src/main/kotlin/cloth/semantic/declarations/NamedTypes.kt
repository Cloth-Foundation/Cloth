package cloth.semantic.declarations

import cloth.data.*
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.*
import cloth.parser.type.TypeReference
import cloth.target.TargetLayout
import cloth.token.Keywords
import cloth.token.Token
import cloth.token.TokenSpan
import cloth.token.TokenType

/** Resolve inline struct dependencies and stop layout traversal at class identity. */
internal class NamedTypes(declarations: List<Declaration>, target: TargetLayout) {

	private val names = linkedMapOf<String, StructDeclaration>()
	private val resolved = mutableMapOf<String, CStruct>()
	private val classNames = linkedMapOf<String, CClass>()
	private val enumNames = linkedMapOf<String, CEnum>()
	val root: CClass = TokenSpan(1, 1, 1, 1).let { span ->
		val keyword = Token(TokenType.Keyword, span, Keywords.CLASS.toString())
		CClass(ClassDeclaration(keyword, Token(TokenType.Identifier, span, "Object"), null, null, emptyList(), emptyList(), span)).also { it.defineFields(emptyList()) }
	}
	val classes: Map<String, CClass> get() = classNames
	val enums: Map<String, CEnum> get() = enumNames
	private val active = linkedMapOf<String, StructFieldDeclaration?>()
	private val resolver = TypeResolver(target) { name -> classNamed(name) ?: enums[name] ?: names[name]?.let(::resolve) }
	private fun classNamed(name: String) = if (name == root.spelling) root else classNames[name]

	init {
		for (declaration in declarations) {
			val typeName = when (declaration) {
				is ClassDeclaration  -> declaration.name
				is StructDeclaration -> declaration.name
				is EnumDeclaration   -> declaration.name
				else                 -> null
			}
			if (typeName?.lexeme == root.spelling) throw SemanticErrors.classMember("'${root.spelling}' is the built-in class root", typeName.span)
			when (declaration) {
				is StructDeclaration -> {
					val name = declaration.name
					(classNames[name.lexeme]?.name ?: enums[name.lexeme]?.name)?.let { throw SemanticErrors.duplicateClassType(name, it) }
					names.putIfAbsent(name.lexeme, declaration)?.let { throw SemanticErrors.duplicateStruct(name, it.name) }
				}

				is ClassDeclaration  -> {
					val name = declaration.name
					val previous = names[name.lexeme]?.name ?: classNames[name.lexeme]?.name ?: enums[name.lexeme]?.name
					if (previous != null) throw SemanticErrors.duplicateClassType(name, previous)
					classNames[name.lexeme] = CClass(declaration)
				}

				is EnumDeclaration   -> {
					val name = declaration.name
					val previous = names[name.lexeme]?.name ?: classes[name.lexeme]?.name ?: enums[name.lexeme]?.name
					if (previous != null) throw SemanticErrors.duplicateClassType(name, previous)
					enumNames[name.lexeme] = CEnum(declaration)
				}

				else                 -> Unit
			}
		}
		for (owner in classes.values) {
			val clause = owner.declaration.base
			val base = if (clause == null) root else classNamed(clause.name.lexeme) ?: throw SemanticErrors.classMember("'${clause.name.lexeme}' is not a class base", clause.name.span)
			if (base.declaration.finalKeyword != null) throw SemanticErrors.classMember("Cannot inherit from final class '${base.spelling}'", checkNotNull(clause).name.span, base.name)
			if (base == root && clause?.arguments?.isNotEmpty() == true) throw SemanticErrors.classMember("'${root.spelling}' takes no base arguments", clause.span)
			owner.base = base
		}
		for (owner in classes.values) {
			val seen = mutableSetOf<CClass>()
			for (base in owner.ancestry()) {
				if (!seen.add(base)) throw SemanticErrors.classMember("Inheritance cycle involving '${base.spelling}'", checkNotNull(base.declaration.base).name.span)
			}
		}
	}

	fun resolveStructs(): Map<String, CStruct> = names.mapValues { resolve(it.value) }

	fun resolveEnumFields() = enums.values.forEach { EnumModel.defineFields(it, resolver) }

	fun resolveClassFields() {
		for (owner in classes.values) {
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
			val type = resolver.resolve(field.type)
			if (type == ValueType.VOID) throw SemanticErrors.voidType(field.type.span)
			StructField(field, index, type)
		}
		active.remove(name)
		return CStruct(declaration, fields).also { resolved[name] = it }
	}
}
