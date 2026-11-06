# Operand contexts and value-target checks

[Stage 6](../../../../todo/object-and-operators-plan.md#stage-6--extend-operator-families-and-the-is-operand-forms)
extends the [ordinary operator implementation](operator-declarations.md).

```cloth
typedef<class> Player (pub def<int> points, def<string> name) {
    operator<int<auto>> +() = self.points -> int<auto>
    operator<string> +() = self.name

    operator is(Object other) {
        if (other is Player) return self.points == other.points
        return false
    }
}
```

## Resolution and evaluation

- A context declaration `operator<T> +()` uses `T` to match the other
  operand's original static type. Its body receives only `self` and can
  contribute a different type. An expression body can infer its result.
  A block body declares it with `-> Type`.
- Look for a full overload first. A best-match tie fails compilation before
  trying contexts. With no full match, choose a context independently for
  each operand, using the original pair. The resulting pair uses an ordinary
  built-in operation or full overload. The resulting pair gets one resolution
  pass and cannot apply more contexts.
- Select the nearest declaration for an inherited selector. Rank an exact
  selector first, a captured width next, and a compatible selector
  last. In the compatible tier, a more specific selector wins. Ties are
  compile-time errors. An untyped integer literal has its ordinary standalone
  `int` type during context matching. Cast it explicitly to select another
  width. Ordinary full overloads retain their existing contextual literal rules.
- Evaluate each operand once in source order. Execute its chosen context
  immediately and preserve the contributed value before evaluating the next
  operand. Apply the resolved operation after capturing both values.
  Flow analysis can invalidate aliased nullable facts before the right operand
  when a left context might run and a full overload cannot yet be established.
- `int<auto>` matches a signed fixed-width `int`, and `float<auto>` matches
  `float<16>`, `float<32>`, or `float<64>`. The compiler checks and emits
  a separate body for each captured width it uses. The body chooses its
  result type, and the resulting pair follows ordinary numeric rules.
  `operator<float<auto>> +() = health -> float` contributes a `float<32>`
  value. Write `health -> float<auto>` to contribute the peer's float width.
  Unsigned integers, `isize`, and `usize` match explicit selectors for their
  own types.

## Forms of `is`

- `a is Player` is the existing built-in type check. A known type name in
  that position selects a type check and retains runtime-backed narrowing.
- `a is b` uses a value overload when the bare name `b` resolves to a
  value binding without a same-named type. A call, field access, or
  parenthesized value can also supply the right operand. Use parentheses
  to select a value that shares a name with a type.
- `operator is(Object other)` is an ordinary class operator returning
  `bool`. Its explicit operand can be another non-nullable class type.
  Either operand can supply the overload using the ordinary receiver position
  rules. A missing overload or a best-match tie fails compilation. The result
  of a value-target check supplies no narrowing proof.

## Operator families

| Family | Current declaration contract |
| --- | --- |
| Unary `+`, `-`, `!` | Full overloads with implicit `self`. |
| Binary `+`, `-`, `*`, `/`, `%`, `**`, `<`, `<=`, `>`, `>=` | Full overloads and operand contexts. |
| `==`, `!=`, `===` | Existing [class comparison](class-equality.md) and [value equality](value-operators.md) rules. Contexts for these operations need a presence and dispatch contract. |
| `::str()` | [Class](class-formatting.md) and [enum](value-operators.md#enum-values-and-formatting) formatters. |
| Value-target `is` | Full class overloads with ordinary receiver positions. |
| Type and state targets of `is` | Type targets use built-in checks. State targets depend on the class state runtime. User-defined narrowing requires a verified proof contract. |
| `&&`, `\|\|`, `?:` | Built-in conditional evaluation. Overloads need rules for when each operand and body run. |
| Assignment, compound assignment, postfix updates, indexing | Built-in stored-place behavior. Overloads need a contract for writable operands, receiver capture, and results. |
| Ranges, call syntax, conversions, other providers | Separate syntax or evaluation contracts remain to be designed before adding overloads. |

## Implementation ownership

- [CallAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/CallAnalyzer.kt)
  selects full overloads and context selectors. It checks the application
  with typed placeholders and shares the ordinary callable and binary rules.
- [FunctionSemanticModel](../../../../Compiler/src/main/kotlin/cloth/semantic/FunctionSemanticModel.kt)
  keeps width-specific body facts. [TypeResolver](../../../../Compiler/src/main/kotlin/cloth/semantic/TypeResolver.kt)
  resolves the captured width in body annotations and casts.
- [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
  captures each reached operand before the next expression and consumes the
  typed application through ordinary lowering. [EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitFunctions.kt)
  uses the ordinary callable ABI for contexts and value-target overloads.
- [DeclarationAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/DeclarationAnalyzer.kt)
  distinguishes a bare value target from a type name. [NullFlowAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/NullFlowAnalysis.kt)
  preserves the built-in narrowing rule and accounts for calls during context
  evaluation.

## Verification

[OperandContextTest](../../../../Compiler/src/test/kotlin/cloth/OperandContextTest.kt)
exercises full-overload priority, context selection on both sides, call order,
width specialization, built-in and overloaded result operations, and both
forms of `is`.
The focused run passed 26 tests in three suites, including the nullable-flow
regression. Both fixtures ran natively on Windows x64 and x86. LLVM IR and
object emission were verified for all nine supported profiles.

On 2026-11-06, the full compiler run passed 1,426 tests in 126 suites with
zero failures, errors, or skips. Both runs used a fresh Compiler build
directory and an isolated Gradle project cache after a class-loading failure
in the shared build directory. The Gradle task and compiler flags were
`:Compiler:test '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --offline --no-build-cache --console=plain --no-daemon`.

The float-width follow-up passed 24 tests in the operand context, operator
declaration, float attachment, and integer-to-float suites. Its fixture ran
on Windows x64 and x86, with LLVM IR and object emission for all nine profiles.
The compiler also built and ran `examples/NewTest.co` with exit status zero.
