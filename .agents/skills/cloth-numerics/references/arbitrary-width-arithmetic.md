# Arbitrary integer widths: Stage 3 arithmetic and conversions

Stage 3 integrated 2026-11-03. This record owns arithmetic/conversion lowering
from the [plan](../../../../todo/arbitrary-integer-widths-plan.md), following
[Stage 2 typing](arbitrary-width-types.md) and the
[Stage 1 representation policy](arbitrary-width-backend.md#selected-representation-and-lowering-policy).
The [accepted semantics](semantics.md#arbitrary-fixed-integer-widths-design-confirmed-2026-11-03)
remain authoritative. Defaults and floating formats are unchanged.

Historical boundary: the source gate described below belongs to Stage 3. Later
[Stage 4](arbitrary-width-storage.md) integrates wide source storage/calls and
composition. [Stage 5](arbitrary-width-output.md) subsequently integrates full
decimal text and retires C4031.

## Source support and stage boundary

Every signed/unsigned width 1..64 now emits exact LLVM scalar values and executes
through existing storage, functions, nullable values, arrays and native output.
For example, this program prints `12`:

```cloth
fn main() {
    def<int<31>> x = 12
    println(x)
}
```

Semantic types still admit every width 1..65536. Production wide arithmetic and
conversion helpers are implemented and tested through the cap, but **wide source
emission remains C4031-gated**. Stage 4 must integrate physical bindings, private
calls/results, snapshots and cleanup; Stage 5 must integrate full-width decimal
text. Helper tests compose production APIs directly and do not establish those
remaining source features. Do not remove the gate merely because kernels pass.

`int<1>` holds -1/0 and unsigned int<1> holds 0/1. Ordinary arithmetic and updates
wrap. The existing power identity is the low bit one, interpreted as -1 for
signed int<1>, including `0 ** 0`. The scalar power loop consumes a one-bit
exponent without shifting i1 by one. Range-header `++`/`--` use mathematical one
in the widened calculation, so ascending -1..=0 and descending 0..=-1 terminate
correctly. Explicit RHS literals still must fit their ordinary context.

## Production ownership and invariants

- [EmitWideInteger](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/EmitWideInteger.kt)
  owns module-local helper installation, constants, storage and operation APIs.
  Buffers are independent stable values, never borrowed mutable bindings. Storage
  rounds to native-sized limbs, least significant first, with canonical zero
  padding. Kernels process i32 chunks with i64 intermediates; they require no
  i128 or enormous LLVM scalar operations. Private noinline loop helpers keep IR
  bounded rather than unrolling an operation per source width.
- [IntegerKernelIR](../../../../Compiler/src/main/kotlin/cloth/emitter/types/kernel/IntegerKernelIR.kt)
  implements wrapping add/subtract/negate/multiply, signed/unsigned compare,
  division/remainder, exponentiation, integer casts and checked header updates.
  Each result masks unused high value bits. Multiplication uses exact unsigned
  i32 products plus carry in i64. Division uses unsigned magnitudes, unit/small
  divisor paths and aligned shift/subtract for larger divisors; restore quotient
  sign and dividend remainder sign. Status checks precede unsafe division.
- Division status 1 is zero divisor; 2 is signed minimum/-1 for either operation.
  Power status 3 is a negative signed exponent. Checked add/subtract/multiply
  compute in 2N bits, narrow and compare with extension to detect overflow; the
  internal limit is 131072 bits even though source types stop at 65536.
- [IntegerFloatKernelIR](../../../../Compiler/src/main/kotlin/cloth/emitter/types/kernel/IntegerFloatKernelIR.kt)
  converts wide integers with guard/round/sticky ties-to-even to float32/64,
  including signed infinity on overflow. Float16 retains the existing float32
  intermediate/half helper policy. Float-to-integer checks the IEEE exponent for
  NaN/both infinities, truncates toward zero, then checks exact integer bounds.
  Fractions in (-1,0) therefore convert to unsigned zero. Signed minimum handling
  checks the truncated magnitude rather than rejecting its fractional precursor.
  Invalid conversion returns status 4 before any unsafe conversion instruction.
- [EmitCast](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitCast.kt),
  [EmitIntegerDivision](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/EmitIntegerDivision.kt),
  [EmitPower](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitPower.kt),
  [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
  and [EmitRangeLoop](../../../../Compiler/src/main/kotlin/cloth/emitter/types/loops/EmitRangeLoop.kt)
  route wide operations to this layer before scalar type construction. Integer
  widening follows source signedness; narrowing preserves low bits, including
  across scalar/buffer boundaries. Runtime failures retain source file and span.

Kernels use bounded per-call stack scratch; caller buffers use EmitMemory.stack.
Loop temporary reclamation, escaping copies and source binding/result ownership
are Stage 4 work. No per-operation heap allocation or GC is introduced. Helpers
are installed per LLVM module; no LLVM handles survive across contexts.

## Verification

- `ArbitraryIntegerArithmeticTest`: native Windows x64/x86 execution with an
  independent BigInteger oracle. Both signednesses, widths 1,3,7,12,31,32,33,63,
  64,65,127,128,129,257,1024,4096,65535,65536; wrapping, comparisons, division/
  remainder, powers, status failures and checked operations. Seeded random
  multiplication/division/remainder and cross-width casts supplement extrema.
- `ArbitraryIntegerFloatTest`: native x64/x86 IEEE bit comparisons and exact
  BigDecimal truncation oracle, rounding ties/sticky/carry, format extrema,
  signedness, NaN/infinities, half inputs and full-cap destinations.
- `ArbitraryIntegerIntegrationTest`: every source scalar width, one-bit power/
  ranges, odd-width effects/casts/arrays/nullability/storage size, located runtime
  failures, direct production operation dispatch, full-cap widening/narrowing and
  wide runtime guards. Verifies bounded kernel IR and emits full-cap operation
  objects for all nine supported target profiles, including AArch64.
- `ArbitraryIntegerWidthsTest` keeps all 13 frontend/gate regressions; pending
  source-emission fixtures now use width 65 rather than 31.

Foreign target evidence is verified IR/object generation, not native execution.
Native AArch64, Linux and macOS runners remain unavailable here. The full suite
command is `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`;
the dated [development entry](../../cloth-language/references/development.md#arbitrary-integer-widths-stage-3-arithmetic-and-conversions-2026-11-03)
records its observed result: 1058 tests across 83 suites pass with zero failures,
errors or skips (4m24s). The three new Stage 3 suites contribute 11 tests.
Tests remove their own workspace tmp directories; scratch is clean.
