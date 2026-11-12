package cloth.semantic.generics

import cloth.data.*
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.CallableSignature
import cloth.parser.declaration.ConstructorGuaranteeDeclaration
import cloth.parser.declaration.Declaration
import cloth.parser.type.ConstructorBound
import cloth.parser.type.TypeReference
import cloth.semantic.ResultShape
import cloth.semantic.declarations.FileBindings
import cloth.semantic.declarations.TypeResolver
import cloth.semantic.symbols.FunctionSignature
import cloth.semantic.symbols.FunctionSymbol
import cloth.semantic.symbols.SignatureType
import java.util.*

/** Compare declared contracts after renaming each callable's parameters by position. */
internal fun checkGenericFunctionSignatures(files: FileBindings, names: GenericNameModel) {
	val keys = GenericSignatureKeys(names)
	for (file in files.files) {
		val groups = file.functions.filter { it.typeParameterCount != 0 }.groupBy { it.name.lexeme to it.typeParameterCount }
		for (group in groups.values.filter { it.size > 1 }) {
			val signatures = mutableMapOf<FunctionSignature, FunctionSymbol>()
			for (function in group) {
				val signature = keys.signature(function.declaration)
				signatures.putIfAbsent(signature, function)?.let { prior ->
					throw GenericErrors.duplicateFunction(function.name, prior.name, signature.spelling, function.typeParameterCount)
				}
			}
		}
	}
}

internal sealed interface GenericBoundSignature { data class Type(val shape: ValueType, val numeric: Boolean) : GenericBoundSignature
	data class Constructor(val parameters: List<ValueType>) : GenericBoundSignature
}

internal data class ConstructorBoundContract(
	val parameterTypes: List<ValueType>,
	val labels: List<String>,
	val defaultDeclaration: ConstructorGuaranteeDeclaration?,
)

/** Owner arguments retain their exact identities. Callable parameters compare by ordinal. */
internal class GenericSignatureKeys(private val names: GenericNameModel) {

	private val positions = mutableMapOf<Int, CTypeParameter>()
	private val typeDefaults = TypeArgumentDefaults(names)
	private val applications = mutableMapOf<NominalApplicationKey, CMemberType>()
	private val normalized = IdentityHashMap<ValueType, ValueType>()

	fun signature(declaration: CallableSignature, owner: Map<TypeParameterSymbol, ValueType> = emptyMap()): FunctionSignature {
		val environment = environment(declaration, owner)
		return FunctionSignature.of(declaration, resolved = { SignatureType.resolved(resolve(it, environment)) })
	}

	fun results(declaration: CallableSignature, owner: Map<TypeParameterSymbol, ValueType>): ResultShape {
		val environment = environment(declaration, owner)
		return ResultShape.of(declaration.returnTypes.map { annotation ->
			resolve(annotation, environment).also {
				if (declaration.returnTypes.size > 1 && it == ValueType.VOID) throw SemanticErrors.voidResult(annotation.span)
			}
		})
	}

	fun bounds(declaration: CallableSignature, owner: Map<TypeParameterSymbol, ValueType>): List<Set<GenericBoundSignature>> {
		val environment = environment(declaration, owner)
		return names.parametersOf(declaration).map { parameter ->
			parameter.declaration.bounds.mapTo(linkedSetOf()) { bound ->
				when (bound) {
					is TypeReference    -> {
						val numeric = names.nameOf(bound) === NumberBoundName
						val shape = if (numeric) TypeResolver(names.target).withSuffixes(bound, ValueType.INT) else resolve(bound, environment)
						GenericBoundSignature.Type(shape, numeric)
					}

					is ConstructorBound -> GenericBoundSignature.Constructor(bound.declaration.parameters.map { resolve(it.type, environment) })
				}
			}
		}
	}

