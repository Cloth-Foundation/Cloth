package cloth.semantic.generics

import cloth.data.ValueType
import cloth.diagnostics.GenericErrors
import cloth.parser.declaration.Declaration
import cloth.parser.type.TypeArgumentList
import cloth.parser.type.TypeParameterDeclaration
import cloth.parser.type.TypeReference
import cloth.parser.type.maximumTypeArgumentNesting
import cloth.semantic.symbols.FunctionGroupSymbol
import cloth.semantic.symbols.FunctionSymbol

internal fun List<TypeParameterDeclaration>.typeArgumentCounts(): IntRange =
	(indexOfLast { it.defaultType == null } + 1)..size

internal fun List<TypeParameterDeclaration>.acceptsTypeArgumentCount(count: Int): Boolean =
	count in typeArgumentCounts()

internal fun FunctionSymbol.acceptsTypeArguments(count: Int): Boolean =
	declaration.typeParameters?.parameters?.acceptsTypeArgumentCount(count) == true

internal fun FunctionGroupSymbol.completesTypeArguments(supplied: TypeArgumentList): Boolean =
	supplied.hasPlaceholders || overloads.any {
		it.typeParameterCount > supplied.arguments.size && it.acceptsTypeArguments(supplied.arguments.size)
	}

/** Complete arguments in declaration order using the types already selected to the left. */
internal class TypeArgumentDefaults(private val names: GenericNameModel) {
	private val resolving = mutableSetOf<TypeParameterSymbol>()

	fun complete(
		declaration: Declaration,
		enclosing: Map<TypeParameterSymbol, ValueType>,
		supplied: List<ValueType?>,
		resolve: (TypeReference, Map<TypeParameterSymbol, ValueType>) -> ValueType,
	): List<ValueType> {
		val environment = enclosing.toMutableMap()
		return buildList {
			for (parameter in names.parametersOf(declaration)) {
				val actual = supplied.getOrNull(parameter.index) ?: defaultOf(parameter, environment, resolve) ?: break
				add(actual)
				environment[parameter] = actual
			}
		}
	}

	private fun defaultOf(
		parameter: TypeParameterSymbol,
		environment: Map<TypeParameterSymbol, ValueType>,
		resolve: (TypeReference, Map<TypeParameterSymbol, ValueType>) -> ValueType,
	): ValueType? {
		val type = parameter.declaration.defaultType ?: return null
		if (resolving.size >= maximumTypeArgumentNesting) throw GenericErrors.nesting(type.span, maximumTypeArgumentNesting)
		if (!resolving.add(parameter)) throw GenericErrors.argument("Cyclic type argument default for '${parameter.name.lexeme}'", type.span)
		return try {
			resolve(type, environment)
		} finally {
			resolving.remove(parameter)
		}
	}
}
