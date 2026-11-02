---
name: cloth-language
description: Use Cloth's shared language authority and select the focused feature skills for language design, compiler behavior, source examples, or implementation work.
---

# Cloth language

Cloth is a modern, strongly typed, object-oriented language for applications and
libraries, with first-class state machines in classes. Java/Kotlin and a light
C++ influence provide context, not imported semantics. Preserve concise confirmed
syntax, explicit object creation/destruction, no GC, and no programmer-visible
pointer, reference, or `mut` qualifiers.

## Authority and reading scope

- [Test.co](../../../examples/Test.co) describes intended language, including
  unimplemented features. Explicit user decisions refine and extend it.
- [Shared semantics and feature index](references/semantics.md) retains portability
  and object/state design, with links to feature-owned confirmed
  semantics. Read the relevant sections and skills before deciding behavior.
- Each feature skill owns its current development guidance, implementation
  boundaries, source/test routing, and links to evidence. Load only those needed
  for the current work; follow cross-feature links when changing an interaction.
- [Development history](references/development.md) preserves dated implementation
  and verification evidence. Read the relevant milestone when needed; older
  snapshots do not establish current support. Recheck source and test results.
- Distinguish confirmed semantics, implementation choices, proposals and unresolved
  design. Missing implementation does not invalidate intended syntax. Ask one
  focused question at a time when a necessary semantic choice remains unresolved.
- Compilation stops at the first detected error, with one diagnostic and any
  labels needed to explain it. Preserve this across every compiler feature.

For a focused verification run from the repository root, use
`.\gradlew.bat :Compiler:test --tests cloth.ArraysTest --offline --console=plain --no-daemon`,
substituting the suite identified by the feature skill. Use `./gradlew` on Unix.
Choose tests for the affected behavior; report actual results and target scope.

## Feature skills

| Work area | Skill |
| --- | --- |
| Optional semicolons and valid terminator placement | [cloth-semicolons](../cloth-semicolons/SKILL.md) |
| Bindings, inference, broadcast assignment, updates | [cloth-bindings](../cloth-bindings/SKILL.md) |
| Integers, floats, booleans, arithmetic, casts | [cloth-numerics](../cloth-numerics/SKILL.md) |
| usize/isize, target-sized counts and identity | [cloth-target-integers](../cloth-target-integers/SKILL.md) |
| Nullable types, presence proofs, Elvis, nullable casts and metadata | [cloth-nullability](../cloth-nullability/SKILL.md) |
| Arrays, inference, rectangular and fixed shapes, indexing | [cloth-arrays](../cloth-arrays/SKILL.md) |
| Buffered copies, snapshots, fresh storage, cleanup | [cloth-buffer-lifetimes](../cloth-buffer-lifetimes/SKILL.md) |
| Characters, strings, interpolation, concatenation | [cloth-strings](../cloth-strings/SKILL.md) |
| Functions, overloads, captures, deferred blocks | [cloth-functions](../cloth-functions/SKILL.md) |
| `opt<T>` parameters, defaults, named calls and placement | [cloth-optional-arguments](../cloth-optional-arguments/SKILL.md) |
| Ordered results, forwarding, destructuring, discards | [cloth-multiple-results](../cloth-multiple-results/SKILL.md) |
| Conditionals, is checks, short circuits, branch merges, for-loop scopes/types/flow | [cloth-control-flow](../cloth-control-flow/SKILL.md) |
| Compiler data access, providers, typeinfo | [cloth-compiler-data](../cloth-compiler-data/SKILL.md) |
| print/println, recursive array text, built-in registration and lookup | [cloth-output](../cloth-output/SKILL.md) |
| main, Unicode arguments, exit status and PEC | [cloth-program-entry](../cloth-program-entry/SKILL.md) |
| Diagnostics, spans, stable codes, first-error handling | [cloth-diagnostics](../cloth-diagnostics/SKILL.md) |
| Targets, C ABI, object mode, linking and CRT discovery | [cloth-toolchains](../cloth-toolchains/SKILL.md) |

## Existing and deferred design records

- Integer/float range and array foreach loops use `<-`; the default range step is one and an
  explicit stepper requires a semicolon. The [implementation plan](../../../todo/for-loops-plan.md)
  records the confirmed contract, stages, source ownership and verification.
  Core design and Stage 1 are complete. Stage 2 implements scopes, strict types
  and repeated flow (105 focused and all 868 compiler tests passed).
  C3067 now gates backend execution; stages 3-5 remain pending. See the
  [stage record](../cloth-control-flow/references/for-implementation.md).
  Deferred extensions in the plan are not additional confirmed language rules.
- `opt<T>` / `final opt<T>`, omission-only `?:` defaults and named arguments are implemented for
  ordinary functions; use [the feature skill](../cloth-optional-arguments/SKILL.md).
  The [plan](../../../todo/optional-arguments-plan.md) retains future dispatch and
  constructor questions separately from the accepted ordinary-call contract.
- `call().1` result selection: [deferred RFC](../../../todo/result-selection-rfc.md).
  Its one-based selectors are not an accepted contract or scheduled implementation.
- `::addr`, unsafe contexts and raw memory handles:
  [deferred plan](../../../todo/unsafe-memory-plan.md). Saving it does not authorize
  implementation or settle lifetime/native-interface contracts.

## Maintain project knowledge

Use [feature integration](../cloth-feature-integration/SKILL.md) for every integrated
feature or stage. Keep confirmed decisions in the owning feature's references,
current implementation guidance in its skill, and dated verification evidence in
the development history or a linked focused record. Update routing when ownership
changes. Shared object/state decisions remain in the shared semantics reference.
