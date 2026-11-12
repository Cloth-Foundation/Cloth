---
name: cloth-bootstrap-parser
description: Extend Bootstrap's Cloth parser, AST, and first syntax errors. Use for its token cursor, arithmetic precedence, tree ownership, and comparison with the Kotlin parser.
---

# Bootstrap parser

Bootstrap parses zero or more brace-bodied `fn` declarations with optional
`pub`. The optional `fn<T>` and `def<T>` annotations and each named parameter
use a single primitive keyword or identifier for the type. Newlines are
allowed inside either annotation. Parameter lists accept an empty list or
comma-separated items with newlines around items and commas. A trailing comma
reports an expected type at the closing parenthesis. Bodies accept single-name
`def` bindings with `=` or `:=` and bare `return`. Newlines and semicolons
separate statements. A bare `return` can also appear immediately before
`}`. At EOF inside the body, the parser expects the closing brace.
A brace-bodied function ends at a newline or EOF. Numeric literals, name
references, parentheses, unary `+` and `-`, and binary `+`, `-`, `*`, `/`,
`%`, and `**` form expressions.
Power associates to the right and binds tighter than unary signs. The other
arithmetic operators associate to the left.

```powershell
.\gradlew.bat :Shuttle:run --args='build --project Bootstrap'
.\Bootstrap\build\Cloth.exe --parse --text 'fn<void> finish() { def<int> saved := 1; return }'
```

The tree is `(source (fn (returnTypes void) finish (def (type int) saved := 1) (return)))`.
Use `--parse --file <path>` or `--parse <path>` for UTF-8 files and `--parse`
alone for an interactive line. The driver selects lexing when the leading
`--parse` flag is absent. [Bootstrap driver](../../../Bootstrap/Frontend/Main.co)

The slice ends at syntax. Multiple result types, composite types, parameter
modifiers and defaults, grouped bindings, other statements
and expressions, imports, and semantic analysis are future work. The AST
retains numeric spellings. Numeric decoding and the Kotlin parser's integer
literal size check are outside this slice.

## Parser layout

- `Parser` coordinates the source unit and its bodies. `ParseCursor` borrows
  the token list and owns token navigation and the first failure during
  parsing. It transfers that failure to `Parser.failure` when the source
  unit fails. [Parser](../../../Bootstrap/Frontend/parser/Parser.co) ·
  [ParseCursor](../../../Bootstrap/Frontend/parser/ParseCursor.co)
- The declaration subparser owns the function header, result annotation,
  and parameter-list separators. The type subparser reads plain types and
  owns `parsePlainTypeAnnotation()`, the angle-bracket rule used by
  `fn<T>` and `def<T>`. It expects `>` after the type, so a comma reports
  the first error at that comma.
  Organize new subparsers by their grammar as that grammar grows.
  [Function header parser](../../../Bootstrap/Frontend/parser/declarations/FunctionHeaderParser.co) ·
  [Type parser](../../../Bootstrap/Frontend/parser/types/TypeParser.co)
- The body parser dispatches statements at the existing separator boundary.
  The statement classes live under `parser/statements/`. Their common type
  supports formatting and deletion through the function's ordered list.
  [Body parser](../../../Bootstrap/Frontend/parser/Parser.co) ·
  [Statement AST](../../../Bootstrap/Frontend/parser/statements/Statement.co)

## Ownership and diagnostics

- Pass the EOF-terminated `TokenList` from `Lexer.scanTokens()` to
  `Parser::new(tokens)`. Call `parse()` once. On success it transfers a
  `SourceUnit` that owns its functions in declaration order. Each function owns
  its parameters and one `List<Statement>` in source order. Each binding
  owns its initializer, and each operator expression owns its operands in
  source order. Parentheses change the shape without adding a node.
  The nodes borrow their syntax tokens.
  [Parser](../../../Bootstrap/Frontend/parser/Parser.co) ·
  [AST](../../../Bootstrap/Frontend/parser/Ast.co)
- A function stores `returnTypes` as a value array with zero or one type
  reference. Explicit `fn<void>` retains a type reference. The header passes
  the array to the declaration using ordinary value lifetime rules, and each
  type reference borrows its token and span.
  [Function header parser](../../../Bootstrap/Frontend/parser/declarations/FunctionHeaderParser.co) ·
  [Kotlin function AST](../../../Compiler/src/main/kotlin/cloth/parser/declaration/Declarations.kt)
