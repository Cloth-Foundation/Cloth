---
name: cloth-compiler-data
description: Design, explain, or extend Cloth compiler data access through double-colon providers, type and value receivers, exact size and bounds results, or typeinfo metadata propagation.
---

# Cloth compiler data access

Read the [confirmed provider semantics](references/semantics.md) and the
[current contract table](../../../todo/compiler-data-access.md#current-contracts-2026-11-01).
The later usize/isize contracts supersede early fixed-64-bit provider records.

## Development rules

- `value::name` and `Type::name` are read-only, lowercase, case-sensitive access.
  Each provider declares supported receiver forms. Parentheses select the separate
  state-call syntax; `int::max()` is not an alternate provider invocation.
- Value receivers evaluate once even for type-dependent constant results. Type
  receivers require no instance. Do not erase effects during constant emission.
- Bounds retain the receiver's exact numeric identity. Floating `min` is the
  most negative finite value. Counts, storage sizes, rank, extent and width yield
  exact target `usize`, including on equal-width integer targets.
- Scalar `size` measures storage bytes; array `size` measures outer elements;
  string `size` measures Unicode scalars. Preserve receiver restrictions and
  overflow checks. An empty outer dimension retains inner shape facts.
  [Nullable scalar size](../cloth-nullability/SKILL.md)
  includes presence and ABI padding, retaining declared binding storage after
  narrowing. Nullable metadata queries expose payload properties while canonical
  text/equality retain nullable identity; keep ordinary capability checks.
- `typeinfo` is one built-in value type. Preserve canonical type spelling and
  capability/provenance facts across copies, conditional continuations, captures,
  calls and results. Static known failures stay compile-time errors; unknown
  capabilities use the existing runtime guard. Probe candidates without leaking
  type or metadata facts into subsequent overload candidates.
- Add behavior to the actual shared type descriptors and typed provider recipes;
  avoid parallel wrapper types and scattered provider-name switches. LLVM handles
  belong to the emission context, not reusable provider definitions.

## Source, tests, and boundaries

Start with [PrimitiveTypes](../../../Compiler/src/main/kotlin/cloth/data/PrimitiveTypes.kt),
[ProviderResult](../../../Compiler/src/main/kotlin/cloth/data/ProviderResult.kt),
[MetadataQuery](../../../Compiler/src/main/kotlin/cloth/data/MetadataQuery.kt),
[MetadataAnalysis](../../../Compiler/src/main/kotlin/cloth/semantic/MetadataAnalysis.kt),
and [EmitTypeInfo](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitTypeInfo.kt).

Relevant suites: `PrimitiveTypesTest`, `ProviderResultsTest`, `ProviderSyntaxTest`,
`ScalarProvidersTest`, `BufferProvidersTest`, `TypeInfoTest`,
`ProviderIntegrationTest`, and `BufferCountsTest`. They exercise exact values,
source forms, skipped-code checks, effects, capabilities, ABI-sized storage, and
type-only overflow. For target width changes use
[target integers](../cloth-target-integers/SKILL.md).

The [provider milestone](../cloth-language/references/development.md#provider-integration-follow-up-chunk-35-2026-10-31)
and subsequent usize migration record verification. Array value `str` uses
recursive contents through `CArray` and `ProviderResult.ArrayText`; read the
[output skill](../cloth-output/SKILL.md#recursive-array-text) for its contract,
implementation and verification. `addr`, class value text conversion and
state/class execution remain deferred. The
[unsafe-memory plan](../../../todo/unsafe-memory-plan.md) does not authorize them.
