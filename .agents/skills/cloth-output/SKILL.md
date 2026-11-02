---
name: cloth-output
description: Design, explain, or extend Cloth print and println, recursive array contents for output/interpolation/str, built-in registration and lookup, printable-value adaptation, or length-aware native UTF-8 output.
---

# Cloth output and built-in functions

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
- Add built-ins through named typed registrations in `BuiltinFunctions` with
  argument contracts and emission owned by the definition. Keep lookup centralized
  and reusable definitions free of per-emission LLVM handles.
- Printable adaptation uses supported value-receiver `str` providers without
  changing ordinary expression typing or introducing general implicit conversion.
  Preserve canonical metadata text. Arrays recursively expose `str` when their
  elements do; print, interpolation and `::str` share that recipe. Class value
  formatting and `::addr` remain deferred.
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
and [EmitOutput](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitOutput.kt).
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
delegates to [EmitArrayText](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitArrayText.kt)
through `EmitString`. Keep the traversal out of print built-ins and reusable type
descriptors. Interpolation resolves the same typed value-receiver providers.

The emitter measures checked target-sized bytes, then fills one result buffer.
Nested arrays write directly into it; do not concatenate growing prefixes or
allocate an intermediate string for every row. Both passes traverse an already
evaluated value and reuse side-effect-free leaf conversions. Per-leaf stack
checkpoints reclaim numeric/character scratch only after consumption; the output
buffer and all source storage predate those checkpoints. Ordinary buffered
returns copy the result before its frame exits. No raw-address access is added.

Example: [ArrayPrinting.co](../../../examples/ArrayPrinting.co). Use `ArrayTextTest`,
`StringInterpolationTest`, `BufferProvidersTest`, `BuiltinFunctionsTest` and
`BufferCountsTest`. Cover empty/nested/dynamic arrays, all supported leaf kinds,
raw Unicode/NUL text, once-only effects, early exits, snapshots/returns, large
inputs, checked overflow and target widths. The
[integration record](../cloth-language/references/development.md#recursive-array-text-2026-11-02)
records observed verification and target scope.
