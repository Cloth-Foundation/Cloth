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

[Shuttle](../../../Shuttle/build.gradle.kts) has production Compiler and Jackson
TOML 2.21.1 dependencies. Bootstrap's module tables use the canonical lowercase
schema. The [compiler facade](../cloth-imports/references/compiler-api.md)
compiles the reachable source graph as one program or object. [Stage 3](../cloth-imports/references/stage-3.md) adds located
compiler import syntax; [Stage 4](../cloth-imports/references/stage-4.md) discovers
source graphs using only supplied roots. [Stage 5](../cloth-imports/references/stage-5.md)
binds/checks those graphs in defining-file contexts. [Compiler Stage 6](../cloth-imports/references/stage-6.md)
emits the complete model and selects the primary file's own main.
[Stage 7](../cloth-imports/references/stage-7.md) completes the
[plan](../../../todo/shuttle-and-imports-plan.md) with a
[maintained nested project](../../../examples/Shuttle/README.md), Bootstrap's
current lexer scaffold and available native/foreign-object checks. Ordinary module
subfolders need no additional manifests, including for nested entry files.

## Development rules

- `Shuttle/` is a Git submodule of Cloth, using
  [Cloth-Foundation/Shuttle](https://github.com/Cloth-Foundation/Shuttle) with
  `main` as its update branch. Cloth pins a specific Shuttle commit. Initialize
  it with `git submodule update --init --recursive -- Shuttle`, and keep using
  Cloth's root Gradle wrapper and existing `include("Shuttle")` wiring.
  Commit and publish Shuttle changes in its repository before recording an
  updated submodule commit in Cloth. The gitlink does not include uncommitted
  source or test files. Shuttle's own ignore rules must allow `src/test/`.

- Keep TOML dependencies and schema/project logic inside Shuttle. Use the existing
  JVM parser with strict duplicate detection and typed Java time values; never
  create a custom TOML-like grammar. Dates must not be coerced into strings.
  Abort immediately on its first syntax failure.
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
dependency-selection probe. Stage 6 adds [ShuttleBuildTest](../../../Shuttle/src/test/kotlin/shuttle/ShuttleBuildTest.kt)
and [ShuttleRunTest](../../../Shuttle/src/test/kotlin/shuttle/ShuttleRunTest.kt),
including child JVM command execution, Unicode/quoted arguments, streams/cwd,
process statuses, one original diagnostic and stale-artifact rejection.
[ShuttlePublicationTest](../../../Shuttle/src/test/kotlin/shuttle/ShuttlePublicationTest.kt)
adds maintained example/Bootstrap runs from nested module folders and foreign
object output. ProjectModelTest also verifies nested entry selection without
native backend jars. See [Stage 7](../cloth-imports/references/stage-7.md) for evidence.
Source semantics should not require TOML.
Publish observed scope through [feature integration](../cloth-feature-integration/SKILL.md).
