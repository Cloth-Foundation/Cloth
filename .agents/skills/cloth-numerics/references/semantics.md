# Cloth numeric and boolean operations: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

## Boolean attachments (design confirmed 2026-11-04)

The user approved only `bool.toInt()` in a separate `BoolAttachments` provider,
using the same inherited `valueMethod` registration style as the other providers.
It takes no explicit arguments and returns ordinary signed `int` (32 bits):
`false` becomes 0 and `true` becomes 1. This is an explicit method conversion;
bool remains separate from numeric types and ordinary boolean/numeric casts
and implicit conversions retain their existing rejection rules.

Evaluate the receiver once, preserving normal early-return, short-circuit and
nullable proof/fallback rules. The method is read-only. Static/type calls,
unqualified global calls and named/extra arguments are not introduced. Other
boolean methods remain deferred. See the [implementation record](bool-attachments.md).

## Float attachments (design confirmed 2026-11-04)

Float attachments are implemented under the [float attachment record](float-attachments.md).
The user approved a separate
`FloatAttachments` provider with the same inherited `valueMethod` declaration
style as integer/string providers, keeping room for type-specific methods.

- The first batch is `abs()`, `clamp(min, max)` and `signum()` for
  `float<16>`, `float<32>` and `float<64>`.
- `abs()` preserves the exact float receiver type, maps negative zero to
  positive zero and negative infinity to positive infinity, preserves positive
  infinity, and returns NaN for NaN.
- `clamp(min, max)` uses inclusive bounds with the receiver's exact float type
  for both bounds and result. Equal bounds are valid. Reversed bounds and NaN
  bounds are errors: diagnose known invalid bounds at compile time and guard
  dynamic bounds at runtime with a located, controlled failure. Validate bounds
  before returning a NaN receiver unchanged as NaN. Infinite bounds are allowed;
  infinite receivers clamp to a finite bound when outside the interval. Preserve
  the receiver, including its zero sign, when it is within the bounds; select a
  bound only when strictly below or above it. Thus negative zero remains negative
  even when both bounds are positive zero, since the zeros compare equal.
- `signum()` always returns ordinary signed `int` (32 bits): -1 for negative
  values, 0 for either signed zero, and 1 for positive values. Negative/positive
  infinity produce -1/1 respectively. NaN produces a controlled error.
- Integer zero-bit counts and integer radix formatting remain integer methods;
  this batch does not add float equivalents.

Calls use the ordinary read-only primitive member contract: receiver then arguments
evaluate once in source order, with earlier values preserved across captured writes.
Nullable receivers/arguments require proofs or fallbacks. Floating literals receive
the exact bound parameter's width; already-typed values require exact types.
Known invalid closed signum/clamp expressions are compile-time errors; dynamic
cases use located runtime failures without PEC. Static/type calls and named method
arguments remain unsupported.

## Integer attachments (design confirmed 2026-11-04)

The six methods below are implemented under the
[integer attachment record](integer-attachments.md). The following rules were
confirmed by the user.

- Integer attachments cover signed `int<N>` and `unsigned int<N>` for every
  supported width 1..65536, plus `isize` and `usize`. Preserve exact fixed-width
  identities and distinct target-sized identities; odd and wide widths receive
  the same API as ordinary integers.
- `abs()` preserves the receiver's exact integer type, including width and
  signedness. Unsigned `abs()` returns its input unchanged.
- For signed integers, the minimum value has no representable positive magnitude
  in the same type. `abs()` on that value is an error: diagnose known cases at
  compile time and guard other cases at runtime with a located, controlled failure.
  Do not wrap, saturate, widen, or return an unsigned result.
- `signum()` always returns ordinary signed `int` (32 bits), independently of
  the receiver's width, signedness or target-sized identity. Its result is -1
  for negative values, 0 for zero and 1 for positive values. Unsigned receivers
  therefore produce only 0 or 1. The result type also applies to `int<1>`.
