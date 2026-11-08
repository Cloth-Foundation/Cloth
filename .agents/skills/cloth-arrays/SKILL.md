---
name: cloth-arrays
description: Design, explain, or extend Cloth arrays, including element inference, rectangular dimensions, fixed and growable shape contracts, indexed mutation, independent values and snapshot foreach traversal.
---

# Cloth arrays

[Arbitrary integer Stage 4](../cloth-numerics/references/arbitrary-width-storage.md)
integrates inline native-limb elements through 65536 bits, including nullable
elements/rows, recursive independent copies and foreach snapshots. Wide reads
copy before later effects, and indices compare every original bit against the
target-sized bound before narrowing.
[Stage 5](../cloth-numerics/references/arbitrary-width-output.md) integrates exact
wide decimal array text and reclaims leaf snapshots/scratch in both passes.
Ordinary array shape/count/allocation limits are unchanged.

[Basic structs](../cloth-structs/references/publication.md) can be array elements
and can hold fixed or growable arrays. Stored field/index writes address the original
element. Copies and foreach receivers preserve recursive independence, including
nullable row shape witnesses. See [Structs.co](../../../examples/Structs.co).

Arrays and foreach snapshots copy class identities along with their element
slots. Class-owned arrays retain explicit extents and initialized inner shape;
growable outer counts may change on whole replacement.
[Class values](../cloth-language/references/class-values.md)

Read [confirmed array semantics](references/semantics.md) for the language contract.
Use [buffer lifetimes](../cloth-buffer-lifetimes/SKILL.md) when changing copying,
argument snapshots, returned storage, or cleanup.

The [growable-array contract](references/growable-arrays.md) confirms:
`T[]` grows, `T[N]` stays fixed, literal inference stays sized, `:=`/`final`
permit length mutation, and temporary receivers follow ordinary method rules.
The initial API is append/insert/removeAt/clear/reserve; rectangular inner shape
survives empty/null states. [Stage 1](references/growable-arrays-stage-1.md)
implements the type distinction, separate current-count/inner-shape facts, all
five signatures, stored/temporary receiver plans and flow effects. Whole-array
replacement with a different growable outer count executes.
[Stage 2](references/growable-arrays-stage-2.md) adds owned capacity, concrete
copy/reserve/relocation/release helpers and executable unsized struct array fields,
including retained nullable-field witnesses. Public mutator calls still stop at
C4032; private helper probes do not establish source-level growth support.

The [Stage 0 record](references/growable-arrays-stage-0.md) publishes a passing
322-test/24-suite baseline, native Windows x64/x86 coverage and all-nine-profile
IR/object evidence. The [plan](../../../todo/growable-arrays-and-lists-plan.md)
stages implementation and the collection proof. Generic `List<T>` additionally
requires separate generic-type support.

## Current implementation rules

- Infer element types across all typed elements before contextual integer literals;
  preserve exact element identity. Do not add widest-type inference or implicit
  conversions between typed values. Floating literal context remains dependent
  on the existing binding/call rules.
- Preserve rectangular shape recursively. Explicit `[]` remains growable rather
  than inferring a fixed type from a known current count. Literal-only inference
  stays sized; growable copies and stored `T[]` function/block results stay
  growable. Explicit extents and established inner-shape constraints remain.
- Use `EmitArray.constrain` for declared extents and retained destination
  shapes. Binding and row writes can retain live descriptors. Nullable class fields
  retain a witness across null and arena replacement. Use `constrainPresent`
  when a nullable destination can still be establishing its first shape.
  `RetainedArrayShape.binding` omits only an unsized binding's outer count;
  embedded-row constraints keep every axis. Owned nullable-row root sharing uses
  the same binding distinction when replacing a value with another outer length.
  [Shared shape operations](../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArray.kt)
- Keep known shape failures at compile time and unknown shape checks at runtime.
  A zero outer count does not erase inner-axis shape metadata.
- Reads and writes evaluate operands once in source order. Resolve assignment
  targets and indices before the RHS; validate bounds before element access.
- Preserve recursive value independence for binding copies, parameters, extracted
  rows, and replacements. `:=` and `final` forbid rebinding while permitting
  element writes. Object field semantics are a separate design concern.
- Keep mutators' `MUTATING_RECEIVER` contract distinct from read-only attachments.
  Use the committed `ArrayMutationPlan` receiver/owner instead of copying a
  receiver as an explicit value argument. Reject fixed/embedded length changes,
  read-only defaults and lost receiver proofs before native emission. C4032 still
  guards public mutator execution; it no longer gates unsized struct fields.
