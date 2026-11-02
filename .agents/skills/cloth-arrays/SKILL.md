---
name: cloth-arrays
description: Design, explain, or extend Cloth arrays, including element inference, rectangular dimensions, fixed binding shapes, indexed reads and writes, and independent array values.
---

# Cloth arrays

Read [confirmed array semantics](references/semantics.md) for the language contract.
Use [buffer lifetimes](../cloth-buffer-lifetimes/SKILL.md) when changing copying,
argument snapshots, returned storage, or cleanup.

## Development rules

- Infer element types across all typed elements before contextual integer literals;
  preserve exact element identity. Do not add widest-type inference or implicit
  conversions between typed values. Floating literal context remains dependent
  on the existing binding/call rules.
- Preserve rectangular shape recursively. A stored binding fixes every initial
  dimension; an unsized signature permits differing shapes across invocations.
  Function-block results may vary between reads until stored in a binding.
- Keep known shape failures at compile time and unknown shape checks at runtime.
  A zero outer count does not erase inner-axis shape metadata.
- Reads and writes evaluate operands once in source order. Resolve assignment
  targets and indices before the RHS; validate bounds before element access.
- Preserve recursive value independence for binding copies, parameters, extracted
  rows, and replacements. `:=` and `final` forbid rebinding while permitting
  element writes. Object field semantics are a separate design concern.

## Implemented boundaries

Sized and unsized arrays, nested literals, indexing, plain indexed `=`, and
function parameters/results are implemented. Current policies require element
context for an empty literal, use C dimension order (`int[2][3]`), accept supported
integer indices, and terminate on invalid executed bounds. Indexed compound
updates, iteration, equality, and array value formatting are not implemented.
These implementation policies do not settle the remaining language decisions.

## Source and verification

Start with [CArray](../../../Compiler/src/main/kotlin/cloth/data/PrimitiveTypes.kt),
[ExpressionTyping](../../../Compiler/src/main/kotlin/cloth/semantic/ExpressionTyping.kt),
and [EmitArray](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitArray.kt).
Runnable examples are [Arrays.co](../../../examples/Arrays.co) and
[ArrayAssignment.co](../../../examples/ArrayAssignment.co).

Relevant suites are `ArraysTest`, `ArrayInferenceTest`, `RectangularArraysTest`,
`ArrayBindingShapeTest`, and `ArrayAssignmentTest`. Their existing coverage checks
static/runtime shapes, inference order, side effects, bounds, and independent
storage. Read the [array milestones](../cloth-language/references/development.md#fixed-initial-array-shapes-2026-10-31)
for recorded verification; historical descriptor widths are superseded by usize.
Use the target-integer skill for target-sized counts. Record fresh results after
running the relevant suites; the historical counts are not a current test run.
