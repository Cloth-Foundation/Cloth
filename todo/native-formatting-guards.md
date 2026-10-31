# Native formatting: interpolation guard

Status: complete. Recorded 2026-10-30 after the chunk 8 review; the guard is now implemented.

Native type printing and conversion to text are still being designed. A
`toString()` method is one possibility mentioned by the user, not an accepted
language contract. This TODO tracks missing compiler guards while that design
remains pending; it does not require implementing array formatting now.

The user's subsequent requirement is that, if introduced, `toString()` must be
available on every data type without overriding, and on future classes with
overridable behavior. That does not settle formatting output or authorize
implementing the method ahead of the member and class foundations. The guard
below is independent of that future API.

## Resolved defect

Array-valued interpolation holes previously passed semantic checking and reached
an internal code-generation error. This included array literals, bindings, nested
arrays and array-returning calls.

```cloth
fn main() {
    def values = [1, 2]
    def message = #"values: {values}"
}
```

Previous result: C9000, `Array printing is not implemented`. Both this example
and the minimal `fn main() { #"{[1]}" }` now fail during semantic analysis with
C3040, `String interpolation does not support values of type 'int[2]' yet`
(or `int[1]` for the minimal example), labeling the unsupported hole expression.

Relevant paths:

- [ExpressionTyping.kt](../Compiler/src/main/kotlin/cloth/semantic/ExpressionTyping.kt):
  the `InterpolatedString` branch permits only supported primitive/string values,
  while preserving non-continuing expressions that return from the function.
- [EmitString.kt](../Compiler/src/main/kotlin/cloth/emitter/types/EmitString.kt):
  supported interpolation continues to use the existing formatting implementation.
- [NativeCABI.kt](../Compiler/src/main/kotlin/cloth/emitter/NativeCABI.kt):
  its array invariant remains a defensive check; source interpolation no longer
  reaches it with an array value.

## Completed checks

- [x] Reject unsupported interpolation types with one source-located semantic
  diagnostic before toolchain discovery, output creation or LLVM emission.
- [x] Cover direct literals, bindings, nested arrays and array-returning calls.
- [x] Check unsupported holes even in skipped branches, following existing
  name/type checking rules.
- [x] Preserve supported primitive/string interpolation, once-only evaluation
  order and returns nested inside interpolation expressions.

`StringInterpolationTest` covers the diagnostic, exact hole spans, fail-fast
behavior, skipped branches, array-returning calls, overload arguments, and
rejection through executable/object/IR/snippet paths before output creation.
A CLI check verifies one rendered C3040 rather than C9000. Native regression
coverage preserves indexed primitive/string interpolation and nested returns.
The focused interpolation tests and full compiler suite passed: 350 tests,
zero failures, errors or skips. Native execution was verified on Windows.

## Deferred language design

Revisit the temporary restriction when the native formatting contract is
designed. Array text syntax and a `toString()` API remain separate future work;
neither is required to complete this compiler guard.
