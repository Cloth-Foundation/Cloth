# Bootstrap lexer port (2026-11-07)

The [generic Stage 6 caller audit](../../cloth-generics/references/caller-audit.md)
preserves the TokenList/TokenCursor and token/span/source lifetime below after
public List delivery. Availability of a generic container does not itself select
an ownership migration.

## Scope and use

The user requested the Kotlin lexer port with string input, stopping before
parsing. The port handles keyword aliases, longest operators and nullable type
receivers, identifier boundaries, newlines, comments, numeric bases/separators/
suffixes, normal/raw strings, scalar character literals and recursive
interpolation. Preserve empty segments, doubled braces, braces inside hole-owned
strings/comments, original lexemes and EOF at the final cursor.

[Main.co](../../../../Bootstrap/Frontend/Main.co) scans its sample or the first
program argument as text. It does not read a file:

~~~powershell
.\gradlew.bat :Shuttle:run --args='run --project Bootstrap'
.\Bootstrap\build\Cloth.exe 'def answer = 42'
~~~

Use the equivalent native executable path on other hosts.

## API and ownership

~~~cloth
def lexer := Lexer::new("def answer = 42")
~~~

An optional SourceFile second argument must describe the same input. It remains
caller-owned. Call scanTokens() once per instance. Success transfers TokenList?
to the caller, including EOF; tokens survive deleting their lexer. Failure
returns null, destroys partial tokens and retains one owned lexer.failure.
Scanning does not print. Each token owns its span; spans borrow SourceFile.
TokenCursor borrows list tokens. Delete cursors before the list and SourceFile
last. Deleting a failed lexer deletes its failure/span.

lexTokens() prints complete token JSON or one code/span/message failure, cleans
up the successful list and returns isize 0/1. Token JSON matches Kotlin's shape,
including operator nameOf and control escapes. Kotlin's diagnostic source
excerpt, labels, help and renderer are not ported.

## Observed boundaries

- **I/O:** source input is already a string. File reading, stream APIs and stderr
  diagnostic rendering need design/library work.
- **Collections:** [growable arrays Stage 7](../../cloth-arrays/references/growable-arrays-stage-7.md)
  proves array-backed concrete collections. The public generic List is now
  implemented, with its own [collection contract](../../cloth-collections/SKILL.md).
  Bootstrap's owning TokenList keeps linked nodes and explicit token
  deletion. A future migration must preserve that ownership and its borrowed
  cursor/source lifetimes in a separate consumer change.
- **String building:** growing JSON concatenation exposed a compiler storage
  defect. The [compiler fix](../../cloth-buffer-lifetimes/references/string-concatenation-storage.md)
  removes prefix stack growth and reclaims replaced strings; ordinary formatting
  now handles the original 3,072-scalar lexeme without recursive chunks. A builder
  remains a possible copying-time optimization, not a correctness requirement.
- **Ownership:** spans and cursors borrow by convention. Public mutable fields
  remain. This port does not establish borrow syntax, read-only property rules or
  collection invalidation semantics.
- **Continuation:** the current parser rejects line breaks inside binary chains,
  including parenthesized chains. Those expressions remain on one line here.

These observations do not establish new language decisions or authorize all
possible follow-up implementations.

The scanner caches toChars() and keeps scalar substring indices separate from
one-based UTF-16 line/columns, matching Kotlin's exclusive spans. Non-BMP scalars
advance columns by two, LF starts a line, and CR is ignored outside literals.
Identifier predicates are BMP-only because Kotlin classifies UTF-16 Char values.
Cloth uses Unicode 17 while JVM tables depend on its version: newly assigned BMP
characters are not exhaustively compared, so version differences remain a parity
boundary. Do not change general Cloth classification to imitate host tables.

Kotlin C1007 covers malformed UTF-16, which cannot inhabit a valid Cloth string;
this belongs to future decoding, not a reachable scanTokens failure. Unexpected
non-BMP scalars retain the Kotlin first-code-unit error span while rendering the
complete character. Non-BMP invalid escape messages/escape spans also differ
because Kotlin's message can contain an isolated surrogate. These cases are not
claimed as exact diagnostic parity.

## Verification

[BootstrapLexerTest](../../../../Compiler/src/test/kotlin/cloth/BootstrapLexerTest.kt)
compiles once per available native profile and compares token JSON, spans, first
error codes/messages and exit status with Kotlin. It verifies source identity,
reads returned tokens after deleting the lexer, exhausts the cursor and deletes
the list before its source. The test-only driver reconstructs NUL because native
argv cannot carry it; no input protocol is added to Bootstrap.

All 124 inputs pass on Windows x64 and x86, including every keyword/operator,
numeric/literal failures, nested holes, CRLF/control/NUL text, deterministic
mixtures and all eight Bootstrap frontend source files. Larger cases cover
2,048 list nodes, a 3,072-scalar raw lexeme and 24 nested interpolations. The
unchanged long-lexeme regression now passes with ordinary unchunked formatting.

[ImportPublicationTest](../../../../Compiler/src/test/kotlin/cloth/ImportPublicationTest.kt)
owns the exact eight-file graph and existing native/foreign-target checks;
[ShuttlePublicationTest](../../../../Shuttle/src/test/kotlin/shuttle/ShuttlePublicationTest.kt)
owns nested-project command checks.

The final integration command is:

~~~powershell
.\gradlew.bat :Compiler:test --tests cloth.BootstrapLexerTest --tests cloth.ImportPublicationTest :Shuttle:check '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --no-build-cache --no-configuration-cache --offline --console=plain --no-daemon
~~~

Verification uses an isolated task-local build directory/project cache. The
final run passes in 49 seconds: four Compiler integration tests (including the
124-input comparison), 51 Shuttle tests and 25 backend-free configuration tests,
with zero failures, errors or skips. The compiler test task tracks Bootstrap
.co files as inputs; editing the port must
invalidate its differential result. The local verification init also tracks
Bootstrap files for Shuttle's existing external-fixture smoke test.

Windows x64/x86 execute the full comparison. ImportPublicationTest verifies
executable IR/startup objects for all nine existing Windows/Linux/macOS ABI
profiles. The GNU Linux x64 object is linked with cc under Ubuntu WSL and agrees
with Windows for sample text, interpolation, an invalid numeric first error and
the long raw lexeme. Native ARM64/macOS/musl execution is not claimed.

The original port run left Compiler production code and Shuttle source unchanged.
The subsequent [compiler storage correction](../../cloth-buffer-lifetimes/references/string-concatenation-storage.md)
removes the formatter workaround and owns its separate verification evidence.
The user has asked that ongoing standard-library work remain untouched here.
