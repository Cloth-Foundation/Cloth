# Operator declarations and resolution

Stage 2 of the [Object and operator plan](../../../../todo/object-and-operators-plan.md)
landed on 2026-11-06. It integrates ordinary operators with the existing
class and struct call path.
The [operator design](object-protocol.md) owns the broader source contract.

```cloth
typedef<class> Player (def<int> points) {
    operator +(int bonus) = self.points + bonus
    operator -(int total, self) -> int { return total - self.points }
}
```

## Supported slice

- Class and struct members parse operator declarations with expression or
  block bodies. Ordinary binary declarations place `self` on the left by
  default. A trailing `self` places it on the right. Operators are available
  through their enclosing type. Ordinary method lookup uses the separate
  method namespace.
- Unary `+`, `-`, and `!`, binary `+`, `-`, `*`, `/`, `%`, `**`, and the
  ordering operators `<`, `<=`, `>`, `>=` resolve and execute. Ordering and
  `!` return `bool`. Other operators can infer one result from an expression
  body or declare it with `-> Type` after the parameters. A block body for an
  operator with a variable result type requires that annotation. Recursive
  result inference also requires an annotation to break the cycle.
- Candidate lookup considers both operand types in source order, including
  the statically visible base chain. The nearest declaration supplies each
  inherited signature. Exact operand types rank first, compatible typed
  operands second, and contextual matches last. Within a tier, a signature
  wins on specificity when it is at least as specific for every operand and
  strictly more specific for one. Equal best candidates report one ambiguity.
  Built-in typing handles expressions without a user-defined candidate set.
- The selected result type supplies context to enclosing expressions. For
  example, when an operator takes `int<64>` and returns `int`, a comparison
  against a literal sees the operator's `int` result. Evaluation follows the
  parsed expression tree. The emitter evaluates operands once, left to right,
  snapshots buffered values, and uses the ordinary callable ABI. Struct
  receivers use independent copies, and class receivers use checked identities.
- The parser records `operator ::str()`, `operator ===(Object other)`,
  `operator is(Object other)`, and `operator<int<auto>> +()`. It retains the
  contextual `auto` width token inside the selector and its body. Later records
  define the execution contracts for these forms.
  [Stage 3](class-equality.md) integrates class equality and identity.
  [Stage 4](class-formatting.md) integrates the class `operator ::str()`.
  [Stage 5](value-operators.md) adds explicit struct equality, nominal enum
  case equality, and enum operator members.
  [Stage 6](operand-contexts.md) adds eager binary contexts and value-target
  `is`, and records the remaining operator family contracts.

## Implementation and diagnostics

- [FunctionParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/FunctionParser.kt)
  parses operator members for both type parsers.
  [OperatorRules](../../../../Compiler/src/main/kotlin/cloth/semantic/OperatorRules.kt)
  checks the supported declaration shapes. C3093 reports invalid operator
  declarations and staged forms. Duplicate ordinary operator declarations use
  C3083 and distinguish the receiver position.
- [CallAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/CallAnalyzer.kt)
  probes operands and candidates in separate semantic facts, commits the
  selected call, and reports C3094 for a missing or ambiguous match.
  [EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitFunctions.kt)
  writes the receiver and argument slots while preserving source evaluation
  order. Lifetime checks treat the selected operator as a call, and the
  nullable and temporary analyses account for its potential effects. Default
  expressions apply the existing read-only receiver rule to operator calls.

## Verification

[OperatorDeclarationsTest](../../../../Compiler/src/test/kotlin/cloth/OperatorDeclarationsTest.kt)
covers declaration forms, type inference, width and ambiguity selection,
inherited lookup, receiver evaluation order, buffered value snapshots,
liveness, nullable results, and read-only defaults. The final run passed
450 tests across 41 suites with zero failures, errors, or skips. The operator
fixture executed on Windows x64 and x86 and verified IR and object emission
for all nine supported profiles.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.OperatorDeclarationsTest --tests 'cloth.Class*Test' --tests 'cloth.Struct*Test' --tests 'cloth.Function*Test' --tests 'cloth.Nullable*Test' --tests 'cloth.Nullability*Test' --tests 'cloth.OptionalArguments*Test' --tests cloth.CallResolutionTest --tests 'cloth.*ParsingTest' --tests 'cloth.*Lexer*Test' --tests cloth.TypeInfoTest --tests cloth.ArrayTemporaryCleanupTest --tests cloth.TargetLayoutTest --offline --console=plain --no-daemon
```
