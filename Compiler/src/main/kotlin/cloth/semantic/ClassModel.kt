package cloth.semantic

import cloth.data.CClass
import cloth.data.ClassField
import cloth.parser.expression.Expression
import cloth.semantic.symbols.ConstructorSymbol
import cloth.semantic.symbols.DestructorSymbol
import cloth.semantic.symbols.ReceiverSymbol

internal data class ClassDefinition(
	val owner: CClass,
	val primary: ConstructorSymbol,
	val secondary: List<ConstructorSymbol>,
	val destructor: DestructorSymbol?,
	val fieldInitializers: List<ClassFieldInitializer>,
) {

	val constructors = listOf(primary) + secondary
}

internal data class ClassFieldInitializer(val field: ClassField, val expression: Expression)

/** A selected object identity, independent of any binding that happens to hold it. */
internal sealed interface ObjectReceiver {

	data class Explicit(val expression: Expression) : ObjectReceiver
	data class Self(val symbol: ReceiverSymbol) : ObjectReceiver
}

/** These projections start after resolving and validating the object identity. */
internal data class InstancePlace(val receiver: ObjectReceiver, val steps: List<PlaceStep>)

internal data class ClassDeletionPlan(val definition: ClassDefinition, val nullable: Boolean)
