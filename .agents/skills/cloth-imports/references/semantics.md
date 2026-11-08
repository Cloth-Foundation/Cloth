# Cloth import contract

Status: Stages 3-6 syntax, discovery, binding, graph semantics and whole-program
emission implemented, 2026-11-07. Stage 7 completes maintained nested examples and
target verification. See [Stage 6](stage-6.md) and [Stage 7](stage-7.md) for evidence.
The user directly confirmed colon selections, wildcard selections, omitted
module qualifiers, direct use of imported names, ambiguous file-path errors and
circular imports. The remaining initial-slice rules below were selected while
carrying out the authorized Stage 0, following the plan's recommendations and
existing language rules. Distinguish these decisions from implemented support.

## Syntax and file scope

```cloth
using token.Token : Token, TokenType, TokenSpan
using lexer.Lexer : Lexer
using text.Formatting : format
using token.Token : *
```

The path before `:` contains one or more dot-separated identifiers. It ends at
the actual source filename without `.co`: `token.Token` means `token/Token.co`.
It does not select a declaration. Names after `:` are comma-separated
declarations from that file; types and file-level functions can be mixed in any
order. A wildcard is the entire selection and cannot be mixed with names.

Imports form a file-level header before the first ordinary declaration. Comments
and blank lines may separate imports. Imports inside functions or types, or after
an ordinary file declaration, are errors in the initial slice. No imports is
valid. No source-level module declaration is required for import resolution.

A completed selection ends at newline, EOF or an optional semicolon. An explicit
semicolon can separate same-line imports or the following declaration. Newlines
are allowed immediately after `:` and after a comma that requires another name.
The dotted path and its colon stay on one line. A newline after a completed name
terminates the import. Trailing commas and repeated names within one selection
list are errors. Newlines do not create empty imports.

```cloth
using token.Token :
    Token,
    TokenType,
    TokenSpan
```

Use this syntax for one name as well as many. Earlier `using path.File.name`,
`using path.File.*` and the legacy `use` spelling are not accepted alternatives.
Import aliases, scoped imports, nested-member selection and re-export syntax
are outside this initial slice. An instance `mt` remains a receiver call through
its type; importing a type does not detach its instance methods.

Stage 3 implements this grammar as a located header, retaining paths, names,
wildcards, colon/dot/comma tokens and optional semicolons. Import spans exclude
the semicolon. Physical source lines preserve these boundaries even when a
multiline block comment produces no newline token. The keyword is Keywords.USING;
`use` is an ordinary identifier and not an alternate import spelling.

## Roots, file candidates and identity

The compiler receives explicit SourceModule roots through the
[compiler request](compiler-api.md). Shuttle supplies those roots from its
[manifest model](../../shuttle-build/references/semantics.md). The compiler does
not discover or parse project manifests. The standalone command supplies its
selected standard-library root by default; it never guesses project roots from
the source or process directory. Explicit API requests receive only their supplied roots.

The [bundled-library contract](../../cloth-toolchains/references/standard-library.md)
and [Stage 2 record](../../cloth-toolchains/references/standard-library-stage-2.md)
own implemented command attachment and the role-based reservation of `cloth.*`.
For a reached path beginning with exact lowercase `cloth`, a regular-root candidate
is C3106 even if the selected library lacks the file. All-regular requests retain
ordinary resolution, including cloth paths. [Selection Stage 3](../../cloth-toolchains/references/standard-library-stage-3.md)
implements `--no-stdlib`, preserving explicit roots as regular modules, and
`--stdlib PATH` for a structurally valid replacement library.

For each import, append the relative dotted file path plus `.co` to every
supplied source root. Require exact case for relative path components and the
extension on every supported OS. A case-insensitive host must not silently accept
a spelling that would fail on another target's filesystem. Apply the following
candidate rules outside the reservation exception above:

- No candidate is a missing-source diagnostic at the import path. Without any
  roots, an import reports missing module context.
- More than one configured module matching that file path is one ambiguous-file
  diagnostic, with the candidate paths/module identities. Check this before
  symbol selection; a selected name existing in only one candidate does not
  disambiguate the file.
- One candidate selects that source. Never prefer the current module, module
  declaration order, filesystem enumeration order or the first matching symbol.

Validate duplicate logical module IDs and roots that identify the same physical
directory before discovery. Candidate ambiguity is evaluated across configured
module roots before consulting the source-load cache. Cache loaded sources by
canonical physical identity, handling symbolic-link/hard-link aliases with the
host's file-identity facilities. Repeated references share a source snapshot and
declaration identities; aliases must not create independent copies of a type.
Do not case-fold the language's identifier paths or diagnostic spellings.

## Visibility, selected names and collisions

Each file retains its own declaration scope and export view. Its own private
declarations remain available to its bodies. Only `pub` top-level declarations
are importable. A named selection of a private or absent declaration is an error;
a wildcard exposes all public top-level declarations without exposing private
helpers or imports that the source file itself received.

Imported type names enter the existing type lookup space; imported functions
enter the existing callable/value lookup space. Preserve the current distinction
between those spaces and ordinary nearer lexical shadowing. Importing a class,
struct or enum preserves its member visibility and original nominal identity.

