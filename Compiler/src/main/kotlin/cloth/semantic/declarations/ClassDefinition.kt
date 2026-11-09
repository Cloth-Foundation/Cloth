package cloth.semantic.declarations

import cloth.data.CClass
import cloth.data.ClassField
import cloth.parser.expression.ConstructorDelegation
import cloth.parser.expression.Expression
import cloth.semantic.symbols.ConstructorSymbol
import cloth.semantic.symbols.DestructorSymbol

internal data class ClassDefinition(
	val owner: CClass,
	val primary: ConstructorSymbol,
	val secondary: List<ConstructorSymbol>,
	val destructor: DestructorSymbol?,
	val fieldInitializers: List<ClassFieldInitializer>,
	val baseCall: ConstructorDelegation?,
) {

	val constructors = listOf(primary) + secondary
}

internal data class ClassFieldInitializer(val field: ClassField, val expression: Expression)
