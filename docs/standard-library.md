# Standard-library sources in a Cloth toolchain

Cloth distributions include the compiler, Shuttle and a pinned standard-library
source snapshot. Both commands make that source root available automatically.
Select public declarations explicitly with `using`; availability adds no names
to a file's scope. Reached files compile with the application into its native
executable or object.

The library supplies generic List, Deque, Queue, and Stack classes.
[Collection APIs](../Standard-Library/cloth/collections/README.md)

The examples demonstrate the imported collections, including independent array
elements and ordinary FIFO and LIFO use.
[List example](../examples/Collections/README.md) ·
[Deque, Queue, and Stack example](../examples/LinearCollections.co)

The bundling example demonstrates the installed layout with its own library
fixture. [Bundling example](../examples/StandardLibrary/README.md)

## Installed layout and relocation

```text
<toolchain>/
    bin/cloth[.bat]
    bin/shuttle[.exe]
    lib/...
    runtime/bin/java[.exe]
    toolchain.properties
    stdlib/module.toml
    stdlib/cloth/Library.co
    stdlib/cloth/...
```

The source root is `stdlib/`. Select `stdlib/cloth/collections/List.co`
with `using cloth.collections.List : List`.
Import paths retain exact case on every host and omit module IDs.

Move the entire installation together. Launchers derive their installation home
from their own location, independently of the caller's working directory. Put
`bin/` on PATH or invoke either launcher directly. The bundled Java 25 runtime
runs the compiler for both commands. The Shuttle command is a native executable.
Building an executable also requires the native
linker, CRT and sysroot for the selected target. Object emission can run with
the bundled toolchain alone.

On Windows, JVM classpath startup supports spaces and installation characters
representable by the host code page. The
[launcher plan](../todo/windows-unicode-launcher-plan.md) tracks arbitrary
Unicode installation paths. The native Shuttle command uses wide Windows
arguments for project paths and program launch.

The resulting executable runs independently of the compiler installation and
Cloth source files. Its normal target platform and C runtime dependencies still
apply. This milestone does not supply a separate dynamic Cloth runtime.

## Select a library

```powershell
cloth run Main.co build/app
shuttle run --project .
cloth run Main.co build/app --stdlib ../Standard-Library
shuttle build --stdlib=../Standard-Library
shuttle build --no-stdlib
```

Default installed selection uses `<toolchain>/stdlib`. Gradle development tasks
use `<checkout>/Standard-Library`. No standard-library manifest entry is required.
The standalone compiler still discovers no project roots or build.toml; use
Shuttle for configured project modules or supply explicit roots through
`ClothCompiler.compile(CompilationRequest)`.

`--stdlib PATH` and `--stdlib=PATH` select another source root. Relative paths
resolve from invocation cwd, including when Shuttle selects a different project
directory. The root requires readable exact-case `module.toml`, `cloth/`, and
`cloth/Library.co`. Selection does not interpret TOML or load every source file.
A development override needs no Git checkout, clean tree, release descriptor or
exact version match. Reached incompatible code reports ordinary compiler errors.

When building the library checkout itself with an installed Shuttle, select that
checkout explicitly so its configured root is promoted rather than conflicting
with the bundled root:

```powershell
shuttle build --project Standard-Library --stdlib Standard-Library --emit object
```

From inside that checkout, use `shuttle build --stdlib . --emit object`.

An invalid override fails without fallback. Repeated overrides use the last path;
every occurrence must still contain a valid nonblank path. Repeated `--no-stdlib`
is harmless. Combining override and disable, or attaching a value to
`--no-stdlib`, is an error. Put build flags before `run`'s first `--`; flags after
it are program arguments. Both commands list selection options through `--help`.

Override and disable selection bypass default-bundle validation. An incomplete
default fails before reading the program/project, even without library imports.
`--no-stdlib` omits the implicit root and permits configured project modules to
supply `cloth/` replacements. Explicit modules remain regular modules; builtins,
primitive operations and class allocation remain available.

While a library is selected, reached lowercase `cloth.*` paths belong to it.
A regular root supplying the same path is C3106, even when the selected library
lacks that file. No shadowing or fallback is allowed. Other imports retain
ordinary ambiguity rules; disabling the library applies those rules to `cloth.*`
as well. A configured alias of the selected physical library root is promoted
once rather than attached twice.

`--runtime default|static|dynamic` separately selects C runtime linkage. Ordinary
library code uses typed `::new`/`::delete` for class storage; portable behavior
stays in Cloth and the compiler emits private helpers for the requested target.
See the [allocation boundary](../.agents/skills/cloth-toolchains/references/standard-library-stage-4.md)
for ownership, failure and target details.

## Identify and prepare a release snapshot

`toolchain.properties` records `format=1`, `compiler.version`, `stdlib.version`
and `stdlib.revision`. Installed default selection validates those fields.
`stdlib.revision` identifies the pinned Git snapshot exported into the release.
Development and override selection use the current selected tree, including local
edits; version metadata alone does not identify uncommitted contents.

For release preparation, initialize the standard-library submodule and publish
its intended source changes before updating Cloth's gitlink. Packaging requires
the library HEAD to match the index pin and its working tree to be clean. It
exports pinned `module.toml` and `cloth/**/*.co`, omitting Git metadata and build
outputs. Neither compiler API calls nor user builds perform Git operations.

From a prepared checkout, build a combined installation or ZIP/TAR archives:

```powershell
.\gradlew.bat :installDist :distZip :distTar
```

Use `./gradlew` on Linux and macOS. Build releases with a redistributable JDK 25
for each host OS and CPU you distribute to. Gradle uses that JDK's `jlink` to
build the runtime and derives the archive suffix from the JDK's platform.
Outputs are `build/install/cloth` and
`build/distributions/cloth-<version>-<os>-<arch>.zip` / `.tar`. Select the archive
matching the machine that runs Cloth, for example `windows-x64` or `macos-arm64`.
Per-command `installDist`, `distZip` and `distTar` tasks in `:Compiler` and
`:Shuttle` include the same runtime and pinned library snapshot.

The recorded library pin is `ea48ad55be9577f0b876f04c5aafe4420919597d` (scaffold
version 0.0.1). The new List source is currently a development-checkout change;
a release must commit the intended library contents and update the compiler pin.
[Generic Stage 5](../.agents/skills/cloth-generics/references/stage-5.md) records
its actual-library native/IR/object checks and temporary pinned export proof.
The earlier [bundling publication](../.agents/skills/cloth-toolchains/references/standard-library-stage-5.md)
retains the installed-command baseline, rather than evidence that this working
checkout has been released.
Precompiled library archives, binary APIs, package downloads/caches, broader native
services and the full Unicode Windows launcher remain separate future work.
