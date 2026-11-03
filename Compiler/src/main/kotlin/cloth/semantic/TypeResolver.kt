package cloth.semantic

import cloth.data.*
import cloth.diagnostics.SemanticErrors
import cloth.parser.type.TypeReference
import cloth.target.TargetLayout
import cloth.token.Keywords

internal class TypeResolver(val target: TargetLayout) {

	fun resolve(annotation: TypeReference, declarations: Boolean = false): ValueType {
		val name = annotation.name.lexeme
		if (name == Keywords.VOID.toString() && annotation.hasNullability) throw SemanticErrors.nullableType("'${Keywords.VOID}' has no value and cannot be nullable", annotation.span)
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
				Keywords.USIZE.toString()     -> CUSize(target.sizeBits)
				Keywords.ISIZE.toString()     -> CISize(target.sizeBits)
				Keywords.BOOLEAN.toString()   -> ValueType.BOOL
				Keywords.CHARACTER.toString() -> ValueType.CHAR
				Keywords.STRING.toString()    -> ValueType.STRING
				Keywords.TYPEINFO.toString()  -> ValueType.TYPEINFO
				Keywords.VOID.toString()      -> if (declarations) ValueType.VOID else throw SemanticErrors.type(annotation)
				else                          -> throw SemanticErrors.type(annotation)
			}
		}
		if (base == ValueType.VOID && annotation.arraySuffixes.isNotEmpty()) throw SemanticErrors.voidType(annotation.span)
		val scalar = if (annotation.nullable != null) CNullable(base) else base
		// Dimensions retain C order; nullable wrappers compose in C# suffix order.
		return annotation.arraySuffixes.indices.reversed().fold(scalar) { element, index ->
			val token = annotation.arraySizes.getOrNull(index)
			val size = token?.lexeme?.toBigIntegerOrNull()
			if (token != null && (size == null || size.signum() < 0 || size > CUSize(target.sizeBits).maximum)) {
				throw SemanticErrors.array("Array count exceeds ${Keywords.USIZE} (${target.sizeBits}-bit target)", token.span)
			}
			val array = CArray(element, size)
			if (annotation.arrayNullability.getOrNull(annotation.arraySuffixes.lastIndex - index) != null) CNullable(array) else array
		}
	}
}
