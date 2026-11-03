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

## Canonical operator and keyword spellings

[Operators](../../../Compiler/src/main/kotlin/cloth/token/Operators.kt) and
[Keywords](../../../Compiler/src/main/kotlin/cloth/token/Keywords.kt) own Cloth's
operator and keyword spellings. Their `.toString()` methods are the source of
truth wherever compiler code needs those spellings.

- Use enum values for operator/keyword identity and dispatch. When comparing or
  producing text, use `.toString()`, for example
  `token.lexeme == Operators.UNDERSCORE.toString()` and
  `Keywords.FUNCTION.toString()`. Use the existing enum lookup APIs where needed.
- Apply this to lexer/parser checks, semantic checks, diagnostics, generated
  Cloth source and any spelling tables. Do not hard-code duplicate spellings,
  including keywords/operators embedded in longer diagnostic messages. Kotlin
  interpolation of an enum, such as `"Expected '${Keywords.FUNCTION}'"`, calls
  its `.toString()` and satisfies this rule.
- Do not derive spellings from enum `.name`, lowercasing or a parallel mapping;
  keyword aliases are defined by `Keywords.toString()`. For character-level
  operator matching, use the existing `Operators.toChar()` helper.
- Lower compound assignments through `Operators.compoundAssignmentBase()` and
  render the returned enum with `.toString()` when constructing an arithmetic
  token. Do not derive the binary operator by slicing its assignment spelling.
  Range emission also keeps comparison and update identities as enum values.
- Keep original token lexemes when reporting what the user actually wrote.
  Literal Cloth examples and fixed source/spelling test fixtures may contain
  syntax directly; compiler logic and dynamically constructed syntax must use
  the canonical methods. This rule concerns Cloth syntax, not target-language
  syntax such as LLVM IR.
- When changing code that duplicates a spelling, replace it with the enum-based
  form. Existing duplicates are cleanup work, not an alternative authority.

