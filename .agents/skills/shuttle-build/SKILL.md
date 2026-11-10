---
name: shuttle-build
description: Design or extend Shuttle, Cloth's build system, including TOML manifests, project and module discovery, configuration diagnostics, build/run orchestration, and the compiler integration boundary.
---

# Shuttle build system

Read the [manifest and build contract](references/semantics.md) before changing
configuration or project commands. Read the
[compiler request contract](../cloth-imports/references/compiler-api.md) for the
public boundary. Source `using` syntax, source graphs and symbol binding belong
to [cloth-imports](../cloth-imports/SKILL.md), not Shuttle.

## Current scope

Stage 2 implements strict TOML manifests, explicit/nearest-ancestor project
selection, canonical module roots, exact-case entry file selection, configuration
diagnostics and an immutable resolved project model. The model prepares plain
compiler requests without parsing Cloth, choosing an ABI or loading LLVM.
[Stage 2 evidence](references/stage-2.md) owns that implementation and verification.
Stage 6 adds the application entry point, shared build options, real compilation,
build-before-run launch and process statuses. Read [Stage 6](references/stage-6.md)
for commands, diagnostics, source ownership and verification.

The [native Shuttle command](references/native.md) is the installed and
development frontend. It parses TOML in Kotlin/Native and calls a private JVM
worker for the existing compiler. `:Shuttle:native` builds both targets from
multiplatform source sets. `:Shuttle` stages the artifacts and exposes the
development, verification and distribution tasks. The native command record
owns the source layout, parser patches, host boundaries and verification.

The interface release exercises the installed `shuttle run` command with a
multi-module generic interface fixture on Windows x64 and x86. The fixture
uses defining-file defaults and exact metadata for same-spelled contracts.
[Imported interface project](../../../examples/Interfaces/build.toml) ·
[Release verification](../cloth-interfaces/references/stage-7.md)

The state release exercises the installed `shuttle run` command with an
imported state attached to two classes. The Compiler checks the host contracts
and transitions through the ordinary graph request.
[Imported state project](../../../examples/States/build.toml) ·
[State release verification](../cloth-language/references/state-publication.md)

[Shuttle](../../../Shuttle/build.gradle.kts) stages the native command and
compiler worker from the same checkout. Bootstrap's module tables use the
canonical lowercase schema. The [compiler facade](../cloth-imports/references/compiler-api.md)
compiles the reachable source graph as one program or object. [Stage 3](../cloth-imports/references/stage-3.md) adds located
compiler import syntax; [Stage 4](../cloth-imports/references/stage-4.md) discovers
source graphs using only supplied roots. [Stage 5](../cloth-imports/references/stage-5.md)
binds/checks those graphs in defining-file contexts. [Compiler Stage 6](../cloth-imports/references/stage-6.md)
emits the complete model and selects the primary file's own main.
[Stage 7](../cloth-imports/references/stage-7.md) completes the
[plan](../../../todo/shuttle-and-imports-plan.md) with a
[maintained nested project](../../../examples/Shuttle/README.md), Bootstrap's
lexer port and available native/foreign-object checks. Ordinary module
subfolders need no additional manifests, including for nested entry files.
The [Bootstrap lexer skill](../cloth-bootstrap-lexer/SKILL.md) owns the scanner,
string-input driver, ownership conventions and current library boundaries.

The [standard-library scaffold](references/standard-library.md) owns the
`Standard-Library/` submodule layout, repository-root source module, `cloth/`
source paths and current object-build validation command.
The [bundled-library Stage 0 contract](../cloth-toolchains/references/standard-library.md)
owns shared installation/development discovery, options, module attachment and
reservation rules. [Bundling Stage 2](../cloth-toolchains/references/standard-library-stage-2.md)
implements shared default selection, command-layer attachment/promotion and
namespace checks. [Bundling Stage 3](../cloth-toolchains/references/standard-library-stage-3.md)
adds shared `--stdlib PATH` and `--no-stdlib` selection before project loading,
including cwd-relative overrides and bypass of broken defaults. Keep installation
discovery outside the TOML project model and compilation API.
[Bundling Stage 1](../cloth-toolchains/references/standard-library-stage-1.md)
implements the build-only Shuttle exporter, reusing ManifestLoader for the pinned
module, and root Gradle distribution/launcher wiring. It adds no runtime TOML/Git
discovery to the compilation API. Stage 2 makes sources available automatically
while preserving explicit imports.
[Bundling publication](../cloth-toolchains/references/standard-library-stage-5.md)
adds a maintained project with only an App module, real packaged external-cwd
builds, target objects and native execution after removing disposable toolchain
and source copies. The [toolchain guide](../../../docs/standard-library.md)
documents release layout, overrides, provenance and current scope.

