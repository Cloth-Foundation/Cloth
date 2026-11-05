# Numeric literal implementation (2026-11-03)

The [confirmed numeric contract](semantics.md) owns the syntax and type rules.
The [implementation plan](../../../../todo/numeric-literals-plan.md) records the
stages and scope. This record describes the integrated compiler behavior and
verification; unresolved forms are not additional accepted language syntax.

The later [arbitrary-width Stage 2](arbitrary-width-types.md) supersedes this
milestone's fixed-width restriction and uint64 expression-magnitude ceiling:
semantic integer widths and literal magnitudes now extend through 65536 bits,
while [Stage 3](arbitrary-width-arithmetic.md) advances source scalar emission to
1..64. [Stage 4](arbitrary-width-storage.md) integrates source values through the
cap. [Stage 5](arbitrary-width-output.md) integrates full decimal output and
retires C4031.

## Implemented forms and constraints

- Lowercase `0x`, `0b` and `0o` select hexadecimal, binary and octal integer
  notation. Hexadecimal digits accept either case. Decimal `i` / `I` selects
  integer literals, and `f` / `F` selects floating literals without fixing width.
- `123f`, `123.f`, `123.0f` and uppercase suffix equivalents are accepted.
  `123.i`, `123.I` and fractional integer-suffix forms are rejected. Base-prefixed
  literals do not accept suffixes; hexadecimal `f` / `F` remain digits.
- Separators must lie between digits valid for the selected base. Missing digits,
  invalid digits and unknown/repeated/forbidden suffixes fail immediately. Decimal
  scanning preserves its existing Unicode digit support; prefixed digits use the
  ASCII base alphabets. Floating decimal digits normalize to ASCII for LLVM.
- Original token spellings and UTF-16 spans include prefixes, separators, decimal
  points and suffixes. Floating AST nodes retain their original token separately
  from exact normalized decimal text; integer AST values remain `BigInteger`.
- Integer literals receive fixed-width signed/unsigned, `usize` or `isize`
  context and must fit that type. Uncontextualized integers still default to int32.
  A sign is a unary operator, preserving signed-minimum literal handling.
- Floating literals, both suffixed and unsuffixed, receive floating width from
  bindings, assignments, returns, calls, arrays, nullable payload contexts and
  ordered reception. Without context, literals retain float32. Normal target-format rounding
  and finite range checks apply; no host Float/Double conversion is introduced.
- A non-floating destination does not supply a floating width. Ordinary
  uncontextualized arithmetic and default-type overload preference are retained.
  Typed bindings/call results still require exact types or explicit casts.
- Declared widths are authoritative: `def<int<8>> x = 0xDEADBEEF`, signed int8
  `0x80` and float16 `70000f` fail. Literal assignment never silently truncates
  or widens the destination. Explicit casts retain their existing conversions
  after evaluating their source in its own type.
- All confirmed integer forms also work in numeric type widths and fixed array
  extents. `int<0x08>` equals `int<8>`, and `int[0b11]` / `int[3i]` have three
  elements. Supported-width and target-sized extent limits remain unchanged.
  Structural signature keys use numeric values, not source notation.
- The lexer leaves range operators intact and does not consume the `f` prefix
  of a longer identifier after a dot. Interpolation uses the same scanner.

Scientific notation, uppercase base prefixes, hexadecimal floats, leading-dot
floats and width/signedness suffixes are unsupported. Unsuffixed `123.` retains
its previous integer-plus-dot tokenization; this work does not confirm it as a
float literal. This literal milestone did not introduce arbitrary-width types
or implicit typed-value conversions; the later Stage 2 adds the width model.

## Source ownership and diagnostics

- [Lexer.number](../../../../Compiler/src/main/kotlin/cloth/lexer/Lexer.kt)
  owns numeric token boundaries, digit/separator rules and suffix categories.
- [NumericLiterals](../../../../Compiler/src/main/kotlin/cloth/token/NumericLiterals.kt)
  centralizes integer radix decoding and exact floating decimal normalization.
- [ExpressionParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ExpressionParser.kt)
  builds numeric AST values. Floating range checks occur during typing, after
  context is known; wider declarations/candidates can admit values beyond float32.
  The literal milestone retained the uint64 parser ceiling; Stage 2 lifts that
  bound to 65536-bit magnitudes without changing no-context int32 inference.
- [TypeResolver](../../../../Compiler/src/main/kotlin/cloth/semantic/TypeResolver.kt)
  and [SignatureType](../../../../Compiler/src/main/kotlin/cloth/semantic/symbols/Symbols.kt)
  share integer decoding for widths/extents. Arbitrarily large width tokens are
  rejected by supported-width validation without narrowing or format exceptions.
