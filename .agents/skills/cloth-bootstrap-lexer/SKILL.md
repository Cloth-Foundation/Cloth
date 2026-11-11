---
name: cloth-bootstrap-lexer
description: Develop Bootstrap's string-input Cloth lexer port, compare tokens and first errors with Kotlin, preserve ownership, and identify library boundaries.
---

# Bootstrap lexer

Read [scope, ownership, boundaries and verification](references/port.md).
The user confirmed on 2026-11-07 that Bootstrap currently targets a faithful copy
of the Kotlin lexer using a string. Parsing and later compiler stages are outside
this slice. Expose concrete library requirements before proposing new facilities.

The [Kotlin lexer](../../../Compiler/src/main/kotlin/cloth/lexer/Lexer.kt),
[keywords](../../../Compiler/src/main/kotlin/cloth/token/Keywords.kt),
[operators](../../../Compiler/src/main/kotlin/cloth/token/Operators.kt) and
[errors](../../../Compiler/src/main/kotlin/cloth/diagnostics/LexerErrors.kt) are
the comparison authority. Keep Bootstrap's explicit spelling enums synchronized.

- [Lexer.co](../../../Bootstrap/Frontend/lexer/Lexer.co) scans without printing.
  Retain all literal forms, recursive interpolation, original lexemes and EOF.
- [Token.co](../../../Bootstrap/Frontend/token/Token.co) owns tokens/spans and
  JSON formatting. Ordinary growing concatenation is supported by the compiler's
  [string storage fix](../cloth-buffer-lifetimes/references/string-concatenation-storage.md);
  do not restore the recursive chunk workaround.
- `TokenList` stores tokens in the standard-library `List<Token>`. Appending
  transfers ownership to `TokenList`, which deletes each token before its backing
  list. Traverse with `size()` and `get(usize index)`. The returned tokens are
  borrowed from the list. [TokenList.co](../../../Bootstrap/Frontend/token/TokenList.co) ·
  [Collection contract](../cloth-collections/SKILL.md)
- [LexerError.co](../../../Bootstrap/Frontend/lexer/LexerError.co) retains one
  owned failure. Destroy partial tokens and stop at the first error.
- The Bootstrap driver opens a source path from a positional argument or
  `--file <path>` before reading stdin. Use `--text <source>` for literal
  source and zero arguments for an interactive line. [Main.co](../../../Bootstrap/Frontend/Main.co)

Preserve UTF-8 strings and scalar chars. Internal substring indices are scalar;
published positions are one-based UTF-16 columns with exclusive ends. Do not
change Cloth's Unicode predicates to imitate a particular JVM version.
Read [strings](../cloth-strings/SKILL.md), [arrays](../cloth-arrays/SKILL.md),
[class lifetimes](../cloth-language/references/class-lifetime.md),
[buffer lifetimes](../cloth-buffer-lifetimes/SKILL.md),
[nullability](../cloth-nullability/SKILL.md) and [imports](../cloth-imports/SKILL.md)
when changing those interactions. [Shuttle](../shuttle-build/SKILL.md) owns builds.

Run [BootstrapLexerTest](../../../Compiler/src/test/kotlin/cloth/BootstrapLexerTest.kt)
for native differential/ownership coverage, [ImportPublicationTest](../../../Compiler/src/test/kotlin/cloth/ImportPublicationTest.kt)
for graph/native/foreign-object checks and [ShuttlePublicationTest](../../../Shuttle/native/src/jvmTest/kotlin/shuttle/ShuttlePublicationTest.kt)
for nested-project command checks. Publish actual scope/evidence through
[feature integration](../cloth-feature-integration/SKILL.md).
