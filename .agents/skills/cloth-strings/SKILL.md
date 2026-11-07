---
name: cloth-strings
description: Design, explain, or extend Cloth characters, strings, Unicode validation, character and string attachments, full Unicode string casing, interpolation, scalar-indexed methods, literal delimiter splitting, literal editing, whitespace methods, typed member calls, and string function boundaries.
---

# Cloth strings and characters

[Arbitrary integer Stage 5](../cloth-numerics/references/arbitrary-width-output.md)
extends exact decimal interpolation and `::str` to every signed/unsigned width
1..65536. It shares the output provider path, including nullable/nested arrays,
and preserves effects, UTF-8 byte lengths and caller-owned returned text.
Floating formats/formatting and string concatenation rules are unchanged.

Read [confirmed string and character semantics](references/semantics.md).
Read [character attachments](references/character-attachments.md) for the separate
`CharAttachments` provider's six bool predicates and int32 `toInt`. Preserve pinned
Unicode 17.0.0 White_Space, Letter L, decimal Nd and binary Uppercase/Lowercase
properties; case does not imply Letter. Use immutable range data and per-module
binary-search helpers, not host predicates. Keep shared registration/evaluation,
nullable and cleanup rules. `CharAttachmentsTest` owns exhaustive scalar native
oracles and portability fixtures; the record owns data regeneration and licensing.
Read [character casing](references/character-case.md) for implemented
`toUpperCase()` and `toLowerCase()`: char results, pinned Unicode 17.0.0 simple
mappings, zero arguments, locale-independent behavior and unchanged unmapped
scalars. `CharCaseTest` checks both mappings exhaustively against the official
raw data. Their char behavior remains distinct from full string casing.
Read [full string casing](references/string-case.md) for string-returning
`toUpperCase`/`toLowerCase`, Unicode 17 full expansions, original-text final-sigma
context, immutable Cased/Case_Ignorable tables and checked UTF-8 measure/fill.
Locale tailoring, normalization, folding and titlecase APIs remain outside scope.
Read [string attachments and member calls](references/member-calls.md) for
`isEmpty`, `contains`, `startsWith`, `endsWith`, typed registry ownership,
receiver-call traversal, diagnostics, lifetime rules and target evidence.
Keep declarations consistent with the other primitive attachments: named registrations use
inherited `PrimitiveAttachment.valueMethod` for explicitly read-only, independent
results; unproven bodies retain `register`'s unknown storage default.
Read [scalar-indexed string methods](references/scalar-methods.md) for `charAt`,
`substring`, `indexOf`, `lastIndexOf` and `toChars`, target count specialization,
UTF-8 traversal, nullable searches and independent buffered results.
Read [literal delimiter splitting](references/split-method.md) for
`split(char)` and `split(string)` returning `string[]`, exact primitive overload
groups, preserved empty pieces, scalar-boundary empty delimiters, checked
descriptor allocation and independent nested string storage.
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
  Additional primitive APIs remain feature-specific. Split's char/string
  overload group is implemented through exact typed binding. Read-only member
  bodies opt into independent-result storage contracts;
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
- `split` takes exactly one positional `char` or `string`, returning independent
  `string[]`. String delimiters match literally, left to right without overlap;
  an empty delimiter matches every Unicode scalar boundary, preserving leading
  and trailing empty pieces. Empty input yields one empty string for nonempty
  delimiters, two for an empty delimiter. Count with checked target arithmetic
  before allocating descriptors, then fill from private UTF-8 bytes. Preserve
  fresh-array adoption, fixed receiving shapes and recursive outgoing string
  copies. Regexes, limits and remove-empty options are not introduced.
- Whitespace methods use Unicode 17.0.0 White_Space, independent of host locale
  or JVM/C predicates. `isBlank` is true for empty text. Trim variants remove only
  the requested boundaries and return independent strings; internal whitespace,
  NULs and non-property scalars remain content. Decode complete live UTF-8 scalars
  before classification and keep byte counts target-sized. Version upgrades
  require explicit semantic review; no custom trim-character arguments are added.
- String casing takes no arguments and returns independent strings using pinned
  Unicode 17 default full mappings, including expansions and final-sigma context.
  Consume Case_Ignorable before testing Cased when scanning original context;
  the properties overlap. Check output bytes before allocation and encode without
  per-scalar buffers. Extend the existing D generator; retain existing char data
  and behavior. Do not use host casing or introduce normalization/locale options.
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
[ExpressionTyping](../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt),
[EmitString](../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitString.kt),
and [EmitOutput](../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitOutput.kt).
Examples: [Strings.co](../../../examples/Strings.co),
[Characters.co](../../../examples/Characters.co), and
[StringFunctions.co](../../../examples/StringFunctions.co).
[StringAttachments.co](../../../examples/StringAttachments.co) includes delimiter splitting.
[StringCase.co](../../../examples/StringCase.co) demonstrates full string casing.

Relevant suites are `StringCaseTest`, `StringSplitTest`, `StringMemberStorageTest`, `StringWhitespaceTest`, `StringEditingTest`, `StringScalarMethodsTest`, `StringAttachmentsTest`, `CharactersTest`, `StringsTest`, `StringInterpolationTest`, `ArrayTextTest`,
and `StringFunctionTest`. Their existing cases cover Unicode boundaries, raw and
quoted literals, NULs, formatting, source spans, skipped holes, independent copies,
recursion and balanced cleanup. Native literal and interpolation fixtures
assert explicit output through the [shared runner](../cloth-language/SKILL.md#compiler-fixtures).
Read the
[formatting guard record](../../../todo/native-formatting-guards.md) and
[development history](../cloth-language/references/development.md#earlier-milestone-snapshots)
for recorded evidence. Recheck current code and run relevant tests before making
new verification claims; escaping into future class objects remains unresolved.
