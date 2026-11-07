# Import Stage 4: source discovery and graphs

Implemented 2026-11-07. The [import contract](semantics.md) owns language rules;
this record owns discovery implementation, diagnostics and observed verification.

## Implemented slice

The compiler now loads every source reachable from the primary file using only
the request's module roots. `using token.Token : Token` and wildcard selections
both resolve token/Token.co across all supplied roots. There is no module prefix,
current-module preference, manifest lookup or cwd fallback. Relative directories,
file names and .co extension require exact case, including on Windows.

Every matching root is considered before consulting the source cache or looking
at selected names. Two roots matching a path are ambiguous even if they identify
one physical file, or a selected declaration could exist in only one candidate.
Candidate diagnostics are ordered by module ID, independently of request order.
An inaccessible search path fails immediately instead of being treated as an
absent candidate.

Discovery parses the primary unit completely, then follows imports in written
depth-first order. An explicit stack handles deep graphs. New sources transition
through registered/loading/loaded states; references to loading sources retain
back-edges. Self imports, mutual imports and longer cycles across modules are
accepted during discovery. Repeated imports and diamond edges reuse the same
source unit and snapshot. No source topological ordering is imposed.

The registry uses canonical paths plus filesystem/file-key identities, falling
back to same-file checks within that filesystem when keys are unavailable.
This handles hard links and directory/symbolic aliases without duplicating
declarations. IDs are local to one graph and allocated in discovery order.
Each node retains its canonical physical path; diagnostic snapshots use the
first selected path spelling and original text. Edges retain the written import,
selected module/path and target ID even for cached targets. A later compilation
gets a fresh registry. Unreachable files are not scanned or parsed.

Primary/imported source reads use strict UTF-8. Read failures retain C0010 and
their cause; imported read failures also locate the requesting path. Imported
lexer/parser failures preserve their own original code, source snapshot and span.
There is no error recovery, collection of failures, merged declaration scope,
symbol selection, visibility checking or body analysis in discovery.

## Source ownership

| Owner | Responsibility |
| --- | --- |
| [CompilationRequestValidation](../../../../Compiler/src/main/kotlin/cloth/api/CompilationRequestValidation.kt) | Shared shape/link-path/module validation, still C0017 before source work; returns canonical roots. Extracted from the facade without changing its public signature. |
| [SourceGraphLoader](../../../../Compiler/src/main/kotlin/cloth/source/SourceGraphLoader.kt) | Request-driven resolution, strict source reads, per-compilation identity cache, explicit traversal stack and load states. |
| [SourceGraph](../../../../Compiler/src/main/kotlin/cloth/source/SourceGraph.kt) | Graph-local SourceId, separate SourceNode units, ImportEdge descriptors/back-edge facts and immutable source/edge catalogs. Provides source/import lookup and output alias checking. |
| [ImportErrors](../../../../Compiler/src/main/kotlin/cloth/diagnostics/ImportErrors.kt) | Located discovery failures and the remaining binding gate. |
| [CompilerDriver](../../../../Compiler/src/main/kotlin/cloth/CompilerDriver.kt) | Uses discovery, protects loaded inputs, then retains the no-import ABI/analysis/emission path. |
| [ClothCompiler](../../../../Compiler/src/main/kotlin/cloth/api/ClothCompiler.kt) | Public compile/result/diagnostic boundary; graph loading performs shared validation before parsing. No public graph/AST protocol is added. |

## Output protection and the binding boundary

Graph output checks protect all loaded sources by normalized path, file key and
physical same-file identity. They include transitive inputs and hard-link aliases,
and create no output directories or files. For the no-import path, the driver
still selects the ABI and checks the actual final filename before body analysis
and emission, preserving existing diagnostic ordering.

Imported programs are not compiled until per-file binding is implemented. After
successful discovery, object requests check their requested destination;
executable requests conservatively check all supported filename variants,
including the possible .exe suffix, without initializing a native backend.
The driver then throws C3101 instead of compiling only the primary unit. This
temporary pre-ABI executable check can reject a potential suffix alias regardless
of the requested target. The actual post-ABI filename check remains in the
emission path and must cover the complete graph when emission is integrated.

Direct SourceUnit semantic adapters still require a resolved graph context and
retain C3096. Binding needs a separate Stage 5 graph entry point; it must register
declaration identities across cycles before resolving types/bodies. Do not remove
either gate by flattening scopes or dropping imports. Inheritance and recursive
inline-layout cycle checking remain the responsibility of semantic analysis.

## Diagnostics and failure order

