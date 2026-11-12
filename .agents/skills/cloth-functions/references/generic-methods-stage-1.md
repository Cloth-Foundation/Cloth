# GEN-2 Stage 1 method syntax and names

Ordinary class and struct instance methods can declare type parameters after
the method name. Calls write the explicit arguments after the member name.
These declarations and calls pass parsing and type-name binding. Stage 3
integrates call resolution and execution. [Stage 3 integration](generic-methods-stage-3.md)

```cloth
typedef<class> Box<T> (def<T> item) {
    pub mt<T, U> pair<U>(U value) = self.item, value
}

typedef<struct> Slot {
    int stored;
    mt<N> echo<N: Number>(N value) = value
}

fn use(Box<int> box, Slot slot) {
    box.pair<string>("label")
    slot.echo<int>(10)
}
```

Each method type parameter is identified by its declaration and position.
Both sets of type parameters are in scope in bounds, signatures, defaults,
and bodies, including deferred function blocks. Each method adds its own scope.
A name already declared by the enclosing type or repeated within the method
produces C3108 with both declaration locations.

The type arguments of a member call resolve in the caller's lexical type
scope. In `box.pair<string>(...)`, the opening angle touches the name and
the closing angle touches the argument list. A spaced comparison such as
`box.value < high > (low)` keeps its ordinary parse. [Generic function calls](generic-functions.md)

## Compiler boundary

- `FunctionParser` reuses the type parameter list grammar for ordinary `mt` declarations,
  including one optional bound. Virtual, override, interface, and state
  method declarations with their own parameters still produce C2011.
  [FunctionParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/FunctionParser.kt)
- `ExpressionParser` reuses the explicit call lookahead for member calls, including calls under
  `?.`. `MemberCallExpression` retains the original type argument list and spans.
  [ExpressionParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ExpressionParser.kt)
- `GenericNames` binds type references under the combined method scope. It checks the
  names and nominal type arity in explicit call arguments and uses the
  existing contextual `Number` bound.
  [GenericNames](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericNames.kt) ·
  [GEN-1 name rules](../../cloth-generics/references/constraints-stage-1.md)
- `ClosedTypeAnalyzer` used C4038 after successful name binding at this
  milestone. Stage 3 retired it when method specialization became available.
  [ClosedTypeAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt) ·
  [Stage 2 integration](generic-overloads-stage-2.md) ·
  [GEN-2 plan](../../../../todo/open-questions-plan.md#generics-interfaces-and-nominal-types)

## Verification on 2026-11-11

On Windows with Java 25, `:Compiler:genericSyntaxTest` passed 118 tests without
native backend binaries. The tests cover method and call spans, comparison and
postfix parsing, enclosing and method name identities, imported call arguments,
and the diagnostic boundary. The focused `:Compiler:test` run passed 111 tests,
including generic functions, ordinary class and struct methods, interfaces,
virtual methods, guarded access, and nested types. These runs verify parsing
and name binding for generic methods. Native execution applies to the existing
features exercised by the regression tests.
[Parser tests](../../../../Compiler/src/test/kotlin/cloth/GenericParsingTest.kt) ·
[Name binding tests](../../../../Compiler/src/test/kotlin/cloth/GenericNamesTest.kt)
