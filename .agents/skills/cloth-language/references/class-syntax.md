# Basic class syntax

Stage 1 landed on 2026-11-05. The [class declaration and constructor rules](semantics.md#class-declarations-and-constructors)
and [object lifetime rules](semantics.md#object-lifetime-and-nullability) define
the language. The [basic classes plan](../../../../todo/basic-classes-plan.md)
stages typing, execution, alias safety and integration with existing values.

## Supported parser forms

```cloth
pub final typedef<class> Counter (def<int> value ?: 0) {
    Counter(float input) : (input -> int) {}
    pub mt<int> read() = self.value
    ~Counter { println(self.value) }
}

fn main() {
    def counter = Counter::new
    counter::delete
}
```

- [Parser](../../../../Compiler/src/main/kotlin/cloth/parser/Parser.kt) dispatches
  `typedef` by kind. [ClassParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ClassParser.kt)
  records header parameters, visibility, `final`, and body members in source
  order. Empty header parentheses and empty braces may each be omitted.
- Constructor and destructor names must match the class. Each secondary
  constructor has a `: (...)` clause with ordinary positional or named
  arguments, including an empty list. Class bodies use `mt` for methods.
  `fn` in a class body reports C2007. The legacy `ctor` spelling is rejected
  in a class body.
- [FunctionParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/FunctionParser.kt)
  shares ordinary parameter and method parsing with classes. A class header
  field with `?:` is represented as an optional parameter without an `opt`
  token. Omission and explicit null remain separate for later call binding.
- [ExpressionParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ExpressionParser.kt)
  produces class creation and deletion nodes for `Type::new`, `Type::new(...)`
  and `object::delete`. Compiler data providers and state calls keep their
  own syntax nodes.
- Stage 1 used C3086 to gate class semantic analysis. Stage 2 moved the gate
  to emission for valid class units. See the [class typing record](class-typing.md)
  for the current diagnostics and stage boundary.

## Verification

The Windows run passed 265 tests across 27 suites with zero failures, errors or
skips. [ClassParsingTest](../../../../Compiler/src/test/kotlin/cloth/ClassParsingTest.kt)
checks seven cases, including mixed headers, delegation, lifecycle AST nodes,
malformed syntax and one CLI diagnostic with no emitted output.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.ClassParsingTest --tests 'cloth.Struct*Test' --tests 'cloth.Function*Test' --tests 'cloth.OptionalArguments*Test' --tests cloth.CallResolutionTest --tests 'cloth.Nullability*Test' --tests cloth.TargetLayoutTest --offline --console=plain --no-daemon
```

This is parser and regression evidence. Class IR, objects and native execution
become verifiable when the corresponding lowering stage lands.
