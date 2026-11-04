package cloth.semantic

import cloth.ast.ASTStructure
import cloth.data.CArray
import cloth.data.ValueType
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.SemanticErrors
import cloth.parser.expression.Expression
import cloth.parser.expression.IfExpression
import cloth.parser.expression.NameExpression
import cloth.parser.statement.*
import cloth.target.TargetLayout
import cloth.token.Token

/** Legacy snippet checking; declaration bodies share its expression rules. */
class SemanticAnalyzer(private val target: TargetLayout = TargetLayout.native()) {

	private data class Binding(val type: ValueType, val mutable: Boolean, val declaration: Token)

	fun analyze(ast: ASTStructure): SemanticModel {
		val scopes = mutableListOf(mutableMapOf<String, Binding>())
		val model = SemanticModel(target)
		val loops = mutableListOf<LoopTarget>()
		val flow = ControlFlowModel()
		fun lookup(name: Token): Binding = scopes.asReversed().firstNotNullOfOrNull { it[name.lexeme] } ?: throw SemanticErrors.unknown(name)
		val typing = object : ExpressionTyping(model) {
			override fun completion(expression: Expression) = flow.of(expression)
			override fun completion(statements: List<Statement>) = flow.of(statements)
			override fun nameType(expression: NameExpression) = lookup(expression.name).type
			override fun withScope(action: () -> Unit) {
				scopes.add(mutableMapOf())
				try {
					action()
				} finally {
					scopes.removeAt(scopes.lastIndex)
				}
			}

			override fun statement(statement: Statement) {
				when (statement) {
					is DoWhileStatement           -> checkDoWhile(statement)
					is WhileStatement             -> checkWhile(statement)
					is LoopJumpStatement          -> {
						model.loopJumps[statement] = loops.lastOrNull() ?: throw SemanticErrors.loopJump(statement.keyword)
						model.recordResult(statement, ResultShape.Never)
					}

					is ForStatement               -> checkFor(statement, forStepperExpression(statement.stepper)) { type ->
						scopes.last()[statement.binding.name.lexeme] = Binding(type, true, statement.binding.name)
					}

					is BindingGroupDeclaration    -> {
						val seen = mutableMapOf<String, Token>()
						for (target in statement.targets) if (target is ReceivingTarget.Named) {
							scopes.last()[target.token.lexeme]?.let { throw SemanticErrors.duplicate(target.token, it.declaration) }
							seen.putIfAbsent(target.token.lexeme, target.token)?.let { throw SemanticErrors.duplicate(target.token, it) }
						}
						val plan = definitionPlan(statement)
						for (slot in plan.slots) if (slot.target is ReceivingTarget.Named) scopes.last()[slot.target.token.lexeme] = Binding(slot.type, statement.mutable, slot.target.token)
					}

					is BindingDeclaration         -> {
						scopes.last()[statement.name.lexeme]?.let { throw SemanticErrors.duplicate(statement.name, it.declaration) }
						val declaredType = statement.type?.let { resolveType(it) }
						val initializerType = typeOf(statement.initializer, declaredType)
						val bindingType = if (declaredType is CArray && initializerType is CArray && declaredType.accepts(initializerType)) declaredType.inferArraySizes(initializerType) else declaredType ?: initializerType
						checkType(bindingType, initializerType, statement.name, statement.initializer, statement.name)
						scopes.last()[statement.name.lexeme] = Binding(bindingType, statement.mutable, statement.name)
						model.statements[statement] = bindingType
					}

					is AssignmentStatement        -> {
						val binding = lookup(statement.name)
						if (!binding.mutable) throw SemanticErrors.immutable(statement.name, binding.declaration)
						checkType(binding.type, typeOf(statement.value, binding.type), statement.name, statement.value, binding.declaration)
						model.statements[statement] = binding.type
					}

					is MultiAssignmentStatement   -> {
						val targets = statement.targets.map { target ->
							if (target is ReceivingTarget.Discard) null else lookup(target.token).also { if (!it.mutable) throw SemanticErrors.immutable(target.token, it.declaration) }
						}
						assignmentPlan(statement, targets.map { it?.type }, targets.map { it?.declaration })
					}

					is UpdateStatement            -> {
						val binding = lookup(statement.name)
						if (!binding.mutable) throw SemanticErrors.immutable(statement.name, binding.declaration)
						if (!binding.type.numeric) throw SemanticErrors.unary(statement.operator, NameExpression(statement.name), binding.type, boolean = false)
						model.statements[statement] = binding.type
					}

					is ElementAssignmentStatement -> {
						val type = typeOf(statement.target)
						val actual = typeOf(statement.value, type)
						if (!type.accepts(actual)) throw SemanticErrors.array("Array element requires '${type.spelling}', got '${actual.spelling}'", statement.value.span)
						model.statements[statement] = type
					}

					is ExpressionStatement        -> model.statements[statement] = typeOf(statement.expression, requireValue = statement.expression !is IfExpression)
					else                          -> throw InternalErrors.invariant("Unsupported statement: $statement")
				}
			}

			override fun loopBody(loop: LoopStatement, body: List<Statement>) {
				loops.add(loop.loopTarget)
				try {
					super.loopBody(loop, body)
				} finally {
					loops.removeAt(loops.lastIndex)
				}
			}

			fun check(statements: List<Statement>) = statements.forEach { statement(it) }
			private fun checkType(expected: ValueType, actual: ValueType, name: Token, value: Expression, declaration: Token) {
				if (!expected.accepts(actual)) throw SemanticErrors.typeMismatch(expected, actual, name, value, declaration)
			}
		}
		typing.check(ast.statements)
		MetadataAnalysis(model).statements(ast.statements)
		return model
	}
}
