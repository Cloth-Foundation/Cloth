package cloth.semantic.declarations

import cloth.ast.SourceUnit
import cloth.builtin.BuiltinFunctions
import cloth.data.CClass
import cloth.data.CEnum
import cloth.data.CInterface
import cloth.data.ClassField
import cloth.data.EnumField
import cloth.data.sameDeclaration
import cloth.diagnostics.GenericErrors
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.declaration.*
import cloth.parser.expression.*
import cloth.parser.statement.*
import cloth.parser.type.TypeReference
import cloth.semantic.OperatorRules
import cloth.semantic.forStepperExpression
import cloth.semantic.placeRoot
import cloth.semantic.storedRoot
import cloth.semantic.symbols.*
import cloth.semantic.generics.GenericAnalyzer
import cloth.semantic.generics.GenericBodyViews
import cloth.semantic.generics.GenericSemanticAnalysis
import cloth.semantic.typing.ArgumentBinder
import cloth.source.SourceGraph
import cloth.target.TargetLayout
import cloth.token.Keywords
import cloth.token.Token
import cloth.token.TokenSpan

/** Collects source callable signatures, then resolves names in each body. */
class DeclarationAnalyzer(private val target: TargetLayout) {

	fun analyze(unit: SourceUnit): DeclarationModel {
		return analyze(FileBindings.single(unit))
	}

	internal fun analyze(graph: SourceGraph): DeclarationModel = analyze(FileBindings.graph(graph))

	internal fun analyze(files: FileBindings): DeclarationModel {
		val genericNames = GenericAnalyzer(target).analyze(files)
		if (genericNames.requiresGenericCompilation || genericNames.interfaceSpan != null || files.topLevelStates.isNotEmpty()) {
			return GenericSemanticAnalysis(target).declarations(files, genericNames)
		}
		return prepared(files)
	}

