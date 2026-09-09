# Compiler architecture

This file is the manual source map for the self-hosted compiler. It records
stable ownership and dependency direction; stage contracts in `cCloth` own
behavior and checkpoint scope.

## Dependency direction

```text
Main
  -> frontend lexer orchestration
       -> lexical scanners
       -> source, token, and diagnostic data
            -> no lexer dependency
```

Dependencies point downward. Source, token, and diagnostic types must remain
usable without importing lexer orchestration. Scanner components may share
cursor, source, sink, and byte-class contracts, but must not call one another
in cycles.

## Source tree

```text
src/
  Main.co                    Process entry and compiler-driver composition
  bootstrap/                 Temporary checkpoint acceptance checks
  frontend/
    source/                  Exact bytes, cursors, spans, and locations
    token/                   Token kinds and bounded token storage
    diagnostic/              Structured frontend diagnostics and storage
    lexer/                   Lexical orchestration and byte classification
      scanners/              Identifier, trivia, operator, numeric, and text domains
  testdata/lexer/            Exact byte inputs used by bootstrap checks
```

Directories and files are added when their scheduled checkpoint needs them;
empty placeholders are not architecture. Each `.co` file defines one primary
implicit type with the same name as the file stem.

## Frontend ownership

### `frontend/source`

Owns exact source bytes and coordinate math. It does not classify tokens,
format diagnostics, parse syntax, or expose platform file handles.

### `frontend/token`

Owns token kinds, source-backed token records, and fixed result storage. Tokens
refer to source spans; they do not copy arbitrary source text.

### `frontend/diagnostic`

Owns structured diagnostic categories and ranges. CLI rendering is a driver
concern and must not leak into scanners.

### `frontend/lexer`

Owns pass coordination, byte classification, and scanners. `Lexer.co` composes
the parts. Each file under `scanners/` owns one complete lexical domain rather
than a collection of unrelated helper functions. `Utf8Decoder.co` validates
canonical scalar byte sequences for the text scanner without owning token
boundaries or decoded text storage.

### `Main.co`

Owns argument handling, phase composition, output selection, and process exit
status. It does not implement a lexical rule or data structure.

### `bootstrap`

Owns temporary executable acceptance checks while the self-hosted compiler has
no dedicated test runner. Check code may inspect public compiler results, but
production frontend code cannot import `bootstrap`. Canonical kind-code and
record writers in this directory are differential-test adapters, not compiler
CLI or artifact contracts. These checks leave `Main` when a dedicated Cloth
test target is available.

### `testdata`

Owns deterministic inputs that are loaded at runtime and are not compiled as
package sources. A fixture covers one named boundary and contains no expected
results; expectations remain in the matching bootstrap check.

## Adding a component

Choose a file by asking which invariant it owns, not which current file has
room. Add a new file when a responsibility has its own state or rules and can
be named precisely. Keep a cohesive implementation together when splitting it
would create public cross-file plumbing without a real ownership boundary.

When a component moves, update imports and this map in the same change. Do not
create catch-all files named `Utils`, `Common`, `Misc`, or `Helpers`.

## Current stage boundary

Stage 44 lexer parity with the C++23 bootstrap is complete. Parser and AST code
are absent and must not enter this tree until their own stage contract is
approved.
