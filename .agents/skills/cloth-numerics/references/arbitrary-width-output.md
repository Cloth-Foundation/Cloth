# Arbitrary integer widths: Stage 5 decimal output

Stage 5 complete, 2026-11-03. This record owns full-width decimal output from
the [plan](../../../../todo/arbitrary-integer-widths-plan.md), following
[Stage 4 storage/composition](arbitrary-width-storage.md). Preserve the
[accepted numeric semantics](semantics.md#arbitrary-fixed-integer-widths-design-confirmed-2026-11-03)
and [output contract](../../cloth-output/references/semantics.md).

## Integrated source behavior

All signed/unsigned integer widths 1..65536 now produce exact decimal text through
`print`, `println`, interpolation, scalar `::str`, nullable values and recursive
array text. Signed minima retain their minus sign; unsigned high bits are never
interpreted as signed. Zero prints `0`. Signed int<1> prints -1/0, unsigned int<1>
prints 0/1; bool remains distinct. Defaults and floating formats/formatting remain
unchanged. Example:

```cloth
fn main() {
    def<unsigned int<128>> large = unsigned int<128>::max
    println(large)
    println(#"value: {large}")
    println(large::str)
    def<unsigned int<128>?> absent = null
    def<unsigned int<128>?[]> values = [large, absent]
    println(values)
}
```

The first three values are `340282366920938463463374607431768211455`; the
interpolated line includes its prefix. Absent nullable values/rows render `null`
without reading their payload. Array punctuation, Unicode, NUL/percent handling,
effect order, early exits and checked target-sized byte counts are unchanged.
Returned formatted strings use ordinary independent caller-owned copies.

**C4031 is retired.** The temporary integer emission/text preflight and its error
factory are removed; the code is reserved rather than reassigned. Valid wide
types and text no longer encounter a stage boundary. Invalid widths still use
C3011, literal range errors retain their existing codes, and compilation still
stops at the first detected error. This stage does not introduce class formatting,
source bitwise operators, C interop or a public wide-integer ABI.

## Shared lowering and capacity

[EmitString.interpolate](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/EmitString.kt)
is the common scalar formatting entry for typed providers and every source output
route. Widths through 64 retain the validated native C formatting fast path;
wider values call
[EmitIntegerText](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/EmitIntegerText.kt).
The legacy snippet printer uses the same formatter for wide values, then writes
the resulting length-aware string. No wide scalar/pointer is sent to C varargs.

`EmitIntegerText` also accepts small scalar inputs, packing them into canonical
bytes before the helper. This path is oracle-tested at small/one-bit widths as
well as the production native fast path. Its fixed bound is:

`ceil(N * 30103 / 100000) + 2` bytes.

Because log10(2) < 30103/100000, the ceiling bounds all unsigned digits; the extra
two bytes reserve a sign and NUL. Kotlin Long arithmetic computes this exactly,
without host floating rounding. The maximum capacity is 19731 bytes: a uint65536
maximum has 19729 digits; int65536 minimum has 19729 magnitude digits plus sign.
Allocation uses the existing checked target count/byte layer.

[IntegerTextKernelIR](../../../../Compiler/src/main/kotlin/cloth/emitter/types/kernel/IntegerTextKernelIR.kt)
defines one private noinline `cloth.integer.decimal` helper per module, installed
with the existing [integer kernels](arbitrary-width-arithmetic.md). It:

- Copies the input into local magnitude scratch, negating modulo the logical
  width only when signed and negative. This represents the minimum's unsigned
  magnitude without signed overflow and leaves the source unchanged.
- Repeatedly divides little-endian i32 chunks by 1000000000, using i64
  intermediates. The carried remainder is below the radix, so the combined
  dividend fits i64. Leading zero chunks are skipped on later passes.
- Emits each remainder as nine reverse digits except the highest chunk, which
  omits leading zeros. Zero still emits one digit. Radices never use the source
  width, so int<1> cannot truncate ten or a billion to an invalid divisor.
- Checks space before every digit/sign store, reserving one byte for NUL. Invalid
  capacity returns -1 before an out-of-bounds store; capacities zero/one fail
  before reading input. After optional sign, reverse bytes in place, terminate
  and return the exact length. The caller checks status before using that length.

Only bounded loops and i32/i64 arithmetic are emitted. Scratch is stack-owned;
no heap block is allocated per formatting operation or array element. Existing
function-result arenas handle escaping text. The helper adds no new external
wide-integer runtime dependency, precision growth or source-visible storage type.

## Recursive formatting and lifetime

[EmitArrayText](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/EmitArrayText.kt)
retains two passes over an already evaluated value: measure checked bytes, then
fill one output buffer, with nested rows writing directly into it. The formatter
does not reevaluate source expressions or concatenate growing prefixes.

Each element now uses the shared Stage 4 stored-value adapter, including inline
wide limbs and nullable payloads. The stack checkpoint **precedes element decoding**,
because a wide read allocates its own independent snapshot. Only after conversion
and append (or nested traversal) is that checkpoint restored. Source storage and
the shared output buffer predate it; only the scalar offset crosses it. This
reclaims both snapshots and decimal scratch each trip in both passes.

Safe source loops continue to use the existing closed cleanup proof. It has not
been widened to accept unknown providers/captured publication; unproven regions
retain frame lifetime. Stage 5 guarantees bounded scratch within conversion and
array traversal, not bounded storage for arbitrary source loops.

## Verification and remaining work

`ArbitraryIntegerOutputTest` contributes seven tests:

- Exact source extrema for every width 1..64 and representative widths through
  65535/65536, matching independent BigInteger decimal strings.
- Production decimal-kernel comparisons for both signednesses, extrema, seeded
  random bit patterns, one-bit inputs and zero-padded base-1e9 chunk boundaries;
  input preservation is checked. Small scalars exercise the helper as well.
- Insufficient-capacity sentinels and zero/one-capacity null-pointer probes;
  exact maximum-width byte bound.
- All text routes, nullable/nested/empty arrays, full-cap returned strings,
  Unicode/NUL/percent content, once-only effects and fatal/non-continuing inputs.
- A native 512-element cap-width array formats correctly without accumulating
  8 KiB read snapshots or roughly 20 KiB decimal buffers per leaf/pass.
- Verified IR/object emission of full-cap recursive formatting across all nine
  supported target profiles. Checks enforce one helper, no huge LLVM scalar
  types, IR under two million characters and objects under 200000 bytes.

These execute on native Windows x64/x86; foreign profiles have IR/object evidence,
not native execution. Two new native x64 `ArrayTemporaryCleanupTest` probes poison
freed storage, verify full-cap returned text, and observe 20000 safe iterations
with exactly 20000 result allocations and a 47-byte peak, ending with no live
allocations. Scalar formatter scratch itself adds no heap allocations.

Existing width/storage tests now verify formerly gated text through every
emission API and CLI object publication. The focused output/lifetime run passes
96 tests across six suites. The dated
[development entry](../../cloth-language/references/development.md#arbitrary-integer-widths-stage-5-decimal-output-2026-11-03)
records final full-suite evidence: 1078 tests across 85 suites pass with zero
failures/errors/skips (4m34s). Metadata/local links and whitespace checks pass,
and scratch is clean. Later [Stage 6 publication](arbitrary-width-integers.md)
adds the maintained example, integrated resource measurements and Linux x64 GNU
execution. Native AArch64/macOS/musl remain untested.
