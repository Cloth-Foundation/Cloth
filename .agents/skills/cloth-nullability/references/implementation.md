# Nullable scalar follow-up rules

Confirmed decisions are maintained in [confirmed nullable semantics](semantics.md#nullable-values-and-optional-parameters).
This record covers the implemented follow-up to Stages 0-3 of the
[nullability plan](../../../../todo/nullability-plan.md), within the existing
integer/float/bool/char/typeinfo slice. Nullable buffers remain deferred.

## Supported behavior and boundaries

- Nullable-target casts preserve absence, convert only present payloads, and
  preserve ordinary numeric wrapping, rounding and checked failures. A plain
  source converts and then lifts. `null -> int?` has an explicit nullable target.
  Casting an unproven nullable value to a non-nullable target still fails;
  bool/char/typeinfo casts retain ordinary exact payload identity restrictions.
- Scalar `is` compares the static expression type, including nullability and
  existing presence narrowing. An unproven `int? x` satisfies `x is int?`, whether
  absent or present; a presence-proven ordinary read satisfies `x is int`.
  Testing for int? does not prove presence. Operand effects still execute once.
  Declared binding metadata remains int? after narrowing. This does not implement
  future class/state runtime tests.
  The user confirmed that bare `null is T?` is rejected: `is` checks a known
  expression type rather than supplying payload context. An explicitly typed
  source such as `(null -> int?) is int?` is valid and true. Existing compiler
  behavior and rejection/acceptance tests already enforce this rule.
- Nullable metadata keeps canonical nullable text and exact identity while
  inspection reports payload properties. `int?::type::isnumeric` is true,
  `::width` is 32, and its metadata differs from int::type. Wrong payload
  capabilities fail at compile time when known and through source-located
  runtime guards otherwise. No unchecked metadata path crosses opaque calls.
- Scalar nullable `::size` reports complete storage bytes, including the presence
  byte and target ABI padding, as usize. Type and value receivers agree. A
  declared nullable binding's size remains nullable even under a presence proof,
  and an absent value needs no proof. Value receivers execute once. Buffered
  content/count size rules are separate and require presence when implemented.
- Bare-left `null ?: fallback` uses destination context or the fallback's type.
  `def n = null ?: 5` infers plain int. New nullable bindings still need explicit
  annotations; `def n = null ?: existingNullable` is rejected without proof.
  `null ?: null` needs explicit payload context. A conditional fallback with a
  nullable result cannot infer its own nullable type. All skipped source stays
  checked, and ordinary strict literal/conversion policies remain unchanged.

[NullableRules.co](../../../../examples/NullableRules.co) is the maintained runnable
example. [NullableScalars.co](../../../../examples/NullableScalars.co) covers the
original slice. Do not admit nullable strings, arrays, elements, class execution,
unsafe handles or optional arguments on the strength of this implementation.

## Compiler ownership and invariants

Paths are relative to `Compiler/src/main/kotlin/cloth/`:

- `semantic/ExpressionTyping.kt` validates cast payload compatibility, records
  scalar `is` targets, and derives bare-left-null context. Inferred nullable
  bindings/conditional results remain rejected by the existing checks.
- `emitter/types/EmitCast.kt` reuses the ordinary conversion helper within
  `EmitNullable.map`; presence branches dominate extraction and conversion.
  Float-to-int guards execute only on the present path, and actual continuing
  predecessor blocks feed the nullable result phi.
- `data/MetadataQuery.kt` owns the metadata payload subject and capability
  preconditions. `semantic/MetadataAnalysis.kt` tracks known descriptors and
  merges fallback effects conservatively; a literal-left null executes its
  fallback facts. `EmitTypeInfo` populates payload properties but names/equality
  still use the complete nullable descriptor spelling.
- `data/CNullable.kt` registers a typed `ProviderResult.StorageSize` recipe for
  non-buffered payloads. It queries `LLVMABISizeOfType` on the actual storage
  representation and the emitted module's installed data layout. Do not sum
  payload bits and a flag or cache LLVM handles in descriptors.
- `semantic/NullFlowAnalysis.kt` preserves declared scalar nullable receivers for
  `::size`, just as it already preserves declared type for `::type`. Ordinary
  payload reads and `is` remain flow-adjusted. Buffered size receivers must retain
  their separate presence-dependent semantics when Stage 4 is implemented.

No source ABI, new target profile, automatic destruction, GC or error recovery is
introduced. Compilation still stops at its first detected error.

## Verification evidence (2026-11-01)

Commands used from the repository root:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.NullabilityRulesTest --tests cloth.NullabilityEmissionTest --offline --console=plain
.\gradlew.bat :Compiler:test --offline --console=plain
```

- All ten `NullabilityRulesTest` cases pass. They cover semantic boundaries,
  casts across every conversion branch, exact nonnumeric identities, checked
  numeric failure, static checks before/after narrowing, full scalar storage,
  metadata capabilities through opaque calls, captured fallback writes, Unicode
  fallback return lifetime, side effects and the runnable CLI example.
- Native execution covers Windows x64 and x86. The same parsed follow-up fixture
  emits verified LLVM IR across all nine supported target profiles; this is not
  native Linux/macOS execution evidence.
- The original nullable suites continue to pass. The CLI rejection matrix now
  exercises 40 invalid invocations (ten cases, executable/object modes, both
  native widths), each reporting one located diagnostic before output/linking.
- The full suite completed 759 tests across 59 suites: 758 passed, one failed,
  zero errors/skips. The sole failure remains the preserved user `opt` sketch in
  the runnable MultiReturns.co fixture. Optional arguments are unimplemented,
  and its location preference remains pending.
- After enforcing the typed-source rule for bare-null `is`, a final focused run
  of all four nullable suites plus `ConditionalsTest`, `TypeInfoTest`, `CastsTest`
  and `ScalarProvidersTest` passed all 85 tests across eight suites. Skill metadata,
  175 local documentation links and whitespace checks passed. The optional
  skill-validator script could not run because neither available Python runtime
  included PyYAML; required metadata and relative links were checked separately.

See [development history](../../cloth-language/references/development.md#nullable-scalar-rule-completion-2026-11-01)
for the integration milestone. Run fresh checks after subsequent changes.
