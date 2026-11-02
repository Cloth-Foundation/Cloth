# Cloth numeric and boolean operations: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

## Integer signedness and widths

- `int` supports 8, 16, 32 (default), and 64 bits, signed or unsigned. Explicit
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
