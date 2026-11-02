---
name: cloth-numerics
description: Design, explain, or extend Cloth fixed-width integers, floats, booleans, arithmetic and comparisons, explicit casts, exponentiation, or checked integer division and remainder.
---

# Cloth numeric and boolean operations

Read [confirmed numeric semantics](references/semantics.md). Use
[target integers](../cloth-target-integers/SKILL.md) for usize/isize and
[bindings](../cloth-bindings/SKILL.md) for assignment/update syntax.

## Development rules and current policies

- Preserve signed/unsigned 8/16/32/64-bit integers and 16/32/64-bit floats with
  default 32-bit `int`/`float`. Typed binding/call/return values keep exact types;
  arithmetic promotion is not an implicit binding conversion. Bool is separate
  from numeric values and occupies a byte in storage despite its LLVM i1 value.
- Integer operations wrap at their width. Preserve full unsigned bits in division,
  comparisons, formatting and float conversion. Keep half arithmetic/conversion
  rounding and double-to-half behavior in shared helpers.
- Diagnose known invalid integer `/` and `%` at compile time; guard executed
  unknown zero divisors and signed-minimum / -1 (also remainder). Evaluate operands
  once and report the first invalid operation with source location.
- Explicit numeric casts preserve source signedness on widening, low bits on
  narrowing, and checked float-to-integer conversion after truncation toward zero.
  NaN/infinity and out-of-range values must follow controlled failure paths.
  [Nullable-target casts](../cloth-nullability/SKILL.md)
  use these same conversions only on the present path; absence stays absent.
  A non-nullable target still requires proof or a non-null fallback.
- Integer powers use wrapping exponentiation by squaring, preserve integer type,
  and reject negative integer exponents. Floating/mixed powers use established
  promotion and ABI math calls. Current choices are right associativity, power
  above unary signs/multiplication, and `0 ** 0 == 1`; these are not independently
  confirmed language decisions. The power contract is recorded with
  [update-operator decisions](../cloth-bindings/references/semantics.md#compound-assignments-and-update-operators).
- Boolean logic short-circuits at runtime while all operands are type-checked.
  Keep NaN, signed-zero and mixed numeric comparison behavior consistent with
  current tests. Character arithmetic/conversions remain separate design work.

## Source and verification

Inspect [CInteger](../../../Compiler/src/main/kotlin/cloth/data/CInteger.kt),
[ExpressionTyping](../../../Compiler/src/main/kotlin/cloth/semantic/ExpressionTyping.kt),
[EmitCast](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitCast.kt),
[EmitHalf](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitHalf.kt),
[EmitIntegerDivision](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitIntegerDivision.kt),
and [EmitPower](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitPower.kt).
Examples: [Math.co](../../../examples/Math.co), [Casts.co](../../../examples/Casts.co),
[Unsigned.co](../../../examples/Unsigned.co), [Booleans.co](../../../examples/Booleans.co).

Relevant suites: `BindingsTest`, `UnsignedIntegersTest`, `NumericWidthsTest`,
`CastsTest`, `AssignmentOperatorsTest`, `IntegerDivisionTest`, and
`SharedIntegersTest`. Existing checks include IEEE boundary rounding, exact uint64
powers, runtime failures and short-circuit guards. Recorded verification lives in
[development history](../cloth-language/references/development.md#integer-division-and-remainder-guards-2026-10-31);
run relevant suites for fresh claims and preserve target-specific ABI validation.
