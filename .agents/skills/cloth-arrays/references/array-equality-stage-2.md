# ARRAY-1 Stage 2 array equality

Read the [array equality contract](semantics.md#array-equality) for exact
element compatibility, literal context, presence, and evaluation order.

```cloth
fn main() {
    def<int[2]> fixed = [1, 2]
    def<int[]> growing = [1, 2]
    println(fixed == growing)
    growing.append(3)
    println(fixed != growing)
    def<int[0][2]> empty = []
    println(empty == [])
}
```

This prints `true` three times. Comparing `int[]` with `float[]` reports
`C3009`. A struct element type needs ordinary `==` even when the arrays
are empty. Array identity with `===` is outside the equality contract.

## Compiler integration

- [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)
  resolves literal element context and records an
  [ArrayEqualityPlan](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ArrayEqualityPlan.kt).
  Compatibility ignores declared extents at each array level and preserves
  nested nullable positions and the exact leaf type. The checker uses the
  existing nominal equality resolver, including ambiguity and read-only
  default rules. Number and class-backed bounds can supply leaf equality in
  generic bodies, and closed bodies use their concrete element types.
- [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
  captures the two operands in source order using independent copies in a
  comparison arena. Copies preserve current shape witnesses and class
  identities. Presence selects whether to compare payloads. The arena is
  released after the comparison and on jumps out of the expression.
- [EmitArrayEquality](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArrayEquality.kt)
  checks extents and known flags through
  [EmitArray](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArray.kt).
  Target-sized index loops visit live slots in order and short-circuit.
  Nested rows reuse the same traversal. Ordinary scalar and nominal equality
  compares leaf values, with checked class/interface dispatch when reached.
  The per-element stack checkpoint bounds scratch for wide element loads.
- Null-flow and state-effect analysis account for custom element equality.
  The temporary-storage analysis keeps the ordinary effect rules for operand
  expressions and recognizes custom equality as a possible call.

## Verification

On 2026-11-12, 245 focused tests passed in 15 suites with Java 25 on
Windows x64. [ArrayEqualityTest](../../../../Compiler/src/test/kotlin/cloth/ArrayEqualityTest.kt)
exercises the production driver, current and retained shape, typed literals,
nullable arrays and rows, ordinary element equality, left-to-right snapshots,
early exits, and first-error diagnostics. Its generic fixture executed on
Windows x64 and x86 and emitted verified LLVM IR and startup objects for all
nine supported profiles.

The example above ran through `:Compiler:runExample` on Windows x64. It printed
three `true` lines and exited with `PEC: 0`.

Before the final operand-context and diagnostic refinements, the full
`:Compiler:check` passed 2,279 of 2,281 main-suite tests and all 342 dedicated
import and generic cases. The two failures were the same [file I/O import
failures reproduced on the baseline](indexed-updates-stage-1.md#verification).
The final focused run includes `ArrayEqualityTest` and `OperandContextTest`.
