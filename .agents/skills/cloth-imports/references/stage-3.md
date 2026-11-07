# Import Stage 3: located compiler syntax

Implemented 2026-11-07. The [import contract](semantics.md) owns the language rules;
this record describes implemented syntax, source ownership and verification.

## Supported slice

```cloth
using token.Token : Token, TokenType, TokenSpan, read
using lexer.Lexer : *
```

Paths contain one or more identifiers and select a source file, without module
qualification or .co. Names can mix types/functions in any order. Parser success
records their spelling and order; it does not prove the file or symbols exist.

Imports form a leading file header, separated by comments/blank lines. Newline,
EOF or one optional semicolon ends a complete selection. A semicolon can separate
same-line imports or the following declaration. Only a colon or comma permits
selection continuation over a newline. The keyword, dotted path and colon stay
on one physical line, including around block comments. A newline after a name
ends that import; a comma on the next line cannot continue it.

Empty/mixed wildcard selections, trailing commas, repeated selectors, malformed
paths, nested-member selections, aliases, legacy dotted-symbol/wildcard syntax
and `use` imports fail. The former `use` keyword is an ordinary identifier;
Keywords.USING spells `using` and full identifier matching preserves prefixes
such as usingValue. Header parsing leaves ordinary function/type syntax and
semicolon restrictions intact. Late imports and imports inside bodies fail.
Repeated import declarations and case-distinct selectors remain represented for
future binding under the contract; parsing introduces no cross-import conflicts.

## Implementation ownership

- [Keywords](../../../../Compiler/src/main/kotlin/cloth/token/Keywords.kt)
  replaces unused USE with USING. The existing lexer already matches complete
  keyword spellings, so no separate import lexer or keyword alias is added.
- [ImportDeclaration](../../../../Compiler/src/main/kotlin/cloth/ast/ImportDeclaration.kt)
  holds the keyword, ImportPath, colon, selection and nullable semicolon token.
  Paths retain component/dot tokens. The sealed selection has Names with
  name/comma tokens, or Wildcard with its star. Every token keeps its source;
  path/selection/import spans have exclusive ends. Import spans exclude their
  semicolon. Spelling uses canonical Operators.DOT.
- [SourceUnit](../../../../Compiler/src/main/kotlin/cloth/ast/SourceUnit.kt)
  adds ordered imports with an empty default, separate from declarations.
  Existing declarations/end constructor arguments still work. Its source
  property exposes the original EOF snapshot, including for empty/header-only
  files. This is snapshot identity, not Stage 4 physical file registration.
- [Parser](../../../../Compiler/src/main/kotlin/cloth/parser/Parser.kt)
  dispatches leading headers and rejects a header after ordinary declarations
  or at a function-statement boundary. Other nested contexts reject imports
  through their existing expected-token/expression grammar checks.
- [ImportParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ImportParser.kt)
  owns path/selection/terminator grammar. Block comments do not emit newline
  tokens, so it also compares physical source lines when deciding whether a
  path can continue or a selection is complete. It does not change comment
  handling for other language syntax.
- [ImportErrors](../../../../Compiler/src/main/kotlin/cloth/diagnostics/ImportErrors.kt)
  owns structured failures. [SyntaxException](../../../../Compiler/src/main/kotlin/cloth/parser/exception/SyntaxException.kt)
  accepts optional related labels so a repeated selector can identify its
  original occurrence without adding another error.

## Compilation boundary and diagnostics

Stage 3 parses imports but does not implement discovery or binding. A successful
parse must never silently discard an import. The current
[driver](../../../../Compiler/src/main/kotlin/cloth/CompilerDriver.kt) rejects an
unresolved header immediately after parsing, before ABI selection, bodies or
output. [DeclarationAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationAnalyzer.kt)
does the same for direct one-unit semantic adapters; FunctionAnalyzer and
ProgramAnalyzer inherit that guard. Existing no-import ordering is unchanged.

