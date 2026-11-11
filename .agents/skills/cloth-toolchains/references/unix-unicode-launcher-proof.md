# BUILD-3 Linux bootstrap proof

The 2026-11-10 proof ran on Ubuntu 24.04.4 LTS under WSL2 x86-64 with glibc
2.39, GCC 13.3.0 and the C.UTF-8 locale. It used the Windows-mounted workspace,
Cloth commit `5c24fcc00fb65edffd934309e26e66091eb4747c` and Standard-Library
pin `efde3d3c2bcde01c834374483ed821b971b9d849`. The bundled image was linked from
Eclipse Temurin `25.0.5+8-LTS` using the release module list. The Linux JDK
archive matched the vendor's SHA-256.
[Temurin release assets](https://github.com/adoptium/temurin25-binaries/releases/expanded_assets/jdk-25.0.5%2B8) ·
[Runtime build recipe](../../../../build.gradle.kts) ·
[Stock launcher reproduction](windows-unicode-launcher.md#linux-emoji-installation-follow-up)

## Prototype handoff

Both installed entries invoke the selected Linux `java` with the existing
`cloth.launcher` module on the module path. The bootstrap forms JAR URLs with
`Path.toUri()` and forwards the original application arguments. The launcher
passes an empty system classpath after the JVM options so that application
loading stays under the bootstrap's control.
[Bootstrap source](../../../../Launcher/java/cloth/launcher/Bootstrap.java)

The temporary Cloth shell script used this startup tail after its installed
toolchain properties.

```sh
        --class-path "" \
        --module-path "$APP_HOME/launcher/cloth-launcher.jar" \
        --module cloth.launcher/cloth.launcher.Bootstrap \
        cloth.EntryKt "$APP_HOME/lib" \
        "$@"
```

The temporary Shuttle frontend supplied the same JVM arguments as a list,
followed by `shuttle.bridge.NativeCompilerWorker`, the absolute `lib` directory
and the normal exchange file. The native frontend still parsed TOML and
launched the compiled program. Its development worker used the existing
classpath handoff.
[Shuttle native entry](../../../../Shuttle/native/src/nativeMain/kotlin/shuttle/nativecli/Entry.kt) ·
[POSIX Java selection and process launch](../../../../Shuttle/native/src/posixMain/kotlin/shuttle/nativecli/PosixHost.kt)

## Build and verification

The detached Windows build selected `linuxX64` in Shuttle's staging task and
cross-linked the Kotlin/Native 2.4.20 frontend. These tasks produced the
application JARs, modular bootstrap, shell script and native stage.

```powershell
.\gradlew.bat :Compiler:startScripts :windowsBootstrap :Shuttle:stageNativeShuttle --offline --max-workers=1
```

The proof installation combined the staged Shuttle executable and libraries,
the modified generated Cloth script, the Java 25 image, and the existing
module at `launcher/cloth-launcher.jar`. The normal packager exported the
pinned `stdlib` and `toolchain.properties` in WSL. The complete installation
moved first to `😀🧵` and then to `Cloth 安装 😀 with spaces`. Tests used an
independent Unicode caller directory, source and project paths at each
location.
[Pinned library exporter](../../../../Shuttle/native/src/jvmMain/kotlin/shuttle/packaging/ToolchainBundle.kt)

| Check | Observed result |
| --- | --- |
| Stock classpath control | The bundled JVM returned 1 with `ClassNotFoundException` for both entry classes when invoked with the emoji-path `lib/*` classpath. |
| Real builds and runs | At both installation paths, bare `cloth` and `shuttle build` produced 34,776-byte x86-64 ELF executables in the caller's output directory. The artifacts ran directly. The public `run` entries also built and ran the fixture through the installed shell and native frontend. |
| Library, arguments and streams | The fixture imported bundled `List`, printed the Unicode argument and piped input, and returned 7. It checked eight arguments including an empty string, a quoted value with a trailing backslash, literal `@literal`, `--debug`, `--` and `*`. Both commands used cold Unicode JavaCPP cache paths. |
| Runtime and options | Java reported the exact relocated runtime and toolchain homes, installed layout, and caller working directory. Bundled Java won with an invalid `JAVA_HOME` and Java off PATH. Both accepted a 256 MiB heap, quoted Unicode properties, and all three JDK option environment variables once. The command-specific options overrode `JAVA_OPTS`. |
| Failure and fallback | Both failed rebuilds returned 1 with one `C3032` and preserved the previous executable. With the image temporarily absent, each command built using `JAVA_HOME` and then using PATH. An invalid `JAVA_HOME` failed before PATH fallback. Shuttle's help returned 0 without Java. |
| Development controls | The direct Cloth JVM development entry and staged native Shuttle development entry both ran the maintained `List` project with exact output and `PEC: 0`. |

The native execution proof covers Linux x64 under WSL2. Other Unix hosts and
architectures need matching runners.
[Maintained List example](../../../../examples/Collections/README.md) ·
[Consolidated implementation plan](../../../../todo/open-questions-plan.md#implementation-work)

## Stage 2 installed distributions

On 2026-11-10 the user selected the proven bootstrap for Unix installation
paths containing supplementary Unicode characters. The shared Java sources
live under `Launcher/java`. The root `:launcherBootstrap` task supplies
`launcher/cloth-launcher.jar` to the combined, Compiler and Shuttle
distributions. Windows packages supply it to the checked JLI host.
[Shared module](../../../../Launcher/java/cloth/launcher/Bootstrap.java) ·
[Gradle distribution wiring](../../../../build.gradle.kts) ·
[Windows launcher contract](windows-unicode-launcher.md)

The generated Unix `cloth` script passes the compiler entry and installed
`lib` path to the module through the selected Java executable. Installed
Kotlin/Native Shuttle passes its worker entry, installed `lib` path and
exchange file through the same module. Both supply an empty system classpath
after the JVM options. The module loads application JARs from `Path.toUri()`
URLs. Bundled Java 25 takes precedence over `JAVA_HOME` and PATH, and the
same module loads through the selected external Java when the image is
absent. Shuttle help and manifest parsing stay in the native frontend.
Gradle development runs use their development classpaths.
[Native worker handoff](../../../../Shuttle/native/src/nativeMain/kotlin/shuttle/nativecli/Entry.kt) ·
[POSIX Java selection](../../../../Shuttle/native/src/posixMain/kotlin/shuttle/nativecli/PosixHost.kt)

### Linux x64 verification

The integrated build used Ubuntu 24.04.4 under WSL2 with bundled Temurin
`25.0.5+8-LTS`, Kotlin/Native 2.4.20 and a UTF-8 locale. The detached Cloth and
Standard-Library revisions matched the proof above. The build used the Stage 2
patch and the workspace's two Shuttle Gradle scripts.

```sh
./gradlew :installDist :Compiler:installDist :Shuttle:installDist \
  :Tests:test --tests cloth.ToolchainDistributionTest --max-workers=1
./gradlew :Compiler:run '--args=run examples/Collections/app/Main.co build/build3-development/cloth' \
  :Shuttle:run '--args=run --project examples/Collections -o build/build3-development/shuttle' \
  --offline --max-workers=1
```

- All three `installDist` outputs contained byte-identical modular bootstrap JARs.
  The distribution suite passed 12 tests and skipped the Windows-only case.
  The relocation regression emitted objects from Chinese-only, emoji-only and
  mixed installation paths on Linux.
- The combined install moved to `😀🧵`. The standalone installs moved to
  `Cloth 安装 😀 with spaces` and `Shuttle 安装 🧵 with spaces`. Bare commands
  resolved on PATH and emitted x86-64 ELF objects. The public `run` commands
  built and ran a bundled `List` fixture from a separate Unicode caller directory
  with cold JavaCPP caches. Both preserved exact arguments and piped UTF-8
  input and returned 7.
- Both selected bundled Java 25 with an invalid `JAVA_HOME` and Java off PATH.
  JVM properties confirmed the bootstrap entry, relocated installation and
  caller directory. Quoted properties, command-specific option precedence and
  the three JDK option environment variables passed. With the combined image
  temporarily absent, `JAVA_HOME` and PATH each worked through the same module.
  An invalid `JAVA_HOME` took precedence over PATH. Shuttle help ran without Java.
- Both Gradle development commands ran the maintained `List` example with its
  expected output and `PEC: 0`.

[Distribution regression](../../../../Tests/src/test/java/cloth/ToolchainDistributionTest.java) ·
[Maintained List example](../../../../examples/Collections/README.md)

### Windows x64 regression

The same source inputs passed the JLI preflight and all 13 installed
distribution tests on Windows 25H2 build `26200.9711` with ACP 1252 and bundled
Oracle OpenJDK `25+36-3489`. The suite exercised the batch wrapper, emoji
relocation, x86 and x64 native execution and external Java fallback.

```powershell
.\gradlew.bat :Tests:test --tests cloth.ToolchainDistributionTest --offline --max-workers=1
```

## Stage 3 archive verification

On 2026-11-10, Gradle 9.8.2 built all six Linux x64 archives on Ubuntu 24.04.4
under WSL2 with bundled Temurin `25.0.5+8-LTS` and Kotlin/Native 2.4.20. The
detached Cloth and Standard-Library revisions matched Stage 2. The checkout
used the workspace's launcher and Shuttle Gradle changes.

```sh
./gradlew :distZip :distTar :Compiler:distZip :Compiler:distTar \
  :Shuttle:distZip :Shuttle:distTar --parallel --max-workers=2
```

| Distribution | Formats extracted | Command entries verified in each format |
| --- | --- | --- |
| Combined | ZIP and TAR | `cloth` and `shuttle` |
| Compiler | ZIP and TAR | `cloth` |
| Shuttle | ZIP and TAR | `shuttle` |

The archives each contained the byte-identical `launcher/cloth-launcher.jar`
from `:launcherBootstrap` and the pinned library provenance. ZIP Unix
attributes and TAR headers recorded `0755` for the packaged command entries,
`runtime/bin/java` and `runtime/lib/jspawnhelper`. The mode checks read the
archive metadata directly because the Windows-mounted filesystem can mask
missing execute bits.
[Distribution wiring](../../../../build.gradle.kts)

Each extracted installation moved twice, ending at `Cloth 安装 with spaces`.
From the separate `客户端 工作目录` caller directory, all eight command entries
emitted x86-64 ELF objects and built and ran a native `List` fixture. The runs
preserved exact arguments and piped UTF-8 input and returned 7. JVM properties
confirmed the relocated home, caller directory, shared bootstrap and bundled
Java 25 with an invalid `JAVA_HOME` and Java off PATH. The runs also checked
quoted Chinese JVM properties, command-specific option precedence and cold
JavaCPP caches.

The archive verification covers Linux x64 under WSL2. Other Unix hosts need
matching runners.
[Path support policy](../../../../docs/standard-library.md#installed-layout-and-relocation)
