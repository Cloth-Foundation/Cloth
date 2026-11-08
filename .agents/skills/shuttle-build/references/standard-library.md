# Standard-library project scaffold

Integrated locally on 2026-11-07. The user selected
[Cloth-Foundation/Standard-Library](https://github.com/Cloth-Foundation/Standard-Library)
as the standard-library repository, requested replacement of its old contents,
and specified `cloth/collections/List.co` as an example source path relative to
that repository's root. This is project scaffolding using existing compiler and
Shuttle behavior, with no new library APIs or compiler features.

## Layout and source roots

- Cloth mounts the repository at `Standard-Library/`, through the `stdlib`
  submodule entry in [.gitmodules](../../../../.gitmodules). Its update branch
  is `main`; the superproject still pins a specific commit.
- [build.toml](../../../../Standard-Library/build.toml) selects the single
  `StandardLibrary` module at `.`. Its matching
  [module.toml](../../../../Standard-Library/module.toml) is at the repository
  root. All library `.co` sources belong under `cloth/`.
- Keeping the repository root as the source root preserves the `cloth` path
  component in imports. A future `cloth/collections/List.co` with a public
  `List` declaration would be selected by `using cloth.collections.List : List`.
  `StandardLibrary` is a configuration identity, not another source path prefix.
  Subfolders such as `collections/` need no manifests.
- `cloth/collections/.gitkeep` preserves the initial empty collection directory.
  It does not stand in for an implemented collection or a settled collection API.

The [import contract](../../cloth-imports/references/semantics.md) owns exact-case
paths, public exports and graph reachability. The [manifest contract](semantics.md)
owns the existing configuration schema. The library
[README](../../../../Standard-Library/README.md) provides consumer and build commands.

## Current build boundary

The manifest selects `StandardLibrary.cloth.Library.main`, resolving the primary
source to [cloth/Library.co](../../../../Standard-Library/cloth/Library.co).
That source initially contains comments only. Shuttle's schema requires the
entryPoint string, but object mode supplies no executable entry to Compiler and
does not require a `main` declaration.

Run from Cloth's root:

```powershell
.\gradlew.bat :Shuttle:run --args="build --project Standard-Library --emit object" --offline --console=plain --no-daemon
```

The output is `Standard-Library/build/Cloth-Standard-Library.o`, ignored by the
submodule's own `.gitignore`. Only the graph reachable from `cloth/Library.co`
is checked. Add appropriate imports to that root as library files are implemented;
do not claim that configuring a source root scans every `.co` file.

This is source-library scaffolding. Consumers supply the repository root as a
source module and compile reachable files with their application. An object
build establishes neither an archive/export ABI nor package downloads. The
scaffold has no executable entry; ordinary executable build/run is not its
validation command. A dedicated library manifest/output mode remains future work.

## Bundled toolchain Stage 0

The user approved the direction of shipping matching library sources with each
compiler release, making them automatically available to both command frontends,
and compiling explicitly imported library files into the application. The
[bundled standard-library TODO](../../../../todo/standard-library-toolchain-plan.md)
records packaging, discovery, overrides, namespace decisions, runtime-service
boundaries and verification stages. Its Stage 0 is complete: the
[bundled-library contract](../../cloth-toolchains/references/standard-library.md)
records the user's modal decisions, selected command semantics, explicit module
role, source-root identity rules and first-error ordering. Packaging and runtime
implementation are separated by stage. [Stage 1 packaging](../../cloth-toolchains/references/standard-library-stage-1.md)
now exports the pinned sources and provenance into combined/per-command toolchain
distributions and supplies installed/development launcher properties.
[Stage 2 integration](../../cloth-toolchains/references/standard-library-stage-2.md)
now consumes that context, automatically attaches or promotes the default source
root, and reserves reached cloth paths. [Stage 3 controls](../../cloth-toolchains/references/standard-library-stage-3.md)
implement explicit checkout selection and disabling with ordinary project
replacements. [Stage 4 allocation](../../cloth-toolchains/references/standard-library-stage-4.md)
specifies and verifies existing typed class operations for an owning collection
fixture. [Stage 5 publication](../../cloth-toolchains/references/standard-library-stage-5.md)
adds the maintained stand-in-library example, toolchain guide and runtime-independence
proof through both packaged commands. The production library remains this scaffold.

## Verification and Git lifecycle

The command above succeeded on Windows x64 on 2026-11-07 and emitted a 306-byte
native object. It exercised the real Shuttle TOML loader, project model and
public compiler facade. It did not execute a library program or validate any
collection implementation. No compiler or Shuttle production source changed.

Git registration was verified as a mode-160000 gitlink at `Standard-Library/`,
using the expected remote and `main` branch setting. The old working-tree files
were removed while preserving the submodule's `.git` link and repository history.
Parent and submodule `git diff --check` checks passed. The skill-creator validator
could not start because its Python environment lacks PyYAML; fallback checks
confirmed both changed skill metadata blocks and 276 relative documentation links.

Submodule source changes must be committed and published in the standard-library
repository before recording the resulting commit in Cloth. A parent gitlink
does not include uncommitted scaffold files. The initial scaffold task left those
changes local and uncommitted. Stage 1 subsequently
committed and published `ea48ad55be9577f0b876f04c5aafe4420919597d` and updated the
compiler index pin; the current bundle exports that commit.
