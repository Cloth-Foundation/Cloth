# GEN-3 Stage 3 constructor type argument inference

At `::new(...)`, a named generic class can infer its complete argument list
from supplied values.

```cloth
typedef<class> Box<T> (pub def<T> value)

fn main() {
    def box = Box::new(value=7)
    println(box.value)
    box::delete
}
```

The call infers `T = int` and selects the same class application and
constructor as `Box<int>::new(7)`. The compiler binds each primary or
secondary candidate's supplied arguments, infers the class's parameters,
and checks the class bounds. The substituted candidates use ordinary
overload ranking and report equal ranks as ambiguous. Constructor defaults
and delegation use the selected constructor's call contract.
[Confirmed inference rules](semantics.md#type-argument-inference-design)

Use an explicit receiver when the supplied values give too little evidence
and the declaration leaves a slot without a default.
For example, `List<int>::new` supplies the argument for a parameterless
constructor. An expected result type and an omitted default provide no
evidence. Type annotations and base class clauses use angle lists.

## Compiler integration

- `GenericAnalyzer` resolves a bare or qualified constructor name in the type
  namespace. The omitted-list path applies to named generic classes.
  `ClosedTypeAnalyzer` collects the primary and secondary parameter patterns
  from the canonical class application.
  [Name resolution](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericNames.kt) ·
  [Type worklist](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt)
- `GenericCallInference` uses the class's exact parameter slots and the shared
  evidence rules. The worklist checks inferred bounds and prepares substituted
  signatures. When a winning class application is new, the nominal interner
  registers it and the next analysis pass binds its real constructor.
  [Inference matcher](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/GenericCallInference.kt) ·
  [Body views](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt)
- Typed calls use the ordinary constructor ABI, named argument placement,
  omission defaults, and explicit lifecycle. The selected class type supplies
  receiver hints and its initial state to flow analysis.
  [Call resolution](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/CallResolution.kt) ·
  [Class execution](../../cloth-language/references/class-lifetime.md)

An uninferred or conflicting parameter in a single candidate reports C3137.
A group that fails inference reports C3024, tied viable contracts report
C3025, and an unsatisfied class bound reports C3135.
[Stage 4](partial-arguments-and-defaults-stage-4.md) covers partial lists and
type-parameter defaults. Constructor guarantees for `T::new(...)` belong
to GEN-5.
[Open questions plan](../../../../todo/open-questions-plan.md#generics-interfaces-and-nominal-types)

## Verification on 2026-11-11

On Java 25, 164 `:Compiler:genericSyntaxTest` tests, 113 import checks, and
540 focused `:Compiler:test` tests passed. The semantic suite covers
constructor ranking, bounds, named arguments, literal context, imported
and nested classes, initial state, and the generic dependency limit.
The native test executes on Windows x64 and x86 and checks defaults,
secondary delegation, argument order, and deletion. Inferred construction
also emits objects for all nine target profiles.
[Semantic tests](../../../../Compiler/src/test/kotlin/cloth/GenericConstructorInferenceTypingTest.kt) ·
[Native tests](../../../../Compiler/src/test/kotlin/cloth/GenericInferenceEmissionTest.kt) ·
[Target object tests](../../../../Compiler/src/test/kotlin/cloth/GenericStageBoundaryTest.kt)
