# Bundled standard library Stage 2: automatic source availability

Implemented 2026-11-07. The [contract](standard-library.md) owns selected policy;
[Stage 1](standard-library-stage-1.md) owns distribution contents and launcher
handoff. This stage consumes that context, attaches the default library and
enforces its source namespace. [Stage 3](standard-library-stage-3.md) subsequently
adds the selected override and disable options.

## Programmer-visible behavior

Both installed commands automatically supply `<toolchain>/stdlib` as the
standard-library source root. Gradle development commands supply the repository's
`Standard-Library/` root instead. Users need no library entry in `build.toml`.
They still select public declarations explicitly with `using`.

A future `cloth/collections/List.co` is selected as
`using cloth.collections.List : List`. `List` remains an illustrative API; this
stage adds no collection implementation. Verification uses small declarations
inserted into copies of the installed bundle, not new shipped library APIs.

Only reachable files are parsed/checked/emitted, including transitive and circular
imports. Availability does not expose names without imports or scan unused files.
The standalone compiler supplies the library root; it still does not infer project
roots from the source directory. Shuttle also supplies its configured project
roots. Consequently unresolved project imports in the standalone command now use
C3098, while an explicit API request without any roots still uses C3097.

## Shared command support

[StandardLibrary](../../../../Compiler/src/main/kotlin/cloth/toolchain/StandardLibrary.kt)
owns default selection and request attachment. Both the
[compiler adapter](../../../../Compiler/src/main/kotlin/cloth/CompilerDriver.kt)
and [Shuttle build adapter](../../../../Shuttle/src/main/kotlin/shuttle/command/ProjectBuild.kt)
call it after option/command validation and before reading the program or Shuttle
project. This enforces early failure for an incomplete default even without imports.

Selection reads only the launcher/development properties `cloth.toolchain.home`
and `cloth.toolchain.layout`. Home must be absolute and layout must be `installed`
or `development`. There is no cwd, PATH, ancestor or alternate-installation fallback,
and no Git, download or TOML dependency in this command support.

Require exact-case readable `module.toml`, directory `cloth`, and readable
`cloth/Library.co`. Shared selection checks structure without interpreting TOML
or parsing the marker's Cloth contents. Installed defaults additionally read
`toolchain.properties` through the JDK properties reader with strict UTF-8 decoding;
require format `1` and nonblank compiler/library versions and library revision.
Development selection requires no release descriptor, clean checkout or revision
match. Source typing still detects incompatibilities in reached files.

`attachTo` validates the existing plain request, promotes a module identifying the
selected physical root or attaches a new `StandardLibrary` module. Promotion keeps
the existing ID and creates no duplicate. Unrelated duplicate IDs/roots remain
invalid; an occupied `StandardLibrary` ID conflicts if no selected root can be
promoted. Preserve output kind, entry, target, link options and immutable snapshots.

Before compilation, protect the selected module manifest, marker and any consumed
release descriptor from output aliasing. Check normalized paths, physical aliases
such as hard links, and all supported executable suffix variants. Compiler retains
its existing loaded-source protection. No selection/attachment failure creates
outputs or launches a stale artifact.

## Explicit compiler API and reserved paths

[SourceModule](../../../../Compiler/src/main/kotlin/cloth/api/CompilationRequest.kt)
now has `role: SourceModuleRole = REGULAR`. Existing two-argument Kotlin and Java
construction remains available. A caller can explicitly mark a library:

```kotlin
SourceModule("Library", libraryRoot, SourceModuleRole.STANDARD_LIBRARY)
```

Module IDs remain configuration identities, not path prefixes or implicit roles.
[Request validation](../../../../Compiler/src/main/kotlin/cloth/api/CompilationRequestValidation.kt)
retains roles while canonicalizing roots and permits at most one standard-library
role. Plain `ClothCompiler.compile(CompilationRequest)` never consults an
installation or project manifests. All-regular requests preserve existing behavior,
including ordinary `cloth.*` sources and ordinary multi-root ambiguity.

[Graph discovery](../../../../Compiler/src/main/kotlin/cloth/source/SourceGraphLoader.kt)
continues to find exact-case candidates across supplied roots before loading a file.
When a marked library exists, a reached path whose first component is exactly
lowercase `cloth` belongs to that library. Any regular-root candidate for that path
is a reserved-namespace error, even if the selected library lacks its own file.
Report the import, selected root and conflicting candidate paths without parsing
the conflicts. There is no implicit shadowing, extension or fallback.

Other paths retain all-root ambiguity rules. Apply the same reservation to
transitive imports. Do not scan unused directories or prohibit an arbitrary primary
file's location. Public exports, defining-file contexts, exact case, circular edges,
single physical source identities and per-file snapshots retain their old rules.

