package cloth.semantic.generics

import cloth.data.CMemberType
import cloth.data.CArray
import cloth.data.CNullable
import cloth.data.CTypeParameter
import cloth.data.ValueType
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.InternalErrors
import cloth.parser.declaration.CallableDeclaration
import cloth.parser.type.TypeReference
import cloth.semantic.declarations.FileBindings
import cloth.semantic.declarations.TypeResolver
import cloth.semantic.symbols.FunctionSignature
import cloth.semantic.symbols.FunctionSymbol
import cloth.semantic.symbols.SignatureType
import java.util.IdentityHashMap

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

/** Owner arguments retain their exact identities. Callable parameters compare by ordinal. */
internal class GenericSignatureKeys(private val names: GenericNameModel) {
	private val positions = mutableMapOf<Int, CTypeParameter>()
	private val typeDefaults = TypeArgumentDefaults(names)
	private val applications = mutableMapOf<NominalApplicationKey, CMemberType>()
	private val normalized = IdentityHashMap<ValueType, ValueType>()

	fun signature(declaration: CallableDeclaration, owner: Map<TypeParameterSymbol, ValueType> = emptyMap()): FunctionSignature {
		val environment = environment(declaration, owner)
		return FunctionSignature.of(declaration, resolved = { SignatureType.resolved(resolve(it, environment)) })
	}

	fun exposedTypes(declaration: CallableDeclaration, owner: Map<TypeParameterSymbol, ValueType>): List<ValueType> {
		val environment = environment(declaration, owner)
		val parameters = names.parametersOf(declaration).map { it.declaration }
		val bounds = parameters.flatMap { it.bounds }.flatMap { it.signatureTypes() }.filter { names.nameOf(it) !== NumberBoundName }
		val defaults = parameters.mapNotNull { it.defaultType }
		return (declaration.parameters.map { it.type } + declaration.returnTypes + bounds + defaults).map { resolve(it, environment) }
	}

	fun parameterSlots(declaration: CallableDeclaration): List<CTypeParameter> =
		names.parametersOf(declaration).map(::slot)

	private fun slot(parameter: TypeParameterSymbol): CTypeParameter =
		positions.getOrPut(parameter.index) { CTypeParameter(parameter.owner, parameter.declaration, parameter.index, numberBound = false) }

	private fun environment(declaration: CallableDeclaration, owner: Map<TypeParameterSymbol, ValueType>): Map<TypeParameterSymbol, ValueType> =
		owner + names.parametersOf(declaration).associateWith(::slot)

	private fun resolve(reference: TypeReference, environment: Map<TypeParameterSymbol, ValueType>): ValueType =
		TypeResolver(names.target, substitutedType = { type ->
			when (val identity = names.nameOf(type)) {
				is TypeParameterSymbol -> normalize(environment[identity] ?: throw InternalErrors.invariant("Missing signature parameter '${identity.name.lexeme}'"))
				is NominalTypeName -> if (type.application == null) identity.type
				else {
					val supplied = type.typeArguments.map { resolve(it, environment) }
					val arguments = typeDefaults.complete(identity.type.declaration, emptyMap(), supplied, ::resolve)
					application(identity.type, arguments)
				}
				else -> null
			}
		}).resolve(reference)

	private fun normalize(type: ValueType): ValueType = normalized.getOrPut(type) {
		when (type) {
			is CArray -> type.copy(element = normalize(type.element))
			is CNullable -> CNullable(normalize(type.payload))
			is CMemberType -> if (type.typeArguments.isEmpty()) type
			else application(type.genericDefinition(), type.typeArguments.map(::normalize))
			else -> type
		}
	}

	private fun application(definition: CMemberType, arguments: List<ValueType>): CMemberType =
		applications.getOrPut(NominalApplicationKey(definition, arguments)) { definition.application(arguments) }
}