| Code | Meaning in this stage |
| --- | --- |
| C2000 / EXPECTED_TOKEN | Missing/invalid path, colon, selector or surrounding declaration grammar, including exact zero-width EOF. |
| C2001 / EXPECTED_NEWLINE | A completed selection needs a separator; also rejects wildcard/name mixing at its first extra punctuation. |
| C2009 / IMPORT_OUTSIDE_HEADER | Header after an ordinary declaration or import at a function-statement boundary. |
| C2010 / DUPLICATE_IMPORT_SELECTION | Repeated selector, with repeated and original locations in one diagnostic. |
| C3096 / IMPORTS_REQUIRE_SOURCE_GRAPH | A one-source compilation/semantic adapter cannot process its unresolved imports. |

The C3096 guard also applies to requests with module roots: having roots does
not yet implement resolution. It is checked before an invalid target, missing
linker or body error, and before any artifact is produced. Malformed source syntax
still fails earlier. CLI build/run report one original diagnostic with status 1;
failed compilation preserves existing output and does not launch it.

Stages 4-6 must replace the driver's guard with real source graph/binding work
before enabling imported programs. Direct single-unit adapters must continue to
reject unresolved imports or gain an explicit resolved graph context. Never
turn this gate into an option that ignores imports, and never reuse its code for
another failure if it is retired. Missing roots/files, ambiguity, public symbol
selection, circular binding, callable ownership and multi-file emission remain
unimplemented here.

## Verification

Final command from the repository root, using the existing JDK 25/Gradle cache:

```powershell
.\gradlew.bat :Compiler:importSyntaxTest :Compiler:test --tests cloth.ImportParsingTest --tests cloth.ImportStageBoundaryTest --tests cloth.FunctionParsingTest --tests cloth.SemicolonsTest --tests cloth.StructParsingTest --tests cloth.ClassParsingTest --tests cloth.DiagnosticsTest --tests cloth.CompilationRequestTest :Shuttle:check --offline --console=plain --no-daemon
```

Observed on Windows x64: successful in 15 seconds, with zero failures/errors/skips
in every task:

- Compiler test: **90 tests across eight suites**, including 19 new
  [ImportParsingTest](../../../../Compiler/src/test/kotlin/cloth/ImportParsingTest.kt)
  cases and four new
  [ImportStageBoundaryTest](../../../../Compiler/src/test/kotlin/cloth/ImportStageBoundaryTest.kt)
  cases. Existing functions, semicolons, class/struct parsing, diagnostics and
  public compiler requests remain covered.
- Compiler importSyntaxTest: the **23 import tests** rerun with LLVM/JavaCPP jars
  removed from its JVM classpath. The suite explicitly checks that LLVM and
  JavaCPP class resources are absent in this task. It validates grammar, original
  locations, graphical related labels, synthetic 32/64-bit semantic guards,
  public requests and CLI failures without loading a native backend. The standard
  Compiler check depends on this task.
- Shuttle test: **28 tests across three suites**; configurationTest: **24 tests
  across two suites**, likewise without native backend jars. The new Git
  submodule remains included through Cloth's existing Gradle project wiring.
  This stage changes no Shuttle source, tests, manifests or Git bookkeeping.

Parser coverage includes both selection forms, arbitrary name order, single
components, header/declaration ordering, continuations, EOF, CRLF/tabs/Unicode,
all punctuation spans, immutable original snapshots, comments, duplicate labels,
misplaced imports, malformed/legacy forms and immediate first-error termination.
The boundary tests preserve stale output and avoid creation of new output paths.

The existing request regression executes a no-import program on native Windows
x64 and checks objects for all nine ABI profiles. That is regression evidence
for existing compilation, not execution/emission of imported programs. New import
syntax checking is target independent; Linux/macOS host execution and circular
multi-file programs were not tested in this stage. The full compiler suite
remains scheduled for the later integration milestone.

All six changed skills pass the bundled quick validator, with PyYAML 6.0.3
installed only in task scratch space. Publication checks pass for 13 documents,
610 local links, 88 anchors, two TOML examples, balanced fences and clean
whitespace; all 13 changed Compiler source/build files also pass whitespace and
final-newline checks. Git diff whitespace checks pass with submodule traversal
disabled. Temporary validation scripts/dependencies are removed after checking.