Selecting a function name imports all of that file's public overloads under the
name. Private overloads remain usable only in their defining file. Existing
argument placement, exact typing and overload ranking still select the call.
Return types do not distinguish overloads.

Within the relevant namespace:

- Repeated imports that bind the same declaration identity are idempotent,
  including overlap between a wildcard and a named import of the same file.
- Different source declarations exposing the same imported name conflict,
  including same-spelled functions with different signatures in different files.
  Do not implicitly merge overload groups from separate defining files.
- A conflict between an imported name and this file's own declaration is an
  error. Import order does not choose a winner; label the import and related
  declarations as one diagnostic.

A public function keeps the definition context of its signatures, defaults and
body. It may use private helpers in its defining file. Callers do not need to
import those helpers. Already resolved signature types can cross a call boundary
without making their defining names visible in the caller. Whether a value has
a public member is governed by the existing type/member contract, not by whether
the caller imported the name of every type mentioned in that signature.

## Circular imports and analysis phases

Circular source imports are allowed within and across supplied modules, including
`A -> B -> A` and longer cycles. The user confirmed this on 2026-11-07. An import
edge to a source currently being loaded records an edge and does not reload it
or diagnose the cycle. No source topological ordering is required.

Implementation must discover the graph, register nominal and callable identities,
bind exports/imports and then resolve contextual signatures, field types, defaults
and bodies. These phases must work across strongly connected source groups.
Keep one built-in Object root for the entire compilation.

This permits mutual function recursion and mutually referring class identities.
It does not permit an inheritance cycle or an infinitely recursive inline value
layout. Those remain ordinary type errors with first-error diagnostics.
Import cycles do not decide future separately built package/artifact dependency
rules in Shuttle.

## Failure order and checked scope

Parse the primary source, then discover imports in written order using a stable
depth-first walk and the single-load registry. Do not collect errors during the
walk. Finish the located graph before binding declaration selections. Bind
selections in written order and check files/declarations in a deterministic
discovery/source order. Selection ordering does not change successful visibility.

Preserve original source snapshots and spans for every file and cross-file label.
Current driver target selection and body-check ordering must be preserved by the
one-file adapter; target layout is available before type-dependent checking.
All declarations in every loaded file are checked, including private/unused
declarations. Unreachable source files are not implicitly scanned just because
their module root was configured.

Any failure stops compilation before native output/linker work. Output alias
protection applies to all loaded sources, not just the primary file. Detailed
diagnostic code allocation belongs to implementation stages; do not reuse retired
compiler codes. See [diagnostic guidance](../../cloth-diagnostics/SKILL.md).

## Current support and verification

[Stage 7](stage-7.md) verifies the maintained nested-folder project and Bootstrap's
current lexer scaffold through the public API and Shuttle. Dot components remain
literal subfolders under module roots, independently of the importing file's
directory. Native execution covers Windows x64/x86 and GNU Linux x64 under WSL;
executable IR and objects cover all nine existing profiles.

This contract is published and routed by Stage 0. Stage 1 adds the public request
API and a Shuttle consumer fixture, verified in the [Stage 1 record](stage-1.md).
Stage 3 parses the header grammar and original locations above; see its
[record](stage-3.md). Stage 4 discovers the complete reachable graph from a plain
compiler request, preserving original snapshots and separate units. It loads each
physical source once and retains self/mutual/longer cycle edges, without flattening
file scopes or selecting names. Exact matching and ambiguity run across all
supplied roots before identity-cache lookup. Unreachable files/manifests are not
read. Primary and imported source reads are strict UTF-8 with C0010 on failure.

The driver now reports missing context (C3097), missing source (C3098), ambiguity
(C3099) or inaccessible import paths (C3100) as appropriate. A complete graph
passes loaded-source output alias checks and binds its per-file exports/imports.
Stage 5 reports missing exports (C3102), private selectors (C3103) and conflicting
identities (C3104) before ABI selection. It then checks the whole graph with the
selected layout, preserving each definition's type/value/default context. Local
same-spelled nominal declarations remain distinct; imports reuse their original
symbols. One Object root is shared across the analysis. C3101 is retired.

Stage 6 emits valid graphs together as one program or object. Only the primary
file's own main candidates select executable startup. Other files' mains are
ordinary callable functions, and object mode selects no entry. Runtime typeinfo
equality preserves nominal declaration identity across files and wrappers;
displayed type text stays unqualified. C3105 is retired alongside C3101.
Direct one-unit semantic adapters retain C3096. Syntax failures still
precede discovery, including C2009/C2010 and original parser/lexer codes.
Discovery and binding precede target selection; type-dependent graph checking
uses its actual layout and precedes emission. No-import target/body ordering
remains unchanged. See [Stage 4](stage-4.md) and [Stage 5](stage-5.md).
See [Stage 6](stage-6.md) for entry integration, native circular execution and
multi-file emission. The
[plan](../../../../todo/shuttle-and-imports-plan.md) stages implementation and
required parser, graph, semantic, circular-recursion and native/foreign-target
checks. [Stage 0 evidence](../../shuttle-build/references/stage-0.md) verifies
documentation and the selected TOML dependency only; it is not import execution
or cross-target compiler evidence.
