---
name: cloth-strings
description: Design, explain, or extend Cloth character and string literals, Unicode validation, interpolation, string equality and concatenation, and string function boundaries.
---

# Cloth strings and characters

Read [confirmed string and character semantics](references/semantics.md).
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
  formatting remain design work. String indexing and content mutation are not
  implemented; do not infer support from arrays or other languages.

## Source and verification

Inspect [Lexer](../../../Compiler/src/main/kotlin/cloth/lexer/Lexer.kt),
[ExpressionTyping](../../../Compiler/src/main/kotlin/cloth/semantic/ExpressionTyping.kt),
[EmitString](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitString.kt),
and [EmitCharacter](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitCharacter.kt).
Examples: [Strings.co](../../../examples/Strings.co),
[Characters.co](../../../examples/Characters.co), and
[StringFunctions.co](../../../examples/StringFunctions.co).

Relevant suites are `CharactersTest`, `StringsTest`, `StringInterpolationTest`, `ArrayTextTest`,
and `StringFunctionTest`. Their existing cases cover Unicode boundaries, raw and
quoted literals, NULs, formatting, source spans, skipped holes, independent copies,
recursion and balanced cleanup. Read the
[formatting guard record](../../../todo/native-formatting-guards.md) and
[development history](../cloth-language/references/development.md#earlier-milestone-snapshots)
for recorded evidence. Recheck current code and run relevant tests before making
new verification claims; escaping into future class objects remains unresolved.