[Generic Stage 5](../cloth-generics/references/stage-5.md) adds the maintained
Collections project and real List consumers through the public compiler facade.
StandardListConsumerTest executes both commands with default/explicit selection
and checks disabled imports; ToolchainBundleTest exports byte-identical List
source from a temporary clean pin. Neither changes working submodule history or
weakens release cleanliness checks. Portable List logic stays in library source.

## Development rules

- `Shuttle/` is a Git submodule of Cloth, using
  [Cloth-Foundation/Shuttle](https://github.com/Cloth-Foundation/Shuttle) with
  `main` as its update branch. Cloth pins a specific Shuttle commit. Initialize
  it with `git submodule update --init --recursive -- Shuttle`, and keep using
  Cloth's root Gradle wrapper and existing `include("Shuttle")` wiring.
  Commit and publish Shuttle changes in its repository before recording an
  updated submodule commit in Cloth. The gitlink does not include uncommitted
  source or test files. Shuttle's ignore rules must allow `native/src/jvmTest/`.

- Keep TOML dependencies and schema/project logic inside Shuttle. Compile the
  pinned tomlkt sources into the native executable and preserve the distinction
  between dates and strings. Abort on the first detected syntax failure. Apply
  grammar fixes in the parser dependency and keep one parser entry point.
- Anchor configured module/output paths to the selected build.toml's parent.
  Preserve invocation cwd for program launch; do not depend on it for imports.
- Convert the validated project to immutable plain compiler inputs. Use the
  one-way `Shuttle -> Compiler` dependency; do not read compiler ASTs or resolve
  source imports in Shuttle.
- Syntax errors keep parser line/column locations when available. Schema errors identify the
  exact key path and original manifest snapshot; the selected parser does not
  provide precise field spans in valid trees. Use honest file-level labels and
  do not invent positions with a second TOML scanner.
- Keep stable Shuttle diagnostics and first-error status handling. Propagate
  compiler diagnostics unchanged; stop build/run before launching on failure.
- Project modules are source roots for one initial whole-program compilation.
  Circular source imports are valid and do not impose module build ordering.
  Package downloads, separate module artifacts and incremental caches are later
  work, not consequences of empty dependency tables.

## Verification

Run `:Shuttle:test :Shuttle:configurationTest` from the repository root. The
configuration task runs ManifestTest and ProjectModelTest with LLVM/JavaCPP jars
removed from its JVM classpath. The normal task additionally checks the real
public compiler boundary. See [Stage 2](references/stage-2.md) for observed counts,
commands and target limits; [Stage 0](references/stage-0.md) retains the original
dependency-selection probe. Stage 6 adds [ShuttleBuildTest](../../../Shuttle/native/src/jvmTest/kotlin/shuttle/ShuttleBuildTest.kt)
and [ShuttleRunTest](../../../Shuttle/native/src/jvmTest/kotlin/shuttle/ShuttleRunTest.kt),
including child JVM command execution, Unicode/quoted arguments, streams/cwd,
process statuses, one original diagnostic and stale-artifact rejection.
[ShuttlePublicationTest](../../../Shuttle/native/src/jvmTest/kotlin/shuttle/ShuttlePublicationTest.kt)
adds maintained example/Bootstrap runs from nested module folders and foreign
object output. ProjectModelTest also verifies nested entry selection without
native backend jars. See [Stage 7](../cloth-imports/references/stage-7.md) for evidence.
Source semantics should not require TOML.
Publish observed scope through [feature integration](../cloth-feature-integration/SKILL.md).