	internal fun prepared(files: FileBindings, views: GenericBodyViews? = null): DeclarationModel {
		val types = if (views == null) NamedTypes(files, target) else null
		val structs = views?.types?.nominalTypes?.filterIsInstance<cloth.data.CStruct>() ?: types!!.resolveStructs()
		types?.resolveClassFields()
		types?.resolveEnumFields()
		val classTypes = views?.types?.nominalTypes?.filterIsInstance<CClass>() ?: types!!.classes
		val enumTypes = views?.types?.nominalTypes?.filterIsInstance<CEnum>() ?: types!!.enums
		val nominal: (TypeReference) -> cloth.data.CMemberType? = { views?.resolvedTypes?.get(it) as? cloth.data.CMemberType ?: files.nominal(it.name) }
		val classes = classTypes.associateWith { owner -> classDefinition(owner, nominal, views) }
		val ordinaryFunctions = files.files.flatMap { it.functions }.filter { it.typeParameterCount == 0 }
		if (views != null) ordinaryFunctions.forEach { it.signatureResolver = views::signatureType }
		val functions = ordinaryFunctions + views?.functions?.values.orEmpty()
		for (file in files.files) {
			val signatures = linkedMapOf<FunctionSignature, FunctionSymbol>()
			for (function in file.functions) {
				if (function.typeParameterCount != 0) continue
				signatures.putIfAbsent(function.signature, function)?.let { throw SemanticErrors.duplicateFunction(function.signature.spelling, it.name, function.name) }
			}
		}
		val enumNames = enumTypes.associateWith(EnumModel::nameReader)
		val enumMembers = enumTypes.map { owner ->
			owner to (owner.declaration.operators + owner.fields.map { it.reader } + enumNames.getValue(owner))
		}
		val methods = (structs.map { it to ((views?.declarationOf(it) as? StructDeclaration) ?: it.declaration).methods } + classTypes.map { it to ((views?.declarationOf(it) as? ClassDeclaration) ?: it.declaration).members.filterIsInstance<MethodDeclaration>() } + enumMembers).flatMap { (owner, members) ->
			val methodSignatures = mutableMapOf<Triple<FunctionSignature, Boolean, Pair<SignatureType, Boolean>?>, MethodSymbol>()
			members.map { declaration ->
				OperatorRules.validate(declaration, owner)
				val method = MethodSymbol(declaration, owner, nominal = nominal)
				if (views != null) method.signatureResolver = views::signatureType
				val selector = declaration.operator?.selector?.let { (views?.signatureType(it) ?: SignatureType.of(it, nominal(it))) to it.capturesWidth }
				val key = Triple(method.signature, declaration.operator?.rightReceiver == true, selector)
				methodSignatures.putIfAbsent(key, method)?.let {
					throw SemanticErrors.duplicateMethod(owner.name, method.signature.spelling, it.name, method.name)
				}
				method
			}
		}
		val model = DeclarationModel(files, functions, target, structs, methods, classes, files.objectRoot, enumTypes, enumNames, views)
		for (callable in model.callableContracts) {
			val scope = LexicalScope(model.fileScopeOf(callable.name))
			if (callable is FunctionSymbol) model.functionSymbols[callable.declaration] = callable
			if (callable is MethodSymbol) model.methodSymbols[callable.declaration] = callable
			callable.receiver?.let(scope::declare)
			model.states.payloadOf(callable.declaration)?.let(scope::declare)
			model.callableScopes[callable.declaration] = scope
			for (parameter in callable.parameters) {
				scope.declare(parameter)
				model.parameterSymbols[parameter.declaration] = parameter
			}
		}
		for (callable in model.callableContracts) {
			val receiver = callable.receiver
			val resolver = BodyResolver(model, receiver, classOwner = model.classOwners[callable.declaration])
			val scope = model.scopeOf(callable.declaration)
			val defaultScope = LexicalScope(model.fileScopeOf(callable.name))
			val constructor = callable is ConstructorSymbol
			if (!constructor) receiver?.let(defaultScope::declare)
			for ((index, parameter) in callable.parameters.withIndex()) {
				parameter.declaration.defaultValue?.let { fallback ->
					BodyResolver(model, receiver.takeUnless { constructor }, defaultContext = true, unavailable = callable.parameters.drop(index).map { it.name.lexeme }.toSet(), preInitialization = constructor, classOwner = model.classOwners[callable.declaration]).expression(fallback, defaultScope)
				}
				defaultScope.declare(parameter)
			}
			when (val declaration = callable.declaration) {
				is PrimaryConstructorDeclaration -> {
					val definition = model.classDefinitions.getValue(model.classOwners.getValue(declaration))
					definition.baseCall?.let { base ->
						val resolver = BodyResolver(model, null, preInitialization = true, classOwner = definition.owner)
						ArgumentBinder.validate(base.suppliedArguments)
						base.arguments.forEach { resolver.expression(it, scope) }
					}
					for ((field, expression) in definition.fieldInitializers) {
						BodyResolver(model, receiver, initializingField = field).expression(expression, scope)
					}
				}

				is ConstructorDeclaration        -> {
					val delegation = BodyResolver(model, null, preInitialization = true, classOwner = model.classOwners[declaration])
					ArgumentBinder.validate(declaration.delegation.suppliedArguments)
					declaration.delegation.arguments.forEach { delegation.expression(it, defaultScope) }
				}

				is StateInitialPayloadDeclaration -> {
					BodyResolver(model, receiver, initializingStatePayload = true).expression(declaration.expression, defaultScope)
				}

				else                             -> Unit
			}
			when (val body = callable.declaration.executableBody) {
				is BlockBody      -> {
					model.blockScopes[body] = scope
					resolver.statements(body.statements, scope)
				}

				is ExpressionBody -> resolver.expression(body.expression, scope)
				null -> Unit
			}
		}
		return model
	}

	private fun classDefinition(owner: CClass, nominal: (TypeReference) -> cloth.data.CMemberType?, views: GenericBodyViews?): ClassDefinition {
		val declaration = (views?.declarationOf(owner) as? ClassDeclaration) ?: owner.declaration
		val primary = ConstructorSymbol(PrimaryConstructorDeclaration(declaration), owner, nominal).also { if (views != null) it.signatureResolver = views::signatureType }
		val signatures = mutableMapOf(primary.signature to primary)
		val secondary = declaration.members.filterIsInstance<ConstructorDeclaration>().map { declaration ->
			ConstructorSymbol(declaration, owner, nominal).also { constructor ->
				if (views != null) constructor.signatureResolver = views::signatureType
				signatures.putIfAbsent(constructor.signature, constructor)?.let {
					throw SemanticErrors.classMember("Constructor '${constructor.signature.spelling}' is already declared", constructor.name.span, it.name)
				}
			}
		}
		val destructors = declaration.members.filterIsInstance<DestructorDeclaration>()
		if (destructors.size > 1) throw SemanticErrors.classMember("A class can declare only one destructor", destructors[1].name.span, destructors[0].name)
		val initializers = declaration.members.filterIsInstance<ClassFieldDeclaration>().map { member ->
			ClassFieldInitializer(checkNotNull(owner.field(member.name.lexeme)), member.initializer)
		}
		val baseCall = declaration.parents.firstOrNull { nominal(it.type) === owner.base }?.let { parent ->
			ConstructorDelegation(parent.type.name, parent.constructorArguments.orEmpty(), parent.span, parent.type)
		}
		return ClassDefinition(owner, primary, secondary, destructors.singleOrNull()?.let { DestructorSymbol(it, owner) }, initializers, baseCall)
	}

