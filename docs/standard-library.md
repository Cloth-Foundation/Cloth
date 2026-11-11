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
    bin/cloth[.exe]
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

Filesystem paths containing emoji are outside the Cloth toolchain's support scope.
Use names such as `Cloth 安装` for installation and working directories,
source and project paths, library overrides, and build outputs.

On Windows x64, `cloth.exe` is the primary Cloth command and `cloth.bat`
forwards to it for compatibility. Both Cloth and Kotlin/Native Shuttle use
the bundled Java 25 bridge for relocated installations. Keep the private
launcher files and `runtime/` with the rest of the installation. The combined,
Compiler and Shuttle ZIP and TAR archives passed Windows x64 builds and runs
after extraction and relocation.
[Windows launcher contract](../.agents/skills/cloth-toolchains/references/windows-unicode-launcher.md) ·
[Archive verification](../.agents/skills/cloth-toolchains/references/windows-unicode-launcher.md#stage-3-archive-verification)

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

Build the combined installation and ZIP and TAR archives from a prepared
checkout.

```powershell
.\gradlew.bat :installDist :distZip :distTar
```

Use `./gradlew` on Linux and macOS. Build releases with a redistributable JDK 25
for each host OS and CPU you distribute to. Gradle uses that JDK's `jlink` to
build the runtime and derives the archive suffix from the JDK's host platform.
Windows x64 packaging also uses Visual Studio C++ x64 tools and a Windows SDK
to build the native launcher and verify the bundled JLI interface.
Outputs are `build/install/cloth` and
`build/distributions/cloth-<version>-<os>-<arch>.zip` / `.tar`. Select the archive
matching the machine that runs Cloth, such as `windows-x64` or `linux-x64`.
Per-command `installDist`, `distZip` and `distTar` tasks in `:Compiler` and
`:Shuttle` include the same runtime and pinned library snapshot.
Extract Unix packages with a tool that preserves archived execute permissions.

The BUILD-1 verification used standard-library revision
`c79b58ffba07d9e2a70e4acf954a2dca47cf5666`, which includes `List`. The
earlier bundling publication used the scaffold snapshot at
`ea48ad55be9577f0b876f04c5aafe4420919597d`.
[Bundling publication](../.agents/skills/cloth-toolchains/references/standard-library-stage-5.md)

## Verified launcher hosts

| Host | Coverage |
| --- | --- |
| Windows x64, Windows 25H2, ACP 1252 | Both commands and the Cloth batch wrapper passed relocated ZIP and TAR checks with bundled Oracle OpenJDK `25+36-3489`. The Windows x64 packages built and ran x86 output, emitted objects for all nine target profiles, and passed both Gradle development runs. |
| Linux x64, Ubuntu 24.04 WSL2, UTF-8 | The combined and standalone `installDist` layouts and all six relocated ZIP and TAR extracts passed native build and run checks with bundled Temurin `25.0.5+8-LTS`. Both Gradle development commands passed. |
| Windows x64, system ACP 65001 | Pending a matching Windows runner. |
| Windows ARM64, native | Pending a matching Windows package and runner. The current Windows packaging tasks require an x64 JDK and select the `mingwX64` Shuttle frontend. |

The nine target profiles describe compiler outputs. Each release archive's
suffix identifies the host that runs the bundled launcher and runtime.
[Host verification record](../.agents/skills/cloth-toolchains/references/windows-unicode-launcher.md#stage-4-host-verification)

The Linux ZIP and TAR archives each carried the shared bootstrap and
recorded executable modes for the command entries, Java and `jspawnhelper`.
The extracts were relocated to `Cloth 安装 with spaces`.
[Installed Unix verification](../.agents/skills/cloth-toolchains/references/unix-unicode-launcher-proof.md#stage-2-installed-distributions) ·
[Linux archive verification](../.agents/skills/cloth-toolchains/references/unix-unicode-launcher-proof.md#stage-3-archive-verification)

Precompiled library archives, binary APIs, package downloads/caches and broader
native services remain separate future work.
