---
name: cloth-nullability
description: Design, explain, or extend Cloth nullable types, null literals, presence proofs, guarded member access, lazy Elvis fallback, nullable casts and equality, or nullable formatting and metadata.
---

# Cloth nullability

Interfaces lift class, parent-interface and `Object` widenings through the
ordinary nullable carrier. Compound membership guards can retain a shared
parent interface. Membership proves presence on stable field paths, and
user calls, equality and formatting invalidate aliased field proofs.
[Interface presence and lifetime](../cloth-interfaces/references/stage-6.md#object-operations-and-proofs)

[Generic Stage 0](../cloth-generics/references/semantics.md#admitted-arguments-and-nullable-substitution)
confirms `T?` substitution normalizes one nullable wrapper when T is already
nullable. Direct duplicate markers stay invalid, suffix positions stay distinct
and generic bodies keep explicit receiving/payload proof rules. The CNullable
carrier still rejects nested wrappers.
[Generic Stage 1](../cloth-generics/references/stage-1.md) preserves nullable
markers and array suffix positions on parameters/applications in the AST.
[Generic Stage 2](../cloth-generics/references/stage-2.md) implements nullable
substitution in closed fields, declared signatures and annotation facts, reusing
one CNullable carrier. Array-valued T retains its suffix structure.
[Generic Stage 3](../cloth-generics/references/stage-3.md) uses substituted types
for presence, casts, deferred results and generic class-check targets in separate
body views. Unknown T still requires explicit receiving context and grants no
nullable-specific size/equality capability.
[Generic Stage 4](../cloth-generics/references/stage-4.md) executes normalized
carriers, nullable wide arguments/results and complete absent nullable-array field
witnesses in closed generic slots. Copies/removals retain shape after source
destruction; incompatible reappearance still fails at the ordinary shape guard.

A nullable bound such as `T: Base? & Named?` or `T: Number?` proves the
nullable shape and the payload's requirements. An ordinary presence check
exposes the exact payload and its bound operations.
[GEN-5 Stage 3](../cloth-generics/references/nullable-bounds-stage-3.md)

Array bounds check nullability at each suffix position. `Number?[]`
requires nullable elements, and `Number[]?` requires a nullable array.
[GEN-5 Stage 4](../cloth-generics/references/array-bounds-stage-4.md)

[Arbitrary integer Stage 4](../cloth-numerics/references/arbitrary-width-storage.md)
integrates nullable payloads through 65536 bits. Stored values use byte presence
and inline native-limb bytes; expression values carry presence and an independent
snapshot pointer. Guard payload copies, compare present contents rather than
addresses and retain target-padded nullable size.
[Stage 5](../cloth-numerics/references/arbitrary-width-output.md) integrates
wide nullable decimal text, retaining `null` and guarding absent payload access.

Read [confirmed nullable semantics](references/semantics.md) before deciding
language behavior. For source ownership, verification and scalar follow-up rules,
read [the implementation record](references/implementation.md). Consult the
[staged plan](../../../todo/nullability-plan.md) when extending coverage, especially
nullable buffers. Proposals in the plan do not establish new language rules.

## Current scope

Guarded member access uses `?.` on nullable receivers. The first absent guard
skips the remaining postfix chain. Single results use the ordinary nullable
carrier and compose with lazy Elvis.
[Semantics](references/semantics.md#guarded-member-access) ·
[Implementation and verification](references/implementation.md#guarded-member-access)

[String scalar searches](../cloth-strings/references/scalar-methods.md)
`indexOf`/`lastIndexOf` produce target `usize?`. They do not prove their own result
present or permit inferred nullable bindings; preserve ordinary explicit
annotations, null tests, lazy Elvis and payload access. Member lookup/registrations
must use the selected target, not host-width result types.

Nullable fixed-width and target-sized integers, floats, bool, char and typeinfo
execute through bindings, parameters, returns, ordered multiple results, equality,
presence proofs, lazy Elvis, nullable-target casts, formatting and metadata.
Nullable strings, whole arrays and scalar/string elements execute with recursive
copying, retained shapes and presence-aware formatting. Read the
[buffer integration record](references/buffers.md) before changing their handling.
Nullable rectangular rows also execute under the
[row integration contract](../cloth-arrays/references/nullable-rows.md).
[Basic structs](../cloth-structs/references/publication.md) support nullable
structs, nullable fields and arrays containing nullable structs. Presence proofs
on unchanged stored paths permit payload access. Replacing a checked field or
its containing value invalidates the proof. Copies skip absent payloads.
[Struct method typing](../cloth-functions/references/method-typing.md) checks
nullable receiver paths after explicit argument evaluation and invalidates
proofs for fields a method may write. Proofs on independent paths still apply.
[Growable arrays Stage 1](../cloth-arrays/references/growable-arrays-stage-1.md)
extends receiver-proof checks to typed array mutators. Length changes invalidate
indexed descendant proofs, including possible class aliases and repeated/captured
writes; reserve preserves logical element proofs. Nullable removeAt results
remain nullable.
[Stage 3](../cloth-arrays/references/growable-arrays-stage-3.md) executes supported
append/reserve on proven-present local/parameter nullable arrays and receives
nullable elements with presence-aware copies. It adds no implicit nullable
inference or new presence proof.
[Stage 4](../cloth-arrays/references/growable-arrays-stage-4.md) executes these same
operations on nullable class/struct fields and nested receiver paths. Presence,
lifetime and saved bounds are checked again after explicit arguments. Class-field
assignment still needs the existing proof before a later payload call.
[Stage 5](../cloth-arrays/references/growable-arrays-stage-5.md) adds insert,
removeAt and clear with presence-aware shifted storage and independent nullable
removed results. Clear preserves outer presence; it does not prove a nullable
element result present. Absent removed struct fields retain their copied inline
shape witnesses.
[Stage 6](../cloth-arrays/references/growable-arrays-stage-6.md) executes rectangular
edits with nullable rows at multiple depths. Null rows establish no width;
present rows establish/check every inner extent. Remove/clear/null and all-null
copies/returns retain established witnesses. Explicit nullable receiving types,
presence checks and proof invalidation remain required.
[Stage 2](../cloth-arrays/references/growable-arrays-stage-2.md) executes nullable
unsized struct array fields. Their inline pointer-free witnesses survive absence,
copy independently and receive the incoming witnesses on whole-struct replacement.
Typed element cleanup skips absent payloads and inactive slots.
Class lifetime and unsafe handles have separate work. Ordinary
optional arguments are implemented under [their own skill](../cloth-optional-arguments/SKILL.md),
including nullable string/array defaults such as `opt<string[]?> args ?: null`.

[Class equality and identity](../cloth-language/references/class-equality.md)
adds `==`, `!=`, and `===` for nullable class operands. The compiler checks
presence before accessing either payload or selecting a live comparison body.

Nullable type receivers expose instance-independent payload providers such as
`int?::max` (plain int) and `int[2]?::size` (usize), preserving nullable-owned
metadata and scalar storage size. Read the
[nullable provider contract](../cloth-compiler-data/references/nullable-providers.md)
before changing delegation or receiver-form diagnostics. Nullable value receivers
still require presence for payload providers.

Minimal supported example:

```cloth
fn<int> next(int? value) {
    println(value) // Absence prints null without accessing a payload.
    if (value != null) {
        def copy = value // A proven-present snapshot infers plain int.
        return copy + 1
    }
    return value ?: 0
}
```

Maintained runnable examples: [NullableScalars.co](../../../examples/NullableScalars.co)
and [NullableRules.co](../../../examples/NullableRules.co).

## Preserve the language contracts

- Do-while Stage 3 invalidates both regions' repeated writes before first body
  checking when a backedge is possible, including nested/captured writes. A
  proven nonrepeating body keeps incoming facts. The trailing test's true facts
  never justify first-entry reads. Condition inputs intersect body-fallthrough
  and reachable continue facts; exits intersect false-test and break facts with
  no zero-trip path. Unreachable suffix writes/tests contribute no path facts.
  Read [do-while flow](../cloth-control-flow/references/do-while-loops.md#stage-3-completion-nullable-and-metadata-flow-2026-11-03)
  and use `DoWhileSemanticsTest`/`DoWhileFlowTest`. Stage 4 executes payload reads
  and nullable exit facts in `DoWhileTest`, preserving the same first-entry and
  continue/break joins.

- While loops scan both condition and body writes before checking repeated
  nullable occurrences. True-condition proofs enter the body; false-condition
  and reachable-break facts intersect at exit. Captured and continue-path writes
  cannot preserve an optimistic first-test proof. See
  [while loops](../cloth-control-flow/references/while-loops.md) and `WhileTest`.

- Stage 2 loop semantics invalidate repeated body/stepper and transitive captured
  writes before publishing nullable occurrence proofs, preserving zero-trip
  exits. Guards inside an iteration can reestablish presence; body assignments
  can establish stepper presence. Endpoint proofs apply before body invalidation.
  Metadata uses a separate conservative mutable-fact policy. Stages 3-4 execute
  numeric ranges and array foreach; nullable elements require an explicit receiving type. See
  [loop scope and tests](../cloth-control-flow/references/for-implementation.md).
- Cloth never infers or assumes nullability. Every new nullable binding needs an
  explicit type, even for a known nullable copy, function result, named result
  reception or nullable element type. Nullable conditional results also need an
  explicit nullable destination; bindings, declared returns and declared
  parameters can supply it. Check that context recursively: `int?[2]` and arrays
  with nullable rows supply explicit nullability without an outer `?`. This also
  applies to generated enum payload readers and positional result contexts.
  [Nullable array conditional integration](../cloth-language/references/development.md#nullable-array-conditional-contexts-2026-11-06)
  Discards create no binding. `def x = null` and
  inferred nullable copies are rejected; `void?` is invalid everywhere.
- Presence proofs change individual ordinary reads to their payload type, while
  the binding keeps its declared nullable type. An inferred proven-present copy
  is an independent snapshot. Such reads prefer the payload overload; ordinary
  default numeric literal preference still precedes nullable lifting. Preserve
  exact widths, signedness and usize/isize identity without implicit widening.
- Payload operations require proof or a non-null fallback. Printing and
  interpolation accept absent values and bare null, rendering exactly `null`.
  Explicit `::str` still needs proof. Do not expose unchecked payload providers
  merely to reuse formatting.
- Null tests inspect presence. Equality between comparable nullable payloads, or
  nullable and payload values, needs no proof: both absent are equal, one absent
  is unequal, and two present use ordinary equality. Evaluate both operands once
  in source order; preserve NaN rules. Reject ordinary non-nullable null tests.
- Expression `?:` evaluates the left once and the fallback only on absence.
  It is right-associative below comparisons/logical operators. `T? ?: T` yields
  T; a nullable or null fallback retains T?. A declared nullable binding stays
  eligible after narrowing; a plain `5 ?: 0` is rejected. Bare-left null can use
  fallback context (`null ?: 5` is int); `null ?: null` needs explicit context.
  Type-check skipped source and retain the explicit nullable-binding rule.
  Float payloads supply their fallback literal width even when Elvis is a member
  receiver or output argument with no outer float context. Already-typed fallback
  values still require exact types. The float attachment regression covers half
  identity and float64 precision; see [float integration](../cloth-numerics/references/float-attachments.md).
- Nullable-target casts preserve absence and perform ordinary payload conversion
  only when present. Plain values may convert and lift; `null -> int?` supplies
  an explicit type. A non-nullable cast target still needs proof or fallback.
- Scalar `is` compares flow-adjusted static expression types, including `?`.
  Testing T? proves no presence. Bare `null is int?` is an error;
  `(null -> int?) is int?` is true. The `is` target supplies no literal context.
- A nullable binding's `::type` retains its declared type after narrowing.
  Metadata text/equality retain nullable identity, while inspection exposes
  payload properties with ordinary compile/runtime capability checks. Nullable
  scalar `::size` includes presence storage and target ABI padding for type and
  value receivers, even when absent or narrowed. Value receivers evaluate once.
- Keep argument omission separate: `opt<T>` uses a required omission-only `?:`
  fallback. Explicit null is a supplied nullable value, not an omitted argument.
  [Optional-argument implementation](../cloth-optional-arguments/SKILL.md)
  owns ordinary-function default evaluation and call placement.

## Compiler invariants and extension boundaries

- [CNullable](../../../Compiler/src/main/kotlin/cloth/data/CNullable.kt) owns exact
  structural identity and context-specific LLVM carriers, without nullable heap
  boxing. Value presence uses i1; stored presence uses a byte. Preserve bool's
  byte payload storage and nullable half's raw i16 call/result ABI transport in
  [EmitResults](../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitResults.kt).
  An internal absence marker is never an inferred source binding type.
- [NullFlowAnalysis](../../../Compiler/src/main/kotlin/cloth/semantic/flow/NullFlowAnalysis.kt)
  records proofs per symbol/occurrence independently of contextual overload
  probes. Assignments, joins, short-circuit paths and captured effects must remain
  sound. Deferred declarations execute no effects; deferred bodies inherit no
  mutable-capture presence proofs, and execution invalidates transitive writes.
- [ExpressionTyping](../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)
  and call checking own explicit contexts/adaptations. Keep candidate facts
  isolated; emission must not invent unwraps or guess result nullability.
- [EmitNullable](../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitNullable.kt)
  guards semantic payload extraction, conversion, formatting and comparison.
  Absent payloads are deterministic and never treated as present. Branches and
  phis use actual continuing predecessors, including nested returns and guards.
  Scalar size queries use the emitted module's data layout, not a sum of bits.
- When extending buffers, preserve snapshots, returns, copy/transfer, freshness,
  discarded-result validation and cleanup recursively through wrappers. A
  `buffered` flag alone is insufficient. Use [buffer lifetimes](../cloth-buffer-lifetimes/SKILL.md)
  and [arrays](../cloth-arrays/SKILL.md) for those changes. Nullable array shape
  constraints persist independently of presence: first presence fixes unspecified
  dimensions, and null never resets them. Nullable-row rectangularity is
  [implemented](../cloth-arrays/references/nullable-rows.md) with independent
  shape vectors shared within each array value. Clearing a future class binding never implicitly deletes its object;
  object presence and liveness remain separate.

## Verification

Relevant suites are `NullabilityParsingTest`, `NullabilityTypingTest`,
`NullabilityEmissionTest`, `NullabilityRulesTest`, `NullableBuffersTest` and `NullableRowsTest`.
`EnumPayloadTest` covers nullable array payloads, header defaults and nested
elements/rows through the same conditional checker, with native Windows x64/x86
execution and IR/object verification across all nine supported targets.
The [buffer record](references/buffers.md) owns current buffer evidence and routing.
Run from the repository root:

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Nullability*' --offline --console=plain
```

Add the affected numeric, metadata, control-flow, multiple-result or buffer suites
when changing their interaction. Preserve first-error diagnostics, including CLI
failure before output/linking. The recorded nullable coverage is 38 tests;
the final broader focused run passed 85. Native execution covers Windows x64/x86,
with verified IR across nine target profiles, not native Linux/macOS evidence.
The [implementation record](references/implementation.md#verification-evidence-2026-11-01)
documents those historical runs. The former `opt` example failure is resolved
by optional-argument integration; see its dated evidence for current full-suite
results. Recheck source and run relevant tests before making fresh claims.
