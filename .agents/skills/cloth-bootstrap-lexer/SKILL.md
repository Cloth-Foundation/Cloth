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
- [TokenList.co](../../../Bootstrap/Frontend/token/TokenList.co) owns nodes and
  tokens. Its cursor borrows them. This is not generic collection support.
  [Growable arrays Stage 7](../cloth-arrays/references/growable-arrays-stage-7.md)
  proves concrete array-backed collections but preserves this owning list and
  borrowed cursor. Default value storage does not implicitly delete Tokens;
  migration needs its own ownership/partial-error/cursor/source lifetime tests.
  The [generic Stage 6 caller audit](../cloth-generics/references/caller-audit.md)
  confirms those contracts against the current sources. Public List now exists,
  but its class-element identity semantics do not replace this owning container.
- [LexerError.co](../../../Bootstrap/Frontend/lexer/LexerError.co) retains one
  owned failure. Destroy partial tokens and stop at the first error.
- [Main.co](../../../Bootstrap/Frontend/Main.co) scans the sample or first
  program argument as text, preserving the dogfooding banner.

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
for graph/native/foreign-object checks and [ShuttlePublicationTest](../../../Shuttle/src/test/kotlin/shuttle/ShuttlePublicationTest.kt)
for nested-project command checks. Publish actual scope/evidence through
[feature integration](../cloth-feature-integration/SKILL.md).
