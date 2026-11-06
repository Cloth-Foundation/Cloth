package cloth.semantic.typing

import cloth.ast.SourceUnit
import cloth.data.*
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.*
import cloth.semantic.OperatorRules
import cloth.semantic.ResultShape
import cloth.semantic.declarations.DeclarationAnalyzer
import cloth.semantic.declarations.DeclarationModel
import cloth.semantic.declarations.TypeResolver
import cloth.semantic.flow.ClassLifetimeAnalysis
import cloth.semantic.flow.NullFlowAnalysis
import cloth.semantic.flow.checkCallableMetadata
import cloth.semantic.symbols.*
import cloth.target.TargetLayout
import cloth.token.Keywords
import cloth.token.Operators

/** Complete function semantics, including call resolution, returns and completion. */
class FunctionAnalyzer(private val target: TargetLayout) {

	fun analyze(unit: SourceUnit): FunctionSemanticModel = analyze(DeclarationAnalyzer(target).analyze(unit))

	fun analyze(declarations: DeclarationModel): FunctionSemanticModel {
		if (target != declarations.target) throw InternalErrors.invariant("Declaration and call analysis targets differ")
		val resolver = TypeResolver(declarations.target, namedType = declarations::namedType)
		val signatures = declarations.concreteCallables.associateWith { function ->
			val parameters = function.parameters.map { parameter ->
				resolver.resolve(parameter.declaredType).also {
					if (it == ValueType.VOID) throw SemanticErrors.voidType(parameter.declaration.type.span)
				}
			}
			val results = function.declaration.returnTypes.map { annotation ->
				resolver.resolve(annotation).also {
					if (function.declaration.returnTypes.size > 1 && it == ValueType.VOID) throw SemanticErrors.voidResult(annotation.span)
				}
			}
			val operator = (function.declaration as? MethodDeclaration)?.takeIf { it.operator != null }
			val comparison = operator?.let { Operators.fromCharacter(it.name.lexeme) }
			if (operator?.name?.lexeme == Keywords.IS.toString() && parameters.single() !is CClass) {
				throw SemanticErrors.operatorDeclaration("Value-target '${Keywords.IS}' requires a non-nullable class operand", function.parameters.single().name.span)
			}
			if (operator != null && comparison in OperatorRules.classComparisons) {
				val operand = parameters.single()
				val owner = (function as MethodSymbol).owner
				val requiredType = if (owner is CClass) declarations.objectRoot else owner
				val valid = if (comparison == Operators.IDENTITY) operand is CClass else operand == requiredType
				if (!valid) {
					val required = if (comparison == Operators.IDENTITY) "a non-nullable class" else "the '${requiredType.spelling}'"
					throw SemanticErrors.operatorDeclaration("Operator '${operator.name.lexeme}' requires $required operand type", function.parameters.single().name.span)
				}
			}
			val fixed = operator?.let(OperatorRules::fixedResult)
			if (operator != null && (ValueType.VOID in results || fixed != null && results.isNotEmpty() && results.single() != fixed)) {
				val message = fixed?.let { "This operator must return '${it.spelling}'" } ?: "An operator must produce a value"
				throw SemanticErrors.operatorDeclaration(message, operator.returnTypes.single().span)
			}
			FunctionType(parameters, when {
				function is ConstructorSymbol -> ResultShape.of(function.owner)
				fixed != null                 -> ResultShape.of(fixed)
				else                          -> ResultShape.of(results)
			})
		}
		val selectors = buildMap {
			for (method in declarations.methods) {
				val selector = method.declaration.operator?.selector ?: continue
				if (selector.capturesWidth) continue
				val type = resolver.resolve(selector)
				if (type == ValueType.VOID) throw SemanticErrors.voidType(selector.span)
				put(method, type)
			}
		}
		val model = FunctionSemanticModel(declarations, CallableContracts(signatures, selectors))
		model.initializeBindings()
		val presence = NullFlowAnalysis(model)
		for (function in declarations.callables) {
			presence.analyze(function.declaration)
			presence.analyzeExpressions(declarations.initializationExpressions(function.declaration))
		}
		for (function in declarations.concreteCallables) {
			val checker = BodyTyping(model, function.declaration, bodyResults(function, model))
			function.parameters.forEachIndexed { index, parameter ->
				parameter.declaration.defaultValue?.let { checker.checkInitializer(it, signatures.getValue(function).parameters[index], parameter.name) }
			}
		}
		for (function in declarations.concreteCallables) {
			val returnResults = bodyResults(function, model)
			val checker = BodyTyping(model, function.declaration, returnResults)
			when (val declaration = function.declaration) {
				is PrimaryConstructorDeclaration -> {
					val definition = declarations.classes.getValue(declaration.name.lexeme)
					declaration.owner.base?.let { clause ->
						declarations.classes[clause.name.lexeme]?.let { checker.bindConstructor(clause, it.constructors) }
					}
					for ((field, expression) in definition.fieldInitializers) {
						checker.checkInitializer(expression, field.type, field.name)
					}
				}

				is ConstructorDeclaration        -> {
					val owner = declarations.classOwners.getValue(declaration)
					val primary = declarations.classes.getValue(owner.spelling).primary
					checker.bindConstructor(declaration.delegation, listOf(primary))
				}

				else                             -> Unit
			}
			checker.checkBody()
		}
		for (function in declarations.concreteCallables) checkCallableMetadata(function, model)
		if (declarations.classes.isNotEmpty()) ClassLifetimeAnalysis(model).analyze()
		return model
	}

	private fun bodyResults(function: CallableSymbol, model: FunctionSemanticModel) = if (function is ConstructorSymbol) ResultShape.Empty else model.typeOf(function).results
}
