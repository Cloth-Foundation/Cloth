package cloth.semantic

import cloth.data.CStruct
import cloth.data.StructField
import cloth.data.ValueType
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.StructDeclaration
import cloth.parser.declaration.StructFieldDeclaration
import cloth.target.TargetLayout

/** Resolve field dependencies before function signatures or bodies use a nominal type. */
internal class StructTypes(declarations: List<StructDeclaration>, target: TargetLayout) {

	private val names = linkedMapOf<String, StructDeclaration>()
	private val resolved = mutableMapOf<String, CStruct>()
	private val active = linkedMapOf<String, StructFieldDeclaration?>()
	private val resolver = TypeResolver(target) { name -> names[name]?.let(::resolve) }

	init {
		for (declaration in declarations) {
			val name = declaration.name
			names.putIfAbsent(name.lexeme, declaration)?.let { throw SemanticErrors.duplicateStruct(name, it.name) }
		}
	}

	fun resolveAll(): Map<String, CStruct> = names.mapValues { resolve(it.value) }

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
