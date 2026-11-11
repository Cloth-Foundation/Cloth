# Windows installed launchers

BUILD-1 Stage 2 integrates the selected Java Launcher Interface (JLI)
bridge. On 2026-11-10 the user chose `cloth.exe` as the primary Windows
entry and kept `cloth.bat` for compatibility with existing scripts.
Kotlin/Native `shuttle.exe` handles commands, TOML and native
program launch. [Stage 1 proof](windows-unicode-launcher-proof.md) ·
[Implementation plan](../../../../todo/windows-unicode-launcher-plan.md)

## Package and startup contract

| Windows distribution | Public commands | Private launcher files |
| --- | --- | --- |
| Combined | `bin/cloth.exe`, `bin/cloth.bat`, `bin/shuttle.exe` | `bin/cloth-jvm.exe`, `launcher/cloth-launcher.jar` |
| Compiler | `bin/cloth.exe`, `bin/cloth.bat` | `launcher/cloth-launcher.jar` |
| Shuttle | `bin/shuttle.exe` | `bin/cloth-jvm.exe`, `launcher/cloth-launcher.jar` |

The Windows x64 executables `cloth.exe` and `cloth-jvm.exe` use the same
native host. The worker filename and a private selector identify Shuttle's
compiler exchange. Each command derives its home from its executable path
using wide APIs. Both inherit the caller's directory and streams. The batch
file forwards to its sibling `cloth.exe` and follows CMD's batch behavior
for console interruption. Use `cloth.exe` for native console behavior.
[Native host](../../../../Launcher/windows/main.cpp) ·
[Batch entry](../../../../Launcher/windows/cloth.bat) ·
[Shuttle handoff](../../../../Shuttle/native/src/nativeMain/kotlin/shuttle/nativecli/Entry.kt)

