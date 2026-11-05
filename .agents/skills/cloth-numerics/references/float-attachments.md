# Float attachments

Integrated 2026-11-04. The [confirmed contract](semantics.md#float-attachments-design-confirmed-2026-11-04)
owns `abs()`, `clamp(min, max)` and `signum()` for float16/32/64. Maintained
example: [FloatAttachments.co](../../../../examples/FloatAttachments.co).

| Method | Result | Special values |
| --- | --- | --- |
| `abs()` | Exact receiver float type | Negative zero becomes positive zero, either infinity becomes positive infinity, NaN remains NaN |
| `clamp(min, max)` | Exact receiver float type | Inclusive ordered non-NaN bounds; equal/infinite bounds valid; NaN receiver preserved after bounds validation; in-range signed zeros preserved |
| `signum()` | Signed int32 | -1/0/1; both zeros yield 0; infinities yield their sign; NaN errors |

## Registration and lookup

[FloatAttachments](../../../../Compiler/src/main/kotlin/cloth/builtin/attachments/FloatAttachments.kt)
is separate from integer attachments, preserving room for family-specific APIs.
Use the same named `valueMethod(name, parameters, returns) { context, values -> ... }`
declarations as strings/integers. `FloatMethod` is only a backend operation enum,
without a spelling table. Inherited `PrimitiveAttachment.valueMethod` explicitly
promises read-only operands and independent results; unproven future methods must
retain `register`'s conservative default. Declaring each named value registers it;
there is no second registration list.

[PrimitiveAttachments](../../../../Compiler/src/main/kotlin/cloth/builtin/attachments/PrimitiveAttachment.kt)
holds one exact definition set per supported float width, independent of compilation
target. Registry entries retain no LLVM handles or compilation state. Integer
specialization remains lazy by exact receiver/target identity. The read-only
`all(target)` catalog includes the three float providers as well as strings,
booleans, characters and one integer representative. Names can occur in multiple receiver families.

For a receiver that never returns, `ExpressionTyping.nonReturningMember` uses
written argument type hints when a method name is shared: prefer typed hints,
then literal defaults. Non-continuing argument hints are ignored. No-argument calls
may use a representative because no receiver value reaches dispatch. Preserve
label/arity checks before hint inspection within expression typing; declaration
resolution can still detect an unknown name before that phase. Every written
argument is statically checked through the selected exact signature. This preserves
early-return integer and float calls without inventing source member overloads.

## Validation and emission

- [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/ExpressionTyping.kt)
  resolves exact float signatures and validates by cached registration identity.
  Floating literals receive bound-parameter context; typed integer/float values
  are not implicitly converted. Nullable values require existing proofs/fallbacks.
  `RangeConstants.floating` evaluates only the existing closed literal/provider/
  arithmetic/cast subset, rounding each operation at its declared format. It never
  executes calls or propagates binding values. Known invalid bounds/NaN signum
  fail with C3076/C3075, including in skipped code and outer overload probes.
- [BuiltinEmissionContext](../../../../Compiler/src/main/kotlin/cloth/emitter/types/BuiltinEmissionContext.kt)
  delegates one emitted call to
  [EmitFloatMember](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/EmitFloatMember.kt).
  Abs clears the sign bit through the exact-width integer representation;
  it does not widen/round a float value. Signum tests sign, magnitude and NaN bits,
  guards NaN, then returns signed int32. Neither needs a native math library call.
- Clamp first checks ordered `min <= max`, which rejects NaN/reversed bounds.
  It uses strict comparisons to select a bound; otherwise it returns the original
  received value. Do not replace this with min/max intrinsics that alter NaN or
  signed-zero behavior. Float16 comparison operands pass through existing software
  `EmitHalf.toFloat`; selected results remain original half values. Avoid native
  half comparisons or arithmetic/runtime-helper dependencies for these methods.
- Dynamic failures occur after receiver/arguments execute once in order, carry
  the original call location, and exit without PEC. Ordinary short circuits and
  early returns skip guards/bodies while static checking still applies.
- The shared Elvis inference fix preserves a nullable float payload's fallback
  literal width even when the whole expression is a member receiver or output
  argument, neither of which supplies numeric context. Typed fallback values
  retain exact-type requirements. `FloatAttachmentsTest` checks float64 precision
  at 16777217 and half type preservation; nullable/literal suites also rerun.

## Values and temporary storage

All three methods consume/produce scalar SSA values; captures cannot change an
earlier received operand. They neither publish nor retain operand storage.
Array elements, ordinary calls, recursion/deferred blocks, nullable payloads and
ordered results use the existing value paths. Existing closed reclamation proofs
admit safe float members and release temporary array producers after scalar or
discarded consumers, including repeated while/do-while conditions. Unknown or
captured publication remains conservative. No GC or object lifetime policy is added.

## Verification

`FloatAttachmentsTest` covers exact registration identities/signatures, strict
arguments and unavailable integer methods, nullable proofs/fallbacks, once-only
ordered effects, captured receiver rebinding, ordered results, call chaining,
non-continuing receivers and skipped execution. CLI tests verify one first-error
diagnostic and no object/linker output. Dynamic guard cases cover all three NaN
signum widths, reversed bounds and NaN bounds, including a NaN receiver.

The native oracle compiles parameterized implementations once, then calls them
with boundary and deterministic random bit patterns. Independent JVM float/half
conversion/comparison oracles check exact non-NaN result bits (including zero
signs), NaN classification, subnormal/normal edges, finite extrema, infinities and
signum. Nine bound pairs include both orders of signed zero, equal zero/finite/
infinite bounds and unbounded intervals. Oracle programs, source integration and
the maintained example execute on Windows x64/MSVC and x86/MSVC. Example and
parameterized float16/32/64 fixtures verify IR and emit nonempty objects across
all nine profiles. Native Linux/macOS/ARM64 execution remains untested here.

The `ArrayTemporaryCleanupTest` float-member probe checks bounded live bytes for
1,000 iterations consuming fresh arrays through all three methods, repeated
while/do-while conditions, preserved preexisting storage and final allocation
balance under poisoned frees.

Focused command:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.FloatAttachmentsTest --tests cloth.IntegerAttachmentsTest --tests cloth.StringAttachmentsTest --tests cloth.StringMemberStorageTest --tests cloth.ArrayTemporaryCleanupTest --tests 'cloth.Nullability*' --tests cloth.NumericLiteralsTest --tests cloth.NumericLiteralsIntegrationTest --offline --console=plain --no-daemon
```

Passed 151 tests across 11 suites in 48 seconds, zero failures/errors/skips.
The full command `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passed 1,269 tests across 98 suites in 5m 42s, zero failures/errors/skips.
The [development record](../../cloth-language/references/development.md#float-attachments-2026-11-04)
retains the milestone. Gradle uses the existing external cache. Direct metadata,
relative link and whitespace checks pass. The optional Python skill validator
cannot start because PyYAML is unavailable.

Float rounding/classification convenience methods, float radix formatting, bit
counts, static/type calls, named/default method arguments and member overloads
remain outside this integrated slice.
