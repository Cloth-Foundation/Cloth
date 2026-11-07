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

fn showDamageTypes() {
    for (def kind <- DamageType::values) {
        println(kind::name)
    }
}
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
- `value::name` returns the selected case's original declared spelling as
  a non-nullable `string`. For example, `DamageType.FALL::name` returns
  `"FALL"`. It evaluates its receiver once and requires an enum value.
  Nullable enum values use the ordinary presence-proof rule.
  A payload accessed through `.name` has its declared payload value.
- `EnumType::values` returns a non-nullable, fixed-size `EnumType[N]` array,
  where N is the declared case count. It contains every case exactly once in
  declaration order, independently of custom equality or formatting. Enumeration
  does not call `operator ::str()`. Only enum type receivers support it; enum
  instances, arrays, primitives, structs, classes and typeinfo values reject it.
- The `::values` result follows ordinary independent array semantics for
  binding copies, arguments, returns and snapshot foreach traversal. Changing
  a stored result cannot alter subsequent enumeration. The provider is read-only
  and cannot be overridden. A payload accessed through `.values` keeps its
  declared field value; `values` remains an ordinary contextual identifier.
- Optional header fields declare a common payload shape. A case supplies
  positional values for the first fields. Omitted fields use their header
  defaults. Supply literal values and explicit casts, including negative
  numeric literals. Payload fields are read-only. Reading an array payload
  supplies an independent value. `pub def<T>` exposes the
  field outside the enum. Within the enum, read fields through `self` or
  their bare names.
- Header defaults and supplied payload values must match their declared field
  types. Check header defaults first, then cases and supplied arguments in
  declaration order. An unused default is still checked. A field-type mismatch
  reports C3095 with original value, field annotation and applicable case labels,
  independently of case count. Specific literal, cast, array and struct errors
  retain the shared type checker's own diagnostic codes and source labels.
- Admitted constant payloads also validate numeric cast values before emission,
  even in unread cases and overridden header defaults. C3005 identifies the
  first trapping cast and labels the original cast, field annotation and
  applicable case. Arrays and structs validate their contained values recursively.
- Constant numeric conversion uses each operand's checked source type and rounds
  every intermediate float conversion. Float-to-integer casts require finite
  values whose result fits after truncation toward zero. Integer casts retain
  wrapping/narrowing and source-signedness widening. Nullable absence skips
  conversion; known-present nullable values use the same payload checks.
  Target-sized casts use the selected target's `usize`/`isize` bounds.
- Payload annotations supply explicit nullable element and row context to
  generated readers, including multiple cases and header defaults. For example,
  `typedef<enum> E (pub def<int?[2]> values = [1, null]) { A, B([null, 2]) }`
  permits `E.A.values` and `E.B.values`. A new binding receiving a payload with
  nullable elements still requires its own explicit nullable element annotation.
- `value::str`, interpolation, `print`, and `println` share
  `operator ::str()`. The declaration returns non-nullable `string`.
  The default is the case name in its declared spelling. Nullable output
  renders absence as `null`. Recursive array text captures each present
  formatter result once in traversal order.
  The built-in `::name` provider reads the declared case name independently
  of the enum's formatter.
- Secondary enum constructors and ordinary enum methods are outside this
  slice. A case that needs numeric conversion can use an explicit cast in
  its payload argument.

## Implementation ownership

- [EnumParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/EnumParser.kt)
  parses case lists and reuses the operator member parser.
  [NamedTypes](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/NamedTypes.kt)
  registers nominal enum types. [EnumModel](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/EnumModel.kt)
  checks payload forms and builds private readers for case payloads and names.
- [EnumPayloadTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/EnumPayloadTyping.kt)
  validates original header defaults and supplied case values through
  `BodyTyping`, with each field's declared type as context. `FunctionAnalyzer`
  runs this preflight before checking callable defaults and generated reader
  bodies. Keep payload mismatches independent of synthetic return/branch shapes;
  let intrinsic errors propagate without recovery or code replacement.
- [EnumConstantExpressions](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/EnumConstantExpressions.kt)
  visits admitted payload forms through shared `eagerOperands` order and stops
  at the first invalid numeric cast. The declaration form check reuses that
  operand traversal. [NumericConstants](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/NumericConstants.kt)
  supplies closed numeric values with exact integer wrapping, per-format float
  rounding and checked truncation. Unknown bindings/calls supply no constant
  facts. `RangeConstants` delegates ordinary non-nullable numeric facts to this
  same evaluator; enum validation also handles known nullable presence/absence.
  Generated readers retain ordinary numeric lowering and buffer transport.
- [FunctionAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt)
  checks equality signatures and uses the shared comparison overload ranking.
  [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)
  records the selected comparison and the nullable presence path.