	fun constructorContracts(declaration: CallableSignature, owner: Map<TypeParameterSymbol, ValueType>): List<Map<ConstructorBoundContract, Int>> {
		val environment = environment(declaration, owner)
		return names.parametersOf(declaration).map { parameter ->
			parameter.declaration.bounds.filterIsInstance<ConstructorBound>().groupingBy { bound ->
				val constructor = bound.declaration
				ConstructorBoundContract(
					constructor.parameters.map { resolve(it.type, environment) },
					constructor.parameters.map { it.name.lexeme },
					constructor.takeIf { it.parameters.any { parameter -> parameter.optional } },
				)
			}.eachCount()
		}
	}

	fun declaredTypeDefaults(declaration: CallableSignature, owner: Map<TypeParameterSymbol, ValueType>): List<ValueType?> {
		val environment = environment(declaration, owner)
		return names.parametersOf(declaration).map { it.declaration.defaultType?.let { type -> resolve(type, environment) } }
	}

	fun exposedTypes(declaration: CallableSignature, owner: Map<TypeParameterSymbol, ValueType>): List<ValueType> {
		val environment = environment(declaration, owner)
		val defaults = names.parametersOf(declaration).mapNotNull { it.declaration.defaultType }
		return (declaration.parameters.map { it.type } + declaration.returnTypes + boundAnnotations(declaration) + defaults).map { resolve(it, environment) }
	}

	fun exposedBounds(declaration: CallableSignature, owner: Map<TypeParameterSymbol, ValueType>): List<ValueType> {
		val environment = environment(declaration, owner)
		return boundAnnotations(declaration).map { resolve(it, environment) }
	}

	private fun boundAnnotations(declaration: CallableSignature): List<TypeReference> = names.parametersOf(declaration).flatMap { it.declaration.bounds }.flatMap { it.signatureTypes() }.filter { names.nameOf(it) !== NumberBoundName }

	/** Inference matches the written wrappers, even when a bound proves its slot nullable. */
	fun inferencePatterns(declaration: CallableSignature, parameterOwner: Declaration = declaration): GenericParameterPatterns {
		val parameters = names.parametersOf(parameterOwner)
		val slots = parameters.map(::slot)
		val environment = parameters.zip(slots).toMap()
		return GenericParameterPatterns(declaration.parameters.map { resolve(it.type, environment) }, slots)
	}

	fun parameterSlots(declaration: CallableSignature): List<CTypeParameter> = names.parametersOf(declaration).map(::slot)

	private fun slot(parameter: TypeParameterSymbol): CTypeParameter = positions.getOrPut(parameter.index) { CTypeParameter(parameter.owner, parameter.declaration, parameter.index, numberBound = false) }

	private fun environment(declaration: CallableSignature, owner: Map<TypeParameterSymbol, ValueType>): Map<TypeParameterSymbol, ValueType> = owner + names.parametersOf(declaration).associateWith(::slot)

	private fun resolve(reference: TypeReference, environment: Map<TypeParameterSymbol, ValueType>): ValueType = TypeResolver(names.target, substitutedType = { type ->
		when (val identity = names.nameOf(type)) {
			is TypeParameterSymbol -> normalize(environment[identity] ?: throw InternalErrors.invariant("Missing signature parameter '${identity.name.lexeme}'"))
			is NominalTypeName     -> if (type.application == null) identity.type
			else {
				val supplied = type.typeArguments.map { resolve(it, environment) }
				val arguments = typeDefaults.complete(identity.type.declaration, emptyMap(), supplied, ::resolve)
				application(identity.type, arguments)
			}

			else                   -> null
		}
	}).resolve(reference)

	private fun normalize(type: ValueType): ValueType = normalized.getOrPut(type) {
		when (type) {
			is CArray      -> type.copy(element = normalize(type.element))
			is CNullable   -> CNullable(normalize(type.payload))
			is CMemberType -> if (type.typeArguments.isEmpty()) type
			else application(type.genericDefinition(), type.typeArguments.map(::normalize))

			else           -> type
		}
	}

	private fun application(definition: CMemberType, arguments: List<ValueType>): CMemberType = applications.getOrPut(NominalApplicationKey(definition, arguments)) { definition.application(arguments) }
}
