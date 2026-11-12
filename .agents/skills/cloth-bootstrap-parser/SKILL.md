---
name: cloth-bootstrap-parser
description: Extend Bootstrap's Cloth parser, AST, and first syntax errors. Use for its token cursor, arithmetic precedence, tree ownership, and comparison with the Kotlin parser.
---

# Bootstrap parser

The first slice parses zero or more parameterless `fn` declarations with brace
bodies and optional `pub`. Bodies accept single-name `def` bindings with `=` or
`:=`. Newlines and semicolons separate bindings, and a brace-bodied function
ends at a newline or EOF. Numeric literals, name references, parentheses, unary
`+` and `-`, and binary `+`, `-`, `*`, `/`, `%`, and `**` form expressions.
Power associates to the right and binds tighter than unary signs. The other
arithmetic operators associate to the left.

```powershell
.\gradlew.bat :Shuttle:run --args='build --project Bootstrap'
.\Bootstrap\build\Cloth.exe --parse --text 'fn main() { def answer := 1 + 2 * 3 }'
```

The tree for this source is `(source (fn main (def answer := (+ 1 (* 2 3)))))`.
Use `--parse --file <path>` or `--parse <path>` for UTF-8 files and `--parse`
alone for an interactive line. The driver selects lexing when the leading
`--parse` flag is absent. [Bootstrap driver](../../../Bootstrap/Frontend/Main.co)

The slice ends at syntax. Parameter and result types, typed or grouped bindings,
other statements and expressions, imports, and semantic analysis are future
work. The AST retains numeric spellings. Numeric decoding and the Kotlin
parser's integer literal size check are outside this slice.

## Ownership and diagnostics

- Pass the EOF-terminated `TokenList` from `Lexer.scanTokens()` to
  `Parser::new(tokens)`. Call `parse()` once. On success it transfers a
  `SourceUnit` that owns its functions in declaration order. Each function owns
  its bindings, each binding owns its initializer, and each operator expression
  owns its operands in source order. Parentheses change the shape without
  adding a node. The nodes borrow their tokens and spans.
  [Parser](../../../Bootstrap/Frontend/parser/Parser.co) ·
  [AST](../../../Bootstrap/Frontend/parser/Ast.co)
- On the first syntax error, `parse()` returns `null`, releases partial nodes,
  and retains one owned `ParseFailure` with the offending token. Keep the token
  list and source alive while using the tree or the failure. The parser can be
  deleted before a successful tree. Delete the tree and parser before deleting
  the token list, and delete the source last.
  [Lexer ownership](../cloth-bootstrap-lexer/SKILL.md)
- Compare accepted syntax, precedence, separator rules, and diagnostic
  locations with Kotlin. The driver prints the AST on success and one
  code/span/message diagnostic on a lexical or syntax failure. It returns 1
  on failure. Syntax messages describe the supported subset. Use the Bootstrap
  keyword and operator enums for spellings. [Kotlin parser](../../../Compiler/src/main/kotlin/cloth/parser/Parser.kt) ·
  [Arithmetic precedence](../../../Compiler/src/main/kotlin/cloth/parser/subs/ExpressionParser.kt) ·
  [Syntax errors](../../../Compiler/src/main/kotlin/cloth/diagnostics/SyntaxErrors.kt)

## Verification

[ImportPublicationTest](../../../Compiler/src/test/kotlin/cloth/ImportPublicationTest.kt)
compares the printed AST with the Kotlin tree and checks first diagnostic codes,
spans, and exit statuses. It also checks text and file input, the reachable
source graph, and executable IR and objects. The allocator probe in
[BootstrapLexerTest](../../../Compiler/src/test/kotlin/cloth/BootstrapLexerTest.kt)
checks release after successful scans and parses and after their first errors.

On 2026-11-11, the five focused compiler tests and four
[Shuttle publication tests](../../../Shuttle/native/src/jvmTest/kotlin/shuttle/ShuttlePublicationTest.kt)
passed in an isolated checkout. The compiler tests ran Bootstrap natively on
Windows x64 and x86 and verified IR and startup objects for all nine ABI
profiles. Shuttle built and ran the working Bootstrap project with a copy of
the standard library whose moved `File.co` imported `cloth.io.path.Path`.
The resulting executable parsed text, a positional file, and standard input,
and returned 1 on a missing closing parenthesis.
