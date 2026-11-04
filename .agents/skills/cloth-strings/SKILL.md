---
name: cloth-strings
description: Design, explain, or extend Cloth character and string literals, Unicode validation, interpolation, equality and concatenation, string attachments, scalar-indexed methods, literal editing, Unicode trimming and blank checks, typed member calls, and string function boundaries.
---

# Cloth strings and characters

[Arbitrary integer Stage 5](../cloth-numerics/references/arbitrary-width-output.md)
extends exact decimal interpolation and `::str` to every signed/unsigned width
1..65536. It shares the output provider path, including nullable/nested arrays,
and preserves effects, UTF-8 byte lengths and caller-owned returned text.
Floating formats/formatting and string concatenation rules are unchanged.

Read [confirmed string and character semantics](references/semantics.md).
Read [string attachments and member calls](references/member-calls.md) for
`isEmpty`, `contains`, `startsWith`, `endsWith`, typed registry ownership,
receiver-call traversal, diagnostics, lifetime rules and target evidence.
Read [scalar-indexed string methods](references/scalar-methods.md) for `charAt`,
`substring`, `indexOf`, `lastIndexOf` and `toChars`, target count specialization,
UTF-8 traversal, nullable searches and independent buffered results.
Read [literal string editing](references/editing-methods.md) for `replace`,
`replaceFirst`, `removePrefix` and `removeSuffix`, scalar-boundary empty matches,
non-overlapping literal matching, checked measure/fill and independent results.
Read [Unicode whitespace methods](references/whitespace-methods.md) for `trim`,
`trimStart`, `trimEnd` and `isBlank`, pinned White_Space classification and
independent UTF-8 boundary copies.
Read [string member storage cleanup](../cloth-buffer-lifetimes/references/string-member-storage.md)
before changing operand copies, callable storage contracts or member temporary regions.
Use [buffer lifetimes](../cloth-buffer-lifetimes/SKILL.md) for string parameters,
returned text, interpolation buffers, or nested string arrays; use
[output](../cloth-output/SKILL.md) for built-in printing.

## Development rules and current boundaries

- A character is one Unicode scalar, stored in 32 bits. Validate literal escapes
  and reject isolated surrogates. Character ordering/arithmetic/numeric casts
  remain separate unresolved design work.
- Strings retain well-formed UTF-8 with explicit byte lengths, including embedded
  NULs. Equality compares exact contents without Unicode normalization or case
  folding. `+` requires two strings and never converts another value implicitly.
- The four initial string queries return bool and use exact, case-sensitive
  matching; empty search strings match. Resolve dot calls by receiver type,
  preserve once-only receiver-before-argument value capture and ordinary nullable
  proofs, and reuse bound built-in emission without global LLVM handles.
  Other unregistered string/integer methods, static/class methods and member overloads remain
  deferred. Read-only member bodies opt into independent-result storage contracts;
  immutable string operands avoid redundant byte copies. Cleanup proofs include
  safe member regions while unknown/captured publication retains frame storage.
- Scalar position methods use zero-based Unicode scalar indices and exact target
  `usize` parameters. Substrings have exclusive ends and independent contents;
  `toChars` yields independent `char[]`. Searches return `usize?`, preserving
  explicit nullable-binding rules and absence. Empty-needle first/last positions
  are zero/scalar-count. Bounds fail through located runtime guards after operand
  effects. Reuse existing result freshness/recursive return copies for buffers.
- Literal editing returns independent strings. Replacement consumes original
  matches left to right without overlap or rescanning replacement text. Empty
  needles match scalar boundaries, including start/end; first replacement only
  inserts at the start. Affix removal strips one matching boundary or preserves
  contents. Measure output with checked target-byte additions, allocate once,
  then fill from the same stable operand values. Retain unproven frame lifetimes.
- Whitespace methods use Unicode 17.0.0 White_Space, independent of host locale
  or JVM/C predicates. `isBlank` is true for empty text. Trim variants remove only
  the requested boundaries and return independent strings; internal whitespace,
  NULs and non-property scalars remain content. Decode complete live UTF-8 scalars
  before classification and keep byte counts target-sized. Version upgrades
  require explicit semantic review; no custom trim-character arguments are added.
- Interpolation uses `#"value: {expression}"`. Check holes even in skipped code;
  evaluate them once, left to right, preserving early returns. Formatting resolves
  value-receiver `str` providers, including recursive array contents; see the
  [array text contract](../cloth-output/references/semantics.md#recursive-array-text).
- Current interpolation policy uses `{{`/`}}` for literal braces, ordinary quoted
  escapes in text, no literal text line breaks, and no custom format specifiers
  or raw interpolated strings. Preserve original hole spans through nested lexing.
  These are implementation choices unless separately confirmed.
- Unsupported value interpolation must fail with a located capability
  diagnostic before emission/toolchain/output work. Future `toString()` and class
  formatting remain design work. Direct string `[]` access and content mutation
  remain unimplemented; scalar-indexed methods do not introduce those forms.

## Source and verification

Inspect [Lexer](../../../Compiler/src/main/kotlin/cloth/lexer/Lexer.kt),
[ExpressionTyping](../../../Compiler/src/main/kotlin/cloth/semantic/ExpressionTyping.kt),
[EmitString](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitString.kt),
and [EmitCharacter](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitCharacter.kt).
Examples: [Strings.co](../../../examples/Strings.co),
[Characters.co](../../../examples/Characters.co), and
[StringFunctions.co](../../../examples/StringFunctions.co).

Relevant suites are `StringMemberStorageTest`, `StringWhitespaceTest`, `StringEditingTest`, `StringScalarMethodsTest`, `StringAttachmentsTest`, `CharactersTest`, `StringsTest`, `StringInterpolationTest`, `ArrayTextTest`,
and `StringFunctionTest`. Their existing cases cover Unicode boundaries, raw and
quoted literals, NULs, formatting, source spans, skipped holes, independent copies,
recursion and balanced cleanup. Read the
[formatting guard record](../../../todo/native-formatting-guards.md) and
[development history](../cloth-language/references/development.md#earlier-milestone-snapshots)
for recorded evidence. Recheck current code and run relevant tests before making
new verification claims; escaping into future class objects remains unresolved.
