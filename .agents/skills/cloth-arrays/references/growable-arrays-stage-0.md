# Growable arrays Stage 0: contract and baseline

Completed 2026-11-07 against the current working tree, including its existing
compiler, Bootstrap and standard-library work. This stage publishes the
[confirmed contract](growable-arrays.md) and establishes a baseline for later
implementation. It changes documentation and skill routing, not compiler behavior.

## Decisions established

The user explicitly confirmed the following during Stage 0:

- `T[]` is growable, `T[N]` is fixed, and unannotated array literals continue to
  infer fixed-size types. Known current length does not fix a growable type.
- `:=` and `final` retain binding-only immutability, permitting length mutation
  of a growable array while forbidding rebinding.
- Rectangular arrays grow/shrink by complete outer rows. Established inner shape
  survives clearing and null assignment; an embedded row cannot grow alone.
- The first API includes append, insert, removeAt, clear and reserve. Only
  removeAt returns an element; the other four return void. Method index/count
  arguments use target usize.
- Temporary growable receivers follow existing method receiver rules. This
  replaces the draft plan's proposed temporary-receiver rejection.

The contract also carries forward ordinary exact element typing, independent
array values, explicit class-object deletion, nullable presence, first-error
diagnostics and foreach snapshots. The array-owned contract identifies the
historical fixed-initial-outer-shape rule that the new design supersedes.

No Stage 1+ implementation is claimed. The current tests still exercise and
preserve the historical fixed-initial-shape behavior, which later stages must
migrate deliberately. User-defined generic types and public List delivery remain
separate dependencies in the [plan](../../../../todo/growable-arrays-and-lists-plan.md).

## Focused baseline

The following corrected PowerShell command passed in **1m 46s**:

```powershell
.\gradlew.bat :Compiler:test `
  --tests cloth.ArraysTest `
  --tests cloth.ArrayInferenceTest `
  --tests cloth.ArrayBindingShapeTest `
  --tests cloth.RectangularArraysTest `
  --tests cloth.ArrayAssignmentTest `
  --tests cloth.ArrayCopyElisionTest `
  --tests cloth.NullableBuffersTest `
  --tests cloth.NullableRowsTest `
  --tests cloth.ForEachTest `
  --tests cloth.ArrayTemporaryCleanupTest `
  --tests cloth.ClassValueIntegrationTest `
  --tests cloth.StructMethodTypingTest `
  --tests cloth.StructMethodEmissionTest `
  --tests cloth.BufferCountsTest `
  --tests cloth.BufferProvidersTest `
  --tests cloth.TypeInfoTest `
  --tests cloth.ProviderResultsTest `
  --tests cloth.ProviderIntegrationTest `
  --tests cloth.NullableTypeProvidersTest `
  --tests cloth.StringSplitTest `
  --tests cloth.StringScalarMethodsTest `
  --tests cloth.EnumValuesTest `
  --tests cloth.ArrayTextTest `
  --tests cloth.ForIntegrationTest `
  --offline --console=plain --no-daemon `
  '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

All **322 tests across 24 suites** passed, with zero failures, errors or skips.
JUnit reports span 2026-11-08 03:40:21-03:41:36 UTC, still 2026-11-07 in the
workspace's America/Phoenix timezone. Aggregate reported test time is 76.741s;
Gradle's elapsed time also includes compilation and startup.

| Suite | Tests |
| --- | ---: |
| ArrayAssignmentTest | 18 |
| ArrayBindingShapeTest | 14 |
| ArrayCopyElisionTest | 17 |
| ArrayInferenceTest | 18 |
| ArraysTest | 18 |
| ArrayTemporaryCleanupTest | 70 |
| ArrayTextTest | 8 |
| BufferCountsTest | 8 |
| BufferProvidersTest | 11 |
| ClassValueIntegrationTest | 11 |
| EnumValuesTest | 4 |
| ForEachTest | 19 |
| ForIntegrationTest | 6 |
| NullableBuffersTest | 9 |
| NullableRowsTest | 6 |
| NullableTypeProvidersTest | 6 |
| ProviderIntegrationTest | 6 |
| ProviderResultsTest | 11 |
| RectangularArraysTest | 14 |
| StringScalarMethodsTest | 13 |
| StringSplitTest | 13 |
| StructMethodEmissionTest | 3 |
| StructMethodTypingTest | 8 |
| TypeInfoTest | 11 |

Baseline XML is retained under
`build/growable-arrays-stage0/test-results/`, copied from the completed
`Compiler/build/test-results/test/` run. Re-running Compiler tests replaces the
latter, so use the preserved copy for this baseline's evidence.

The first sandboxed attempt could not lock the existing Gradle cache. The first
cache-enabled attempt failed before tests because PowerShell split unquoted
dotted Kotlin property options. The command above quotes those options and is
the successful baseline; neither setup attempt is counted as a test run.

## Actual target scope

The executable fixtures ran on **Windows x64 and Windows x86/MSVC**. Native x86
cases, ownership probes, recursive snapshots, retained shapes, class fields,
method receivers and first-error runtime guards passed without skipped tests.
Not every individual test runs on both profiles; the selected integration suites
provide the cross-profile native coverage.

`NullableRowsTest`, `StructMethodEmissionTest` and `ForIntegrationTest` also
verify IR and emit nonempty objects across all nine existing ABI profiles:

| OS/environment | Architectures |
| --- | --- |
| Windows/MSVC | x86, x86_64, AArch64 |
| Linux/GNU | x86_64, AArch64 |
| Linux/musl | x86_64, AArch64 |
| macOS/Darwin | x86_64, AArch64 |

The environment has WSL with Ubuntu installed; distribution inventory was
checked, but Linux compilation/execution was not part of this baseline. Native
Linux, macOS, AArch64 and musl execution is not claimed. Target object/IR evidence
does not substitute for native runtime evidence.

## Handoff to Stage 1

Publication checks pass for all three changed skills with the full skill-creator
validator. PyYAML 6.0.3 was installed only into a workspace temporary directory
for validation and removed afterward. Ten documents and 556 local link targets,
balanced fences, trailing whitespace and scoped Git diff checks pass.

Use the [array skill](../SKILL.md) and confirmed contract for type/inference,
receiver-place and effect work. Existing compiler semantics remain the baseline
until that work lands. No new compiler tests, diagnostic codes or production
storage changes are part of Stage 0.
