---
name: cloth-output
description: Design, explain, or extend Cloth print and println, recursive array contents for output/interpolation/str, built-in registration and lookup, printable-value adaptation, or length-aware native UTF-8 output.
---

# Cloth output and built-in functions

[Arbitrary integer Stage 5](../cloth-numerics/references/arbitrary-width-output.md)
integrates exact decimal text for signed/unsigned widths 1..65536 across print,
interpolation, `::str`, nullable values and recursive arrays. Wide values use a
shared bounded base-1e9 helper with checked width-derived capacity; <=64-bit
native fast paths and float formatting remain unchanged. C4031 is retired.
The record owns capacity, input preservation, scratch lifetimes and target evidence.
The [final integer record](../cloth-numerics/references/arbitrary-width-integers.md)
adds the maintained example and production measurements, including exact cap
output on Windows x64/x86 and Linux x64 GNU. Other targets retain object/assembly
evidence with explicit native-runner limits.

Read [confirmed built-in semantics](references/semantics.md). For interpolation
use [strings](../cloth-strings/SKILL.md); for normal executable completion use
[program entry](../cloth-program-entry/SKILL.md).

## Development rules and current boundaries

- `print(value)`, `println(value)`, and `println()` are unimported void functions.
  `print()` and multi-value calls are arity errors. Interpolation composes text.
- Preserve lexical shadowing: bindings shadow built-ins even in call position;
  a source function group shadows the whole built-in group. Mismatched source
  overloads do not fall back to the built-in. Names are ordinary case-sensitive
  identifiers; source-level printf is not a provided built-in.
- Add global built-ins through named typed registrations in `BuiltinFunctions` with
  argument contracts and emission owned by the definition. Keep lookup centralized
  and reusable definitions free of per-emission LLVM handles.
- Primitive value methods use the [attachment registry](../cloth-strings/references/member-calls.md)
  with a hidden typed receiver and the same bound built-in contracts. They do not
  become lexical global built-ins or receive printable argument adaptation.
- Printable adaptation uses supported value-receiver `str` providers without
  changing ordinary expression typing or introducing general implicit conversion.
  Preserve canonical metadata text. Arrays recursively expose `str` when their
  elements do. `implicitText` binds the conversion for a print argument,
  interpolation part or array element. Interpolation records its recipe during
  typing and lowering consumes it. Explicit `::str` checks its receiver through
  ordinary provider resolution. The
  [class formatting contract](../cloth-language/references/class-formatting.md)
  covers checked `operator ::str()` dispatch and once-only array formatting.
  [Enum formatting](../cloth-language/references/value-operators.md#enum-values-and-formatting)
  uses the same output routes and array capture.
  `::addr` remains outside current compiler support.
- Evaluate an argument once before conversion/output. If it exits its enclosing
  function, emit no output or added newline on that path. General multi-result
  expansion is not a print call convention.
- Native output is length-aware UTF-8: NULs remain data and percent signs remain
  text. Newlines follow native text-stream conventions. Detected write failures
  use the runtime error path; no rollback, atomic-write or explicit flush guarantee
  is promised. Console/code-page behavior and delayed buffered flush failures
  retain the limits recorded in the contract.

## Source and verification

Inspect [BuiltinFunctions](../../../Compiler/src/main/kotlin/cloth/builtin/BuiltinFunctions.kt),
[BuiltinFunction](../../../Compiler/src/main/kotlin/cloth/builtin/BuiltinFunction.kt),
[PrintFunction](../../../Compiler/src/main/kotlin/cloth/builtin/PrintFunction.kt),
[BuiltinEmissionContext](../../../Compiler/src/main/kotlin/cloth/emitter/types/BuiltinEmissionContext.kt),
and [EmitOutput](../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitOutput.kt).
Example: [Builtins.co](../../../examples/Builtins.co).

Use `BuiltinFunctionsTest`, affected `CallResolutionTest` cases, and native output
tests. Verify lookup identity, shadowing, arity, void contracts, exact UTF-8/NUL
bytes and interrupted argument behavior. The
[built-in milestone](../cloth-language/references/development.md#built-in-function-registry-and-explicit-output-2026-10-31)
records the integrated behavior and evidence; the current suite must be rerun
before claiming new validation.

## Recursive array text

Read [the array text contract](references/semantics.md#recursive-array-text).
`CArray.providers` in `PrimitiveTypes.kt` owns capability registration, and
`ProviderResult.ArrayText` retains the element recipe. `ProviderEmissionContext`
delegates to [EmitArrayText](../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitArrayText.kt)
through `EmitString`. Keep the traversal out of print built-ins and reusable type
descriptors. Interpolation resolves the same typed value-receiver providers.

Nullable whole arrays, rows and scalar/string elements render absence as `null`.
`ProviderResult.NullableText` handles implicit conversion. `EmitArrayText`
decodes stored carriers and uses the same presence branch in both passes.
Explicit nullable `::str` requires a presence proof. See the
[nullable buffer record](../cloth-nullability/references/buffers.md) and
[row implementation](../cloth-arrays/references/nullable-rows.md). Nested nullable
rows branch on presence while writing directly into the shared text buffer.
[Growable arrays Stage 6](../cloth-arrays/references/growable-arrays-stage-6.md)
verifies recursive output, interpolation and `::str` after whole-row growth,
insertion, removal and clear. Traverse the current live prefix only; retained
shape witnesses and spare capacity contribute no formatted elements. Returned
and removed rows keep independent text buffers after source destruction.
[Printable recipes](../../../Compiler/src/main/kotlin/cloth/data/ImplicitText.kt) ·
[Integration record](../cloth-language/references/development.md#shared-value-transfer-and-text-recipes-2026-11-06)

The emitter measures checked target-sized bytes, then fills one result buffer.
Nested arrays write directly into it; do not concatenate growing prefixes or
allocate an intermediate string for every row. Both passes traverse an already
evaluated value and reuse side-effect-free leaf conversions. Per-leaf stack
checkpoints precede stored-value decoding and reclaim snapshots plus numeric/
character scratch only after consumption; the output
buffer and all source storage predate those checkpoints. Ordinary buffered
returns copy the result before its frame exits. No raw-address access is added.

Example: [ArrayPrinting.co](../../../examples/ArrayPrinting.co). Use `ArrayTextTest`,
`StringInterpolationTest`, `BufferProvidersTest`, `BuiltinFunctionsTest` and
`BufferCountsTest`. Cover empty/nested/dynamic arrays, all supported leaf kinds,
raw Unicode/NUL text, once-only effects, early exits, snapshots/returns, large
inputs, checked overflow and target widths. The
[integration record](../cloth-language/references/development.md#recursive-array-text-2026-11-02)
records observed verification and target scope.