- [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/ExpressionTyping.kt)
  propagates floating context through the existing reception machinery, including
  nullable and array types. [CallAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/CallAnalyzer.kt)
  retains default-type preference and contextual candidate range filtering.
- Existing constant emission and `RangeConstants` consume normalized AST values;
  they do not interpret literal spelling or choose host-dependent integer bounds.

C1013 / INVALID_NUMERIC_LITERAL identifies malformed numeric syntax through
`LexerErrors.number`. C1002 retains separator errors. C2004 retains the expression
integer magnitude ceiling, C3004 contextual integer range errors, C3028 contextual
floating range errors, and C3011 unsupported widths. CLI errors return 1 before
object/linker work. Rendering may show adjacent source lines as context while
reporting only the first diagnostic; this is not multi-error reporting.

## Verification

- `NumericLiteralsTest`: source-preserving tokens/spans, exact decoding, Unicode
  decimal compatibility, CRLF/EOF/interpolation, malformed literals, range/member
  boundaries, every fixed integer width/signedness in all bases, initialization
  and reassignment failures, target-sized bounds with one reused AST, numeric
  widths/extents and duplicate structural signatures, floating width/range
  contexts, strict typed values and overload preference/filtering.
- `NumericLiteralsIntegrationTest`: native Windows x64 and Windows x86 PE32
  execution of exact integer edges, float rounding, nullable fallback, optional
  defaults, conditional and ordered returns, arrays, element writes, positional
  and broadcast reception, numeric ranges, metadata and interpolation. The same
  mixed AST verifies LLVM IR and emits objects across all nine supported ABI
  profiles. Foreign native Linux/macOS execution is not claimed.
- The integration suite executes [NumberTypes.co](../../../../examples/NumberTypes.co)
  on Windows x64/x86 and checks CLI malformed/range/width failures for one
  diagnostic, original source text, exit 1 and absence of output objects.
- Existing strict assignment and return tests now distinguish contextual literals
  from typed values. Legacy snippet float overflow checks now occur in semantics,
  with decimal rounding/finite-range runtime coverage preserved.

Focused and full compiler regression results are recorded in
[development history](../../cloth-language/references/development.md#numeric-literals-2026-11-03).
The initial focused run passed 175 tests across 11 suites; its full compiler run
passed all 1,027 tests across 78 suites, with zero failures, errors or skips.

## Arithmetic context review fixes

The 2026-11-04 [float attachment integration](float-attachments.md) also repairs
Elvis fallback inference when no enclosing float context exists: nullable float
payloads supply literal width for member receivers/output arguments. This retains
float64 precision and half identity while rejecting mismatched typed fallbacks.
`FloatAttachmentsTest` owns the new native regressions; the focused integration
command also reruns both numeric-literal suites and all scalar nullability suites.

The 2026-11-03 review reproduced two missing context paths. Integer peer context
could overwrite floating context and round `16777217f` to float32 before storing
a float64 result; nullable arithmetic destinations could drop their payload width
and reject valid expressions or check their literals against float32 bounds.

`ExpressionTyping.typeOf` / `resultOf` now carry an optional floating literal
format independently of the ordinary expected type used by integer literals.
Binary arithmetic discovers integer peer context and floating context separately,
unwraps a nullable receiving payload, and forwards the floating format through
nested arithmetic, unary expressions and conditional result branches. Elvis
fallbacks retain their own payload context. Calls, casts and statement scopes do
not inherit an enclosing arithmetic format as a replacement for their own contracts.

Regressions preserve exact typing of stored values, unsigned and narrow integer
literal bounds, target-sized identities, float16/32/64 overflow checks, and the
existing minimum-float32 mixed arithmetic rule. Nullable operands still require
presence proofs; receiving a non-null arithmetic result into a nullable binding
does not permit arithmetic on absent values.

Two new semantic tests cover integer-peer orders/types, nested/unary/conditional
arithmetic, initialization/reassignment, nullable returns/calls/array elements,
valid values beyond float32 range in float64 context, and range/type failures.
A new native regression prints the formerly rounded value as `16777217` and
executes nullable arithmetic receptions on Windows x64/x86. Both the original
literal fixture and the new arithmetic fixture verify IR and emit objects across
all nine supported ABI profiles. CLI regressions check contextual range failures
still produce one diagnostic before object/linker work.

All three new tests failed before the fix and passed afterward. The expanded
focused numeric/call/return/reception/range run passed 131 tests across six suites,
with zero failures/errors/skips. The final full compiler run passed all 1,030
tests across 78 suites, with zero failures, errors or skips. Metadata, 126 local
link targets across six changed Markdown files and `git diff --check` passed
publication checks; detailed results are recorded in the development history.
