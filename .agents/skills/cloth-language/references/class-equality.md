# Class equality and instance identity

Stage 3 of the [Object and operator plan](../../../../todo/object-and-operators-plan.md)
landed on 2026-11-06 and integrates class `==`, `!=`, and `===`. The
[operator design](object-protocol.md)
owns the broader source contract.

```cloth
typedef<class> Account (def<int> id) {
    operator ==(Object other) {
        if (other is Account) return self.id == other.id
        return false
    }
}
```

## Supported slice

- `operator ==(Object other)` and `operator !=(Object other)` take one
  non-nullable `Object` operand and return `bool`. `self` is on the left.
  Declaration lookup inherits the nearest body for each operation.
- For two present operands, logical comparison checks both identities for
  liveness. Two different runtime classes compare unequal. For `==`, two
  aliases of the same instance compare equal, and distinct instances of the
  same runtime class can invoke the closest equality override. Dispatch works
  through `Object` and base bindings. During construction, dispatch uses the
  initialized class level, as for [class type checks](class-inheritance.md).
- An explicit `!=` body for the matching runtime class takes precedence,
  including for aliases of the same instance. Otherwise `!=` negates `==`.
- `===` defaults to comparing the carried instance identities, including
  their generations. A user-defined `===` has a non-nullable class operand,
  returns `bool`, and can place `self` on either side. Selection follows the
  ordinary static rules for both operand types. A matched body receives two
  live values. When no body matches, comparison uses the raw identities.
- Explicit upcasts preserve identities. Use
  `(a -> Object) === (b -> Object)` to select raw identity even when a concrete
  class has an overload. Use `Object?` as the cast target for nullable values.
  Raw identity is valid for stale aliases. Alias invalidation and destruction
  always use the carried identities.
- Every comparison captures both operands once, left to right. Two absent
  operands compare equal. One absent operand compares unequal to a present
  operand. These cases inspect presence before any payload or liveness check.
  A literal `null` test uses the existing typed-nullable rule and can prove
  presence for `===` as well as `==` and `!=`.

## Implementation ownership

- [OperatorRules](../../../../Compiler/src/main/kotlin/cloth/semantic/OperatorRules.kt)
  checks class declaration forms. [FunctionAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt)
  enforces the class signatures and selects a static identity overload.
  [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)
  records class comparisons independently of ordinary operators and primitive
  nullable equality.
- [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
  shares presence guards with nullable equality and separates raw identity
  from checked accesses. [EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
  emits private dispatch functions for logical comparisons. The functions use
  checked class tags and ordinary callable signatures. The lifetime pass
  diagnoses provably stale checked operands and leaves conditional or escaped
  identities to the runtime checks.

## Verification

[ClassEqualityTest](../../../../Compiler/src/test/kotlin/cloth/ClassEqualityTest.kt)
covers logical dispatch, identity overloads, nullability, deletion, ordering,
and target emission. The focused run passed 425 tests across 40 suites with
zero failures, errors, or skips. Both comparison fixtures ran natively on
Windows x64 and x86 and verified IR and object emission across all nine
supported profiles.

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Class*Test' --tests cloth.OperatorDeclarationsTest --tests 'cloth.*Null*Test' --tests 'cloth.Struct*Test' --tests 'cloth.Function*Test' --tests cloth.CallResolutionTest --tests 'cloth.OptionalArguments*Test' --tests cloth.RangeMembershipTest --tests cloth.TypeInfoTest --tests cloth.ArrayTemporaryCleanupTest --tests cloth.TargetLayoutTest --offline --console=plain --no-daemon
```
