---
name: cloth-numerics
description: Design, explain, or extend Cloth fixed-width integers, floats, booleans, arithmetic and comparisons, explicit casts, exponentiation, or checked integer division and remainder.
---

# Cloth numeric and boolean operations

Read [confirmed numeric semantics](references/semantics.md). Use
[target integers](../cloth-target-integers/SKILL.md) for usize/isize and
[bindings](../cloth-bindings/SKILL.md) for assignment/update syntax.

Integer and float ranges execute through Stage 3; Stage 4 adds array foreach and
conservative body/stepper cleanup. C3067 is retired.
The [loop contract](../cloth-control-flow/references/semantics.md#for-loops-and-ranges-design-confirmed-implementation-deferred)
and [implementation plan](../../../todo/for-loops-plan.md) own their scope.
Range counters are non-nullable signed/unsigned integers at existing widths,
usize/isize, or 16/32/64-bit floats. Bool/char/nullable counter types are rejected
in this slice; nullable endpoint expressions use ordinary proof/fallback rules.
Foreach element types retain their existing independent support boundaries.
Ordinary float comparisons and rounding with no snapping or tolerance are
confirmed, as are finite-only float ranges and exact typed endpoint/counter
matching. An unchanged explicit/default range update is a runtime error if
another iteration would run. Endpoint expressions/calls evaluate, validate and
store their results once per entry, starting endpoint before ending endpoint.
Descending ranges with explicit decreasing steppers use `>` / `>=`; the default
remains `+1`, so reversed unstepped ranges are empty. Saved range bounds stay
fixed while step direction may change freely; leave the loop when a tested
counter is outside either boundary. Bodies may update the counter; the stepper
uses its current value after normal body completion, followed by the range test.
Integer overflow in the header counter update ends traversal after executing
stepper effects once, without storing a wrapped value. Body and step-expression
arithmetic retain ordinary wrapping; float non-finite updates retain their errors.
Loop binding inference is confirmed: infer one fixed type from endpoints or array
element type with ordinary literal defaults/context and exact typed compatibility;
the body/stepper cannot supply type context. Loop bindings are local to the
body/stepper, with endpoints/source resolved in surrounding scope before the new
binding is introduced. Ordinary arithmetic promotion is not endpoint binding conversion.
Equal endpoints make an exclusive range empty and an inclusive range a singleton,
using ordinary comparisons and the existing update/stalled-counter rules.

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

Range execution/closed constant validation lives in EmitRangeLoop and
RangeConstants; [source invariants and verification](../cloth-control-flow/references/for-implementation.md#stage-3-numeric-range-execution-2026-11-02)
cover all admitted formats/identities, finite failures and unchanged updates.
Use `ForRangeTest` when changing numeric emission shared with loops.

Inspect [CInteger](../../../Compiler/src/main/kotlin/cloth/data/CInteger.kt),
[ExpressionTyping](../../../Compiler/src/main/kotlin/cloth/semantic/ExpressionTyping.kt),
[EmitCast](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitCast.kt),
[EmitHalf](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitHalf.kt),
[EmitIntegerDivision](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitIntegerDivision.kt),
and [EmitPower](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitPower.kt).
Examples: [Math.co](../../../examples/Math.co), [Casts.co](../../../examples/Casts.co),
[Unsigned.co](../../../examples/Unsigned.co), [Booleans.co](../../../examples/Booleans.co),
[ForRanges.co](../../../examples/ForRanges.co) and [FloatRanges.co](../../../examples/FloatRanges.co).
`ForIntegrationTest` covers native Windows x86 counter overflow (including dynamic
uint64 multiplication), float failures, and IR/object emission across ABI profiles.

Relevant suites: `BindingsTest`, `UnsignedIntegersTest`, `NumericWidthsTest`,
`CastsTest`, `AssignmentOperatorsTest`, `IntegerDivisionTest`, and
`SharedIntegersTest`. Existing checks include IEEE boundary rounding, exact uint64
powers, runtime failures and short-circuit guards. Recorded verification lives in
[development history](../cloth-language/references/development.md#integer-division-and-remainder-guards-2026-10-31);
run relevant suites for fresh claims and preserve target-specific ABI validation.
