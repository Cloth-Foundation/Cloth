# Struct and enum operators

Stage 5 of the [Object and operator plan](../../../../todo/object-and-operators-plan.md)
integrates explicit struct equality and a nominal enum value model.

```cloth
typedef<struct> Point {
    int x,
    int y;

    operator ==(Point other) = self.x == other.x && self.y == other.y
}

pub typedef<enum> DamageType (pub def<int> number = 1) {
    ATTACK,
    FALL,
    ENVIRONMENT(10.0 -> int),
    UNKNOWN(-1);

    operator ::str() = #"DamageType({self.number})"
}

fn<bool> isFall(DamageType kind) = kind == DamageType.FALL
```

## Equality

- A struct or enum can declare `operator ==(Type other)` or
  `operator !=(Type other)`. The operand has the same non-nullable nominal type
  as `self`, and the result is `bool`. A trailing `self` places the receiver on
  the right. A tie between applicable declarations is a source error.
- An explicit `!=` takes precedence. Otherwise `!=` negates the selected
  `==` or the built-in enum case comparison. Default enum equality compares
  case ordinals within the same enum, so distinct cases with identical
  payloads compare unequal. Struct equality requires a declaration.
- Evaluate operands once, from left to right. Capture struct values, including
  nested buffers, before evaluating the next operand. With two present payloads,
  invoke the selected overload. Two absent values compare equal and one absent
  value compares unequal to a present value. A comparison with literal `null`
  checks presence.

## Enum values and formatting

- A `typedef<enum>` declares one or more named cases. Cases are comma-separated.
  A semicolon separates the cases from operator members. Qualify a case as
  `DamageType.FALL`. The type name is used when there is no value binding of
  that name. A case carries a zero-based ordinal, exposed by an explicit
  `-> int` cast.
- Optional header fields declare a common payload shape. A case supplies
  positional values for the first fields. Omitted fields use their header
  defaults. Supply literal values and explicit casts, including negative
  numeric literals. Payload fields are read-only. Reading an array payload
  supplies an independent value. `pub def<T>` exposes the
  field outside the enum. Within the enum, read fields through `self` or
  their bare names.
- `value::str`, interpolation, `print`, and `println` share
  `operator ::str()`. The declaration returns non-nullable `string`.
  The default is the case name in its declared spelling. Nullable output
  renders absence as `null`. Recursive array text captures each present
  formatter result once in traversal order.
- Secondary enum constructors and ordinary enum methods are outside this
  slice. A case that needs numeric conversion can use an explicit cast in
  its payload argument.

## Implementation ownership

- [EnumParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/EnumParser.kt)
  parses case lists and reuses the operator member parser.
  [NamedTypes](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/NamedTypes.kt)
  registers nominal enum types. [EnumModel](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/EnumModel.kt)
  checks payload forms and builds private readers for case payloads.
- [FunctionAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt)
  checks equality signatures and uses the shared comparison overload ranking.
  [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)
  records the selected comparison and the nullable presence path.
- [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
  snapshots both operands and guards payload comparisons.
  [EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
  uses the ordinary callable ABI for overloaded comparisons and formatter
  results. Enums carry an ordinal in storage and at call boundaries.
- [EmitArrayText](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitArrayText.kt)
  shares its text capture arena between enum and class formatter recipes.

## Verification

[ValueOperatorsTest](../../../../Compiler/src/test/kotlin/cloth/ValueOperatorsTest.kt)
covers buffered struct snapshots, nullable comparisons, enum payloads,
distinct cases with equal payloads, formatter effects, and source diagnostics.
The two runtime fixtures ran on Windows x64 and x86 and verified LLVM IR and
object emission for all nine supported target profiles.

On 2026-11-06, the clean full suite passed 1,421 tests in 125 suites with zero
failures, errors, or skips.

```powershell
.\gradlew.bat :Compiler:clean :Compiler:test '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --offline --console=plain --no-daemon
```
