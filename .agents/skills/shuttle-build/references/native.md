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

## Ownership

[The native entry](../../../../Shuttle/native/src/commonMain/kotlin/shuttle/nativecli/Entry.kt)
handles help and command dispatch, performs TOML syntax parsing, launches the
JVM compiler worker, and starts the compiler's returned artifact. It uses
versioned private JSON files and passes program arguments directly to the child.
The result of `run` is the child status. A failed compilation stops before launch.

[The compiler worker](../../../../Shuttle/src/main/kotlin/shuttle/bridge/NativeCompilerWorker.kt)
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
The typed tree retains the distinction between strings, tables and other TOML
values. See [ManifestTest](../../../../Shuttle/src/test/kotlin/shuttle/ManifestTest.kt).

The [Windows host](../../../../Shuttle/native/src/mingwX64Main/kotlin/shuttle/nativecli/WindowsHost.kt)
uses wide OS paths and argument transport. The worker JVM's startup classpath
still follows Java's Windows encoding. The
[launcher plan](../../../../todo/windows-unicode-launcher-plan.md) tracks
arbitrary Unicode installation paths. The bundled runtime failed to load
`java.dll` when invoked through a Chinese and emoji installation alias.

Use `:Shuttle:configurationTest` for manifest and project checks and
`:Shuttle:test` for compiler and native command integration. The root `:test`
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
