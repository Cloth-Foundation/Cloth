# GEN-3 Stage 2 method type argument inference

Ordinary class and struct method calls can infer their complete method type
argument list from supplied values. The receiver's static type determines the
available overloads and the exact declaring owner.

```cloth
typedef<class> Box<T> (def<T> item) {
    pub mt<T, U> pair<U>(U value, opt<T> first ?: self.item) = first, value
}

fn main() {
    def box = Box<int>::new(7)
    def<int, string> number, label = box.pair(value="label")
    println(number)
    println(label)
    box::delete
}
```

The receiver supplies `T = int`, the named argument supplies `U = string`,
and the selected method's default supplies `first`. The call
`box.pair<string>("label")` selects the same specialization.
Implicit self calls and calls in a guarded continuation use the same inference
rules. A matching ordinary overload takes precedence. Inherited hiding applies
before generic candidates are inferred and checked against their bounds.
[Inference rules](semantics.md#type-argument-inference-design)

`box.pair(null)` requires an explicit `U` that can receive `null`. Result types
and omitted value defaults supply no inference evidence. A single candidate
with a missing or conflicting argument reports C3137, a group that fails
inference reports C3024, and equal substituted ranks report C3025.

## Compiler integration

- `CallResolution` applies ordinary visibility and overload resolution to the
  effective method group. `GenericCallInference` binds supplied values and
  matches their types against the value parameter patterns. Method-owned
  slots use exact identities so an enclosing type parameter with the same
  ordinal stays fixed.
  [Call resolution](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/CallResolution.kt) ·
  [Inference matcher](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/GenericCallInference.kt)
- The worklist uses the original method, exact declaring owner, and ordered
  method arguments as the specialization key. A selected bound call keeps
  its declaration-site contract and substitutes the chosen arguments in the
  caller's catalog. Candidate trials prepare signatures and request body
  specialization only for a committed choice.
  [Type worklist](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt) ·
  [Bound operations](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/BoundOperations.kt)
- Nullable flow uses committed result types for inference-capable calls and
  invalidates receiver field presence conservatively. Each operand-context
  body uses the results selected at its own numeric width. Native calls use
  the ordinary class receiver and stored or temporary struct receiver plans.
  [Nullable flow](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/NullFlowAnalysis.kt) ·
  [Operator body typing](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/OperatorBodies.kt) ·
  [Method execution](../../cloth-functions/references/method-execution.md)

[Stage 3](constructor-inference-stage-3.md) covers class constructor calls.
[Stage 4](partial-arguments-and-defaults-stage-4.md) covers partial lists and
type-parameter defaults.
Method-owned parameters for virtual, interface, and state dispatch are
tracked under GEN-6.
[Open questions plan](../../../../todo/open-questions-plan.md#generics-interfaces-and-nominal-types)

## Verification on 2026-11-11

On Java 25, 154 `:Compiler:genericSyntaxTest` tests, 113 import checks,
and 489 focused `:Compiler:test` tests passed. Coverage includes fixed owner
slots, inherited hiding, class-bound selection, contextual literals, nullable
flow, and width-specific result proofs. The native test runs on Windows x64
and x86 and checks inherited defaults, implicit calls, ordered results, struct
mutation, and guarded argument evaluation.
[Semantic tests](../../../../Compiler/src/test/kotlin/cloth/GenericMethodInferenceTypingTest.kt) ·
[Native test](../../../../Compiler/src/test/kotlin/cloth/GenericMethodEmissionTest.kt)

An earlier full `:Compiler:test` run completed 2,158 tests with five failures.
The two File I/O failures could not resolve `cloth.io.File`. The three
Bootstrap publication failures could not resolve `cloth.io.Path`.
[File I/O tests](../../../../Compiler/src/test/kotlin/cloth/FileIOTest.kt) ·
[Publication tests](../../../../Compiler/src/test/kotlin/cloth/ImportPublicationTest.kt)
