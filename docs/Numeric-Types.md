# Numeric Types

Loom provides a compact, explicit set of numeric primitives for systems-level and application programming. This page describes built-in numeric types, literal syntax (bases and separators), and type suffixes.

## Built-in Numeric Types

- Signed integers: `i8`, `i16`, `i32`, `i64`
- Unsigned integers: `u8`, `u16`, `u32`, `u64`
- Floating-point: `f16`, `f32`, `f64`
- Byte: `byte` (alias of `u8`)
- Bit: `bit` (1-bit integer; only 0 or 1)
- Bool: `bool` (logical true/false; conventionally backed by a bit)

## Numeric Literals

Loom supports integer and floating-point literals in multiple bases. Readability separators (`_`) are allowed within digits.

### Integer literals

- Decimal (no prefix): `0`, `42`, `1_000_000`
- Hexadecimal (`0x`/`0X`): `0xA`, `0xFF`, `0xDEAD_BEEF`
- Binary (`0b`/`0B`): `0b1010`, `0b1111_0000`
- Octal (`0o`/`0O`): `0o755`, `0o7_123`

### Floating-point literals

- Decimal only, with `.` separating integral and fractional parts
- Examples: `0.0`, `3.14`, `3.141_592_653_59`

Note: Scientific notation (exponents) is planned but not yet supported.

### Type suffixes

Literals may carry an explicit type suffix to pin their type without inference.

- Integer suffixes: `i8`, `i16`, `i32`, `i64`, `u8`, `u16`, `u32`, `u64`
- Float suffixes: `f16`, `f32`, `f64`

Examples:

```
10i32            # decimal i32
0xFFu8           # hex u8
0b1111_0000u16   # binary u16
0o755u16         # octal u16
3.14f32          # 32-bit float
3.1415926535f64  # 64-bit float
```

Invalid combinations are rejected by the type system. For example: `3.14i32` (float literal with integer suffix) is invalid.

## Type inference and conversions

- With a suffix, the literal’s type is explicit.
- Without a suffix, type is inferred from context (assignment target, operator expectations). Where no context exists, compiler defaults apply.
- Conversions use `as`, with overflow/precision semantics defined by the destination type.

Examples:

```
let a: i8 = 10          # inferred i8 from context
let b = 0xA             # inferred from context/use; hex form is permitted
let c = 0b1010u32       # explicit u32 suffix
let d = 3.0f32          # explicit f32
let e = (c as i64)      # cast to i64
```

## Bit and Bool

- `bit` holds only `0` or `1`. Designed for low-level bitwise logic.
- `bool` represents logical truth values `true` and `false`.

```
let on: bit = 1
let off: bit = 0

let truth: bool = true
let flag: bit = truth ? 1 : 0
```

## Separators and readability

- Underscores are permitted as digit separators in integer and floating-point literals.
- They have no effect on the numeric value.

Examples: `1_000_000`, `0xDEAD_BEEF`, `3.141_592`.

## Future extensions

- Scientific notation for floats (e.g., `1.23e-4`).
- Wider integer/float types (e.g., `i128`, `u128`, `f128`).
- Configurable default inference rules for unannotated literals.