With `runtime/` present, the host requires the bundled image files and
checks the SHA-256 of `runtime/bin/jli.dll` before loading it by absolute
path. The process manifest establishes a UTF-8 code page. JLI receives the
UTF-8 launch command and handles launcher options and argument files. The
named Java bootstrap module loads the application from the installed `lib/`
JARs via `Path.toUri()` URLs in a context class loader. The direct-Java
argument recovery still recognizes its original launch forms. The native
host's original command line has a different application boundary, so the
bootstrap's argument array arrives intact.
[Bootstrap module](../../../../Launcher/java/cloth/launcher/Bootstrap.java) ·
[Direct Java argument adapter](../../../../Compiler/src/main/kotlin/cloth/util/JavaCommandLine.kt) ·
[Windows process code page](https://learn.microsoft.com/en-us/windows/apps/design/globalizing/use-utf8-code-page)

The host applies the installed context and native-access default, then
`JAVA_OPTS`, then `CLOTH_OPTS` or `SHUTTLE_OPTS`. JLI and HotSpot handle
`JDK_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS` and `_JAVA_OPTIONS` in their normal
order. Application arguments keep their order, empty strings, and literal
`@` values. An incomplete bundled installation fails at startup. When the
image is absent, the existing external Java selection uses `JAVA_HOME`
before PATH. External Java fallback uses the stock Java launcher's path
and encoding behavior. Unix launches the bundled image with `runtime/bin/java`.
The Gradle development commands use the configured Java 25 toolchain.

## Build requirements and compatibility check

Build Windows x64 releases with a redistributable x64 JDK 25, Visual Studio
C++ x64 tools and a Windows SDK. The Gradle launcher build uses the developer
environment or `vswhere` and `vcvars64.bat`. It embeds the UTF-8 manifest
and links the C++ runtime statically. The manifest setting is available from
Windows 10 version 1903. A packaged launcher needs its matching bundled
Java image. [Native build script](../../../../Launcher/windows/build.cmd) ·
[Gradle wiring](../../../../build.gradle.kts) ·
[Windows process code page](https://learn.microsoft.com/en-us/windows/apps/design/globalizing/use-utf8-code-page)

Gradle builds the host against the SHA-256 of the selected image's JLI DLL.
`:verifyWindowsLauncher` exercises the five JLI exports and loads the module
main through Java's `--dry-run` on that image. Combined and per-command
installation and archive tasks depend on the check. A different bundled JDK
build goes through the same check. Use the installed distribution tests to
exercise the JARs, native dependencies and actual compiler work.
[ToolchainDistributionTest](../../../../Tests/src/test/java/cloth/ToolchainDistributionTest.java)

## Stage 2 verification

Verification on 2026-11-10 used Windows x64 25H2 build `26200.9711`, system
ACP 1252, Oracle OpenJDK `25+36-3489`, MSVC 14.51.36231, Windows SDK
10.0.26100.0 and Kotlin/Native 2.4.20. The JLI SHA-256 matched the Stage 1
proof. The standard-library pin was `c79b58ffba07d9e2a70e4acf954a2dca47cf5666`.

| Check | Observed result |
| --- | --- |
| Installed combined commands | Both built caller-relative objects after separate relocations to `安装目录`, `😀🧵` and `Cloth 安装 🧵 with spaces`. JVM properties reported the relocated runtime, toolchain home, installed layout and the Unicode caller directory. |
| Standalone and compatibility entry points | Bare `cloth` and `shuttle` built 29,307-byte objects from standalone Unicode installs via CMD PATH. `cloth.bat` did the same from the mixed-path combined install. PowerShell resolved bare `cloth` to `cloth.exe` and help exited 0. |
| Runtime and failure boundaries | Both selected bundled Java with an invalid `JAVA_HOME`. Both reported one launcher error for changed JLI bytes, missing JVM configuration, missing bootstrap and missing application JAR directory. With the image absent, both built via `JAVA_HOME` and then PATH, and an invalid `JAVA_HOME` failed before PATH. The four checked vendor files matched the selected JDK. |
| Native program run | Both compiled and ran an imported `List` fixture from the mixed path with fresh Unicode JavaCPP caches. They accepted the spaced, empty, emoji, quoted, trailing-backslash, literal `@` and flag arguments, read piped UTF-8, and returned 7. JVM settings and program output arrived on separate streams. |
| JVM options | Both accepted a 256 MiB heap, a quoted Unicode property, all three JDK option environment variables, and command-specific property precedence over `JAVA_OPTS`. |
| Ctrl+C | The isolated console probe reached a compiled program waiting on input. `cloth.exe` returned 130 without a prompt. `shuttle.exe` returned `0xC000013A` without a prompt. CMD asked for confirmation for `cloth.bat` and returned 255 after Y. All three left zero processes in the test job. |
| Distribution artifacts | Combined, Compiler and Shuttle `installDist`, `distZip` and `distTar` completed. All six `windows-x64` archives contained the expected launcher, runtime and provenance entries. Launcher files, JLI and checked standard-library files matched their installations byte for byte. |

The isolated offline Gradle run passed 13 `ToolchainDistributionTest` tests,
30 `ProgramEntryTest` tests, 64 Shuttle tests and 27 Shuttle configuration tests.
The distribution suite includes the nine-target object checks and native x86
program runs on the x64 host.

The later Shuttle dependency-wiring recheck rebuilt all three installations
and passed the distribution, native Shuttle and configuration checks. It
reused the verified export of the same pin while the library worktree had edits.

```powershell
.\gradlew.bat :verifyWindowsLauncher :Tests:test --tests cloth.ToolchainDistributionTest --offline
.\gradlew.bat :Compiler:test --tests cloth.ProgramEntryTest :Shuttle:test :Shuttle:configurationTest --offline
```

## Stage 3 archive verification

On 2026-11-10, a detached checkout of `aa649032b10a1fe3f9b84b5a1fe5fe68a80a3aae`
and its clean standard-library pin `c79b58ffba07d9e2a70e4acf954a2dca47cf5666`
built the release archives through Gradle. The host was Windows x64 25H2 build
`26200.9711` with system ACP 1252. The archives carried Oracle OpenJDK
`25+36-3489` and the Kotlin/Native 2.4.20 Shuttle frontend.

| Package | Formats | Installed entries exercised |
| --- | --- | --- |
| Combined | ZIP and TAR | `cloth.exe`, `cloth.bat`, `shuttle.exe` |
| Compiler | ZIP and TAR | `cloth.exe`, `cloth.bat` |
| Shuttle | ZIP and TAR | `shuttle.exe` |

Each of the six archives was extracted for both `安装目录` and `😀🧵`.
Both combined archives also ran from `Cloth 安装 🧵 with spaces`, and the
combined TAR supplied the ASCII control. Each extracted root moved twice
before invocation. The archives contained the intended command and worker
files, Java bootstrap module, runtime and pinned sources. The four checked
Java vendor files matched the selected JDK in every extraction.

- **Build and run.** All 15 relocations produced 33 caller-relative
  x64 COFF objects of 29,412 bytes through PATH commands. Direct invocations
  built and ran 33 x64 PE executables of 169,984 bytes. All runs imported
  the packaged `List`, received exact arguments and piped UTF-8 input, and
  returned 7. The mixed-path runs also checked zero program arguments.
- **JVM and working directory.** The relocated `java.home`, installed
  toolchain home and layout, caller `user.dir` and UTF-8 process encoding
  matched. Every entry used a fresh JavaCPP cache. The Unicode cases used
  Unicode cache, temporary, project and output paths. An invalid `JAVA_HOME`
  and Java absent from PATH established bundled-runtime priority. Quoted
  Unicode JVM properties, the heap flag and the three JDK option variables
  passed the precedence checks.
- **Failure and fallback.** The mixed-path cases reported only the first
  compile error, preserved the existing executable bytes, and wrote no
  executable at a fresh failed destination. Malformed Shuttle TOML reported
  one S0004. In the ASCII TAR control, a missing bootstrap module produced
  one startup error and exit 2. With the image absent, all three entries
  built via `JAVA_HOME` and via PATH, with invalid `JAVA_HOME` taking
  precedence over PATH. The ASCII and console controls passed when run
  serially after one parallel run hit Windows error 1455.
- **Native process and independence.** Both mixed-path archives ran the
  maintained example's transitive imports through a caller-relative
  `--stdlib` override. A test-only C initializer recorded the native
  program's actual working directory and wrote a separate stderr marker.
  All six example executables returned 0 with the expected greeting after
  the extracted toolchain and example sources were removed. After the
  original checkout moved away, the emoji ZIP and Chinese TAR entries
  ran again with an exact multi-scalar emoji argument and exit 7. In
  those runs `_JAVA_OPTIONS` overrode the earlier property values.
  [Maintained example](../../../../examples/StandardLibrary/README.md)
- **Ctrl+C.** Both direct stock Java and `cloth.exe` produced 130 and
  `0xC000013A` across the isolated console trials. `shuttle.exe` returned
  `0xC000013A`. `cloth.bat` prompted and returned 255 after Y. The completed
  probes left zero processes in their test jobs.

The clean checkout also passed 13 installed-distribution tests, 30 compiler
program-entry tests, two native Shuttle tests and 27 Shuttle configuration
tests. The archive and test tasks were run offline.

```powershell
.\gradlew.bat :distZip :distTar :Compiler:distZip :Compiler:distTar :Shuttle:distZip :Shuttle:distTar --offline
.\gradlew.bat :Tests:test --tests cloth.ToolchainDistributionTest :Compiler:test --tests cloth.ProgramEntryTest :Shuttle:test --tests shuttle.NativeShuttleTest :Shuttle:configurationTest --offline
```

Additional host coverage and publication are tracked in the
[Stage 4 plan](../../../../todo/windows-unicode-launcher-plan.md#stage-4-host-coverage-and-publication).

## Stage 4 host verification

On 2026-11-10 the user selected the available host checks and asked to
record the pending Windows host checks. Both hosts started from detached
Cloth commit `aa649032b10a1fe3f9b84b5a1fe5fe68a80a3aae` and standard-library
pin `c79b58ffba07d9e2a70e4acf954a2dca47cf5666`.

### Windows x64

Windows 25H2 build `26200.9711` with system ACP 1252 and bundled Oracle
OpenJDK `25+36-3489` passed the installed command tests. The distribution
suite repeated the Chinese-only, emoji-only and mixed-path relocations,
ran x64 and i686 programs through both commands, and emitted 18 objects
across the nine target profiles. The focused Gradle run passed 13 distribution
tests, 30 compiler program-entry tests, two native Shuttle tests and 27
Shuttle configuration tests. Both Gradle development commands ran the
maintained `List` example with its expected output and status 0.
[Distribution tests](../../../../Tests/src/test/java/cloth/ToolchainDistributionTest.java) ·
[Collections example](../../../../examples/Collections/README.md)

```powershell
.\gradlew.bat :Tests:test --tests cloth.ToolchainDistributionTest :Compiler:test --tests cloth.ProgramEntryTest :Shuttle:test --tests shuttle.NativeShuttleTest :Shuttle:configurationTest --offline --max-workers=1
.\gradlew.bat :Compiler:run '--args=run examples/Collections/app/Main.co build/stage4-development/cloth' :Shuttle:run '--args=run --project examples/Collections -o build/stage4-development/shuttle' --offline --max-workers=1
```

### Linux x64 under WSL2

Ubuntu 24.04.4 LTS under WSL2 used the same Cloth and standard-library
revisions, glibc 2.39, a UTF-8 locale and Temurin `25.0.5+8-LTS`. The
temporary Linux JDK matched the vendor's published SHA-256. The builds
and runs used the Windows-mounted workspace.
[Eclipse Temurin release assets](https://github.com/adoptium/temurin25-binaries/releases/expanded_assets/jdk-25.0.5%2B8)

The combined and standalone installations built with a native
Kotlin/Native 2.4.20 Linux x64 Shuttle executable. The distribution suite passed
12 applicable tests and skipped its Windows-only case. All 30 program-entry
tests and 27 Shuttle configuration tests passed. Both Gradle development
commands ran the maintained `List` example with status 0.
[Distribution tests](../../../../Tests/src/test/java/cloth/ToolchainDistributionTest.java)

In the combined and per-command installations, both commands emitted native
x86-64 ELF objects from caller-relative paths and ran a program that imported
the pinned `List`. The smoke runs checked exact arguments, including an empty
argument and multi-scalar emoji, piped UTF-8 input, output and child status 7.
Both commands loaded JavaCPP from fresh Unicode cache paths and selected the
bundled Java 25 over an invalid `JAVA_HOME`. JVM property output confirmed the
installation home, caller working directory, UTF-8 encoding and the precedence
of `CLOTH_OPTS` and `SHUTTLE_OPTS` over `JAVA_OPTS`.

```sh
./gradlew :installDist :Compiler:installDist :Shuttle:installDist :distZip :distTar \
  :Tests:test --tests cloth.ToolchainDistributionTest \
  :Compiler:test --tests cloth.ProgramEntryTest :Shuttle:configurationTest --max-workers=1
./gradlew :Compiler:run '--args=run examples/Collections/app/Main.co build/stage4-development/cloth' \
  :Shuttle:run '--args=run --project examples/Collections -o build/stage4-development/shuttle' \
  --offline --max-workers=1
```

The initial combined ZIP and TAR recorded `bin/shuttle` with mode
`0644`. Gradle 9 assigns reproducible archive file modes independently
of the staged file's mode. The distribution archive tasks now assign
`0755` to the Unix Shuttle entry in both layouts and track it as a task input
so an incremental release build refreshes existing archives.
[Gradle archive permissions](https://docs.gradle.org/current/userguide/working_with_files.html) ·
[Archive task wiring](../../../../build.gradle.kts)

The incremental build regenerated the combined ZIP and TAR. Direct inspection
of both archive formats found mode `0755` for `bin/shuttle`, `runtime/bin/java`
and `runtime/lib/jspawnhelper` in both layouts, and for `bin/cloth` in the
combined layout.

```sh
./gradlew :distZip :distTar :Shuttle:distZip :Shuttle:distTar --offline --max-workers=1
```

Both commands built x86-64 ELF objects and ran the same `List` fixture from
the extracted combined ZIP and TAR after relocation to `cloth 安装`. The
caller directory, source, project and output names contained emoji. Both
commands preserved the exact arguments and UTF-8 input and returned status 7.
With the TAR's runtime temporarily absent, both commands built objects using
`JAVA_HOME` and then PATH. An invalid `JAVA_HOME` took precedence over PATH.

### Linux emoji installation follow-up

Both commands started bundled Java from the extracted Linux ZIP and TAR at
`cloth 安装 😀`. They returned status 1 with `ClassNotFoundException` for
`cloth.EntryKt` and `shuttle.bridge.NativeCompilerWorker`, respectively. The
compiler JAR contained its expected entry class. The OpenJDK classpath loader
delegates file URL encoding to `ParseUtil`, which percent-encodes the two
UTF-16 units of a supplementary scalar separately.
[OpenJDK classpath loader](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/jdk/internal/loader/URLClassPath.java#L218-L228) ·
[OpenJDK path encoder](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/sun/net/www/ParseUtil.java#L100-L143)

Invoking the existing Java bootstrap module directly with the bundled Linux
JVM loaded both entries from that same installation. The compiler and the
worker each emitted an x86-64 ELF object. On 2026-11-10 the user chose to track
Unix launcher work separately. BUILD-3 owns that follow-up.
[Bootstrap module](../../../../Launcher/java/cloth/launcher/Bootstrap.java) ·
[BUILD-3 installed-entry build and run proof](unix-unicode-launcher-proof.md) ·
[Consolidated plan](../../../../todo/open-questions-plan.md#implementation-work)

### Pending Windows checks

The system-UTF-8 Windows check needs an existing Windows runner with ACP
65001. The native ARM64 check needs an ARM64 package and matching Windows
runner. The current launcher build requires a Windows x64 JDK, and Windows
Shuttle builds the `mingwX64` frontend. The i686 run and nine-target object
matrix verify compiler output from the x64 host.
[Windows build wiring](../../../../build.gradle.kts) ·
[Shuttle host selection](../../../../Shuttle/build.gradle.kts)
