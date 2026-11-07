# Import Stage 5: per-file binding and graph semantics

Implemented 2026-11-07. The [import contract](semantics.md) owns language rules;
this record owns the semantic implementation and verification. Whole-program
emission and graph executable entry integration remain Stage 6.

## Implemented behavior

Every discovered file keeps its own type names and callable scope. The compiler
registers nominal and function identities across the entire graph before binding
imports or resolving layouts/signatures. Selective imports receive public types
and all public overloads of each selected function name. Wildcards receive only
the defining file's own public declarations. Imported bindings are never implicit
exports; private overloads and helpers remain available only in their defining
file. Instance methods remain associated with their types.

Type and callable names occupy separate namespaces. A selection can populate
both when a file publicly defines a type and function with the same spelling.
Repeated imports of the same identities are idempotent, including wildcard/name
overlap and self imports of public declarations. Different identities exposing
the same name conflict in their namespace. Functions from separate files never
merge into one overload group. Ordinary nearer lexical shadowing and builtin
lookup precedence remain unchanged.

Types, parameters/results, fields, bases, constructors, defaults, methods,
operators and body annotations resolve in their original source context. Public
functions keep their own private helpers and default expressions. Already-typed
values can cross a public call without exposing the defining type's name to the
caller. Same-spelled private structs/classes/enums remain distinct nominal types.
Method signature keys include nominal parameter identity so inherited methods
cannot hide a different file's same-spelled private parameter type.

Source cycles require no topological ordering. Circular functions and mutually
referring class identities bind before body checking. One builtin Object root
is shared across the analysis. Cross-file inheritance cycles and recursive inline
struct layouts still fail with their existing diagnostics. All ordinary loaded
declarations, including private/unused functions, remain checked under the
existing unit-checking and width-specialization rules.

## Source ownership and invariants

- [FileBindings](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/FileBindings.kt)
  builds one FileContext per original unit, public export views, received bindings
  and original FunctionSymbol instances. It maps source snapshots to defining
  contexts by identity. Nominal catalogs use declaration identity plus a separate
  declaration-order list; lexical maps are local to each file.
- [NamedTypes](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/NamedTypes.kt)
  resolves bases and fields through those contexts. Struct identity now exists
  before fields: [CStruct](../../../../Compiler/src/main/kotlin/cloth/data/CStruct.kt)
  finalizes fields before layout/storage facts are consumed. Inline traversal
  uses descriptor identity and retains the original cycle labels.
- [DeclarationAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationAnalyzer.kt)
  has an internal graph/bindings entry point and keeps public one-unit adapters.
  File scopes parent callable/default scopes. Constructors, generated enum readers
  and deferred blocks retain their source/owner context.
- [DeclarationModel](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationModel.kt)
  owns global identity-based class definitions, all structs/enums and contextual
  name lookup. Its existing name-keyed structs/classes/enums views describe only
  the primary file's visible names for single-file compatibility; production
  global traversals use the complete identity catalogs.
- [TypeResolver](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/TypeResolver.kt)
  accepts a token-aware nominal callback. When present, a missing name never
  falls back to a different file's names. [FunctionAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt)
  and [FunctionSemanticModel](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionSemanticModel.kt)
  carry that context through signatures, typeinfo, trials and specialized bodies.
- [Symbols](../../../../Compiler/src/main/kotlin/cloth/semantic/symbols/Symbols.kt)
  preserve object identity. Function signatures are observed after binding;
  nominal signature components retain their descriptor. Specialized method
  symbols retain that same context through
  [CallableFacts](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/CallableFacts.kt).
- Flow, enum payload, operator, constructor and storage consumers now use
  contextual types or identity-based catalogs. The small emitter consumer updates
  preserve existing one-file output; they do not enable graph emission.

Internal `FunctionAnalyzer(target).analyze(graph)` performs graph semantic analysis
with an explicit TargetLayout and no manifests. Reanalyzing one graph at another
layout creates fresh nominal descriptors and one fresh Object root. No source
concatenation, declaration renaming or mutable ambient current-file state is used.

## Driver boundary and diagnostics

The public compiler still accepts plain requests and returns only actual artifact
paths. It discovers the complete graph and protects loaded inputs as in Stage 4.
For import-bearing graphs it binds exports/selections before native ABI selection,
then checks the complete semantic model using the actual target layout. Original
type/body errors propagate unchanged. Successful checking throws C3105 before
emitter construction; it cannot emit only the primary file or replace stale output.
Graph executable main selection/validation remains Stage 6; internal graph type
analysis does not select an entry point.

