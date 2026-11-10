# Bootstrap lexer port (2026-11-07)

## Scope and use

The user requested the Kotlin lexer port with string input, stopping before
parsing. The port handles keyword aliases, longest operators and nullable type
receivers, identifier boundaries, newlines, comments, numeric bases/separators/
suffixes, normal/raw strings, scalar character literals and recursive
interpolation. Preserve empty segments, doubled braces, braces inside hole-owned
strings/comments, original lexemes and EOF at the final cursor.

The Bootstrap driver scans source text from the first program argument or
standard input. [Main.co](../../../../Bootstrap/Frontend/Main.co)

~~~powershell
.\gradlew.bat :Shuttle:run --args='run --project Bootstrap'
.\Bootstrap\build\Cloth.exe 'def answer = 42'
~~~

Use the equivalent native executable path on other hosts.

## API and ownership

~~~cloth
def lexer := Lexer::new("def answer = 42")
def<TokenList?> tokens := lexer.scanTokens()
lexer::delete
if (tokens != null) {
    for (def index <- 0..tokens.size()) println(tokens.get(index))
    tokens::delete
}
~~~

The caller owns the optional `SourceFile` argument, which must describe the
same input. Call `scanTokens()` once per instance. Success transfers a
`TokenList` ending in EOF, and the list can outlive its lexer. Failure returns
null, destroys partial tokens and stores one owned failure on the lexer.
Deleting a failed lexer deletes its failure and span.

`TokenList.get(usize index)` returns the borrowed token at a zero-based index
and uses the standard library's bounds check. Use `size()` as the exclusive
upper bound when traversing the list. Deleting `TokenList` deletes its tokens
and their spans. Spans borrow `SourceFile`, so delete the source last.

`lexTokens()` prints complete token JSON followed by the count including EOF,
then deletes the list. On failure it prints one code/span/message diagnostic.
It returns isize 0/1. Token JSON matches Kotlin's shape,
including operator nameOf and control escapes. Kotlin's diagnostic source
excerpt, labels, help and renderer are not ported.

## Observed boundaries

- **I/O:** source input is already a string. File reading, stream APIs and stderr
  diagnostic rendering need design/library work.
- TokenList imports `cloth.collections.List` and keeps a private
  `List<Token>` for append and indexed traversal. The generic list stores token
  identities. TokenList deletes the tokens before deleting that list.
  Shuttle selects the library by default. Public compiler requests supply its
  module explicitly. [TokenList.co](../../../../Bootstrap/Frontend/token/TokenList.co) ·
  [Collection contract](../../cloth-collections/SKILL.md)
- **String building:** growing JSON concatenation exposed a compiler storage
  defect. The [compiler fix](../../cloth-buffer-lifetimes/references/string-concatenation-storage.md)
  removes prefix stack growth and reclaims replaced strings; ordinary formatting
  now handles the original 3,072-scalar lexeme without recursive chunks. A builder
  remains a possible copying-time optimization, not a correctness requirement.
- Tokens and spans have public mutable fields. Borrowing follows the ownership
  conventions above.
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
reads the tokens by index after deleting the lexer, and deletes the list before
its source. The test driver reconstructs NUL because native
argv cannot carry it. A second test uses the allocator probe to check that
successful scans and first errors after two tokens release the list storage,
tokens, and spans.

[ImportPublicationTest](../../../../Compiler/src/test/kotlin/cloth/ImportPublicationTest.kt)
checks the exact graph of eight Bootstrap sources plus the library's List.co.
[ShuttlePublicationTest](../../../../Shuttle/native/src/jvmTest/kotlin/shuttle/ShuttlePublicationTest.kt)
builds and runs the project from its nested lexer directory. Compiler and
Shuttle track the Bootstrap fixtures as Gradle test inputs.

Run the focused integration checks from the repository root.

~~~powershell
.\gradlew.bat :Compiler:test --tests cloth.BootstrapLexerTest --tests cloth.ImportPublicationTest :Shuttle:check '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --no-build-cache --no-configuration-cache --offline --console=plain --no-daemon
~~~

On 2026-11-08 the indexed lexer passed five focused Compiler tests in a copy of
commit `3567392` with the Bootstrap patch and the checkout's standard library.
Windows x64 and x86 matched Kotlin on 124 inputs, including all eight Bootstrap
sources, 2,048 tokens, a 3,072-scalar raw lexeme and 24 nested interpolations.
The allocator probe passed on both profiles. ImportPublicationTest verified
executable IR and startup objects for all nine Windows, Linux and macOS
profiles. Shuttle passed 61 tests and 25 backend-free configuration checks.

The original lexer port also linked and ran on GNU Linux x64 under Ubuntu WSL
for sample text, interpolation, an invalid numeric first error and the long
raw lexeme. That Linux execution predates the List migration.

The user has asked that ongoing standard-library work remain untouched here.

On 2026-11-09 Kotlin and Bootstrap matched on 124 inputs on Windows x64 and
x86, including `?.` and adjacent nullable operators. The public request test
verified the count with a program argument, and Shuttle verified the count
with source text on standard input. [Guarded access verification](../../cloth-nullability/references/implementation.md#verification-2026-11-09)