## Diagnostics

Definitions live in
[StandardLibraryErrors](../../../../Compiler/src/main/kotlin/cloth/diagnostics/StandardLibraryErrors.kt)
and [ImportErrors](../../../../Compiler/src/main/kotlin/cloth/diagnostics/ImportErrors.kt).

| Code | Meaning |
| --- | --- |
| C0017 | Invalid request, including multiple standard-library roles or duplicate IDs/roots. |
| C0018 | Missing/invalid default context, incomplete structure, unreadable input or invalid release metadata. |
| C0019 | Implicit library module ID conflicts with a different configured root. |
| C0020 | Output would overwrite selected toolchain metadata or its marker. |
| C3106 | A regular module supplies a reached reserved standard-library path. |

Both frontends propagate these original C diagnostics and render one error, with
related labels/paths for that error. Existing parser, binding, target and body
failures retain their codes/order after source discovery. Expected failures return
1; existing internal failures retain 2. No diagnostic accumulation was added.

## Verification routing and scope

- [StandardLibraryTest](../../../../Compiler/src/test/kotlin/cloth/StandardLibraryTest.kt):
  11 backend-free cases for contexts, strict descriptor decoding, exact marker
  case, immutable promotion/options, physical aliases, duplicate roots/roles,
  ID conflicts, metadata/marker aliases, API independence and early default failure.
- [StandardLibraryGraphTest](../../../../Compiler/src/test/kotlin/cloth/StandardLibraryGraphTest.kt):
  seven backend-free cases for transitive/circular reachability, reserved paths
  with/without a library candidate, original transitive snapshots, missing sources,
  ordinary ambiguity, regular-root replacements and exact lowercase reservation.
  Both suites join the existing `:Compiler:importResolutionTest` native-free task.
- [Distribution tests](../../../../src/test/java/cloth/ToolchainDistributionTest.java):
  four cases cover the packaged layout/relocation, actual native library calls
  through both commands without manifest entries, cyclic library fixtures,
  unreachable malformed source, promotion, explicit imports, reserved conflicts,
  metadata protection and early failure before malformed project/source input.

Root Gradle supplies explicit development context to command-oriented test tasks;
Shuttle's child-process helper forwards that context. This does not inject roots
into API requests or project-model preparation. Legacy standalone missing-project
expectations were updated from C3097 to C3098.

Focused verification passed with all 46 native-free resolution tests and all four
distribution tests, with no failures/errors/skips. New packaged execution covers Windows x64; no new
Linux/macOS execution or full Windows Unicode installation-path support is claimed.

The production library still contains only its scaffold. Native-service APIs,
collection APIs and a separate library binary ABI are pending. Override/disable
flags are implemented in the subsequent Stage 3 record linked above.

### Full verification (2026-11-07)

```powershell
.\gradlew.bat :Compiler:check :Shuttle:check :test :distZip :distTar :Compiler:installDist :Shuttle:installDist --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

Passed in 9m 2s: 1,629 Compiler tests across 147 suites, 56 Shuttle tests, four
distribution tests, 23 native-free import syntax checks, 46 native-free resolution
checks, 33 native-disabled semantic checks and 25 backend-free configuration
checks. Every suite has zero failures, errors and skips. Combined archives and
both per-command installations are rebuilt with the Stage 2 implementation.
Existing native/foreign-target regression coverage remains distinguished from the
new Windows x64 standard-library fixture execution above.

The library's own project also builds through the development Shuttle command,
emitting its 306-byte object and confirming promotion of the already configured
root. During verification, unrelated emitter/string-storage source and test edits
arrived in the shared workspace. They were preserved. The full-suite counts above
refer to that run's compiled snapshot; the full Compiler suite was not rerun after
the later unrelated edits were rebuilt.

After refreshing the compiler, `:Compiler:importResolutionTest :Shuttle:check :test`
passed again in 1m 26s: all 46 resolution checks, 56 Shuttle tests, 25 configuration
checks and four packaged-command tests. Final archives/per-command installations
are repacked from those tested JARs with compilation/JAR generation excluded,
keeping this validation separate from concurrent source changes.
SHA-256 checks confirm that both ZIP/TAR application JARs and the per-command
installations match the tested combined installation.

Publication checks pass for 16 documents, 721 local links, 83 heading anchors,
fences, whitespace, 17 source/configuration files and completion of only Stages
0-2. Five relevant skills pass the full bundled validator with temporary PyYAML
under workspace `tmp/`, removed after validation. Source and library-pin checks
preserve unrelated working-tree changes.