- A brace-bodied function exposes `span` and `bodySpan`. The declaration
  starts at `pub` when present or `fn` otherwise. The body starts at `{`.
  Both end at the exclusive end of `}`. The parser constructs the function
  after accepting the closing brace and transfers its statements and body span.
  The function owns both spans, and consumers borrow them for the function's
  lifetime. Each span borrows the original optional source.
  [Kotlin function AST](../../../Compiler/src/main/kotlin/cloth/parser/declaration/Declarations.kt) ·
  [Bootstrap AST](../../../Bootstrap/Frontend/parser/Ast.co)
- A parameter owns its span from the type token through the exclusive end
  of its name. Its type reference and name borrow their tokens. The header
  parser deletes partial parameters on a list failure and transfers a
  completed list to the declaration parser. The declaration parser releases
  it on a body failure or transfers it to the completed function.
  [Parameter AST](../../../Bootstrap/Frontend/parser/declarations/ParameterDeclaration.co) ·
  [Kotlin parameter grammar](../../../Compiler/src/main/kotlin/cloth/parser/subs/FunctionParser.kt)
- A binding's optional `type` borrows the annotation token and span.
  The binding keeps the original name and assignment tokens and is
  constructed after its initializer parses. An initializer failure
  releases any partial expression.
  [Bootstrap binding AST](../../../Bootstrap/Frontend/parser/statements/BindingDeclaration.co) ·
  [Kotlin binding parser](../../../Compiler/src/main/kotlin/cloth/parser/subs/BindingsParser.kt)
- A bare return borrows its keyword token and uses that token's span.
  A value after `return` reports `C2001` at the value token in this slice.
  [Bootstrap return AST](../../../Bootstrap/Frontend/parser/statements/ReturnStatement.co) ·
  [Kotlin return AST](../../../Compiler/src/main/kotlin/cloth/parser/statement/Statements.kt)
- A function destructor and a failed body parse both use `deleteStatements`.
  It releases each completed node through `Statement`, invoking the binding
  destructor to release its initializer, then releases the list.
  [Statement cleanup](../../../Bootstrap/Frontend/parser/statements/Statement.co) ·
  [Cloth class deletion rules](../cloth-language/references/class-inheritance.md)
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

On 2026-11-11, the function range stage passed all five tests in
`BootstrapLexerTest` and `ImportPublicationTest`. On Windows x64 and x86 the
native allocator fixture matched Kotlin's declaration and body ranges and
source identity for an empty function and a multiline public function. It
also exercised a missing closing brace after a completed binding. In a
scratch checkout, omitting body span deletion triggered the allocator guard.
The restored code passed the same probe. The publication tests verified
executable IR and startup objects for all nine ABI profiles.

On 2026-11-11, all five focused tests passed after the cursor and header split,
before parameter parsing was connected. All five passed again with parameters.
Windows x64 and x86 matched Kotlin's AST, parameter and function spans,
source identity, and first error codes and spans for the tested headers
and bodies. The allocator probe exercised incomplete headers and failed
bodies. Omitting partial header cleanup in a scratch checkout triggered the
allocator guard, and the restored code passed. The publication tests verified
executable IR and startup objects for all nine ABI profiles.

On 2026-11-11, the single result annotation stage passed all five focused
Compiler tests. Native comparisons on Windows x64 and x86 matched Kotlin's
AST, spans, source identity, and first error codes and spans for the supported
headers. The allocator probe checked failures during a result annotation and
after a valid result followed by a malformed parameter list or body. The
publication tests verified executable IR and startup objects for all nine
ABI profiles.

On 2026-11-11, the typed binding stage passed all five focused Compiler
tests. Native runs on Windows x64 and x86 matched Kotlin on inferred and
typed binding ASTs, type and name spans, source identity, and first errors.
The shared reader also passed the function result annotation fixtures.
The allocator probe checked cleanup after a malformed annotation following
a completed binding and after a typed initializer failed. The publication
tests verified executable IR and startup objects for all nine ABI profiles.

On 2026-11-11, the bare return stage passed all five focused Compiler tests.
Windows x64 and x86 matched Kotlin's statement order, keyword and body spans,
source identity, and first error codes and spans for shared syntax. The
allocator probe exercised successful and failed mixed bodies. Omitting the
binding destructor in the isolated checkout triggered the allocator guard,
and restoring it passed the same probe. The publication tests verified
executable IR and startup objects for all nine ABI profiles.