The [compiler-wide migration record](references/development.md#canonical-operator-and-keyword-spellings-2026-11-02)
captures implementation scope and regression evidence.

## Feature skills

| Work area | Skill |
| --- | --- |
| Optional semicolons and valid terminator placement | [cloth-semicolons](../cloth-semicolons/SKILL.md) |
| Bindings, inference, broadcast assignment, updates | [cloth-bindings](../cloth-bindings/SKILL.md) |
| Numeric literal prefixes/suffixes, integers, floats, booleans, arithmetic, casts | [cloth-numerics](../cloth-numerics/SKILL.md) |
| Arbitrary fixed integer widths, private value transport, full decimal output and target evidence | [integrated integer implementation](../cloth-numerics/references/arbitrary-width-integers.md) |
| usize/isize, target-sized counts and identity | [cloth-target-integers](../cloth-target-integers/SKILL.md) |
| Nullable types, presence proofs, Elvis, nullable casts and metadata | [cloth-nullability](../cloth-nullability/SKILL.md) |
| Nullable strings/whole arrays/elements, retained shapes and buffer lifetimes | [nullable buffer integration](../cloth-nullability/references/buffers.md) |
| Nullable rectangular rows, shared shape witnesses and nested row writes | [nullable row integration](../cloth-arrays/references/nullable-rows.md) |
| Arrays, inference, rectangular and fixed shapes, indexing | [cloth-arrays](../cloth-arrays/SKILL.md) |
| Buffered copies, snapshots, fresh storage, cleanup | [cloth-buffer-lifetimes](../cloth-buffer-lifetimes/SKILL.md) |
| Characters, strings, interpolation, concatenation | [cloth-strings](../cloth-strings/SKILL.md) |
| Functions, overloads, captures, deferred blocks | [cloth-functions](../cloth-functions/SKILL.md) |
| `opt<T>` parameters, defaults, named calls and placement | [cloth-optional-arguments](../cloth-optional-arguments/SKILL.md) |
| Ordered results, forwarding, destructuring, discards | [cloth-multiple-results](../cloth-multiple-results/SKILL.md) |
| Conditionals, is checks, short circuits, branch merges, range/foreach/while, break/continue and loop cleanup | [cloth-control-flow](../cloth-control-flow/SKILL.md) |
| Compiler data access, providers, typeinfo | [cloth-compiler-data](../cloth-compiler-data/SKILL.md) |
| Nullable type providers, payload bounds and fixed array type counts | [nullable provider integration](../cloth-compiler-data/references/nullable-providers.md) |
| print/println, recursive array text, built-in registration and lookup | [cloth-output](../cloth-output/SKILL.md) |
| main, Unicode arguments, exit status and PEC | [cloth-program-entry](../cloth-program-entry/SKILL.md) |
| Diagnostics, spans, stable codes, first-error handling | [cloth-diagnostics](../cloth-diagnostics/SKILL.md) |
| Targets, C ABI, object mode, linking and CRT discovery | [cloth-toolchains](../cloth-toolchains/SKILL.md) |

## Existing and deferred design records

- Arbitrary fixed integer widths: [implementation plan](../../../todo/arbitrary-integer-widths-plan.md)
  and [accepted numeric contract](../cloth-numerics/references/semantics.md#arbitrary-fixed-integer-widths-design-confirmed-2026-11-03).
  Signed/unsigned `int<N>` admits 1..65536 with the same cap on
  x86_64 and AArch64; default integers remain 32 bits, and floats remain 16/32/64.
  [Stage 2](../cloth-numerics/references/arbitrary-width-types.md) integrates all
  widths in semantic typing, contextual literals and diagnostics.
  [Stage 3](../cloth-numerics/references/arbitrary-width-arithmetic.md) enables
  source scalar widths 1..64 and production wide operation/conversion kernels.
  [Stage 4](../cloth-numerics/references/arbitrary-width-storage.md) integrates
  source storage, calls, nullable values, arrays, loops and metadata through the
  cap. [Stage 5](../cloth-numerics/references/arbitrary-width-output.md) adds exact
  decimal text through the cap for every output route and retires C4031.
  The [final integration record](../cloth-numerics/references/arbitrary-width-integers.md)
  publishes [IntegerWidths.co](../../../examples/IntegerWidths.co), source
  invariants, completed-stage coverage and full-cap resource measurements.
  Windows x64/x86 and Linux x64 GNU programs execute against independent full-value
  oracles; all nine profiles' objects/assembly pass. Native AArch64/macOS/musl
  execution remains untested.
  The [Stage 1 backend record](../cloth-numerics/references/arbitrary-width-backend.md)
  captures isolated LLVM experiments, the selected small-scalar/wide-limb
  representation and backend evidence. The plan separates fixed/target/native
  widths and stages backend feasibility, typing, arithmetic/casts, storage/calls,
  full-width output and publication. Probe success is not source feature support.
- Numeric literal prefixes and suffixes: [implementation plan](../../../todo/numeric-literals-plan.md).
  Decimal-only `i` / `I` and `f` / `F` select numeric category without fixing
  width; `123.f`, `123f` and `123.0f` are valid float forms, while `123.i` is
  invalid. Hexadecimal/binary/octal integers receive ordinary contextual integer
  types, including usize/isize. Contextual float widths in bindings/assignments/
  returns and integer literal forms in type widths/extents are also confirmed;
  declared widths and range checks remain authoritative. The
  [implementation record](../cloth-numerics/references/numeric-literals.md) covers
  the integrated syntax, shared numeric decoding, strict contexts and verification.
- Integer/float range and array foreach loops use `<-`; the default range step is one and an
  explicit stepper requires a semicolon. The [implementation plan](../../../todo/for-loops-plan.md)
  records the confirmed contract, stages, source ownership and verification.
  Stages 0-5 integrate design, parsing, scopes/strict types/repeated flow, numeric
  ranges, array foreach, conservative lifetimes and publication. Ranges preserve saved
  bounds, default/all explicit updates, finite/progress guards and overflow exits.
  Foreach uses independent snapshots and mutable independent elements; proven
  loop regions reclaim arena/stack temporaries. C3067 is retired. Runnable
  [integer](../../../examples/ForRanges.co), [float](../../../examples/FloatRanges.co)
  and [foreach](../../../examples/ForEach.co) examples execute on Windows x64/x86;
  loop IR/objects verify across all nine supported ABI profiles. All 936 compiler
  tests pass; the agreed loop slice is complete. See the
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

Current for/while loops support `break`/`continue` through reusable loop targets,
completion outcomes and crossed-region cleanup. Read the
[loop-control extension](../cloth-control-flow/references/loop-control.md) before
changing loop exits. [While loops](../cloth-control-flow/references/while-loops.md)
implement repeated bool testing and conservative flow/cleanup; do/while remains deferred.

Use [feature integration](../cloth-feature-integration/SKILL.md) for every integrated
feature or stage. Keep confirmed decisions in the owning feature's references,
current implementation guidance in its skill, and dated verification evidence in
the development history or a linked focused record. Update routing when ownership
changes. Shared object/state decisions remain in the shared semantics reference.
