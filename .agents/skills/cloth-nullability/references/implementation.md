# Nullable scalar follow-up rules

Confirmed decisions are maintained in [confirmed nullable semantics](semantics.md#nullable-values-and-optional-parameters).
This record covers the implemented follow-up to Stages 0-3 of the
[nullability plan](../../../../todo/nullability-plan.md), within the existing
integer/float/bool/char/typeinfo slice. This is the scalar milestone;
the later [buffer integration](buffers.md) now admits nullable strings, whole
arrays and scalar/string elements. Nullable rows now execute under the
[rectangular row record](../../cloth-arrays/references/nullable-rows.md).

## Supported behavior and boundaries

- Nullable numeric destinations supply their payload's literal context through
  arithmetic, including returns, argument reception and array element writes.
  Integer peer widths and floating literal formats remain independent; declared
  widths and literal range checks still apply before lifting the present result.
  This does not permit arithmetic on nullable operands without a presence proof.
  See the [numeric context review fixes](../../cloth-numerics/references/numeric-literals.md#arithmetic-context-review-fixes)
  and `NumericLiteralsTest` / `NumericLiteralsIntegrationTest` for regressions.
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
original slice. Nullable strings/whole arrays/elements and optional arguments
have since been integrated under their own records; they do not follow from
this scalar milestone alone. Class execution and unsafe handles remain deferred.

## Compiler ownership and invariants

Paths are relative to `Compiler/src/main/kotlin/cloth/`:

- `semantic/typing/ExpressionTyping.kt` validates cast payload compatibility, records
  scalar `is` targets, and derives bare-left-null context. Inferred nullable
  bindings/conditional results remain rejected by the existing checks.
- `emitter/types/EmitCast.kt` reuses the ordinary conversion helper within
  `EmitNullable.map`; presence branches dominate extraction and conversion.
  Float-to-int guards execute only on the present path, and actual continuing
  predecessor blocks feed the nullable result phi.
- `data/MetadataQuery.kt` owns the metadata payload subject and capability
  preconditions. `semantic/flow/MetadataAnalysis.kt` tracks known descriptors and
  merges fallback effects conservatively; a literal-left null executes its
  fallback facts. `EmitTypeInfo` populates payload properties but names/equality
  still use the complete nullable descriptor spelling.
- `data/CNullable.kt` registers a typed `ProviderResult.StorageSize` recipe for
  non-buffered payloads. It queries `LLVMABISizeOfType` on the actual storage
  representation and the emitted module's installed data layout. Do not sum
  payload bits and a flag or cache LLVM handles in descriptors.
- `semantic/flow/NullFlowAnalysis.kt` preserves declared scalar nullable receivers for
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

## Guarded member access

The lexer recognizes `?.`, `?:` and nullable `?::` independently. Bootstrap uses
the same operator name and spelling. [Guarded access contract](semantics.md#guarded-member-access)

- The parser puts the remaining postfix chain under the guard. A guard introduces one
  payload reference for its continuation. The reference never repeats the
  receiver expression. Grouped chains are separate expressions.
  [ExpressionParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ExpressionParser.kt)
- Expression typing checks the receiver's nullable source type, even with a prior presence proof,
  and checks the continuation using ordinary member typing. C3125 rejects a
  non-nullable or untyped receiver. A void call can be used for its side effects.
  Multiple results require an ordinary call and a presence proof, and are
  rejected with C3055 inside a guarded chain. The guard itself is a value
  expression. A guarded struct or array payload uses temporary receiver
  semantics. A guarded class payload keeps its identity, so subsequent class
  field selections and calls use ordinary instance storage and lifetime checks.
  [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)
- Completion and presence analysis account for both the present continuation and
  the absent path. Stable receiver proofs apply on the present path while valid.
  Metadata, array shape and class lifetime analyses merge the continuing paths.
  Generic body views keep the guard and its payload reference linked within each
  specialization.
- The emitter evaluates the receiver once, then uses the existing nullable branch helper.
  The present branch alone evaluates and wraps the continuation. Existing
  nullable results pass through directly. A return or loop jump in an argument
  ends that branch without supplying a value to the join. Ordinary snapshots
  and storage ownership rules apply inside each reached expression.
  [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)

### Verification (2026-11-09)

The guarded access tests check skipped receivers, arguments and defaults,
nullable result flattening, generic bodies, early returns, void calls and
first diagnostics. The parser test covers chaining, grouping and the Elvis
boundary. Bootstrap compares the operator token with Kotlin and asserts the
printed count through the public compiler request and Shuttle.
[GuardedAccessTest](../../../../Compiler/src/test/kotlin/cloth/GuardedAccessTest.kt) ·
[NullabilityParsingTest](../../../../Compiler/src/test/kotlin/cloth/NullabilityParsingTest.kt) ·
[Bootstrap verification](../../cloth-bootstrap-lexer/references/port.md#verification)

The full run passed 2,035 Compiler tests across 193 suites, 61 Shuttle tests,
and 25 Shuttle configuration checks, with zero failures, errors or skips.
Guarded chains linked and ran on Windows x64 and x86. Their executable LLVM IR
verified on all nine Windows, Linux and macOS target profiles.

```powershell
.\gradlew.bat :Compiler:test :Shuttle:check --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```
