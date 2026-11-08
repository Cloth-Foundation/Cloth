---
name: cloth-imports
description: Design or extend Cloth using imports, selective and wildcard bindings, source graphs, circular imports, per-file scopes, or the manifest-independent compiler request API.
---

# Cloth imports

Read the [import contract](references/semantics.md) before changing import syntax,
visibility, overload binding, file resolution or circular-source handling. Read
the [compiler API contract](references/compiler-api.md) when changing the driver,
request/result types, entry selection or the Shuttle integration boundary.

## Current scope

Stage 1 implements the public compiler request API for one SourceUnit and keeps
the CLI/test adapter on that path. See the [API contract](references/compiler-api.md)
and [Stage 1 evidence](references/stage-1.md) for validation, immutable inputs,
diagnostic propagation and the real Shuttle consumer fixture. Stage 3 adds
located import syntax and file headers; see [Stage 3 evidence](references/stage-3.md).
Stage 4 adds [source discovery and graphs](references/stage-4.md), including
exact-case root matching, physical file identity, circular edges and output
protection. Stage 5 adds [per-file binding and semantic analysis](references/stage-5.md),
including public exports, overload groups and contextual nominal types across
cycles. Stage 6 adds [whole-program emission and primary entry selection](references/stage-6.md),
including native circular calls, nominal runtime metadata and Shuttle integration.
[Stage 7](references/stage-7.md) adds the maintained nested project,
Bootstrap source integration and native/IR/object evidence. Subfolders stay literal
parts of paths under a module root; the importing file's directory is not a new root.
The [staged plan](../../../todo/shuttle-and-imports-plan.md) owns implementation order;
the [Stage 0 record](../shuttle-build/references/stage-0.md) retains the design and
dependency-selection snapshot.

Stage 2 implements [Shuttle manifest/model loading](../shuttle-build/references/stage-2.md)
and converts it into this same request. This adds no compiler import support;
Compiler still has no TOML or Shuttle dependency.

The [bundled-library contract](../cloth-toolchains/references/standard-library.md)
and [Stage 2 record](../cloth-toolchains/references/standard-library-stage-2.md)
own default command attachment and explicit source-module roles. A marked library
reserves reached `cloth.*` paths; regular-root candidates produce C3106. All-regular
requests keep ordinary ambiguity rules and do not discover an installation.
Canonicalization must retain each module's role.
[Selection controls](../cloth-toolchains/references/standard-library-stage-3.md)
allow another library root or `--no-stdlib`; disabled commands preserve configured
roots as regular modules, permitting replacements with ordinary ambiguity checks.
[Bundling publication](../cloth-toolchains/references/standard-library-stage-5.md)
verifies the maintained transitive library graph, explicit visibility and real
packaged builds whose executables survive removal of compiler/source copies.
StandardLibraryPublicationTest covers the exact graph and all-profile startup objects.

```cloth
using token.Token : Token, TokenType, TokenSpan
using token.Token : *
```

## Preserve these boundaries

- Source paths omit module names. Resolve them across explicit module roots from
  the request; multiple matching roots are ambiguous even inside a cycle.
- Circular imports are allowed. Load each physical source once, register
  declaration identities and connect file scopes before resolving types/bodies.
  Keep existing inheritance and inline-layout cycle checks.
- Import original symbols and visible overload groups. Preserve each defining
  file's private helpers, nominal identities, defaults and member lookup context.
  Do not concatenate sources or use global simple-name maps for their identities.
- Keep one built-in Object identity per compilation. Existing strict typing,
  nullable flow, value copies and explicit object lifetime apply across files.
- The compiler accepts plain paths/options and owns source discovery, language
  diagnostics, target layout and emission. TOML and project discovery belong to
  [Shuttle](../shuttle-build/SKILL.md); never depend on Shuttle from Compiler.
- Keep fail-first diagnostics and original source snapshots. Use enum spellings
  for Keywords.USING and operators. The former `use` keyword is now an ordinary
  identifier, and is not an alternate import command.

## Implementation and verification routing

Syntax is owned by [ImportParser](../../../Compiler/src/main/kotlin/cloth/parser/subs/ImportParser.kt),
[import nodes](../../../Compiler/src/main/kotlin/cloth/ast/ImportDeclaration.kt),
[SourceUnit](../../../Compiler/src/main/kotlin/cloth/ast/SourceUnit.kt) and
[ImportErrors](../../../Compiler/src/main/kotlin/cloth/diagnostics/ImportErrors.kt).
SourceUnit retains ordered imports separately from declarations, original tokens
and EOF/source identity. It preserves the existing constructor defaults.
Discovery is owned by [SourceGraphLoader](../../../Compiler/src/main/kotlin/cloth/source/SourceGraphLoader.kt)
and [SourceGraph](../../../Compiler/src/main/kotlin/cloth/source/SourceGraph.kt).
They consume validated compiler requests, retain per-file units and import edges,
and never merge declarations or select symbols. Resolve across all roots before
consulting the load cache. Follow written depth-first order with an explicit
stack and loading/loaded states; reuse both active and completed source identities.

