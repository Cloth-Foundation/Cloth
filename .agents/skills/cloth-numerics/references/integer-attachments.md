# Integer attachments

Integrated 2026-11-04. The [confirmed contract](semantics.md#integer-attachments-design-confirmed-2026-11-04)
owns the API for all signed/unsigned fixed widths 1..65536 and distinct target
`isize`/`usize` identities. Maintained example:
[IntegerAttachments.co](../../../../examples/IntegerAttachments.co).

| Method | Result | Boundary behavior |
| --- | --- | --- |
| `abs()` | Exact receiver type | Signed minimum errors; unsigned values are unchanged |
| `clamp(min, max)` | Exact receiver type | Inclusive exact-typed bounds; equal bounds valid; reversed bounds error |
| `signum()` | Signed `int` (32 bits) | -1, 0, or 1, including on one-bit receivers |
| `leadingZeros()` | `usize` | Declared bits only; zero returns width |
| `trailingZeros()` | `usize` | Two's-complement bits; zero returns width |
| `toStringRadix(int radix)` | Independent `string` | Bases 2..36; lowercase; signed magnitude; no prefix/padding; signed minimum succeeds |

## Source ownership

- [IntegerAttachments](../../../../Compiler/src/main/kotlin/cloth/builtin/attachments/IntegerAttachments.kt)
  declares each named entry exactly once and reuses exact built-in parameter,
  return and bound-call contracts. Match `StringAttachments`: each named `val`
  calls inherited `valueMethod(name, parameters, returns) { context, values -> ... }`,
  keeping its name, signature and body together. Names are defined only in these
  registrations; `IntegerMethod` selects backend operations without a spelling
  table. The shared `PrimitiveAttachment.valueMethod` explicitly opts into
  `READ_ONLY_INDEPENDENT_RESULT`; future mutating/retaining or borrowed-result
  methods must use `register` with an appropriate contract instead.
- [PrimitiveAttachments](../../../../Compiler/src/main/kotlin/cloth/builtin/attachments/PrimitiveAttachment.kt)
  lazily caches exact receiver/target-count pairs. Do not eagerly enumerate every
  possible width or collapse `isize`/`usize` to fixed integers. `all(target)` is a
  name/result-nullability catalog with one fixed-integer representative, used by
  pre-typing presence analysis and non-continuing receiver lookup; it is not an
  exhaustive list of receiver specializations. Registry entries retain no LLVM
  handles or compilation state. Published catalogs remain read-only.
- [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)
  gives bounds the receiver's numeric context and radix ordinary `int` context.
  Already-typed values still require exact identities. It applies the shared
  [RangeConstants](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/RangeConstants.kt)
  closed integer evaluator after operand typing: literals, constant providers,
  integer casts, signs and basic arithmetic with per-operation wrapping. Binding
  reads/calls are not executed or propagated by this evaluator. They retain runtime
  guards. Validation selects `ABS`/`CLAMP`/`TO_STRING_RADIX` by the cached provider's
  attachment identities, avoiding a second method-name table. Member contract
  errors stay intrinsic inside outer overload probes.
- C3072 `INTEGER_ABS_OVERFLOW`, C3073 `INVALID_CLAMP_BOUNDS` and C3074
  `INVALID_INTEGER_RADIX` own known invalid operation diagnostics. Existing
  member arity/type/named-argument and nullable checks remain unchanged. Compilation
  stops on the first error before object/linker work. Dynamic guards run after all
  operands have evaluated, report the original call location and exit without PEC.
- [EmitIntegerMember](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/EmitIntegerMember.kt)
  handles scalar operations and delegates wide comparisons/negation to existing
  limb helpers. Scalar zero counts use zero-defined i32/i64 LLVM intrinsics,
  adjusting for declared width. Wide leading counts subtract bit length from N;
  wide trailing counts scan canonical i32 words. Never count storage padding.
- [IntegerMemberKernelIR](../../../../Compiler/src/main/kotlin/cloth/emitter/types/kernel/IntegerMemberKernelIR.kt)
  adds bounded trailing-count and radix helpers to the existing kernel module.
  Radix conversion copies/negates into local magnitude scratch, divides by powers
  of the radix no larger than 1e9, emits reversed chunks with required internal
  zero padding, appends a sign and reverses the final bytes. This preserves signed
  minimum without calling `abs()`. i32 words/i64 division avoid huge LLVM scalars
  and retain x86 portability. Validate radix/capacity before input access; guard
  each output byte before writing and return a failure status on capacity exhaustion.

## Storage and control flow

Existing member emission snapshots wide operands before evaluating later arguments;
scalar operands are SSA values. Wide `abs`/`clamp` may select an already-independent
received snapshot; no mutable binding bytes are exposed. Radix formatting reserves
N+2 stack bytes (base-two digits, optional sign, NUL) and returns its own descriptor.
Kernel magnitude scratch dies at helper return. Ordinary outgoing copies preserve
strings/wide values through source-function, deferred-block, nullable and ordered
returns. Arrays receive ordinary independent values.

The existing closed temporary proof accepts these opt-in members only when every
operand is safe. Scalar/discarded results and proven loops can release dynamic
storage after consumption; escaping indirect results retain storage through their
copy/consumer. Unknown captured/indexed publication remains conservative. No GC,
reference counting, borrowing API or class-lifetime policy is added.

## Verification

`IntegerAttachmentsTest` covers family registrations, exact signatures, all scalar
widths 1..64 and wide widths 65/127/128/129/257/1024/4096/65535/65536. Independent
BigInteger oracles check extrema, zero, random patterns, sign/absolute value,
inclusive/equal clamps, zero counts, full text and receiver preservation. All bases
2..36 run at widths 1/8/65/129; representative bases 2/8/10/16/36 run through the
cap. Oracle scratch is reclaimed after each complete text comparison.

Source fixtures check side effects, captured receiver replacement, early returns,
skipped calls, nullable presence/fallback, result chaining, ordered outputs, exact
target identities and reused ASTs. Static errors inside skipped/overloaded contexts
remain intrinsic; CLI first-error failure creates no object. Native dynamic failures
include one-bit/default/wide/full-cap signed minima, reversed bounds and invalid radix.

The maintained example, oracle programs and a 2,000-iteration full-cap source
stress fixture execute on Windows x64/MSVC and x86/MSVC. Parameterized call IR and
nonempty objects verify across all nine profiles; native Linux/macOS/ARM64 execution
is untested for this feature. The poisoned-free `ArrayTemporaryCleanupTest` probe
checks outgoing radix strings, wide ordered results and captured array publication,
with balanced allocations after the receiving frame exits.

Focused command:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.IntegerAttachmentsTest --tests cloth.StringAttachmentsTest --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon
```

Passed 81 tests across three suites in 35 seconds, zero failures/errors/skips.
After extending the dynamic abs guard fixtures, the full command
`.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon` passed 1,258
tests across 97 suites in 5m 33s, zero failures/errors/skips. The
[development record](../../cloth-language/references/development.md#integer-attachments-2026-11-04)
retains the milestone. Gradle uses the existing external cache. Direct changed-skill
metadata and relative file-link checks pass; the optional Python validator cannot
start because PyYAML is unavailable.

Static/type calls, integer member overloads, named/default method arguments and
additional bit operations are outside this slice. Float abs/clamp/signum and
shared-name lookup are covered by the [float extension](float-attachments.md);
the [boolean extension](bool-attachments.md) adds only bool-to-int conversion.
The [character extension](../../cloth-strings/references/character-attachments.md)
adds scalar classification and explicit code-point conversion to int32.
