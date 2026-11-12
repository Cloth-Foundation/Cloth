package cloth.semantic.generics

import cloth.data.*
import cloth.diagnostics.ClothDiagnostic
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.InterfaceErrors
import cloth.diagnostics.SourceLabel
import cloth.parser.declaration.ConstructorGuaranteeDeclaration
import cloth.parser.type.ConstructorBound
import cloth.parser.type.TypeParameterBound
import cloth.parser.type.TypeReference
import cloth.token.Operators
import cloth.token.TokenSpan

internal fun TypeParameterBound.signatureTypes(): List<TypeReference> = when (this) {
	is TypeReference    -> listOf(this)
	is ConstructorBound -> declaration.parameters.map { it.type }
}

/** A resolved requirement with the source location of the written bound. */
internal sealed interface GenericBound {

	val span: TokenSpan
	val spelling: String

	data class Number(override val span: TokenSpan) : GenericBound {

		override val spelling get() = NumberBoundName.spelling
	}

	data class Nominal(override val span: TokenSpan, val type: CMemberType) : GenericBound {

		override val spelling get() = type.spelling
	}

	data class Nullable(val payload: GenericBound) : GenericBound {

		override val span get() = payload.span
		override val spelling get() = payload.spelling + Operators.QUESTION
	}

	data class Array(val shape: ValueType, val numericElement: Boolean, val reference: TypeReference) : GenericBound {

		override val span get() = reference.span
		override val spelling get() = if (numericElement) reference.spelling else shape.spelling

		fun matches(argument: ValueType): Boolean {
			if (!numericElement) return shape == argument
			var required = shape
			var actual = argument
			while (true) when (val part = required) {
				is CArray    -> {
					val array = actual as? CArray ?: return false
					if (part.size != array.size) return false
					required = part.element
					actual = array.element
				}

				is CNullable -> {
					required = part.payload
					actual = (actual as? CNullable)?.payload ?: return false
				}

				else         -> return actual.supportsNumericOperations
			}
		}
	}

	data class Constructor(val declaration: ConstructorGuaranteeDeclaration, val parameterTypes: List<ValueType>) : GenericBound {

		override val span get() = declaration.span
		override val spelling
			get() = declaration.keyword.lexeme + parameterTypes.joinToString(
				"${Operators.COMMA} ", Operators.PAREN_OPEN.toString(), Operators.PAREN_CLOSE.toString(), transform = ValueType::spelling,
			)
	}

	val nominalType: CMemberType?
		get() = when (this) {
			is Nominal  -> type
			is Nullable -> payload.nominalType
			else        -> null
		}

	fun accepts(argument: ValueType, hasConstructor: (CClass, List<ValueType>) -> Boolean): Boolean = when (this) {
		is Number      -> argument.supportsNumericOperations
		is Nominal     -> type.accepts(argument)
		is Nullable    -> argument is CNullable && payload.accepts(argument.payload, hasConstructor)
		is Array       -> matches(argument)
		is Constructor -> when (argument) {
			is CTypeParameter -> parameterTypes in argument.constructorSignatures
			is CClass         -> hasConstructor(argument, parameterTypes)
			else              -> false
		}
	}
}

internal class GenericBoundCheck(
	private val parameter: TypeParameterSymbol,
	private val argument: ValueType,
	private val bounds: List<GenericBound>,
	val use: TokenSpan?,
) {

	fun failure(hasConstructor: (CClass, List<ValueType>) -> Boolean, interfacesOf: (CMemberType) -> Sequence<CInterface>): GenericBoundFailure? {
		if (bounds.size > 1) {
			val written = if (use == null) mutableMapOf<CMemberType, TokenSpan>() else null
			val constructorSignatures = if (use == null) mutableMapOf<List<ValueType>, TokenSpan>() else null
			val interfaces = mutableMapOf<CInterface, MutableMap<CInterface, TokenSpan>>()
			for (bound in bounds) {
				if (bound is GenericBound.Constructor) {
					constructorSignatures?.putIfAbsent(bound.parameterTypes, bound.span)?.let {
						throw GenericErrors.duplicateBound(bound.spelling, bound.span, it)
					}
				}
				val type = bound.nominalType ?: continue
				written?.putIfAbsent(type, bound.span)?.let { throw GenericErrors.duplicateBound(bound.spelling, bound.span, it) }
				for (required in interfacesOf(type)) {
					val applications = interfaces.getOrPut(required.definition) { linkedMapOf() }
					if (required in applications) continue
					for ((first, firstSpan) in applications) {
						if (knownDifferent(first, required)) {
							return GenericBoundFailure.InterfaceConflict(parameter, first, firstSpan, required, bound.span)
						}
					}
					applications[required] = bound.span
				}
			}
		}
		if (use == null) return null
		val failed = bounds.firstOrNull { !it.accepts(argument, hasConstructor) } ?: return null
		return GenericBoundFailure.Unsatisfied(parameter, argument, failed)
	}
}

internal sealed class GenericBoundFailure(private val parameter: TypeParameterSymbol) {

	fun diagnostic(supplied: TypeArgumentSpans) = diagnostic(supplied.arguments[parameter.index])
	abstract fun diagnostic(use: TokenSpan?): ClothDiagnostic

	class Unsatisfied(
		private val parameter: TypeParameterSymbol,
		private val argument: ValueType,
		private val bound: GenericBound,
	) : GenericBoundFailure(parameter) {

		override fun diagnostic(use: TokenSpan?) = GenericErrors.unsatisfiedBound(parameter.name, argument.spelling, bound.spelling, checkNotNull(use), bound.span)
	}

	class InaccessibleConstructor(
		private val parameter: TypeParameterSymbol,
		private val argument: CClass,
		private val bound: GenericBound.Constructor,
	) : GenericBoundFailure(parameter) {

		override fun diagnostic(use: TokenSpan?) = GenericErrors.inaccessibleConstructor(parameter.name, argument, bound.spelling, checkNotNull(use), bound.span)
	}

	class InterfaceConflict(
		private val parameter: TypeParameterSymbol,
		private val first: CInterface,
		private val firstSpan: TokenSpan,
		private val second: CInterface,
		private val secondSpan: TokenSpan,
	) : GenericBoundFailure(parameter) {

		override fun diagnostic(use: TokenSpan?) = InterfaceErrors.applications(parameter.name.lexeme, first.spelling, second.spelling, listOfNotNull(
			use?.let { SourceLabel(it, "type argument here") },
			SourceLabel(secondSpan, "requires '${second.spelling}' through this bound"),
			SourceLabel(firstSpan, "requires '${first.spelling}' through this bound"),
		))
	}
}

/** Compare fixed type structure and defer differences that depend on a type parameter. */
private fun knownDifferent(first: ValueType, second: ValueType): Boolean {
	val pending = ArrayDeque<Pair<ValueType, ValueType>>()
	val seen = mutableSetOf<Pair<ValueType, ValueType>>()
	pending.add(first to second)
	while (pending.isNotEmpty()) {
		val pair = pending.removeLast()
		val (left, right) = pair
		if (left === right || !seen.add(pair)) continue
		when {
			left is CTypeParameter || right is CTypeParameter -> Unit
			left is CNullable && right is CNullable           -> pending.add(left.payload to right.payload)
			left is CArray && right is CArray                 -> {
				if (left.size != right.size) return true
				pending.add(left.element to right.element)
			}

			left is CMemberType && right is CMemberType       -> {
				if (left.genericDefinition() !== right.genericDefinition()) return true
				pending.addAll(left.typeArguments.zip(right.typeArguments))
			}

			left != right                                     -> return true
		}
	}
	return false
}
