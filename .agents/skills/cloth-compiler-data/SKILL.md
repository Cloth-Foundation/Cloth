---
name: cloth-compiler-data
description: Design, explain, or extend Cloth compiler data access through double-colon providers, type and value receivers, exact size and bounds results, or typeinfo metadata propagation.
---

# Cloth compiler data access

Do-while Stage 3 visits metadata in body-before-condition order, forgetting mutable
represented-type facts before a body that may repeat. Its test joins actual
fallthrough/continue snapshots; normal exits join reachable false-test/break
snapshots without zero trips or unreachable suffix writes. Nonrepeating bodies
keep incoming facts, immutable/local scopes remain distinct, and disagreements
stay unknown. Read [do-while flow](../cloth-control-flow/references/do-while-loops.md#stage-3-completion-nullable-and-metadata-flow-2026-11-03)
and use `DoWhileFlowTest`. Stage 4 emits providers using these facts and checked
runtime recipes; `DoWhileTest` verifies mutable metadata behavior during execution.

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

Nullable type receivers delegate instance-independent payload providers under the
[nullable provider contract](references/nullable-providers.md): `int?::max` returns
plain int and fixed-size nullable array types expose their outer count. Nullable
`type` identity and scalar storage size remain nullable-owned. Unproven nullable
value receivers still need presence for payload access. Use typed numeric facts
on recipes when consuming constants through adapters; do not assume every numeric
constant recipe is a concrete IntegerConstant/FloatingConstant.

## Source, tests, and boundaries

[Struct metadata](../cloth-structs/references/semantics.md#struct-type-metadata)
preserves nominal identity. For `Name::type`, a visible local binding takes
precedence over a type name, and a nullable stored field keeps its declared
metadata type after narrowing. Struct storage size and reflection remain deferred.

[Arbitrary-width Stage 2](../cloth-numerics/references/arbitrary-width-types.md)
registers odd/wide integer bounds and metadata without eagerly applying byte-size
division. `ProviderResult.StorageSize` accepts integer or nullable scalar receivers;
new widths use the lazy recipe, while existing machine-width constants retain
their representation. [Stage 3](../cloth-numerics/references/arbitrary-width-arithmetic.md)
enables scalar width 1..64 emission, including target-layout storage sizes
(int<24> occupies four bytes in the tested Windows profiles, int<1> one byte).
[Stage 4](../cloth-numerics/references/arbitrary-width-storage.md) connects all
wide bounds, metadata and storage recipes to physical native-limb layouts.
Its record owns observed size/nullable-size facts and target coverage. Value
receivers retain effects; width is logical N as usize and size is allocated bytes.
[Stage 5](../cloth-numerics/references/arbitrary-width-output.md) integrates
full-width scalar/array `::str` with the shared exact decimal formatter and retires
C4031. Ordinary provider receiver/presence restrictions are unchanged.
The [final implementation record](../cloth-numerics/references/arbitrary-width-integers.md)
owns completed integration, maintained examples and target/resource evidence.

Start with [PrimitiveTypes](../../../Compiler/src/main/kotlin/cloth/data/PrimitiveTypes.kt),
[ProviderResult](../../../Compiler/src/main/kotlin/cloth/data/ProviderResult.kt),
[MetadataQuery](../../../Compiler/src/main/kotlin/cloth/data/MetadataQuery.kt),
[MetadataAnalysis](../../../Compiler/src/main/kotlin/cloth/semantic/MetadataAnalysis.kt),
and [EmitTypeInfo](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitTypeInfo.kt).

Relevant suites: `PrimitiveTypesTest`, `ProviderResultsTest`, `ProviderSyntaxTest`,
`ScalarProvidersTest`, `BufferProvidersTest`, `TypeInfoTest`,
`ProviderIntegrationTest`, and `BufferCountsTest`.
`NullableTypeProvidersTest` additionally checks nullable type bounds/counts,
receiver rejection, defaults/overloads, native x64/x86 and nine-profile IR/objects.
The suites exercise exact values, source forms, skipped-code checks, effects,
capabilities, ABI-sized storage, and
type-only overflow. For target width changes use
[target integers](../cloth-target-integers/SKILL.md).

The [provider milestone](../cloth-language/references/development.md#provider-integration-follow-up-chunk-35-2026-10-31)
and subsequent usize migration record verification. Array value `str` uses
recursive contents through `CArray` and `ProviderResult.ArrayText`; read the
[output skill](../cloth-output/SKILL.md#recursive-array-text) for its contract,
implementation and verification. `addr`, class value text conversion and
state/class execution remain deferred. The
[unsafe-memory plan](../../../todo/unsafe-memory-plan.md) does not authorize them.
