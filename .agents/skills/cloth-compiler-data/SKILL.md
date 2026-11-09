---
name: cloth-compiler-data
description: Design, explain, or extend Cloth compiler data access through double-colon providers, type and value receivers, exact size and bounds results, or typeinfo metadata propagation.
---

# Cloth compiler data access

[Generic Stage 3](../cloth-generics/references/stage-3.md) supports universal
`T::type` during declaration checking and keeps represented types separate for
closed owner bodies. Unknown T gains no size, text or numeric provider merely
from one selected actual type. Canonical argument identity stays separate from
bounded human-facing display.
[Generic Stage 4](../cloth-generics/references/stage-4.md) emits exact closed
metadata. Share a module-local EmitTypeInfo.Types registry across callable string
services; canonical nominal descriptors key identity, including private actual
arguments and array/nullable wrappers. Canonical text keeps numeric widths and
premeasures shared arguments before flattening; C4034 rejects expansion beyond
1,048,576 UTF-8 bytes rather than truncating metadata.

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

- Import [Stage 5](../cloth-imports/references/stage-5.md) resolves nominal type
  receivers and annotations through original source context. Same-spelled private
  types in separate files stay distinct in represented-type metadata. Preserve
  this context through callable trials and width-specialized method bodies.
  [Stage 6](../cloth-imports/references/stage-6.md) also preserves this identity in
  native typeinfo descriptors and equality, recursively through arrays/nullable
  types. Intern descriptors within the emitted module by canonical structural
  identity and original nominal declarations with exact closed arguments, never
  displayed type text. Keep
  displayed type names unchanged; binary cross-module metadata ABIs remain deferred.
- `value::name` and `Type::name` are read-only, lowercase, case-sensitive access.
  Each provider declares supported receiver forms. Parentheses select the separate
  state-call syntax; `int::max()` is not an alternate provider invocation.
- Value receivers evaluate once even for type-dependent constant results. Type
  receivers require no instance. Do not erase effects during constant emission.
- Enum value `::name` returns the case's declared spelling as `string`.
  Use a presence proof for a nullable value. The compiler-owned name reader
  also supplies default enum formatting.
  [Enum names](../cloth-language/references/value-operators.md#enum-values-and-formatting)
- Enum type `::values` returns a fixed-size array of every case in declaration
  order. Use the `EnumValues` recipe and shared array literal emission; preserve
  the exact nominal element type, case count and independent array semantics.
  Enum instances and non-enum types reject this provider. Enumeration invokes
  no user equality or formatter.
  [Enum enumeration](../cloth-language/references/value-operators.md#enum-values-and-formatting)
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
- `ArraySize` reads the target-sized outer count from the value descriptor
  when the static extent is unknown. Inner extents carry a count and a separate
  known flag. Tests exercise that layout with zero, maximum and unknown extents.
  Immediate class array projections can read that count from checked storage.
  Preserve receiver evaluation and liveness checks before consuming the count.
  [Immediate class array reads](../cloth-collections/references/linear-collections.md#immediate-class-array-reads)
  [Buffer counts](../../../Compiler/src/test/kotlin/cloth/BufferCountsTest.kt) ·
  [Provider results](../../../Compiler/src/test/kotlin/cloth/ProviderResultsTest.kt)
- [Growable arrays Stage 1](../cloth-arrays/references/growable-arrays-stage-1.md)
  preserves declared `T[]` identity even when separate shape facts know a current
  count. `value::size` therefore reads the descriptor; `value::type` remains
  growable. Do not turn current count facts into fixed type extents or stale
  provider constants across mutations, branches, repeated loops or captures.
  [Stage 2](../cloth-arrays/references/growable-arrays-stage-2.md) appends private
  capacity/owner fields after the existing shape fields. Providers keep reading
  live length and extents; capacity never becomes a language size or type fact.
  Enum values, formatting and foreach use the shared array-only layout.
  [Stage 3](../cloth-arrays/references/growable-arrays-stage-3.md) executes append
  and reserve on the supported one-dimensional receivers: append changes current
  `::size`, reserve does not, and both retain declared `T[]` metadata identity.
  [Stage 4](../cloth-arrays/references/growable-arrays-stage-4.md) extends those
  operations to stored fields. Read current field length after growth; private
  capacity, owner and scoped observation bookkeeping remain outside metadata.
  [Stage 5](../cloth-arrays/references/growable-arrays-stage-5.md) executes insert,
  removeAt and clear: value length changes with the operation, clear produces
  zero, and growable type identity stays unchanged. Saved scalar metadata remains
  its received value; current queries cannot reuse stale pre-mutation lengths.
  [Stage 6](../cloth-arrays/references/growable-arrays-stage-6.md) verifies these
  rules for rectangular edits and retained empty shapes: outer size changes,
  row size retains the inner extent, and rank/canonical growable identity stay
  unchanged across append/insert/remove/clear and result transport.
  The [Stage 8 audit](../cloth-arrays/references/growable-arrays-stage-8.md)
  retains these rules for the completed array surface. List `size()` remains an
  ordinary method; generic collections do not inherit array-only providers.
- `typeinfo` is one built-in value type. Preserve canonical type spelling and
  capability/provenance facts across copies, conditional continuations, captures,
  calls and results. Static known failures stay compile-time errors; unknown
  capabilities use the existing runtime guard. Probe candidates without leaking
  type or metadata facts into subsequent overload candidates.
- [MetadataAnalysis](../../../Compiler/src/main/kotlin/cloth/semantic/flow/MetadataAnalysis.kt)
  keys slots by resolved binding identity. Use the existing declaration and
  assignment symbols for declarations, grouped targets, writes and loop bindings.
  Keep slot facts scoped to the current branch or body and preserve the separate
  merge rules for represented types.
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
[MetadataAnalysis](../../../Compiler/src/main/kotlin/cloth/semantic/flow/MetadataAnalysis.kt),
and [EmitTypeInfo](../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitTypeInfo.kt).

Relevant suites: `PrimitiveTypesTest`, `ProviderResultsTest`, `ProviderSyntaxTest`,
`ScalarProvidersTest`, `BufferProvidersTest`, `TypeInfoTest`,
`ProviderIntegrationTest`, and `BufferCountsTest`.
`EnumValuesTest` verifies type-only enum enumeration, array independence,
returns/defaults, foreach and receiver/type rejection, with native x64/x86
execution and verified IR/objects across all nine targets.
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
