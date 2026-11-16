# FLOW-2 Stage 2 stored range execution

Cloth executes stored numeric ranges with the ordinary value rules. Range
creation evaluates and validates the start, then evaluates and validates the
end. A dynamic non-finite endpoint fails at creation, including for unused or
discarded results. Bindings, arguments, returns, fields, and arrays can hold
independent copies. The [confirmed range contract](semantics.md#stored-numeric-ranges-flow-2-design)
defines the saved bounds and inclusion rule.

```cloth
fn<range<T>> inclusive<T: Number>(T start, T end) = start..=end

fn main() {
    def bounds = inclusive<int<8>>(0, 10)
    println(10 == bounds)
    println(bounds == 0..=10)
    println(bounds == 0..11)
}
```

This prints `true`, `true`, and `false`. Membership uses the exact numeric
endpoint type in either operand order. A typed range supplies that context to
a peer range literal. Already-typed endpoints keep their type. Equality of
ranges compares both saved endpoints and the inclusion rule, and `!=` negates
that result. Ordinary nullable equality and array element equality use the
same range comparison when both payloads are present. Numeric membership
requires a present range and numeric operand. Stage 3 adds traversal through
`for (def i <- bounds)`.

## Compiler ownership

- [CRange](../../../../Compiler/src/main/kotlin/cloth/data/CRange.kt) lays out
  two inline endpoints and a byte for inclusion. Wide endpoints store their
  limb bytes inline. Its internal ABI transports a pointer to a value snapshot.
  [EmitStoredValue](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitStoredValue.kt)
  copies the bytes before another operand can modify their original storage.
  [EmitStringArena](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitStringArena.kt)
  copies escaping values into the caller's result arena. Arrays move or copy
  range elements as inline bytes.
- [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)
  distinguishes range equality from numeric membership. It records the range
  operand for membership and checks both operands with the exact endpoint type.
  [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
  evaluates operands once in written order. Inline numeric membership still
  consumes checked endpoints directly.
- [EmitRangeValues](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitRangeValues.kt)
  shares finite checks and numeric predicates with inline ranges. Range
  comparisons use numeric equality, including equality of signed floating
  zeros. Wide comparisons read limbs from the captured range snapshot.
- [ArrayTemporaryAnalysis](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/ArrayTemporaryAnalysis.kt)
  tracks indirect inline snapshots as temporary storage. Early release uses
  the existing proof that a result or write cannot retain the scratch bytes.
  C4039 is retired. Invalid endpoints and incompatible comparisons still
  report the first semantic error before emission.

## Verification on 2026-11-14

- The focused compiler run passed 357 tests, including
  [StoredRangeEmissionTest](../../../../Compiler/src/test/kotlin/cloth/StoredRangeEmissionTest.kt),
  the inline range and loop suites, equality, nullable storage, ordered results,
  and generic bounds and inference. Stored range comparison and copy fixtures
  ran on Windows x64 and x86. The transport fixture verified LLVM IR and emitted
  objects for all nine ABI profiles.
- [ArrayTemporaryCleanupTest](../../../../Compiler/src/test/kotlin/cloth/ArrayTemporaryCleanupTest.kt)
  verified that 4,000 temporary full-width range results were reclaimed across
  2,000 iterations, with a saved range still valid and no outstanding arena
  allocations after return.
- The example above ran through `:Compiler:runExample` and printed the three
  expected values. The full `:Compiler:test` run passed 2,343 of 2,345 tests.
  The two existing `FileIOTest` fixtures that import `cloth.io.File` failed in
  source loading because the pinned library supplies `cloth.io.fs.File`.
