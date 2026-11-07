# Equality operand contexts

Integrated 2026-11-07 while verifying Bootstrap for imports/Shuttle Stage 7.
The user confirmed that an enum compared with a string contributes its name as
text through the existing operand-context form. Bootstrap's declaration was
correctly written; equality had been excluded from the implementation.

```cloth
typedef<enum> TokenType {
    Identifier, Number;
    operator<string> ==() = self::name.toLowerCase()
}

fn<bool> matches(TokenType kind) = kind == "identifier"
```

The selector string matches the other operand's original static type. The
context body returns a contributed string; the resulting string/string equality
returns bool. `self::name` is the case name, such as Identifier. It is distinct
from the enum type's spelling TokenType. The lowercase behavior here is chosen
by the body, and does not implicitly lowercase the other operand or change enum
formatting. Reversing the operand order uses the same context selection.

## Integrated behavior and limits

- Existing same-type nominal comparisons and class/class dispatch take priority.
  Mixed present values can use one context per original operand, followed by one
  ordinary comparison of their contributed values. This includes enum/string,
  struct/string, class/string and contexts on both distinct nominal operands.
- Selectors retain exact, captured-width and compatible ranking from the
  [operand-context contract](operand-contexts.md). Receiver restrictions, original
  defining-file lookup and read-only default rules are shared with existing contexts.
- For !=, an applicable != context takes priority. If none matches the peer,
  an applicable == context can contribute the value, and the resulting pair uses
  ordinary !=. An equality contribution is not itself a boolean comparison to negate.
- Evaluate each original operand once, left to right; run its context immediately
  and snapshot the contributed value before evaluating the next operand. Buffered
  results use the ordinary callable/value-transfer machinery. Projected values
  cannot trigger another context pass.
- Null presence comparisons retain their existing paths and invoke no context.
  An unproven nullable enum cannot be compared to a string through its payload
  context. An existing presence proof permits the ordinary present-value path.
  Context calls invalidate aliased presence facts before later operands and after
  the comparison. Class identity === has no operand contexts.
- The supported extension is mixed present-value equality. This does not define
  a new nullable dispatch policy, replace class/class comparisons, add struct
  structural equality or introduce implicit string casts outside selected contexts.

## Ownership and verification

[OperatorRules](../../../../Compiler/src/main/kotlin/cloth/semantic/OperatorRules.kt)
admits == and != selectors. [FunctionAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt)
keeps zero-parameter context signatures separate from one-operand full comparison
signatures. [OperatorResolution](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/OperatorResolution.kt)
reuses the existing trial, selector, projection and single-application path.
[BodyTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/BodyTyping.kt)
and [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)
connect it before rejecting mixed nominal/scalar operands.
[NullFlowAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/NullFlowAnalysis.kt)
accounts for equality contexts, including the != fallback, in call/effect ordering.
Emission uses the existing resolved context plan and callable ABI.

[EqualityOperandContextTest](../../../../Compiler/src/test/kotlin/cloth/EqualityOperandContextTest.kt)
adds six cases covering both operand positions, inequality priority/fallback,
captured widths, full-comparison priority, present/absent nullable values,
buffered results, both-sided call order, readonly defaults, rejected chaining and
alias invalidation before/after comparisons. Its fixtures execute on Windows
x64/x86 and verify IR/objects for all nine existing profiles. The focused run
with OperandContextTest passes 14 tests. The
[nested Shuttle example](../../../../examples/Shuttle/README.md) exercises this
behavior across imported enum identities and also runs on GNU Linux x64 under WSL.
Full-suite evidence is recorded in [imports Stage 7](../../cloth-imports/references/stage-7.md).
