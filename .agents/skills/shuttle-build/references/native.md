# Native Shuttle command

Build and run from the Cloth checkout with Java 25. The maintained
[Shuttle example](../../../../examples/Shuttle/README.md) contains multiple
modules and a nested entry.

```powershell
.\gradlew.bat :Shuttle:run --args='run --project examples/Shuttle -- nested'
.\gradlew.bat :Shuttle:installDist
.\Shuttle\build\install\shuttle\bin\shuttle.exe build --project examples\Shuttle --emit=object
```

The [Shuttle build](../../../../Shuttle/build.gradle.kts) stages the native
executable and private compiler worker. The combined toolchain and per-command
distribution install `bin/shuttle.exe` on Windows and `bin/shuttle` on Unix.
The installed command uses the bundled Java 25 runtime for compilation.
Development uses the Gradle toolchain and the checkout's standard library.
On Unix the installed frontend starts its worker through the shared Java
bootstrap module. The worker's module and library paths resolve relative to
the native executable, and the worker receives the same private exchange.
[Installed Unix worker handoff](../../cloth-toolchains/references/unix-unicode-launcher-proof.md#stage-2-installed-distributions)

## Ownership

The [multiplatform build](../../../../Shuttle/native/build.gradle.kts) owns all
Shuttle sources. `commonMain` defines the JSON exchange and typed manifest tree
for both targets. `jvmMain` builds the worker JAR, including the standard-library
exporter, and `jvmTest` owns the project and command tests. `nativeMain` and the
host-specific source sets build the CLI. The [parent build](../../../../Shuttle/build.gradle.kts)
resolves the JVM runtime variants for staging and testing in its own project,
so these tasks work in parallel. The release exporter runs against the staged
worker JAR and dependencies.

[The native entry](../../../../Shuttle/native/src/nativeMain/kotlin/shuttle/nativecli/Entry.kt)
handles help and command dispatch, performs TOML syntax parsing, launches the
JVM compiler worker, and starts the compiler's returned artifact. It uses
versioned private JSON files and passes program arguments directly to the child.
The result of `run` is the child status. A failed compilation stops before launch.

[The compiler worker](../../../../Shuttle/native/src/jvmMain/kotlin/shuttle/bridge/NativeCompilerWorker.kt)
uses the existing Java filesystem validation, Shuttle schema, CompilerOptions
and `ClothCompiler.compile`. It invokes the native parser for each manifest
snapshot. The build-only standard-library exporter uses that parser as well.
The worker uses fixed terminal rendering to avoid Jansi DLL extraction from
the JVM's temporary directory.

## Parser and verification

[The native build](../../../../Shuttle/native/build.gradle.kts) pins the tomlkt
0.6.1 source archive and compiles it with kotlinx.serialization. Its parser
patch rejects a value following a table header on the same line, raw control
characters, bare null, and repeated explicit declarations of a super-table.
Keep those checks inside the parser so they run with TOML lexical context.
The generated parser's `expect` declarations compile in `nativeParserMain`
and its `actual` declarations compile in the dependent `nativeMain` source set.
The build scopes parser opt-ins to the native source sets and removes the
JVM-only `@JvmInline` marker from the generated native parser value class.
The typed tree retains the distinction between strings, tables and other TOML
values. See [ManifestTest](../../../../Shuttle/native/src/jvmTest/kotlin/shuttle/ManifestTest.kt).

The [Windows host](../../../../Shuttle/native/src/mingwX64Main/kotlin/shuttle/nativecli/WindowsHost.kt)
uses wide OS paths and argument transport. In Windows x64 installations,
it starts the JVM worker through the shared, version-checked JLI host
`bin/cloth-jvm.exe`. The exchange keeps the same JSON contract and native
program launch. Help stays native. With the bundled image absent, the worker
uses `JAVA_HOME` and then PATH.
[Installed launcher contract](../../cloth-toolchains/references/windows-unicode-launcher.md) ·
[Relocated ZIP and TAR verification](../../cloth-toolchains/references/windows-unicode-launcher.md#stage-3-archive-verification)

Use `:Shuttle:configurationTest` for manifest and project checks and
`:Shuttle:test` for compiler and native command integration. `:Tests:test`
checks the combined installed distribution and its bundled Java runtime.

On Windows x64, both Shuttle suites and all 12 combined distribution tests
passed. Both install layouts contained one native `shuttle.exe`. The maintained
example ran through the standalone installation with a missing `JAVA_HOME`
and through the normal `:Shuttle:run` task. Package verification used the
existing export after comparing its seven files byte for byte with the pinned
standard-library Git archive. The standard-library worktree had a staged change
during verification.

The Linux x64 executable cross-linked from Windows. Ubuntu WSL ran its help
and rejected the same-line table-header fixture through the native parser.

## Consolidated source sets (2026-11-10)

On Windows x64 with Java 25, `:Shuttle:check` passed 64 tests and 27
configuration checks. `:Shuttle:native:jvmTest --tests shuttle.ManifestTest`
passed 14 manifest tests directly from the JVM target. `:Tests:test` passed
all 13 installed-toolchain tests. Both `:Shuttle:run` and the binary from
`:Shuttle:installDist` ran the maintained nested project with `PEC: 0`.
The exporter completed against the clean library checkout. After that checkout
gained local edits, the final package verification reused its export after
checking all seven files byte for byte against the Git pin.

`:Shuttle:native:allMetadataJar` assembled the multiplatform metadata.
`:Shuttle:native:linkReleaseExecutableLinuxX64` cross-linked on Windows, and
the Linux x64 executable printed its help in Ubuntu WSL.

## BUILD-1 host checks (2026-11-10)

On a separate pinned checkout, Ubuntu 24.04 WSL2 built the Linux x64 frontend
and the bundled Temurin 25 runtime. The standalone and combined installations
ran real Cloth programs from a Unicode caller directory. Both combined
archives passed after relocation to `cloth 安装`, and `:Shuttle:run` ran the
maintained `List` example. The Linux ZIP and TAR tasks now assign mode `0755`
to `bin/shuttle` and track the mode as an archive task input. This host check
found the Linux worker's class-loading failure from emoji installation paths.
[Host versions, commands and results](../../cloth-toolchains/references/windows-unicode-launcher.md#stage-4-host-verification) ·
[Linux worker reproduction and bootstrap proof](../../cloth-toolchains/references/windows-unicode-launcher.md#linux-emoji-installation-follow-up)

## BUILD-3 Linux proof (2026-11-10)

The cross-linked Linux x64 prototype used bundled Java 25 and the existing
Java bootstrap for the installed worker handoff. The actual native frontend
built and ran a Cloth program from emoji-only and mixed installation paths
under WSL2. The development classpath handoff ran the maintained List project.
[Worker handoff, host details and results](../../cloth-toolchains/references/unix-unicode-launcher-proof.md)

Stage 2 verified the native worker in the combined and standalone Linux
`installDist` outputs after emoji relocation. The installed worker used bundled
Java and both external Java fallbacks. `:Shuttle:run` ran the maintained List
project with `PEC: 0`.
[Installed Unix verification](../../cloth-toolchains/references/unix-unicode-launcher-proof.md#stage-2-installed-distributions)

Stage 3 ran the native frontend from the combined and Shuttle-only Linux ZIP
and TAR extracts after relocation to paths with Chinese characters and spaces.
[Linux archive verification](../../cloth-toolchains/references/unix-unicode-launcher-proof.md#stage-3-archive-verification)
