# Compiler architecture

This file is the manual source map for the self-hosted compiler. It records
stable ownership and dependency direction; stage contracts in `cCloth` own
behavior and checkpoint scope.

## Dependency direction

```text
Main
  -> compiler driver
    -> frontend parser orchestration
       -> syntax storage and tree
            -> token and source data
       -> token and diagnostic data
    -> frontend lexer orchestration
       -> lexical scanners
       -> source, token, and diagnostic data
            -> no lexer or parser dependency

self-host tests
  -> repository-private testing assertions
  -> public clothc package surface
       -> no production dependency on either test layer
```

Dependencies point downward. Source, token, and diagnostic types must remain
usable without importing lexer orchestration. Scanner components may share
cursor, source, sink, and byte-class contracts, but must not call one another
in cycles.

## Source tree

```text
src/
  Main.co                    Process entry and compiler-driver composition
  driver/                    CLI, compilation requests, and diagnostics
  frontend/
    source/                  Exact bytes, cursors, spans, and locations
    token/                   Token kinds and bounded token storage
    diagnostic/              Structured frontend diagnostics and storage
    lexer/                   Lexical orchestration and byte classification
      scanners/              Identifier, trivia, operator, numeric, and text
                             domains
    syntax/                  Managed abstract syntax-tree representation
      declaration/          Field, function, and constructor nodes
      expression/           Exact expression kinds and payloads
      statement/            Blocks, statements, loop and switch records
      storage/               Segmented arena and child-sequence construction
      tree/                  Root, types, imports, verification, and publication
    parser/                  Two-pass syntax orchestration
      declaration/          File and member outline pass
      definition/           Declaration materialization and publication
      expression/           Precedence and postfix expression parsing
      statement/            Blocks, statements, loops, and switches
      support/              Shared cursors, facts, ranges, and budgets
      storage/               Typed segmented declaration construction
tests/
  support/src/                Repository-private assertions and typed failure
  smoke/                      Focused Bazel rule and execution checks
  self_host/
    Shuttle.toml             Focused build-system compatibility package
    src/checks/              Lexer, parser, and syntax acceptance checks
    src/fixtures/            Focused construction and lifetime fixtures
    src/parity/              Canonical kind codes and record adapters
    tests/                   Named Bazel assertion adapters
    testdata/lexer/          Exact byte inputs used by lexer checks
    testdata/parser/         Parser grammar and recovery inputs
    tools/                   Parity emitters and failure-injection executables
tools/
  bazel/
    cloth/
      BUILD.bazel            Registered bootstrap toolchain
      defs.bzl               BUILD-file rule entry points
      extensions.bzl         Bzlmod bootstrap repository entry
      private/               Private Bazel package with rule implementations
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

### `frontend/syntax`

Owns typed syntax handles, append-only segmented storage, immutable child
sequences, and the abstract syntax tree. One owning storage appends expression,
statement, and block families to managed 64-slot pages. Handles resolve through
their owner, page, slot, and global ordinal; specialized builders freeze ordered
child handles into exact arrays. The file root retains imports, enum cases,
inheritance, conformance, and one direct declaration sequence in source order.
Names and arbitrary spellings remain source-backed. Syntax may depend on source
and token data but cannot depend on parser orchestration or semantic analysis.
`VerifiedSyntaxTree` is the publication boundary: its factory validates the
complete reachable graph before a parser result may retain the root.

### `frontend/parser`

Owns the declaration and definition grammar passes. The declaration pass
verifies immutable token buffers, supplies bounded EOF-saturating cursors and
half-open token intervals, parses declaration signatures, records exact
deferred initializer and body intervals, and publishes only fully verified
immutable results. Typed 64-slot builders handle unknown declaration counts;
stable sorting provides deterministic diagnostics and `O(D log D)` duplicate
validation. The parser consumes source, token, diagnostic, and grammar-neutral
syntax data without changing their ownership. The definition pass consumes
only those retained intervals, uses explicit managed frames for expression and
statement nesting, materializes declarations in outline order, seals one
syntax storage, and publishes one verified tree with combined diagnostics.

### `Main.co`

Owns argument handling, phase composition, output selection, and process exit
status through `driver/CompilerDriver.co`. It does not import tests, implement a
lexical rule, or own a syntax data structure.

### `tests/self_host`

Owns the self-hosted compiler checks. Bazel compiles their shared implementation
as `clothc-self-host-support`, then gives each assertion, GC/depth invariant,
failure mode, and parity adapter an independently named target and process.
The no-op `clothc-tests` Shuttle package exercises the public build-system
boundary through one explicit compatibility audit. Neither test graph can be
imported by production compiler sources. Canonical kind-code and record writers
are differential-test adapters, not compiler CLI or artifact contracts.

### `tests/self_host/testdata`

Owns deterministic inputs that are loaded at runtime and are not compiled as
package sources. A fixture covers one named boundary and contains no expected
results; expectations remain in the matching bootstrap check.

### `tools/bazel/cloth`

Owns repository-private Starlark integration for building and testing Cloth
code. Compile and link actions consume the public compiler process protocol and
do not reproduce or invoke Shuttle as a nested build system. One explicit test
invokes Shuttle against a temporary staged repository to preserve its supported
product boundary. The bootstrap repository validates and imports the frozen
compiler distribution, compiler-paired standard library, action interpreter,
and Windows host runtime. Rules stage only declared `.co` sources, validate
protocol receipts, publish deterministic package closures, generate test
entries, and expose declared fixture data through Bazel runfiles.

### `tests/support` and `tests/smoke`

`tests/support` owns the private `testing` package, typed `TestFailure`, and
assertion diagnostics. `tests/smoke` verifies successful assertions, enum value
comparison, declared runfiles, generated entries, and the explicit failed-test
path. Compiler subsystem checks are exposed under `tests/self_host`, while
`//tests:presubmit` and `//tests:full` define the repository entry points.

## Adding a component

Choose a file by asking which invariant it owns, not which current file has
room. Add a new file when a responsibility has its own state or rules and can
be named precisely. Keep a cohesive implementation together when splitting it
would create public cross-file plumbing without a real ownership boundary.

When a component moves, update imports and this map in the same change. Do not
create catch-all files named `Utils`, `Common`, `Misc`, or `Helpers`.

## Current stage boundary

Stage 44 lexer parity, Stage 45 syntax foundations, Stage 46 declarations, and
Stage 47 definitions form the implemented self-hosted frontend baseline. Stage
48 establishes Bazel as the self-hosted repository's test authority with
isolated tests, exact-output records, diagnostic-checked failure probes,
explicit presubmit/full suites, exhaustive declared-input C++ parity, and one
temporary-directory Shuttle compatibility audit. The bounded definition layer
uses explicit managed parse frames,
covers all current expression and statement forms, preserves exact precedence
and structured recovery, shares package constant budgets, and verifies complete
trees iteratively. Its canonical full-tree records agree with the C++ oracle for
all production compiler sources and the focused valid and malformed corpus. The
C++ parser remains authoritative until the complete parser authority-transfer
audit. The production `clothc` executable performs a single-file frontend
check. The legacy `clothc-tests` dispatcher and CMake bridge have been retired;
the remaining Shuttle package has a no-op entry and exists only to validate the
supported package/build boundary.
