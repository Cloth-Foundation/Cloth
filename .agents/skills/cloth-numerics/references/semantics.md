# Cloth numeric and boolean operations: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

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
- Existing supported-width and target-sized extent checks remain in force:
  `int<0x07>` remains an invalid width. Floating literals cannot supply integer
  widths/extents. These extensions are implemented; see the
  [literal implementation record](numeric-literals.md) for source and evidence.

## Integer signedness and widths

- Intended arbitrary widths are confirmed in the
  [new width contract](#arbitrary-fixed-integer-widths-design-confirmed-2026-11-03)
  below; compiler support currently remains the historical widths listed here.
- The existing compiler supports 8, 16, 32 (default), and 64 bits, signed or
  unsigned. Explicit
  widths use the required angle-bracket syntax: `int<8>`, `int<16>`, `int<32>`,
  and `int<64>`. Width is a parameter of the numeric type, not a separate type
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
- Compiler implementation is deferred. The
  [implementation plan](../../../../todo/arbitrary-integer-widths-plan.md)
  records source findings, staged lowering/layout/output work and required
  verification. Odd-width storage, private wide-value carriers and Windows x86
  policy are implementation proposals in that plan, not established new ABI or
  packing guarantees. Current support remains 8/16/32/64 pending integration.

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
