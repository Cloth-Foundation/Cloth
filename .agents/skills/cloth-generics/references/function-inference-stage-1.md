# GEN-3 Stage 1 function type argument inference

Top-level function calls can omit the entire type argument list. The compiler
infers it from supplied value arguments when ordinary overloads have no match.

```cloth
fn<T> choose<T>(T left, T right) = right
fn<T> first<T>(T[] values) = values[0]
fn<T[]> empty<T>() = []

fn main() {
    def<int<8>> small = 4
    println(choose(1, small))
    println(first([2, 3]))
    def<int[]> values = empty<int>()
}
```

The calls select `choose<int<8>>` and `first<int>`. The typed argument supplies
the literal context for `1`. `empty<int>()` provides its result-only parameter
explicitly. A supplied list still selects the matching generic arity.
[Confirmed inference rules](semantics.md#type-argument-inference-design)

## Compiler integration

- `CallResolution` attempts ordinary overloads before inference. It invokes
  inference after a binding or type mismatch. Ambiguity and located errors in
  written arguments stop compilation through the usual diagnostic path.
  [Call resolution](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/CallResolution.kt)
- `GenericCallInference` uses `ArgumentBinder` to pair supplied arguments
  with the canonical parameter patterns. It matches exact typed evidence,
  then uses typed array elements and ordinary literal defaults for unresolved
  parameters. Nullable wrappers, array layers, and matching nominal
  applications supply their nested types. Substituted signatures use ordinary
  range, shape, and widening checks.
  [Inference matcher](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/GenericCallInference.kt)
- `GenericBodyViews` submits inferred arguments and their source locations to
  the generic worklist. It filters bounds before ranking, and a committed
  choice requests the selected body and defaults. Explicit and inferred
  routes share the same specialization key.
  [Body views](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt) ·
  [Type worklist](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt)
- When nullable flow needs a call's result contract, only a committed choice
  supplies that fact. A fresh flow pass can then use the selected ordinary or
  inferred result type. Rejected trials retain independent expression facts.
  [Semantic facts](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionSemanticModel.kt) ·
  [Nullable flow](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/NullFlowAnalysis.kt)

C3137 locates a missing or conflicting type parameter for a single inference
candidate. A group whose candidates all fail inference reports C3024.
Substituted ties report C3025 and unsatisfied bounds retain C3135.
[Stage 2](method-inference-stage-2.md) extends the resolver to ordinary methods.
[Stage 3](constructor-inference-stage-3.md) covers class constructor calls.
Partial lists and type-parameter defaults remain follow-ups.
[GEN-3 plan](../../../../todo/open-questions-plan.md#generics-interfaces-and-nominal-types)

## Verification on 2026-11-11

On Java 25, 143 `:Compiler:genericSyntaxTest` tests, 113 import checks, and
436 focused `:Compiler:test` tests passed. The semantic tests cover literal
context and range checks, nullable and array patterns, ancestry and conformance,
overload precedence, bounds, argument placement, imports, and nullable flow.
The native test runs inferred calls on Windows x64 and x86 and checks
recursion, defaults, ordered results, and once-only argument evaluation.
[Semantic tests](../../../../Compiler/src/test/kotlin/cloth/GenericInferenceTypingTest.kt) ·
[Native test](../../../../Compiler/src/test/kotlin/cloth/GenericInferenceEmissionTest.kt)
