# FLOW-2 Stage 1 stored range typing

Cloth checks both range expressions in ordinary value positions and recognizes
the built-in type constructor `range<T>`. Inclusive and exclusive expressions
over the same endpoint type share one type. The delimiter is retained in the
syntax tree for lowering. The [confirmed contract](semantics.md#stored-numeric-ranges-flow-2-design)
owns creation, comparisons, storage and traversal semantics.

```cloth
fn<range<T>> span<T: Number>(T start, T end) = start..end
fn<range<int<8>>> keep(range<int<8>> value) = value

fn main() {
    def<range<int<8>>> limits = -128..=127
    def small = keep(0..10)
    def wide = span<int<64>>(1, 5)
}
```

An expected `range<T>` supplies `T` to each endpoint. Without a receiving type,
a typed endpoint supplies context before literals use their ordinary defaults.
Already-typed endpoints must agree exactly. A `T: Number` endpoint preserves
its symbolic identity until specialization. Inside such a generic body, use
an explicit cast for a literal endpoint, such as `start..(0 -> T)`.

`range<bool>`, `range<int?>`, a missing endpoint type and an unconstrained
`range<T>` fail during type resolution. Endpoint mismatches and known
non-finite float endpoints use C3139. Integer and float literal overflow use
the ordinary diagnostics. A range variable or literal can be typed inside a
field or array. At this milestone native emission reported C4039 for stored
ranges. [Stage 2](stored-ranges-stage-2.md) replaces that diagnostic with native
creation, value storage, and comparisons. Stage 3 adds traversal.

## Compiler ownership

- [ExpressionParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ExpressionParser.kt)
  retains the existing range precedence and the loop parser still consumes an
  outer inline range delimiter. The type parser uses the existing type
  application grammar for `range<T>`.
- [TypeResolver](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/TypeResolver.kt)
  checks the single endpoint argument using the same numeric capability as
  `Number`. [CRange](../../../../Compiler/src/main/kotlin/cloth/data/CRange.kt)
  carries exact endpoint identity through signatures and specialization.
  Signature keys compare generic parameters by position before the bound-aware
  pass validates the endpoint. Inference matches the endpoint wrapper through
  the ordinary supplied-argument resolver.
- [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)
  discovers literal context and checks stored endpoints. Inline membership
  keeps its separate three-operand context. It records an empty result for
  the inline range syntax, so native lowering can distinguish that use from
  a stored range expression.
- Endpoint errors precede native emission. Stage 1 also verified this ordering
  through the temporary C4039 diagnostic.

## Verification on 2026-11-14

- The focused `:Compiler:test` run passed all 192 tests, including six in
  [StoredRangeTypingTest](../../../../Compiler/src/test/kotlin/cloth/StoredRangeTypingTest.kt).
  Those tests cover exact endpoint types on 32-bit and 64-bit layouts, contextual
  literals in bindings and calls, `Number` specialization and inference, and
  the emission diagnostic across IR, object, and executable entry points.
- `RangeMembershipTest` and `ForIntegrationTest` passed native Windows x64
  and x86 execution for inline ranges. They verified IR and emitted objects
  for all nine ABI profiles.
- The broader compiler run passed 2,336 of 2,338 tests. Two `FileIOTest` fixtures
  use the former `cloth.io.File` import and fail in source loading. The pinned
  library supplies `cloth.io.fs.File`.