| Code | Meaning |
| --- | --- |
| C3096 / IMPORTS_REQUIRE_SOURCE_GRAPH | A direct SourceUnit semantic adapter still needs an explicit graph context for imports. |
| C3101 | Retired after binding integration; never reuse. |
| C3102 / IMPORT_NAME_NOT_FOUND | The target file's own public export view lacks the selector. |
| C3103 / IMPORT_PRIVATE_NAME | The requested name is private; label selector and original definition. |
| C3104 / IMPORT_NAME_CONFLICT | Different definitions collide; label the import, existing/imported definitions and earlier import when applicable. |
| C3105 / IMPORT_EMISSION_REQUIRED | The imported graph passed semantic checking but Stage 6 emission is pending. |

Discovery and syntax retain their existing codes. Binding order is file discovery
order, then written header/selector order. Catalogs are registered before binding,
so selection order does not change successful visibility. Semantic passes retain
the existing deterministic layout/signature/default/body phase order. Throw one
original diagnostic, with related source labels explaining that same failure.

## Verification

[ImportSemanticsTest](../../../../Compiler/src/test/kotlin/cloth/ImportSemanticsTest.kt)
contains **33 source-tree cases** for selected/wildcard imports, public/private
visibility, no reexports, separate namespaces, collisions and original labels,
idempotence, overload filtering, lexical/builtin shadowing, defining-file helpers
and defaults, private signature types, same-spelled nominal isolation, circular
functions/classes, one Object root, invalid inheritance/inline cycles, imported
methods/enums, unused/private validation, member privacy, inherited nominal
overloads, private constructors/readers, contextual typeinfo, 32/64-bit reanalysis
and cross-module cycles.

Run `:Compiler:importSemanticsTest` to execute these with native platform jars
excluded and `org.bytedeco.javacpp.loadlibraries=false`. Existing builtin
descriptors reference LLVM JVM API types, so their API jars remain on this task's
classpath. Native loading is explicitly disabled and asserted in the suite.
Syntax/discovery tasks still remove all LLVM/JavaCPP jars. All three tasks are
dependencies of the standard Compiler check.

[ImportSemanticBoundaryTest](../../../../Compiler/src/test/kotlin/cloth/ImportSemanticBoundaryTest.kt)
adds three normal-runtime public-facade cases: checking an unused imported body,
retaining stale output at C3105 and reporting private imports before target work.
Existing import syntax/resolution boundary cases now expect Stage 5 binding
diagnostics, while preserving C3096 for direct single-unit analysis.

The full Compiler/Shuttle checks passed on Windows x64/JDK 25 in **6m 59s**:

| Task | Suites | Tests | Failures/errors/skips |
| --- | ---: | ---: | --- |
| Compiler test, full suite | 141 | 1,593 | 0 / 0 / 0 |
| Compiler importSyntaxTest | 2 | 23 | 0 / 0 / 0 |
| Compiler importResolutionTest | 1 | 27 | 0 / 0 / 0 |
| Compiler importSemanticsTest | 1 | 33 | 0 / 0 / 0 |
| Shuttle test | 3 | 28 | 0 / 0 / 0 |
| Shuttle configurationTest | 2 | 24 | 0 / 0 / 0 |

Run the standard checks from the repository root after dependencies are cached:

```powershell
.\gradlew.bat :Compiler:check :Shuttle:check --offline --console=plain --no-daemon
```

The observed run used `--init-script tmp/imports-stage5-20261107/isolate.gradle`
before the task arguments to isolate class files and reports from other workspace
builds. That temporary init script contained:

```groovy
gradle.beforeProject { project ->
    if (project.rootProject.name == 'Cloth') {
        def name = project.path == ':' ? 'root' : project.path.substring(1).replace(':', '_')
        project.layout.buildDirectory.set(new File(project.rootProject.projectDir, 'tmp/imports-stage5-20261107/build/' + name))
    }
}
```

New graph semantic tests use synthetic 32/64-bit layouts. The full suite includes
existing native Windows and all-nine-profile object/IR regressions for existing
one-file features. Imported native execution is still Stage 6 work; no new foreign
host execution claim is made here. Shuttle's source/submodule bookkeeping is
unchanged by this stage. Temporary isolated outputs and validation helpers are
removed after verification.

All eight changed skills pass the bundled quick validator using task-local
PyYAML 6.0.3. Publication checks pass for 15 documents, 710 local links, 97
anchors, two TOML examples, balanced fences and whitespace. All 26 touched
Compiler source/build files pass whitespace/final-newline checks; Git diff
whitespace checks pass with submodule traversal disabled.