	private class BodyResolver(
		private val model: DeclarationModel,
		private val receiver: ReceiverSymbol?,
		private val defaultContext: Boolean = false,
		private val unavailable: Set<String> = emptySet(),
		private val allowNestedReturn: Boolean = false,
		private val preInitialization: Boolean = false,
		private val initializingField: ClassField? = null,
		private val initializingStatePayload: Boolean = false,
		private val classOwner: CClass? = receiver?.owner as? CClass,
	) {

		private val loops = mutableListOf<LoopTarget>()

		private fun lexical(name: Token, scope: LexicalScope): Symbol? = scope.lookup(name.lexeme) ?: BuiltinFunctions[name.lexeme]?.let { BuiltinGroupSymbol(it, name) }

		private fun lookup(name: Token, scope: LexicalScope, member: () -> Symbol?): Symbol {
			val symbol = lexical(name, scope)
			if (name.lexeme in unavailable && symbol !is LocalSymbol && symbol !is GroupLocalSymbol && symbol !is LoopBindingSymbol) throw SemanticErrors.parameterDefault("A default cannot read its own or a later parameter '${name.lexeme}'", name.span)
			return symbol ?: member() ?: throw if (name.lexeme == Keywords.SELF.toString()) SemanticErrors.receiverOutsideMethod(name) else SemanticErrors.unknownName(name)
		}

		private fun receiverField(self: ReceiverSymbol?, name: Token): ReceiverFieldSymbol? {
			val field = self?.field(name.lexeme) ?: return null
			val classField = field.field as? ClassField
			if (classField != null && !classField.owner.sameDeclaration(classOwner) && classField.visibility == null) throw SemanticErrors.privateClassMember(name, classField.name)
			if (initializingField != null && classField?.owner == initializingField.owner && field.field.index >= initializingField.index) {
				throw SemanticErrors.partialClassInstance("Field '${name.lexeme}' has not been initialized", name.span)
			}
			return field
		}

		private fun value(name: Token, scope: LexicalScope) = lookup(name, scope) { receiverField(receiver, name) }

		private fun callGroup(call: CallExpression, group: CallableGroupSymbol): CallableGroupSymbol {
			val arguments = call.typeArguments
			if (group !is FunctionGroupSymbol) {
				if (arguments != null) throw GenericErrors.callArity(call.name, arguments.span, arguments.arguments.size, listOf(0), group.name)
				return group
			}
			val count = arguments?.arguments?.size ?: 0
			val matching = group.overloads.filter { it.typeParameterCount == count }
			if (matching.isEmpty()) {
				val expected = group.overloads.map { it.typeParameterCount }.distinct().sorted()
				throw GenericErrors.callArity(call.name, arguments?.span ?: call.name.span, count, expected, group.name)
			}
			if (arguments != null) {
				val types = arguments.arguments.map(model.typeResolver()::resolve)
				val function = checkNotNull(model.genericViews).specialize(matching.single(), types)
				return FunctionGroupSymbol(listOf(function))
			}
			return if (matching.size == group.overloads.size) group else FunctionGroupSymbol(matching)
		}

		private fun checkReceiverWrite(binding: Symbol?, span: TokenSpan) {
			if (initializingField != null && (binding is ReceiverSymbol || binding is ReceiverFieldSymbol)) {
				throw SemanticErrors.partialClassInstance("Fields can only be read during field initialization", span)
			}
			if (defaultContext && (binding is ReceiverSymbol || binding is ReceiverFieldSymbol)) {
				throw SemanticErrors.parameterDefault("'${Keywords.SELF}' is read-only in defaults", span)
			}
		}

		private fun checkSelfCall(name: Token) {
			if (initializingField != null) throw SemanticErrors.partialClassInstance("Methods on '${Keywords.SELF}' require a fully initialized instance", name.span)
			if (defaultContext) throw SemanticErrors.parameterDefault("A default cannot call a user-defined method on '${Keywords.SELF}'", name.span)
		}

		private fun explicitReceiver(expression: Expression) = (expression as? NameExpression)?.let { model.names[it] as? ReceiverSymbol }

		// A cast can preserve the receiver's identity without creating a stored place.
		private fun readOnlyDefaultRoot(operand: Expression): BindingSymbol? {
			if (!defaultContext) return null
			return when (operand) {
				is CastExpression        -> readOnlyDefaultRoot(operand.operand)
				is GuardedAccessExpression -> readOnlyDefaultRoot(operand.continuation)
				is GuardedReceiver       -> readOnlyDefaultRoot(operand.source)
				is FieldAccessExpression -> readOnlyDefaultRoot(operand.receiver)
				is IndexExpression       -> readOnlyDefaultRoot(operand.array)
				else                     -> model.storedRoot(operand)?.takeIf { it is ParameterSymbol || it is ReceiverSymbol }
			}
		}

		private fun receiverValue(operand: Expression, scope: LexicalScope) {
			expression(operand, scope)
			if (readOnlyDefaultRoot(operand) != null) model.readOnlyValueReceivers.add(operand)
		}

		private fun assignmentTarget(name: Token, scope: LexicalScope): BindingSymbol {
			val symbol = value(name, scope)
			if (symbol is ReceiverFieldSymbol && symbol.field is EnumField) throw SemanticErrors.enumMember("Enum payload fields are read-only", name.span)
			if (defaultContext && symbol is ParameterSymbol) throw SemanticErrors.parameterDefault("Earlier parameters are read-only in defaults", name.span)
			checkReceiverWrite(symbol, name.span)
			if (symbol !is BindingSymbol) throw SemanticErrors.bindingRequired(name, symbol.name.takeUnless { symbol is BuiltinGroupSymbol })
			if (symbol is LocalSymbol && symbol.declaration.initializer is FunctionBlockExpression) throw SemanticErrors.blockAssignment(name, symbol.name)
			if (!symbol.mutable) {
				if (symbol is ParameterSymbol) throw SemanticErrors.immutableParameter(name, symbol.name)
				if (symbol is ReceiverSymbol) throw SemanticErrors.fixedReceiver(name, symbol.keyword)
				throw SemanticErrors.immutable(name, symbol.name)
			}
			return symbol
		}

		fun statements(statements: List<Statement>, scope: LexicalScope) {
			for (statement in statements) when (statement) {
				is FieldWriteStatement        -> {
					expression(statement.target, scope)
					val root = statement.target.placeRoot()?.let { model.names[it] as? BindingSymbol }
					if (model.classDefinitions.isEmpty() && model.enumTypes.isEmpty() && (root == null || root is LocalSymbol && root.declaration.initializer is FunctionBlockExpression)) throw SemanticErrors.fieldTarget(statement.target.span)
					val defaultRoot = readOnlyDefaultRoot(statement.target)
					if (defaultRoot is ParameterSymbol) throw SemanticErrors.parameterDefault("Earlier parameter fields are read-only in defaults", statement.target.span)
					checkReceiverWrite(defaultRoot ?: root, statement.target.span)
					if (root != null) model.fieldRoots[statement] = root
					statement.value?.let { expression(it, scope) }
				}

				is DoWhileStatement           -> {
					val bodyScope = LexicalScope(scope)
					model.loopBodyScopes[statement] = bodyScope
					loops.add(statement.loopTarget)
					try {
						statements(statement.body.statements, bodyScope)
					} finally {
						loops.removeAt(loops.lastIndex)
					}
					// The trailing test sees outer bindings and enclosing body targets only.
					expression(statement.condition, scope)
				}

				is WhileStatement             -> {
					expression(statement.condition, scope)
					val bodyScope = LexicalScope(scope)
					model.loopBodyScopes[statement] = bodyScope
					loops.add(statement.loopTarget)
					try {
						statements(statement.body.statements, bodyScope)
					} finally {
						loops.removeAt(loops.lastIndex)
					}
				}

				is LoopJumpStatement          -> {
					model.loopJumps[statement] = loops.lastOrNull() ?: throw SemanticErrors.loopJump(statement.keyword)
				}

				is ForStatement               -> {
					when (val source = statement.source) {
						is ForSource.Range    -> {
							expression(source.start, scope); expression(source.end, scope)
						}

						is ForSource.Elements -> expression(source.expression, scope)
					}
					val loopScope = LexicalScope(scope)
					val symbol = LoopBindingSymbol(statement)
					loopScope.declare(symbol)
					model.loopSymbols[statement] = symbol
					model.loopScopes[statement] = loopScope
					val stepper = forStepperExpression(statement.stepper)
					model.loopSteppers[statement] = stepper
					stepper?.let { expression(it, loopScope) }
					val bodyScope = LexicalScope(loopScope)
					model.loopBodyScopes[statement] = bodyScope
					loops.add(statement.loopTarget)
					try {
						statements(statement.body.statements, bodyScope)
					} finally {
						loops.removeAt(loops.lastIndex)
					}
				}

				is BindingGroupDeclaration    -> {
					if (statement.initializer is FunctionBlockExpression) throw SemanticErrors.blockReception(statement.span)
					val seen = mutableMapOf<String, Token>()
					for (target in statement.targets) if (target is ReceivingTarget.Named) {
						scope.checkAvailable(target.token)
						seen.putIfAbsent(target.token.lexeme, target.token)?.let { throw SemanticErrors.duplicate(target.token, it) }
					}
					// All producer positions resolve before any receiving name is introduced.
					expression(statement.initializer, scope)
					model.groupSymbols[statement] = statement.targets.mapIndexed { index, target ->
						if (target is ReceivingTarget.Discard) null else GroupLocalSymbol(statement, index).also { scope.declare(it) }
					}
				}

				is BindingDeclaration         -> {
					scope.checkAvailable(statement.name)
					// An initializer can read an outer binding before its local shadow exists.
					expression(statement.initializer, scope)
					val symbol = LocalSymbol(statement)
					scope.declare(symbol)
					model.localSymbols[statement] = symbol
				}

				is AssignmentStatement        -> {
					model.assignments[statement] = assignmentTarget(statement.name, scope)
					expression(statement.value, scope)
				}

				is MultiAssignmentStatement   -> {
					model.multiAssignments[statement] = statement.targets.map { if (it is ReceivingTarget.Discard) null else assignmentTarget(it.token, scope) }
					expression(statement.value, scope)
				}

				is ElementAssignmentStatement -> {
					expression(statement.target, scope)
					var root: Expression = statement.target.array
					while (root is IndexExpression) root = root.array
					val binding = (root as? NameExpression)?.let(model::symbolOf)
					val defaultRoot = readOnlyDefaultRoot(statement.target)
					if (defaultRoot is ParameterSymbol) throw SemanticErrors.parameterDefault("Earlier parameter arrays are read-only in defaults", statement.target.span)
					checkReceiverWrite(defaultRoot ?: binding, statement.target.span)
					expression(statement.value, scope)
				}

				is UpdateStatement            -> model.updates[statement] = assignmentTarget(statement.name, scope)
				is ExpressionStatement        -> expression(statement.expression, scope)
				is ReturnStatement            -> {
					if (defaultContext && !allowNestedReturn) throw SemanticErrors.parameterDefault("A return cannot escape from a parameter default", statement.span)
					if (!allowNestedReturn && (preInitialization || initializingField != null || initializingStatePayload)) {
						throw SemanticErrors.partialClassInstance("A return cannot escape from class initialization", statement.span)
					}
					statement.value?.let { expression(it, scope) }
				}

				else                          -> throw InternalErrors.invariant("Unsupported statement during name resolution: $statement")
			}
		}

		fun expression(expression: Expression, scope: LexicalScope) {
			when (expression) {
				is StructLiteral                                                                                                     -> expression.fields.forEach { expression(it, scope) }
				is FieldAccessExpression                                                                                             -> {
					val target = expression.receiver
					if (target is NameExpression) {
						val owner = model.namedType(target.name) as? CEnum
						if (owner != null && lexical(target.name, scope) !is BindingSymbol && receiverField(receiver, target.name) == null) {
							val case = owner.cases[expression.name.lexeme] ?: throw SemanticErrors.enumMember("Enum '${owner.spelling}' has no case '${expression.name.lexeme}'", expression.name.span)
							model.enumCases[expression] = case
							return
						}
					}
					if (initializingField != null && target is NameExpression && target.name.lexeme == Keywords.SELF.toString()) model.names[target] = value(target.name, scope)
					else expression(target, scope)
					explicitReceiver(expression.receiver)?.let { self ->
						model.receiverFields[expression] = receiverField(self, expression.name) ?: throw if (self.owner is CClass) {
							SemanticErrors.classMember("Class '${self.owner.spelling}' has no field '${expression.name.lexeme}'", expression.name.span)
						} else if (self.owner is CEnum) {
							SemanticErrors.enumMember("Enum '${self.owner.spelling}' has no field '${expression.name.lexeme}'", expression.name.span)
						} else if (self.owner is CInterface) {
							SemanticErrors.unknownField(self.owner, expression.name)
						} else SemanticErrors.structField("Struct '${self.owner.spelling}' has no field '${expression.name.lexeme}'", expression.name.span)
					}
				}

				is ResultListExpression                                                                                              -> expression.values.forEach { expression(it, scope) }
				is TypeReceiverExpression                                                                                            -> Unit
				is ProviderAccessExpression                                                                                          -> {
					val receiver = expression.receiver
					val type = (receiver as? NameExpression)?.let { model.genericViews?.receivers?.get(it) } ?: if (receiver is NameExpression) {
						val symbol = lexical(receiver.name, scope)
						val member = if (symbol == null) receiverField(this.receiver, receiver.name) else null
						if (symbol !is BindingSymbol && member == null) model.namedType(receiver.name) else null
					} else null
					if (type != null && receiver is NameExpression) model.namedTypeReceivers[receiver] = type
					else receiverValue(receiver, scope)
				}

				is MemberCallExpression                                                                                              -> {
					expression(expression.receiver, scope)
					if (initializingField != null) model.fieldInitializerCalls.add(expression)
					if (readOnlyDefaultRoot(expression.receiver) != null) model.readOnlyDefaultReceivers.add(expression)
					explicitReceiver(expression.receiver)?.let { self ->
						val group = model.memberGroup(self.owner, expression.name.lexeme) ?: throw SemanticErrors.unknownMember(self.owner, expression.name)
						checkSelfCall(expression.name)
						ArgumentBinder.validate(expression.suppliedArguments)
						model.receiverCalls[expression] = group
					}
					expression.arguments.forEach { expression(it, scope) }
				}

				is ClassCreationExpression                                                                                           -> {
					val receiver = expression.receiver as? NameExpression
					if (receiver?.name?.lexeme == model.objectRoot.spelling) throw SemanticErrors.classLifecycle("'${model.objectRoot.spelling}' is an implicit base and has no source constructor", receiver.span)
					val type = when (val target = expression.receiver) {
						is NameExpression -> model.genericViews?.receivers?.get(target) ?: model.namedType(target.name)
						is TypeReceiverExpression -> model.typeResolver().resolve(target.annotation)
						else -> null
					}
					val definition = (type as? CClass)?.let { model.classDefinitions[it] } ?: throw SemanticErrors.classLifecycle("'${Keywords.NEW}' requires a class type receiver", expression.receiver.span)
					model.classCreations[expression] = definition
					ArgumentBinder.validate(expression.suppliedArguments)
					expression.arguments.forEach { expression(it, scope) }
				}

				is ClassDeletionExpression                                                                                           -> {
					expression(expression.receiver, scope)
					if (readOnlyDefaultRoot(expression.receiver) != null) {
						throw SemanticErrors.parameterDefault("A default cannot delete an object through a read-only receiver", expression.span)
					}
				}

				is StateCallExpression                                                                                               -> {
					ArgumentBinder.validate(expression.suppliedArguments)
					receiverValue(expression.receiver, scope)
					expression.arguments.forEach { expression(it, scope) }
				}

				is StateTransitionExpression                                                                                         -> {
					receiverValue(expression.receiver, scope)
					expression.payload?.let { expression(it, scope) }
				}

				is FunctionBlockExpression                                                                                           -> {
					if (expression.declaration.returnType == null) throw SemanticErrors.blockReturnType(expression.declaration.name)
					val body = expression.declaration.body as BlockBody
					val child = LexicalScope(scope)
					model.blockScopes[body] = child
					classOwner?.let { model.classOwners[expression.declaration] = it }
					(receiver?.owner as? CEnum)?.let { model.enumOwners[expression.declaration] = it }
					BodyResolver(model, receiver, defaultContext, unavailable, allowNestedReturn = true, preInitialization = preInitialization, initializingField = initializingField, initializingStatePayload = initializingStatePayload, classOwner = classOwner).statements(body.statements, child)
				}

				is ArrayLiteral                                                                                                      -> expression.elements.forEach { expression(it, scope) }
				is IndexExpression                                                                                                   -> {
					expression(expression.array, scope); expression(expression.index, scope)
				}

				is NameExpression                                                                                                    -> {
					if (expression.name.lexeme == Keywords.SELF.toString()) {
						if (preInitialization) throw SemanticErrors.partialClassInstance("'${Keywords.SELF}' is unavailable before instance initialization", expression.span)
						if (initializingField != null) throw SemanticErrors.partialClassInstance("'${Keywords.SELF}' can only select an initialized field here", expression.span)
					}
					model.names[expression] = value(expression.name, scope)
				}

				is CallExpression                                                                                                    -> {
					ArgumentBinder.validate(expression.suppliedArguments)
					val symbol = lookup(expression.name, scope) { receiver?.let { model.memberGroup(it.owner, expression.name.lexeme) } }
					if (symbol !is CallableGroupSymbol) throw SemanticErrors.notCallable(expression.name, symbol.name)
					if (symbol is MethodGroupSymbol || symbol is InterfaceMethodGroupSymbol) {
						checkSelfCall(expression.name)
						model.implicitReceivers[expression] = checkNotNull(receiver)
					}
					model.calls[expression] = callGroup(expression, symbol)
					expression.arguments.forEach { receiverValue(it, scope) }
				}

				is UnaryExpression                                                                                                   -> receiverValue(expression.operand, scope)
				is ElvisExpression                                                                                                   -> {
					expression(expression.left, scope)
					expression(expression.fallback, scope)
				}

				is GuardedAccessExpression -> {
					expression(expression.receiver, scope)
					expression(expression.continuation, scope)
				}
				is GuardedReceiver -> Unit

				is BinaryExpression                                                                                                  -> {
					receiverValue(expression.left, scope)
					receiverValue(expression.right, scope)
				}

				is CastExpression                                                                                                    -> {
					if (model.unboundStateName(expression.target) != null) receiverValue(expression.operand, scope)
					else expression(expression.operand, scope)
				}
				is TypeCheckExpression                                                                                               -> {
					val target = expression.target
					val name = target.name
					val bare = target.bareIdentifier != null
					val value = if (bare && model.genericViews?.resolvedTypes?.containsKey(target) != true && model.namedType(name) == null) lexical(name, scope) ?: receiverField(receiver, name) else null
					if (value is BindingSymbol) {
						val check = BinaryExpression(expression.operand, expression.operator, NameExpression(name))
						expression(check, scope)
						model.recordValueTarget(expression, check)
					} else {
						val operand = expression.operand
						// An allocated receiver can test its state even while its fields are being initialized.
						if (initializingField != null && model.unboundStateName(target) != null && operand is NameExpression && operand.name.lexeme == Keywords.SELF.toString()) {
							model.names[operand] = value(operand.name, scope)
						} else expression(operand, scope)
					}
				}

				is InterpolatedString                                                                                                -> expression.parts.forEach { receiverValue(it, scope) }
				is IfExpression                                                                                                      -> {
					for (branch in expression.branches) {
						expression(branch.condition, scope)
						val child = LexicalScope(scope)
						model.branchScopes[branch] = child
						statements(branch.statements, child)
					}
					expression.otherwise?.let {
						val child = LexicalScope(scope)
						model.elseScopes[expression] = child
						statements(it, child)
					}
				}

				is NullLiteral, is IntegerLiteral, is FloatingPointLiteral, is BooleanLiteral, is CharacterLiteral, is StringLiteral -> Unit
				else                                                                                                                 -> throw InternalErrors.invariant("Unsupported expression during name resolution: $expression")
			}
		}
	}
}
