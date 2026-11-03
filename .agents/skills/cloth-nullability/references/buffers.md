# Nullable strings and arrays (2026-11-03)

The user requested executable nullable strings and arrays so
`opt<string[]?> args ?: null` works, selected C# nullability placement, and
initially kept nullable rows gated. Their
[rectangular shape policy](../../cloth-arrays/references/semantics.md#nullable-rectangular-rows)
now executes under the [row implementation record](../../cloth-arrays/references/nullable-rows.md).
The [C# array reference](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/arrays)
provides the syntax precedent; Cloth retains its own strict types, explicit
nullable destinations, rectangular shapes, independent array values and lifetimes.

## Supported contract

- `string?` permits an absent string. `T[]?` permits an absent array;
  `T?[]` has present array storage with nullable scalar/string elements;
  `T?[]?` permits both. A trailing marker wraps the complete array, so
  `int?[][]?` is an absent-or-present rectangular matrix of nullable ints.
  Fixed extents retain Cloth's outer-to-inner C dimension order:
  `int?[2][3]?` is a nullable two-by-three matrix.
- A marker between array suffixes denotes nullable rows: `int[]?[]` and
  `int[]?[]?` now execute with shared rectangular constraints and independent copies.
  No jagged arrays, automatic null initialization or nullable inference is added.
- Nullable strings/whole arrays/elements execute in bindings, parameters,
  ordinary optional defaults, returns, multiple results, deferred computations,
  conditional/Elvis expressions, exact nullable-target casts and metadata.
  Array literals receive explicit nullable element context; existing typed arrays
  do not implicitly convert their element storage to a different nullable layout.
- Omission and null stay separate. `opt<string[]?> args ?: null` defaults only
  on omission; explicit null remains a supplied value. A non-null fallback is
  skipped for a supplied null. Present arrays follow ordinary snapshot rules.
- `main` accepts one `string[]?` as well as the existing `string[]` or no
  parameter. Startup wraps the validated argument array as present, even when
  empty, and never selects an optional main parameter's omission fallback.
  Ordinary calls to main retain ordinary call/default rules.
- Arrays capture all known dimensions at initialization, or at first presence
  when initialized null. Runtime shape constraints live separately from value
  presence; null never resets them. Each call has independent parameter shape
  storage. Present values still enforce explicit extents, including discarded
  results; absent values perform no payload/shape/copy operation.
- Null tests work for whole arrays. Array-to-array equality remains unsupported.
  Nullable string equality uses ordinary Unicode string content equality when
  both present and presence comparison otherwise.
- Print/interpolation and recursive array text render absent values/elements as
  `null`. Explicit nullable `::str`, indexing and buffered value `::size` still
  require a presence proof or non-null fallback. `::type` retains declared
  nullable identity. Foreach accepts nullable elements with an explicit receiving
  type, retaining independent snapshots and normal nullable-binding rules.

Maintained executable example: [NullableBuffers.co](../../../../examples/NullableBuffers.co).

## Implementation ownership and invariants

- `TypeResolver` resolves nullable scalar/string elements, constructs rectangular
  array descriptors in existing dimension order, then wraps the complete array
  for a trailing marker. Earlier array markers compose the implemented nullable rows.
- `CNullable` delegates compatible array shape acceptance/inference/merging to
  its payload without allowing typed-array element layout conversions.
- `EmitNullable` presence branches dominate payload transformations. Value flags
  are i1; stored flags are bytes. `EmitArray` converts nullable elements on loads
  and stores, including bool bytes and half payloads. Never memcpy an LLVM value
  carrier into storage with a different presence representation.
- `EmitStringArena.copyValue` recursively copies only present payloads, including
  nullable strings inside arrays. Return copying finishes before frame release.
  `EmitExpression` recursively snapshots nullable arrays on arguments, bindings,
  branches, block/results and assignments. Nullable wrappers conservatively copy;
  they do not acquire a fresh-array transfer proof merely by being unwrapped.
- `EmitBindings` retains separate nullable array shape storage. First-present
  assignments initialize it; later present assignments validate/merge dimensions;
  absent assignments leave it intact. Shape storage is initialized at the actual
  declaration execution, including repeated loop declarations.
- `ProviderResult.NullableText` is an internal array-element formatting recipe.
  It is not a public nullable str provider. `EmitArrayText` decodes stored
  nullable elements before using it; both sizing/filling passes branch on presence.
- `ProgramAnalyzer`/`EmitEntryPoint` admit and wrap nullable argument arrays.
  Buffer-returning functions retain the existing caller arena and optional
  omission flags. No public native ABI, GC, destruction or error recovery is added.

## Verification

Use `NullableBuffersTest`, all `Nullability*` and `OptionalArguments*` suites,
`ArrayTemporaryCleanupTest`, array shape/copy/text suites, foreach and entry suites.
The new suite executes on Windows x64/x86 and verifies its mixed nullable-buffer
fixture across all nine supported target profiles. This does not claim native
Linux/macOS execution. The cleanup probe counts actual allocations, poisons freed
buffers, checks outgoing string/array survival, and observes zero live allocations
for absent optional buffers. Full-suite results are recorded in
[development history](../../cloth-language/references/development.md#nullable-strings-and-arrays-2026-11-03).
The final `:Compiler:test --offline --console=plain --no-daemon` run passed all
1,000 tests across 74 suites with zero failures/errors/skips.
