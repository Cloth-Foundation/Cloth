package cloth.semantic.flow

import cloth.diagnostics.InternalErrors
import cloth.parser.expression.*
import cloth.semantic.declarations.DeclarationModel

/** Source order for eager traversal. Callers handle conditional evaluation separately. */
internal fun Expression.eagerOperands(declarations: DeclarationModel? = null): List<Expression> = when (this) {
	is CallSite                         -> when (this) {
		is MemberCallExpression                                                 -> listOf(receiver) + arguments
		is CallExpression, is ClassCreationExpression, is ConstructorDelegation -> arguments
	}

	is StateCallExpression              -> listOf(receiver) + arguments
	is BinaryExpression                 -> listOf(left, right)
	is UnaryExpression                  -> listOf(operand)
	is CastExpression                   -> listOf(operand)
	is TypeCheckExpression              -> declarations?.valueTargetOf(this)?.eagerOperands() ?: listOf(operand)
	is IndexExpression                  -> listOf(array, index)
	is FieldAccessExpression            -> listOf(receiver)
	is ProviderAccessExpression         -> listOf(receiver)
	is ClassDeletionExpression          -> listOf(receiver)
	is ResultListExpression             -> values
	is ArrayLiteral                     -> elements
	is StructLiteral                    -> fields
	is InterpolatedString               -> parts
	is BooleanLiteral, is CharacterLiteral, is FunctionBlockExpression, is NameExpression,
	is NullLiteral, is NumberLiteral, is StringLiteral, is TypeReceiverExpression,
	                                    -> emptyList()

	is IfExpression, is ElvisExpression -> throw InternalErrors.invariant("Conditional operands require their own traversal")
	else                                -> throw InternalErrors.invariant("Unsupported expression during operand traversal: $this")
}
