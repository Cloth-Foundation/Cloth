package cloth.semantic

import cloth.data.CArray
import cloth.data.CFloat
import cloth.data.CInt
import cloth.data.ValueType
import cloth.diagnostics.SemanticErrors
import cloth.parser.type.TypeReference
import cloth.token.Keywords

internal object TypeResolver {

	fun resolve(annotation: TypeReference, declarations: Boolean = false): ValueType {
		val name = annotation.name.lexeme
		if (annotation.unsigned && name != Keywords.INTEGER.toString()) throw SemanticErrors.unsigned(annotation)
		val widths = when (name) {
			Keywords.INTEGER.toString() -> ValueType.INTEGER_WIDTHS
			Keywords.FLOAT.toString()   -> ValueType.FLOAT_WIDTHS
			else                        -> null
		}
		val base = if (widths != null) {
			val width = annotation.width?.lexeme?.toIntOrNull() ?: if (annotation.width == null) ValueType.DEFAULT_BITS else null
			if (width == null || width !in widths) throw SemanticErrors.width(annotation, widths)
			if (name == Keywords.INTEGER.toString()) CInt(width, annotation.unsigned) else CFloat(width)
		} else {
			if (annotation.width != null) throw SemanticErrors.type(annotation)
			when (name) {
				Keywords.BOOLEAN.toString()   -> ValueType.BOOL
				Keywords.CHARACTER.toString() -> ValueType.CHAR
				Keywords.STRING.toString()    -> ValueType.STRING
				Keywords.TYPEINFO.toString()  -> ValueType.TYPEINFO
				Keywords.VOID.toString()      -> if (declarations) ValueType.VOID else throw SemanticErrors.type(annotation)
				else                          -> throw SemanticErrors.type(annotation)
			}
		}
		if (base == ValueType.VOID && annotation.arraySuffixes.isNotEmpty()) throw SemanticErrors.voidType(annotation.span)
		return annotation.arraySuffixes.indices.reversed().fold(base) { element, index ->
			val token = annotation.arraySizes.getOrNull(index)
			val size = token?.lexeme?.toBigIntegerOrNull()
			if (token != null && (size == null || size.signum() < 0 || size > CInt(64, unsigned = true).maximum)) {
				throw SemanticErrors.array("Array count exceeds unsigned int<64>", token.span)
			}
			CArray(element, size)
		}
	}
}
