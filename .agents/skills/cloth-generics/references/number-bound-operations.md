# Operations through a `Number` bound

[Bound satisfaction](constraints-stage-2.md) checks each type argument at its
application. `Number` accepts signed and unsigned fixed-width integers, `isize`,
`usize`, and floats at their supported widths. A forwarded parameter proves the
requirement with its own `Number` bound. C3135 labels the first unsatisfied
argument and its bound declaration.

The [numeric bounds example](../../../../docs/generics.md#numeric-bounds) uses
integer and floating-point specializations of the same function.

Each `T` retains its exact identity in the template. Binary arithmetic and
comparisons take two operands of that same parameter type. Arithmetic returns
`T` and comparisons return `bool`. Cast numeric literals explicitly, as in
`2 -> T`. `value + 2` and arithmetic between independent type parameters fail
during declaration checking, including in unused templates. Explicit casts can
convert between two `Number`-bounded parameters or between a bounded parameter
and a concrete numeric type.

Unary signs, postfix updates and compound assignments follow the same numeric
contract. A bare bounded `T` is non-nullable. An explicit `T?` uses ordinary
presence proofs, casts and equality. `value::str`, output and interpolation use
the actual type's formatter. [Confirmed Number design](semantics.md#number-constraint-design)

## Compiler ownership

- [ClosedTypeAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt)
  records the resolved `Number` capability on the exact symbolic parameter.
  The same capability predicate checks bound satisfaction and numeric casts.
- [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)
  checks exact operands before concrete numeric promotion.
  [BodyTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/BodyTyping.kt)
  applies that check to compound writes and recognizes numeric postfix updates.
  [NullFlowAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/NullFlowAnalysis.kt)
  uses the bound's presence guarantee.
- [CTypeParameter](../../../../Compiler/src/main/kotlin/cloth/data/CTypeParameter.kt)
  exposes the scalar text recipe during declaration checking. Each closed view
  checks its own operations and uses the existing numeric and text lowering for
  the substituted type.
- [Lexer.co](../../../../Bootstrap/Frontend/lexer/Lexer.co) specializes
  `CursorPosition<T: Number>` with `int` coordinates. The struct updates its
  `usize` scalar index and its line and column in `advance()`.

[Class and interface bound operations](constraints-stage-3.md) share the
symbolic and closed body views. Generic numeric range contracts are a follow-up.

## Verification

[GenericBoundsTest](../../../../Compiler/src/test/kotlin/cloth/GenericBoundsTest.kt)
covers bound satisfaction and rejection. [GenericCallableTypingTest](../../../../Compiler/src/test/kotlin/cloth/GenericCallableTypingTest.kt)
covers unused templates, exact operands, explicit casts and nullable reception.
[GenericEmissionTest](../../../../Compiler/src/test/kotlin/cloth/GenericEmissionTest.kt)
exercises the numeric operations with concrete narrow, wide and target-sized types.
[Bootstrap lexer verification](../../cloth-bootstrap-lexer/references/port.md#verification)
covers cursor movement through the native lexer and public build entry points.

On 2026-11-11, the native-free generic task passed 108 tests across 11 suites.
The expanded Compiler run passed 317 tests across 39 suites, covering generic
and interface checking, numeric casts and division, assignments, nullability,
and Bootstrap. All three Shuttle publication tests passed. These runs had zero
failures or skips.

Windows x64 and x86 executed the numeric specializations, including `int<129>`
updates, `float<16>` division, and a `usize` to `float<64>` cast. Bootstrap matched
Kotlin on 126 inputs on both profiles. Import publication verified Bootstrap's
executable IR and startup objects across all nine ABI profiles. The guide example
printed `2` and `2.5` through the staged Windows Shuttle command.
