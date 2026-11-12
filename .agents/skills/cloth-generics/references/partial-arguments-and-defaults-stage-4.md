# GEN-3 Stage 4 partial type arguments and defaults

Use `_` in a direct function, ordinary method, or class constructor type
argument slot to infer that slot from supplied values. A declaration can
also give a type parameter a default with `=`, following its optional bound.

```cloth
typedef<class> Box<T = int, U = T> (pub def<T> item, pub def<U> label)
fn<T> echo<T: Number = int<8>>(T value) = value

fn main() {
    def first = Box<int, _>::new(7, "label")
    def<Box<>> second = Box<>::new(2, 3)
    println(first.label)
    println(second.label)
    println(echo(300))
    println(echo<>(12))
    first::delete
    second::delete
}
```

The first constructor selects `Box<int, string>` and the second selects
`Box<int, int>`. `echo(300)` infers `int` and `echo<>(12)` selects `int<8>`.

An omitted list or a `_` slot tries ordinary inference first. A declared
default fills the slot when no evidence remains. In a written list, each
unwritten trailing slot needs a declared default. `<>` selects all defaults.
Type annotations retain their angle lists and may use trailing defaults,
as in `Box<string>`. Type annotations and nested type arguments require
explicit types for their written slots.

Defaults resolve in their defining scope. They can refer to earlier
parameters and an ordinary method's enclosing type parameters. The compiler
checks default types, bounds, and public signature visibility at declaration
time. The inferred or defaulted type arguments use ordinary bound checks and
the resulting value signatures use ordinary overload ranking.
[Confirmed inference rules](semantics.md#type-argument-inference-design)

## Compiler integration

- `TypeParser` retains placeholder and default spans. `GenericAnalyzer` binds
  defaults in their declaring type scope and recognizes placeholders at direct
  call sites. A raw nominal annotation still reports C3110. A written list
  missing a required trailing argument reports C3109.
  [Type parser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/TypeParser.kt) ·
  [Generic names](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericNames.kt)
- `TypeArgumentDefaults` completes ordered type tuples in the nominal resolver
  and signature normalizer. A default can introduce a nominal application.
  The worklist completes its ancestry and conformance before using it in
  candidate bound checks.
  [Type defaults](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/TypeArgumentDefaults.kt) ·
  [Type worklist](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt)
- `GenericCallInference` fixes written types, infers the open slots, then
  completes the tuple with declared defaults. Completed calls share the
  specialization keys of fully explicit calls. Calls through a class bound
  keep their declaration-site choice and completed tuple. Nullable flow uses
  the committed call's result.
  [Call inference](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/GenericCallInference.kt) ·
  [Bound operations](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/BoundOperations.kt)

An invalid placeholder or a cyclic, self-referential, or forward-referencing
default reports C3111. A type that fails its bound reports C3135. An open
slot with neither evidence nor a default reports C3137 for a single
candidate. [Open questions plan](../../../../todo/open-questions-plan.md#generics-interfaces-and-nominal-types)

## Verification on 2026-11-11

On Java 25, 175 `:Compiler:genericSyntaxTest` tests, 113 import checks, and
553 focused `:Compiler:test` tests passed. Coverage includes explicit and
inferred slots, literal precedence, declared and dependent defaults,
declaration-time bounds and visibility, imported defaults, signature
normalization, and class-bound method specialization. The native fixture
executes partial and defaulted functions, methods, and constructors on
Windows x64 and x86.
[Semantic tests](../../../../Compiler/src/test/kotlin/cloth/GenericTypeArgumentCompletionTest.kt) ·
[Native tests](../../../../Compiler/src/test/kotlin/cloth/GenericInferenceEmissionTest.kt)