The driver now discovers the graph and checks loaded-source output aliases.
Missing context/files and ambiguous paths have their own located diagnostics.
[FileBindings](../../../Compiler/src/main/kotlin/cloth/semantic/declarations/FileBindings.kt)
registers original identities and export views for every file before binding
imports. Type/function namespaces stay separate, private overloads stay local,
and received imports are never reexported. Bindings/types resolve through original
source context; global catalogs use declaration/nominal identity. The primary
name views on DeclarationModel exist for single-file compatibility.

The driver binds imports before ABI selection, then checks all declarations,
signatures/defaults/bodies with the actual target layout. It emits that complete
checked model through one LLVM module. ProgramAnalyzer selects
only the primary file's own main; other files' mains remain ordinary callables.
C3101 and C3105 are retired; direct one-unit semantic analysis retains C3096.
Never discard imports or emit just the primary file. Callable symbols, class tags
and layout caches retain nominal identity. Runtime typeinfo descriptors distinguish
nominal declarations by original source/position, recursively through arrays and
nullable types, while their displayed names stay unqualified.

For the next stages, start with [CompilerDriver](../../../Compiler/src/main/kotlin/cloth/CompilerDriver.kt),
[Parser](../../../Compiler/src/main/kotlin/cloth/parser/Parser.kt),
[DeclarationAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationAnalyzer.kt),
[NamedTypes](../../../Compiler/src/main/kotlin/cloth/semantic/declarations/NamedTypes.kt),
[FunctionAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt)
and [ProgramAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/ProgramAnalyzer.kt).
The plan records the identity audit, maintained examples and observed target scope.

[CompilationRequestTest](../../../Compiler/src/test/kotlin/cloth/CompilationRequestTest.kt)
and [Shuttle's API fixture](../../../Shuttle/src/test/kotlin/shuttle/CompilerApiBoundaryTest.kt)
verify the public boundary. [ImportParsingTest](../../../Compiler/src/test/kotlin/cloth/ImportParsingTest.kt)
and [ImportStageBoundaryTest](../../../Compiler/src/test/kotlin/cloth/ImportStageBoundaryTest.kt)
cover Stage 3. Run `:Compiler:importSyntaxTest` for these 23 tests with native
backend jars removed from the JVM classpath. The standard Compiler check includes
this task. [ImportResolutionTest](../../../Compiler/src/test/kotlin/cloth/ImportResolutionTest.kt)
adds 27 graph cases, also run without native backend jars by
`:Compiler:importResolutionTest`. The standard Compiler check includes both tasks.
See [Stage 4 evidence](references/stage-4.md) for the full focused run and host
filesystem limits. [ImportSemanticsTest](../../../Compiler/src/test/kotlin/cloth/ImportSemanticsTest.kt)
adds 33 source-tree cases. `:Compiler:importSemanticsTest` uses synthetic layouts,
excludes native platform jars and disables native loading while retaining the JVM
API classes required by existing builtin descriptors. The normal suite also runs
[ImportSemanticBoundaryTest](../../../Compiler/src/test/kotlin/cloth/ImportSemanticBoundaryTest.kt)
through the public facade. [ImportIntegrationTest](../../../Compiler/src/test/kotlin/cloth/ImportIntegrationTest.kt)
adds Stage 6 native execution, primary entry selection, buffered/default contexts,
private nominal emission, exact runtime metadata and all-nine-profile objects.
See [Stage 6 evidence](references/stage-6.md) for observed results and limits.
Preserve visibility, circular calls, overloads and defining-file defaults.
Stage 7 adds [ImportPublicationTest](../../../Compiler/src/test/kotlin/cloth/ImportPublicationTest.kt)
for maintained source graphs, available native profiles and all-nine-profile
executable IR/startup objects. The graph suite also tests a deep importing folder
with a local decoy and rejects flattened basename lookup. C3097 guides project
users to Shuttle or explicit module roots. See the
[nested example](../../../examples/Shuttle/README.md) and [Stage 7](references/stage-7.md).
Use synthetic layouts for semantic tests and distinguish native execution from
foreign IR/objects. Apply [feature integration](../cloth-feature-integration/SKILL.md)
to every implemented stage and update this scope with observed evidence.
