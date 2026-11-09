---
name: cloth-multiple-results
description: Design, explain, or extend Cloth ordered multiple function results, result-list returns and conditionals, positional destructuring, discard positions, forwarding, or reception plans.
---

# Cloth multiple results

[Generic Stage 3](../cloth-generics/references/stage-3.md) resolves ordered generic
result shapes and receiving contexts per owner, including forwarding, defaults
and deferred captures. Preserve explicit nullable reception and the ordinary
discard/shape rules. Native generic result transport remains Stage 4.

[Arbitrary integer Stage 4](../cloth-numerics/references/arbitrary-width-storage.md)
integrates wide and nullable-wide slots, ordered snapshots, forwarding and
conditional result carriers through 65536 bits. Small private carriers contain
snapshot pointers, with each outgoing wide value copied into caller-owned storage.

Read [confirmed result semantics](references/semantics.md), then the
[implementation plan and completed stages](../cloth-language/references/multi-return-implementation.md).
The plan separates confirmed direction, implementation choices, and remaining
boundaries. All five stages are complete for implemented value types.

## Preserve the integrated contract

- Ordered result lists are not first-class tuples. Each received slot has its own
  type and ordinary binding behavior. Example: `fn<int, bool> pair() = 1, true`
  with `def number, valid = pair()`.
- `_` discards one receiving position without suppressing producer evaluation or
  that position's explicit type/shape constraints. Explicit positional type lists
  retain discarded positions: `def<int, bool> number, _ = pair()`.
- A scalar-result RHS can broadcast to several names; a multi-result RHS maps
  positionally. Preserve both paths, including assignment to existing bindings.
- Preserve ordered snapshots, side effects, forwarding, early termination and
  complete producer execution. Buffered results are independent and follow
  [buffer lifetimes](../cloth-buffer-lifetimes/SKILL.md).
- Keep result-shape typing separate from ordinary scalar expression typing and
  complete semantic reception plans before emission. Preserve the implemented
  runtime validation/write order; a discard cannot bypass an array shape guard.
- [Growable arrays Stage 1](../cloth-arrays/references/growable-arrays-stage-1.md)
  preserves explicit `T[]` in each received slot, separately from current-count
  facts. Whole replacement may change a growable outer count; explicit extents
  and independently retained inner witnesses still guard each destination after
  complete producer evaluation. Broadcast and positional copies stay independent.
  [Stage 3](../cloth-arrays/references/growable-arrays-stage-3.md) executes append
  and reserve after these receptions. Mutating one received array cannot change
  a sibling slot, the source binding or a previously returned/captured value.
  [Stage 4](../cloth-arrays/references/growable-arrays-stage-4.md) extends growth
  to stored fields; exported field arrays and containing value copies remain
  independent. Class-valued result slots still share identity explicitly.
  [Stage 5](../cloth-arrays/references/growable-arrays-stage-5.md) receives removed
  elements independently before releasing their original slot. Forwarding and
  ordered reception preserve those buffers and retained field witnesses before
  later operands edit or clear the producer's array.
  [Stage 6](../cloth-arrays/references/growable-arrays-stage-6.md) verifies grown
  rectangular arrays and removed rows through ordered results/destructuring.
  Each received slot preserves independent recursive storage and inner witnesses.
- Multi-result conditionals use the same continuing-flow rules as
  [control flow](../cloth-control-flow/SKILL.md); unannotated functions still mean void.
- Declaration resolution reports C3053 for grouped deferred-block
  initializers before reception typing.
  [Declaration check](../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationAnalyzer.kt) ·
  [Rejection fixtures](../../../Compiler/src/test/kotlin/cloth/MultiReturnParsingTest.kt)

## Source, evidence, and boundaries

Inspect [ResultShape](../../../Compiler/src/main/kotlin/cloth/semantic/ResultShape.kt),
[ReceptionPlan](../../../Compiler/src/main/kotlin/cloth/semantic/ReceptionPlan.kt),
[FunctionSemanticModel](../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionSemanticModel.kt),
[EmitResults](../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitResults.kt),
and [EmitFunctions](../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt).
Example: [MultiReturns.co](../../../examples/MultiReturns.co), including its
optional-parameter declaration, is now executable. Read [optional arguments](../cloth-optional-arguments/SKILL.md)
when changing named argument placement or default evaluation around result producers.

Relevant suites: `MultiReturnParsingTest`, `MultiReturnTypingTest`,
`DestructuringTest`, `MultiReturnEmissionTest`, `MultiReturnIntegrationTest`,
and `MultiReturnReviewTest`. Recorded verification covers Windows x64/x86 native
execution and verified IR across nine profiles. Keep those scopes distinct.

`DestructuringTest` checks positional reception and swapped metadata in full
function bodies. Its native fixture checks the received values and `PEC`
through the shared compile driver, and the same body emits a nonempty object.
[Program fixtures](../../../Compiler/src/test/kotlin/cloth/ProgramFixtures.kt) ·
[Reception test evidence](../cloth-language/references/development.md#production-path-reception-tests-2026-11-06)

See the [final review](../cloth-language/references/multi-return-implementation.md#final-review)
for discarded-array constraints and remaining implementation boundaries.
Numeric result selection (`call().1`) remains a
[deferred RFC](../../../todo/result-selection-rfc.md), not implemented syntax.