- `clamp(min, max)` uses inclusive bounds and preserves the receiver's exact
  integer type. Both bounds require that exact type; integer literals receive
  receiver-type context without implicitly converting already-typed values.
  Values below `min` produce `min`; values above `max` produce `max`; values
  within the interval are preserved. Equal bounds are valid and produce that
  bound. Reversed bounds (`min > max`) are an error: diagnose known cases at
  compile time and guard other cases at runtime with a located, controlled failure.
- `leadingZeros()` and `trailingZeros()` return `usize`. They count zero bits
  in the receiver's declared N-bit representation, excluding storage padding.
  Both return N for zero. Signed receivers use their two's-complement bit pattern;
  negative values therefore have zero leading zeros. For `isize` and `usize`,
  N is the compilation target's integer width, not the host's width.
- `toStringRadix(radix)` takes ordinary signed `int` (32 bits), with a radix
  from 2 through 36 inclusive, and returns an independent `string`. Digits are
  lowercase `0`-`9` and `a`-`z`, without a base prefix or leading zeros. Zero
  produces `"0"`. Negative values use `-` followed by their magnitude, including
  signed minimum, which formats successfully. Invalid radices are errors:
  diagnose known cases at compile time and guard others at runtime with a
  located, controlled failure.

Calls use the ordinary primitive member contract: evaluate the receiver once,
then explicit positional arguments once from left to right, preserving earlier
received values across later captured writes. Early returns/jumps stop evaluation;
short circuits skip execution while all source is statically checked. Nullable
receivers/arguments require ordinary presence proofs or a non-null fallback.
These are read-only methods; they do not change their receiver. Type/static calls,
named method arguments and method values remain outside this slice.

## Decimal literal suffixes (design confirmed 2026-11-03)

- Decimal numeric literals accept `i` / `I` to indicate an integer and `f` / `F`
  to indicate a float. These suffixes select the numeric category, not a width;
  ordinary contextual width selection and default widths still apply.
- Suffixes apply only to decimal literals, not hexadecimal, octal or binary
  literals. Hexadecimal `f` / `F` digits remain digits, not float suffixes.
- All three spellings `123.f`, `123f` and `123.0f` are valid float literals.
  The integral decimal form with a suffix is valid too, for example `123_456i`.
- The dot in `123.f` is the literal's decimal point, with no fractional digits;
  it does not introduce a call or member access. A decimal point denotes a
  floating form, so integer suffixes cannot follow it: `123.i` / `123.I` are
  errors even in `def<int> x = 123.i`. Use `123i` / `123I` for integer literals.
  An integer suffix does not convert a floating form into an integer.
- These forms are implemented under the [literal implementation record](numeric-literals.md).
  Additional forms such as an unsuffixed trailing dot or scientific notation
  remain unconfirmed and unsupported.

## Integer literal prefixes (design confirmed 2026-11-03)

- Hexadecimal uses `0x`, binary `0b`, and octal `0o`, for example `0x7B`,
  `0b1111011` and `0o173`; all three represent decimal 123. "Ordinal" in the
  discussion refers to octal, not a separate literal category.
- Base-prefixed literals follow ordinary integer contextual typing, including
  fixed-width signed/unsigned integers, `usize` and `isize`. Without integer
  context they default to `int`. Decimal integer suffixes also admit these
  contextual integer types; `i` does not force fixed-width signed `int`.
- All literal magnitudes must fit the contextual type. Base notation does not
  imply unsignedness, wrapping or signed bit-pattern reinterpretation.
  `def<int> hex = 0xDEADBEEF` is an error, while an `unsigned int` binding fits.
- Target-sized bounds remain target-dependent: `0xFFFFFFFF` fits 32/64-bit
  `usize`, but fits `isize` only on a 64-bit target. Typed integer identities
  remain distinct; these forms do not add implicit value conversions.
