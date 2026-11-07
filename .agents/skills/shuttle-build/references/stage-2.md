# Shuttle Stage 2: manifest loading and project model

Implemented 2026-11-07. The [manifest contract](semantics.md) owns the schema and
discovery rules; this record owns source routing, diagnostics and verification.
The [plan](../../../../todo/shuttle-and-imports-plan.md) marks Stages 0-2 complete.

## Implemented boundary

[Shuttle's Gradle module](../../../../Shuttle/build.gradle.kts) now depends on
Compiler in production and Jackson TOML 2.21.1 through its aligned BOM. Compiler
still has no Shuttle/TOML dependency. Production code is under `shuttle`:

- [ManifestLoader](../../../../Shuttle/src/main/kotlin/shuttle/manifest/ManifestLoader.kt)
  reads strict UTF-8 snapshots, parses standard TOML, and validates canonical
  project/module fields, identifiers, entry selection, portable artifact names,
  unknown keys, exact types and empty dependency tables. Versions are optional
  metadata. It never invokes a Cloth lexer/parser.
- [ProjectLoader](../../../../Shuttle/src/main/kotlin/shuttle/project/ProjectLoader.kt)
  selects an explicit directory/build.toml or the nearest ancestor manifest,
  resolves configuration from its parent, canonicalizes physical module roots,
  checks module metadata, then resolves output and exact-case entry components.
  Invalid selected manifests never fall back to another project.
- [ResolvedProject](../../../../Shuttle/src/main/kotlin/shuttle/project/ResolvedProject.kt)
  retains immutable module/manifest lists and snapshots. `toCompilationRequest`
  produces absolute compiler inputs and forwards output kind, target and link
  options. It checks manifest output aliases but never compiles source, chooses
  an ABI, discovers a linker, creates directories or writes artifacts.
- [ShuttleDiagnostic](../../../../Shuttle/src/main/kotlin/shuttle/diagnostics/ShuttleDiagnostic.kt)
  is a separate structured Yvette diagnostic with stable S codes. It reuses the
  public source snapshot/range utilities without wrapping compiler diagnostics.

These loader/model APIs are internal to Shuttle; the public cross-module API
remains `cloth.api`. `ProjectLoader.load(invocationDirectory, project)` accepts a
nullable explicit selection. Default output paths are output.directory/name for
executables and output.directory/name.o for objects. An explicit output override
must already be absolute, as must nested link paths supplied by a future CLI
adapter. Object requests use null entryFunction and need no main declaration.
Target/linker validity remains Compiler's responsibility when compilation is
actually requested.

Output normalization resolves the existing directory prefix before appending and
normalizing a nonexistent suffix. Module duplicate checks use physical identity.
Request preparation rejects literal/physical aliases of all loaded manifests,
including hard links and every supported executable suffix possibility. The
conservative executable check protects both the requested name and its possible
.exe variant without consulting a target or loading native classes.

Bootstrap's Frontend, Compiler and Backend module manifests now use lowercase
module/dependencies tables. Their source imports were not changed by this stage.
The project loads and prepares a request; its complete source scaffold is not
yet a supported compilation fixture.

The Stage 2 production package/dependency setup is complete. Gradle application
packaging and its actual main class are kept together with the Stage 6 CLI;
there is no placeholder launcher or command that pretends to build a project.

## TOML and first-error behavior

The mapper enables STRICT_DUPLICATE_DETECTION and PARSE_JAVA_TIME. The latter is
necessary because otherwise valid TOML date/time values become textual tree
nodes and could pass a string schema field. Typed time nodes fail string schema
validation instead. All schema strings must be nonblank; values are preserved,
not trimmed or case folded. Paths have no environment/shell expansion.

TOML parsing throws immediately at its first failure. Some invalid date/time
values throw DateTimeException without parser locations; these receive file
context. Other parser failures retain original line/column positions when valid
for the snapshot. Successful trees have no field locations: schema diagnostics
identify the exact logical key and use a label saying "In this manifest". There
is no guessed key span, custom TOML scanner, recovery or list of errors.

Project fields are checked in documented schema order, then modules in ordinal
ID order, then output directory and entry file. Unknown keys are ordinal within
their table after known fields. Request output checks run before compilation.
Unreadable/invalid UTF-8 files retain path and cause without replacement text.

## Diagnostics

| Code | Identity | Failure |
| --- | --- | --- |
| S0001 | PROJECT_NOT_FOUND | No ancestor build.toml. |
| S0002 | MANIFEST_READ | Missing/unreadable selected project or module manifest. |
| S0003 | INVALID_MANIFEST_ENCODING | Invalid UTF-8. |
| S0004 | INVALID_TOML | First syntax, duplicate-definition or invalid date/time failure. |
| S0005 | INVALID_MANIFEST_SCHEMA | Missing/wrong/unknown fields, invalid identifiers or paths. |
| S0006 | UNSUPPORTED_DEPENDENCIES | Nonempty dependency table. |
| S0007 | INVALID_PROJECT_SELECTION | Invalid explicit selection or invocation directory. |
| S0008 | INVALID_MODULE_ROOT | Missing/inaccessible/non-directory source root. |
| S0009 | DUPLICATE_MODULE_ROOT | Two configured modules share one physical root. |
| S0010 | MODULE_NAME_MISMATCH | module.name differs from its project mapping key. |
| S0011 | INVALID_ENTRY_POINT | Invalid dotted entry, undeclared module or missing/wrong-case source. |
| S0012 | OUTPUT_OVERWRITES_MANIFEST | Artifact aliases a loaded manifest. |
| S0013 | INVALID_OUTPUT_SELECTION | Invalid/inaccessible output directory or artifact override. |

Throw exactly one failure. Future presentation code reports it once. Process
statuses and launch failures remain Stage 6 work; allocating these codes does
not implement command-line error handling.

## Verification

Run from the repository root on JDK 25 after first-time dependency resolution:

```powershell
.\gradlew.bat :Shuttle:test :Shuttle:configurationTest --offline --console=plain --no-daemon
```

The final `:Shuttle:build` run also passes in 10 seconds. Its standard check task
depends on configurationTest, so future builds include the native-independent
checks alongside the normal test task.

Observed on Windows x64: the normal task passes **28 tests across three suites**,
with zero failures, errors or skips:

- [ManifestTest](../../../../Shuttle/src/test/kotlin/shuttle/ManifestTest.kt): 12
  tests covering strict types including dates, required/unknown/case-sensitive
  fields, optional metadata, artifact/identifier rules, unsupported dependencies,
  duplicates, first syntax error, CRLF, retained snapshots, malformed UTF-8,
  Unicode/multiline values and graphical S-code rendering.
- [ProjectModelTest](../../../../Shuttle/src/test/kotlin/shuttle/ProjectModelTest.kt):
  12 tests covering nested/explicit project selection, paths with spaces/Unicode,
  no fallback, missing files, deterministic failures, duplicate physical roots,
  module mismatch, exact-case entry files, absolute/root-relative configuration,
  output normalization without writes, immutable collections, option forwarding,
  object entry policy, manifest hard-link/suffix protection and migrated Bootstrap.
- [CompilerApiBoundaryTest](../../../../Shuttle/src/test/kotlin/shuttle/CompilerApiBoundaryTest.kt):
  four tests, including a newly loaded TOML project compiled to an AArch64 Linux
  ELF object through the public facade without main or a linker. A later source
  error remains the original compiler C3020 diagnostic. Existing consumer tests
  also retain architecture checks, rendering and invalid-request coverage.

The separate configurationTest task runs the same **24 manifest/model tests**
with LLVM/JavaCPP jars removed from its JVM classpath: zero failures, errors or
skips. Invalid Cloth text, an unsupported target string and a missing linker can
all be loaded/prepared there, proving that configuration does not invoke source
compilation or target/linker discovery. Gradle dependency resolution may still
need its normal cache; this is runtime isolation rather than an offline package
installation test.

No Compiler production source changed in Stage 2. The object test uses the native
LLVM library to emit a foreign object; it does not execute AArch64 code. This
stage adds no new native program execution evidence and makes no Linux/macOS
host-runtime claim. Compiler import syntax, source graphs, circular binding and
Shuttle build/run remain later stages. The
[Stage 0 record](stage-0.md) retains the original 23-check dependency probe;
[Stage 1](../../cloth-imports/references/stage-1.md) retains broader compiler API
and target verification.

Both compileClasspath dependency reports pass: Compiler contains no Shuttle or
TOML dependency, while Shuttle's compile classpath includes the public compiler
diagnostic libraries and TOML without LLVM/JavaCPP. The configuration test's
runtime filtering is separately verified by its successful 24-test run.

All four changed skills pass the bundled quick validator with PyYAML 6.0.3 in
an isolated temporary directory. Publication checks pass for 10 documents, 538
local links, 76 anchors, two TOML examples, balanced fences and whitespace; all
nine Shuttle source/build files and four Bootstrap TOML files also pass their
whitespace/syntax checks as applicable. Git diff whitespace checks pass. Temporary
validation dependencies/scripts are removed after verification.

## Repository split verification (2026-11-07)

`Shuttle/` is now a submodule of Cloth, using the Shuttle repository's `main`
branch and initially pinning `de7b9c6cd1cd9b84e43cf5ae4d62aaad3898b4f0`.
Its production source and Gradle build file match the previous Cloth directory.
Cloth's existing `include("Shuttle")` and one-way Compiler dependency still apply;
run verification through Cloth's root Gradle wrapper.

The same offline verification command passes after the split on Windows x64:
28 normal tests and 24 configuration tests, with no failures, errors or skips.
This rerun adds no new native-platform coverage.

The starting published Shuttle commit omits the four existing test/support
files because its copied ignore rules use the old Cloth-relative paths. The
migration preserves those files as local Shuttle changes and replaces those
exceptions with `!src/test/`. These changes still need publishing in Shuttle;
Cloth's initial submodule pin alone does not include them. See the owning
[skill](../SKILL.md) for the repository and commit workflow.