- Array descriptors append target-sized capacity and an owner after the existing
  shape fields. Strings keep their two-field layout. Use `EmitMemory.bytes` for
  raw byte spans and `EmitArrayStorage` for typed array copies and private growth.
  Borrowed arrays have null owner; reserve copies them before moving storage.
  Relocation transfers live element arenas and reparents moved head cells.
  [Storage invariants and measured probes](references/growable-arrays-stage-2.md)

## Implemented boundaries

Sized and unsized arrays, nested literals, indexing, plain indexed `=`, and
function parameters/results are implemented. Current policies require element
context for an empty literal, use C dimension order (`int[2][3]`), accept supported
integer indices, and terminate on invalid executed bounds. Recursive array value
formatting is implemented for print, interpolation and `::str`; read the
[output skill](../cloth-output/SKILL.md#recursive-array-text) and its contract.
Enum type `::values` produces an ordinary fixed-size array of nominal enum cases;
preserve the same independent copies, return snapshots and foreach traversal.
[Enum enumeration](../cloth-language/references/value-operators.md#enum-values-and-formatting)
Snapshot foreach is implemented through Stage 4. Indexed compound updates and
array equality are not implemented.
These implementation policies do not settle the remaining language decisions.

[String `toChars()`](../cloth-strings/references/scalar-methods.md) produces an
independent dynamic `char[]` using checked scalar-count storage and ordinary
member-result freshness. Preserve fixed receiving shapes, stored-copy independence,
parameter/return snapshots and foreach behavior for these generated arrays.

Foreach evaluates the source once per loop entry and traverses an independent
contents/shape snapshot.
Original-source changes cannot affect later visited values; physical copies may
be elided only under existing value-independence/lifetime proofs. See the
[loop contract](../cloth-control-flow/references/semantics.md#for-loops-and-ranges-design-confirmed-implementation-deferred)
and [implementation plan](../../../todo/for-loops-plan.md).
Foreach element bindings are mutable independent values initialized anew for
each element. Row mutation/rebinding cannot change the traversal snapshot or
original source; retain ordinary type/shape checks and recursive independence.
Foreach headers do not admit a `; stepper` clause; traversal advances through the
snapshot automatically, independently of local element rebinding or row writes.
Stage 2 resolves foreach scopes, element/row inference and received shape, with
ordinary mutable assignment checks. Stage 4 executes snapshots, independent
element/row reception and conservative arena/stack cleanup. C3067 is retired;
nullable scalar/string elements execute with explicit receiving types. Nullable
whole arrays and nullable rows retain shape through null assignments. Read the
[confirmed rectangular row contract](references/semantics.md#nullable-rectangular-rows)
and [row implementation record](references/nullable-rows.md) before changing
nullable-row descriptors, shared shape witnesses, copies or nested writes.
Read the [nullable buffer contract](../cloth-nullability/references/buffers.md) and the
[stage scope](../cloth-control-flow/references/for-implementation.md) before
extending traversal or lifetimes. `ForEachTest` and `ArrayTemporaryCleanupTest`
cover executable independence, shape checks, saved values and bounded safe regions.

## Source and verification

Start with [CArray](../../../Compiler/src/main/kotlin/cloth/data/PrimitiveTypes.kt),
[ExpressionTyping](../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt),
and [EmitArray](../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArray.kt).
Runnable examples are [Arrays.co](../../../examples/Arrays.co) and
[ArrayAssignment.co](../../../examples/ArrayAssignment.co), plus
[ForEach.co](../../../examples/ForEach.co) for independent snapshot traversal.
`ForIntegrationTest` executes the foreach example on Windows x64/x86 and verifies
loop IR/object emission across all supported ABI profiles.

Relevant suites are `ArraysTest`, `ArrayInferenceTest`, `RectangularArraysTest`,
`ArrayBindingShapeTest`, and `ArrayAssignmentTest`. `NullableRowsTest` covers
nullable-row execution and nine-profile IR/objects. The array suites check
static/runtime shapes, inference order, side effects, bounds, and independent
storage. Read the [array milestones](../cloth-language/references/development.md#fixed-initial-array-shapes-2026-10-31)
for recorded verification; historical descriptor widths are superseded by usize.
Use the target-integer skill for target-sized counts. Record fresh results after
running the relevant suites; the historical counts are not a current test run.
[Transfer and formatting integration](../cloth-language/references/development.md#shared-value-transfer-and-text-recipes-2026-11-06)
