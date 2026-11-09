package cloth.semantic.declarations

import cloth.data.*
import cloth.diagnostics.SemanticErrors
import cloth.diagnostics.GenericErrors
import cloth.parser.type.TypeReference
import cloth.semantic.OperatorRules
import cloth.target.TargetLayout
import cloth.token.Keywords
import cloth.token.Token
import cloth.token.integerLiteralValue

internal class TypeResolver(val target: TargetLayout, private val operatorWidth: Int? = null, private val namedType: (String) -> CMemberType? = { null }, private val contextualNamedType: ((Token) -> CMemberType?)? = null, private val substitutedType: ((TypeReference) -> ValueType?)? = null, private val resolvedType: ((TypeReference) -> ValueType?)? = null) {

	fun resolve(annotation: TypeReference): ValueType {
		resolvedType?.invoke(annotation)?.let { return it }
		if (substitutedType == null) annotation.application?.let { throw GenericErrors.compilation(it.span) }
		val name = annotation.name.lexeme
		if (name == Keywords.VOID.toString() && annotation.hasNullability) throw SemanticErrors.nullableType("'${Keywords.VOID}' has no value and cannot be nullable", annotation.span)
		if (annotation.unsigned && name != Keywords.INTEGER.toString()) throw SemanticErrors.unsigned(annotation)
		val base = if (name == Keywords.INTEGER.toString() || name == Keywords.FLOAT.toString()) {
			val widthValue = if (annotation.capturesWidth) {
				if (!OperatorRules.supportsInferredWidth(annotation) || operatorWidth == null) throw SemanticErrors.type(annotation)
				operatorWidth.toBigInteger()
			} else annotation.width?.integerLiteralValue()
			if (name == Keywords.INTEGER.toString()) {
				if (widthValue != null && (widthValue < ValueType.MIN_INTEGER_BITS.toBigInteger() || widthValue > ValueType.MAX_INTEGER_BITS.toBigInteger())) throw SemanticErrors.integerWidth(annotation)
				// Narrow only after checking the exact decoded magnitude, never by truncation.
				CInt(widthValue?.toInt() ?: ValueType.DEFAULT_BITS, annotation.unsigned)
			} else {
				val width = if (widthValue == null) ValueType.DEFAULT_BITS else ValueType.FLOAT_WIDTHS.firstOrNull { widthValue == it.toBigInteger() }
				if (width == null) throw SemanticErrors.width(annotation, ValueType.FLOAT_WIDTHS)
				CFloat(width)
			}
		} else {
			if (annotation.width != null) throw SemanticErrors.type(annotation)
			when (name) {
				Keywords.USIZE.toString()     -> CUSize(target.sizeBits)
				Keywords.ISIZE.toString()     -> CISize(target.sizeBits)
				Keywords.BOOLEAN.toString()   -> ValueType.BOOL
				Keywords.CHARACTER.toString() -> ValueType.CHAR
				Keywords.STRING.toString()    -> ValueType.STRING
				Keywords.TYPEINFO.toString()  -> ValueType.TYPEINFO
				Keywords.VOID.toString()      -> ValueType.VOID
				else                          -> (if (substitutedType != null) substitutedType.invoke(annotation) else if (contextualNamedType != null) contextualNamedType.invoke(annotation.name) else namedType(name)) ?: throw SemanticErrors.type(annotation)
			}
		}
		if (base == ValueType.VOID && annotation.arraySuffixes.isNotEmpty()) throw SemanticErrors.voidType(annotation.span)
		// A nullable actual parameter already supplies the presence carrier for T?.
		val scalar = if (annotation.nullable != null && base !is CNullable) CNullable(base) else base
		// Dimensions retain C order; nullable wrappers compose in C# suffix order.
		return annotation.arraySuffixes.indices.reversed().fold(scalar) { element, index ->
			val token = annotation.arraySizes.getOrNull(index)
			val size = token?.integerLiteralValue()
			if (token != null && (size == null || size.signum() < 0 || size > CUSize(target.sizeBits).maximum)) {
				throw SemanticErrors.array("Array count exceeds ${Keywords.USIZE} (${target.sizeBits}-bit target)", token.span)
			}
			val array = CArray(element, size)
			if (annotation.arrayNullability.getOrNull(annotation.arraySuffixes.lastIndex - index) != null) CNullable(array) else array
		}
	}
}