| Code | Implemented meaning |
| --- | --- |
| C3096 / IMPORTS_REQUIRE_SOURCE_GRAPH | Direct one-unit semantic analysis received imports without a graph context. |
| C3097 / MISSING_IMPORT_CONTEXT | An import has no explicitly supplied roots. |
| C3098 / IMPORTED_SOURCE_NOT_FOUND | No root has that exact relative source file. |
| C3099 / AMBIGUOUS_IMPORT_SOURCE | Multiple roots match; one diagnostic lists module/file candidates before target parsing or selector lookup. |
| C3100 / IMPORT_SOURCE_ACCESS | Directory enumeration or source-identity access failed; original import path and cause retained. |
| C3101 / IMPORT_BINDING_REQUIRED | A complete graph needs the unimplemented cross-file binding stage. |
| C0010 / SOURCE_READ | Primary/imported read or UTF-8 decoding failure, retaining the underlying cause. |
| C0014 / OUTPUT_OVERWRITES_SOURCE | A destination aliases any loaded source. |

Validate the request, parse the primary, then discover/parse imports depth first.
Every source is parsed completely before following its imports. Schema manifests
are irrelevant here. On completed graphs, output protection precedes the binding
gate. No import-bearing path selects an ABI, checks bodies, discovers a linker or
emits output in this stage. No-import target/body ordering is unchanged. CLI
build/run report one original failure with status 1 and preserve stale artifacts.

## Verification

Final command from the repository root with JDK 25 and cached dependencies:

```powershell
.\gradlew.bat :Compiler:importResolutionTest :Compiler:importSyntaxTest :Compiler:test --tests cloth.ImportResolutionTest --tests cloth.ImportParsingTest --tests cloth.ImportStageBoundaryTest --tests cloth.FunctionParsingTest --tests cloth.SemicolonsTest --tests cloth.StructParsingTest --tests cloth.ClassParsingTest --tests cloth.DiagnosticsTest --tests cloth.CompilationRequestTest :Shuttle:check --offline --console=plain --no-daemon
```

Observed on Windows x64: **44 seconds**, with zero failures, errors or skips:

- Compiler normal task: **117 tests across nine suites**. This adds 27
  [ImportResolutionTest](../../../../Compiler/src/test/kotlin/cloth/ImportResolutionTest.kt)
  cases to the previous focused parser/API/diagnostic regressions.
- Compiler importResolutionTest: **27 graph tests** with LLVM/JavaCPP jars removed
  from the JVM classpath; class-resource absence is asserted.
- Compiler importSyntaxTest: **23 syntax/boundary tests**, also without native
  backend jars. [ImportStageBoundaryTest](../../../../Compiler/src/test/kotlin/cloth/ImportStageBoundaryTest.kt)
  now expects missing-context discovery errors or the completed-graph binding
  gate while retaining C3096 for direct semantic adapters.
- Shuttle normal task: **28 tests across three suites**; configurationTest:
  **24 tests across two suites**, with native backend jars removed. Shuttle's
  submodule source, tests and Git bookkeeping are unchanged by this stage.

The standard Compiler check now includes both isolated import tasks. Graph cases
cover root-independent primary parsing, ignored manifests/unreachable sources,
DFS/diamond/transitive discovery, cross-module paths, spaces/Unicode, missing
context/files, exact directory/file/extension case, directory/file mismatches,
ambiguity before selector lookup or parsing, shuffled roots/selectors, repeated
imports, self/mutual/cross-module cycles, a 768-file cycle, hard links to another
source and the primary, directory aliases, duplicate roots/IDs, retained original
snapshots, immutable catalogs, UTF-8 failures, fail-first imported/primary syntax,
all-source output aliases, possible executable suffixes and CLI stale-output
protection. A JDK ZipFS fixture exercises unavailable file keys and identity
comparisons across filesystems. Archive configuration/packaging remains outside
this stage.

Windows policy denies unprivileged symbolic-link creation on this host. The
directory-alias fixture uses an NTFS junction fallback, so it runs rather than
skipping. Hard-link identity and junction traversal are exercised here; POSIX
symbolic-link creation and Linux/macOS host filesystem execution are untested.
Existing no-import request regressions still execute a native Windows x64 program
and check objects for all nine ABI profiles; Shuttle's existing bridge emits an
AArch64 ELF object. This is existing compiler regression evidence. Imported
program typing, circular calls, multi-file native emission and the full compiler
suite remain later-stage verification.

All five changed skills pass the bundled quick validator with PyYAML 6.0.3
installed only in task scratch space. Publication checks pass for 12 documents,
605 local links, 82 anchors, two TOML examples, balanced fences and whitespace;
all 10 changed Compiler source/build files also pass whitespace and final-newline
checks. Git diff whitespace checks pass with submodule traversal disabled.
Temporary validation scripts/dependencies are removed after checking.