- These prefixes are implemented. The
  [implementation plan](../../../../todo/numeric-literals-plan.md) records source
  ownership, stages, verification and remaining scope boundaries.

## Literal context and type syntax (design confirmed 2026-11-03)

- The user confirmed contextual floating literal widths in typed bindings,
  assignments and returns, as well as calls/ranges. This includes both suffixed
  and unsuffixed decimal floating forms: `def<float<64>> x = 123f` and
  `def<float<64>> y = 123.0` receive float64 context. Without context, retain
  default float32. Normal target-format rounding and range checks still apply.
- Declared widths and ranges are authoritative. For example,
  `def<int<8>> x = 0xDEADBEEF` and `def<int<8>> x = 0x80` are errors, while
  `def<int<8>> x = 0x7F` is valid. `def<float<16>> x = 70000f` is an error.
  Never silently truncate a literal or widen the destination to make it fit.
  Reassignment retains the binding's existing type and the same range checks.
- Contextual literals do not relax exact-type rules for already-typed values;
  changing a typed value's width or integer identity still requires a cast.
- Arithmetic preserves integer and floating literal contexts independently:
  an integer peer's width cannot replace the receiving floating width. A nullable
  destination supplies its payload's numeric context, including for arithmetic
  expressions. Validate each literal against that context before emission.
  These contexts do not make nullable operands usable without presence proofs
  and do not change the ordinary minimum-float32 mixed arithmetic rule.
- Integer forms are also accepted in numeric type widths and fixed array
  extents. `int<0x08>` means `int<8>`, `int[0b11]` has three elements, and
  `int[3i]` has three elements. Resolve values consistently in type validation
  and structural signature keys, independent of source notation.
- Fixed width and target-sized extent checks remain in force. Stage 2 now
  accepts `int<0x07>` semantically; `int<0x10001>` is an invalid width.
  Floating literals cannot supply integer
  widths/extents. These extensions are implemented; see the
  [literal implementation record](numeric-literals.md) for source and evidence.

## Integer signedness and widths