- [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
  snapshots both operands and guards payload comparisons.
  [EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
  uses the ordinary callable ABI for overloaded comparisons and formatter
  results. Enums carry an ordinal in storage and at call boundaries.
- `CEnum` registers the `EnumName` provider recipe. It selects the generated
  case-name reader. Default `::str` uses that same reader, and a declared
  `operator ::str()` supplies custom presentation text.
  [Enum type](../../../../Compiler/src/main/kotlin/cloth/data/CEnum.kt) ·
  [Provider recipes](../../../../Compiler/src/main/kotlin/cloth/data/ProviderResult.kt)
- `CEnum` also registers the type-only `EnumValues` recipe, whose result retains
  the nominal enum element type and static case count. It emits each case's
  existing ordinal through `ProviderEmissionContext.arrayLiteral` and
  `EmitArray.literal`. Existing array copying and caller-owned return transport
  preserve independence and lifetime. This adds no separate enum iteration,
  formatting or call path; provider storage retains the existing conservative
  frame lifetime policy.
  [Provider services](../../../../Compiler/src/main/kotlin/cloth/emitter/text/ProviderEmissionContext.kt) ·
  [Array emission](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArray.kt)
- [EmitArrayText](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitArrayText.kt)
  shares its text capture arena between enum and class formatter recipes.

Current receiver grammar recognizes a bare enum type name in `E::values`.
Named nullable/array type expressions such as `E?::type` and `E[2]::type` are
not yet recognized as type receivers. Their type annotations are supported;
use `E::values::type` or a stored array's metadata for array inspection.
This is an implementation boundary, not a restriction on intended type syntax.

## Enum audit follow-ups

The nullable array payload defect found by the 2026-11-06 audit is fixed in
the shared conditional checker. Explicit nullable elements and rows work with
single or multiple cases and header defaults.
[Integration evidence](development.md#nullable-array-conditional-contexts-2026-11-06)

Payload field-type diagnostics are also consistent across single and multiple
cases and header defaults. They report C3095 using original declaration/value
locations before generated reader-body checking.
[Diagnostic integration](development.md#enum-payload-diagnostic-preflight-2026-11-06)

Trapping numeric casts such as `128.0 -> int<8>` now fail before emission, even
when their payload is never read. Nested cast chains and aggregate/default
payloads use the same validation.
[Constant-cast integration](development.md#enum-constant-cast-validation-2026-11-07)

The admitted forms remain literals, unary operations on literals, explicit
casts, arrays and structs. Arithmetic expressions, qualified enum cases, type
metadata, bindings and calls remain outside the current payload form contract.
Broader constant-expression forms are a separate language extension; adding
them requires explicit evaluation and diagnostic coverage.
[Constant-form check](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/EnumModel.kt)

## Verification

`EnumConstantExpressionsTest` covers unread cases, defaults overridden by every
case, nested arrays/structs, nullable absence/presence, exact cast spans, target
integer bounds, wrapping and intermediate half/float rounding. Native valid
fixtures and ordinary dynamic failure guards execute on Windows x64/x86; valid
enum payload IR/objects verify across all nine profiles. CLI rejection checks
select all nine targets and stop before output or linker work. `NumericConstantsTest`
adds exact wide integer results, signedness, decimal half-literal ties versus
cast-source rounding, NaN/infinities, signed zero, nullable range boundaries and
unknown runtime inputs.
[Enum constant tests](../../../../Compiler/src/test/kotlin/cloth/EnumConstantExpressionsTest.kt) ·
[Numeric evaluator tests](../../../../Compiler/src/test/kotlin/cloth/NumericConstantsTest.kt)

`EnumPayloadDiagnosticsTest` checks single/multiple-case parity, defaults even
when overridden, exact field types, original Unicode/CRLF source labels,
header/case/argument ordering, and preserved intrinsic error codes. Valid
contextual numeric, struct and nullable array payloads type-check for Windows
x64/x86 layouts. CLI rejection checks cover all nine target selections and
stop before output creation or linker discovery.
[Diagnostic tests](../../../../Compiler/src/test/kotlin/cloth/EnumPayloadDiagnosticsTest.kt) ·
[Integration evidence](development.md#enum-payload-diagnostic-preflight-2026-11-06)

`EnumValuesTest` checks the exact nominal array type/count, type-only access,
declaration order even under custom equality, copy and foreach independence,
returns, defaults, multiple results, payload `.values` access, formatting effects
and rejection boundaries. Its fixture executes on Windows x64/x86 and verifies
IR and objects across all nine supported target profiles.
[Enumeration tests](../../../../Compiler/src/test/kotlin/cloth/EnumValuesTest.kt) ·
[Integration evidence](development.md#enum-case-enumeration-provider-2026-11-06)

`EnumPayloadTest` checks supplied and default nullable array payloads, nullable
whole arrays and rows, Unicode elements, varying unsized dimensions and
independent reads. The fixture executes on Windows x64/x86 and verifies IR and
objects for all nine supported targets. `NullabilityTypingTest` covers the
shared contextual rule and rejection boundaries.
[Payload tests](../../../../Compiler/src/test/kotlin/cloth/EnumPayloadTest.kt)

`EnumNameTest` checks case spelling, value-only access, nullable proofs,
once-only receiver effects, payload `.name` access, custom formatting,
read-only defaults and returned text. Its runtime fixture executes on Windows
x64 and x86 and emits verified IR and objects for all nine supported targets.
[Case-name tests](../../../../Compiler/src/test/kotlin/cloth/EnumNameTest.kt)

[ValueOperatorsTest](../../../../Compiler/src/test/kotlin/cloth/ValueOperatorsTest.kt)
covers buffered struct snapshots, nullable comparisons, enum payloads,
distinct cases with equal payloads, formatter effects, and source diagnostics.
The two runtime fixtures ran on Windows x64 and x86 and verified LLVM IR and
object emission for all nine supported target profiles.

The original struct and enum operator publication on 2026-11-06 passed
1,421 tests in 125 suites with zero failures, errors, or skips.
The later case-name integration passed 1,440 tests in 128 suites.
[Case-name verification](development.md#enum-case-name-provider-2026-11-06)

```powershell
.\gradlew.bat :Compiler:clean :Compiler:test '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --offline --console=plain --no-daemon
```