- Intended arbitrary widths are confirmed in the
  [new width contract](#arbitrary-fixed-integer-widths-design-confirmed-2026-11-03)
  below; semantic and native source value/text support cover 1..65536.
- Native source emission currently supports each width 1..65536, signed or unsigned,
  with default 32. Explicit widths use the required angle-bracket syntax:
  `int<1>`, `int<12>`, `int<31>` and `int<64>`. Width is a parameter of the numeric type, not a separate type
  spelling hardcoded for each size. Compiler spellings follow `Keywords.INTEGER`
  and `Keywords.FLOAT`.
- C type widths used by the compiler come from explicit ABI profiles selected
  using LLVM's target triple. Unsupported targets must be rejected instead of
  falling back to a global C data model. These profiles do not change Cloth's
  numeric widths or defaults, and do not require a Clang frontend.
- Linker/toolchain configuration is separate from C type definitions and from
  process execution. It resolves the executable, flags, runtime libraries, and
  search paths using the selected target ABI and explicit build options.
- `unsigned` decorates `int`: `def<unsigned int> a = 10` declares an unsigned
  32-bit binding. The modifier applies at every supported integer width, for
  example `unsigned int<8>` and `unsigned int<64>`; `unsigned int<32>` explicitly
  names the default width.
- `unsigned` applies only to integers. Floats retain their existing behavior;
  `unsigned float` is not a Cloth type.
- Unsigned bindings follow the ordinary mutable `=` and immutable `:=` rules.
  Copies and inferred results retain signedness and width, and binding types
  remain fixed on reassignment.
- Integer literals can take their type from a declared integer binding or a
  typed integer operand: `a = 20`, `a + 1`, and `1 < a` work with unsigned `a`.
  This does not convert an already-typed signed binding to unsigned. Without
  integer context, integer literals and their expressions default to `int`.
- Integer literals must fit their contextual type. An unsigned 32-bit literal
  ranges from `0` through `4294967295`; its 64-bit counterpart ranges from `0`
  through `18446744073709551615`. Negative unsigned literals are errors.
- Arithmetic and comparisons between signed and unsigned integer bindings
  require an explicit conversion. Do not silently reinterpret a negative signed
  operand as unsigned or automatically promote the pair to a wider signed type.
- Unsigned arithmetic wraps at the selected width: for example, adding one to
  the maximum value gives zero, and subtracting one from zero gives the maximum.
  Unsigned division, remainder, and ordering use the full nonnegative value.
- On 2026-10-31 the user confirmed guards for integer division and remainder:
  a zero divisor is invalid, including `0 / 0`, and a signed minimum operand
  with divisor -1 is invalid for both operations. Diagnose known constant cases
  during compilation; guard other cases at runtime and terminate on the first
  invalid executed operation, with its source location. This includes `/=` and
  `%=`. Evaluate operands once, left to right, before runtime validation; ordinary
  short-circuit and return behavior still applies. These integer guards do not
  change floating-point arithmetic. The user clarified that unsigned integers
  cannot be negative; signed integers retain their negative range.
- Existing mixed integer/float expression promotion also accepts unsigned
  operands, converting their nonnegative value to float before arithmetic or
  comparison. The existing float32 rounding policy still applies. This does not
  permit implicit numeric conversions in binding assignments.

## Arbitrary fixed integer widths (design confirmed 2026-11-03)

- The user confirmed arbitrary signed and unsigned fixed integer widths with a
  common maximum of 65,536 bits on x86_64 and AArch64. The intended range is
  1..65536 inclusive: `int<12>` and `unsigned int<12>` are ordinary members of
  the existing integer family. This supersedes the earlier proposed 65,535 cap
  and the earlier intended restriction to 8/16/32/64.
- Plain `int` and `unsigned int` retain 32-bit defaults. Floats remain restricted
  to 16/32/64 with default 32; arbitrary widths affect only integers.
- Existing integer range formulas, contextual literal checks, wrapping,
  exact typed compatibility, signedness, explicit casts and division/remainder
  guards extend across the new widths. No implicit mixed-width integer promotion
  or dynamically growing integer type is introduced. Bool stays separate.
- Existing integer literal forms also denote these widths: `int<12>`,
  `int<0x0C>` and `int<12i>` have the same intended identity. No new width
  expression syntax is implied. `usize`/`isize` remain target-sized identities;
  pointer widths and native C ABI widths do not grow with the fixed-int cap.
- Stage 2 integrates semantic types, literal magnitudes/contexts and width
  diagnostics across 1..65536; see the [type integration record](arbitrary-width-types.md).
  [Stage 3 arithmetic/conversion integration](arbitrary-width-arithmetic.md)
  enables source scalar widths 1..64 and production wide kernels through the cap.
  [Stage 4 storage/call integration](arbitrary-width-storage.md) enables source
  values, nullable/array composition, private calls/results, providers and signed
  main through the cap. [Stage 5 decimal output](arbitrary-width-output.md)
  integrates print/interpolation/str/nullable/recursive arrays through 65536 bits
  and retires C4031. The
  [final implementation record](arbitrary-width-integers.md) publishes completed
  stage coverage, maintained examples, production measurements and target limits.
  The
  [implementation plan](../../../../todo/arbitrary-integer-widths-plan.md)
  records source findings, staged lowering/layout/output work and required
  verification. The [Stage 1 backend record](arbitrary-width-backend.md) owns
  the selected small-value/limb storage and private-call strategy, Windows x86
  compatibility policy and measured scope. Those are implementation choices,
  not public ABI or packed-storage guarantees.

## Explicit numeric casts

- `expression -> Type` explicitly converts a numeric value, as demonstrated in
  `../../../../examples/Test.co`. Targets include signed and unsigned integer types and float types,
  with their width syntax: `value -> int<64>`, `value -> unsigned int`, and
  `value -> float<64>`.
- A cast applies to the preceding arithmetic expression: `1 + 2 -> float` means
  `(1 + 2) -> float`. Casts bind below arithmetic and above comparisons. Chains
  convert left to right: `x -> float<64> -> int<64>`. Parentheses select a
  different grouping, including widening an operand before arithmetic.
- Evaluate the source once in its own type, then convert the result. A cast
  does not change an existing binding's type or retroactively widen arithmetic
  inside its operand. Widen first to avoid overflow or precision loss in that
  arithmetic, following the `../../../../examples/Test.co` example.
- Integer-to-integer casts wrap/truncate to the target width. Narrowing retains
  the low bits; widening extends according to the source signedness. The target
  signedness determines how the resulting bits are interpreted. For example,
  `-1 -> unsigned int` is `4294967295`, while converting an unsigned 32-bit
  maximum to `int<64>` preserves its positive value.
- Float-to-integer casts truncate the fractional part toward zero. NaN,
  infinities, and values whose truncated result is outside the target range
  raise an error. The range applies after truncation: `-0.75 -> unsigned int`
  yields zero, while `-1.0 -> unsigned int` is invalid.
- Integer-to-float and float-width casts convert numeric values with floating
  point rounding. Widening an already-rounded float cannot recover lost bits.
- `float` supports 16, 32 (default), and 64 bits, written `float<16>`,
  `float<32>`, and `float<64>`. Two floating operands use the wider of their
  formats: two `float<16>` operands keep a `float<16>` result, rounded to half
  precision. Mixing a floating operand with an integer uses at least float32;
  a float64 operand promotes the expression to float64. This promotion also
  applies to numeric comparisons and does not permit implicit binding conversions.
- Cast results participate in inference, binding assignment, arithmetic,
  comparisons, and ordinary boolean expressions. Binding assignment still
  requires the resulting type to match; a cast does not permit unrelated
  implicit conversions or immutable reassignment.
- Booleans and numbers remain separate, including for explicit casts.
  `true -> int` and `1 -> bool` are errors. Casting a boolean to `bool` preserves
  its value without introducing numeric truthiness.
- On 2026-11-07, enum payload integration added compile-time validation of
  trapping numeric casts within the currently admitted constant forms. It uses
  these same wrapping, rounding, truncated-range and nullable-presence rules.
  [Enum constant contract](../../cloth-language/references/value-operators.md#enum-values-and-formatting)

## Booleans and storage

- `bool` stores the boolean values `true` and `false`.
- Boolean bindings support both explicit `def<bool>` annotations and inference
  from boolean initializers, following the ordinary `=` and `:=` binding rules.
- Prefix `!` accepts a `bool` and returns its logical negation as a `bool`:
  `!true` is `false`, and `!false` is `true`. Numeric operands are errors;
  there is no implicit numeric truthiness. Repeated negation such as `!!alive`
  and parenthesized operands are permitted. Negation itself does not modify its
  operand; `alive = !alive` uses ordinary reassignment.
- `&&` and `||` accept two `bool` operands and return `bool`. They evaluate
  left to right with short circuiting: `a && b` evaluates `b` only when `a` is
  `true`; `a || b` evaluates `b` only when `a` is `false`. Each evaluated operand
  runs once. Both operands must be valid and well typed even when runtime
  evaluation would skip the right operand. No numeric truthiness is introduced.
- For `bool` operands, `==` returns `true` when the values match and `!=` returns
  `true` when they differ. Both return `bool` and evaluate both operands once,
  from left to right; they do not short-circuit. No numeric conversions are
  introduced by boolean equality.
- Each `bool` uses its own byte of storage for now. This user decision supersedes
  the earlier one-bit storage goal and shared-byte packing implementation.
- Boolean values still have only two values; byte storage does not introduce
  numeric conversions or change the ordinary binding rules.
- Packed collections, field layout, atomics, and foreign interfaces have not
  been designed by this decision.
