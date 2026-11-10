# Windows Unicode launcher proof

On 2026-11-10 the user selected the version-checked Java Launcher Interface
(JLI) bridge for BUILD-1 Stage 2. The Stage 1 prototype started Cloth and
the existing Kotlin/Native Shuttle frontend and compiler worker from an
emoji installation using the bundled Java 25 image.
[BUILD-1 plan](../../../../todo/windows-unicode-launcher-plan.md) ·
[Stock failure and manifest experiment](windows-unicode-launcher-baseline.md)

## Selected startup path

The Windows package layout uses a private `bin/cloth-jvm.exe` and a named
bootstrap module at `launcher/cloth-launcher.jar`. The public entries are
`bin/cloth.bat` and `bin/shuttle.exe`. Cloth's script and Shuttle's worker
handoff pass private entry selectors so the Java command-line adapter
keeps the supplied argument arrays. Shuttle still owns TOML parsing, the
compiler exchange and launch of the resulting program.
[Native Shuttle entry](../../../../Shuttle/native/src/nativeMain/kotlin/shuttle/nativecli/Entry.kt) ·
[Windows child launch](../../../../Shuttle/native/src/mingwX64Main/kotlin/shuttle/nativecli/WindowsHost.kt) ·
[Direct Java argument adapter](../../../../Compiler/src/main/kotlin/cloth/util/JavaCommandLine.kt)

- The native host gets its installation path with `GetModuleFileNameW`
  and loads the absolute bundled `runtime/bin/jli.dll` with
  `LoadLibraryExW`. Its embedded UTF-8 manifest establishes the process
  code page before JVM startup. The host checks that setting before
  loading JLI. Windows 10 version 1903 introduced this manifest setting.
  [Windows process code page](https://learn.microsoft.com/en-us/windows/apps/design/globalizing/use-utf8-code-page) ·
  [OpenJDK 25 runtime discovery](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/windows/native/libjli/java_md.c)
- The host reads UTF-16 arguments, quotes a replacement command line
  and encodes it as UTF-8 for JLI. JLI performs its normal launcher
  option and environment handling. It launches the bootstrap on the
  module path. The bootstrap expands the supplied application classpath
  and loads the real entries through a context class loader with URLs
  built from `Path.toUri()`. This avoids the supplementary-character
  encoding in the stock system classpath loader.
  [OpenJDK 25 Windows argument processing](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/windows/native/libjli/cmdtoargs.c) ·
  [OpenJDK 25 classpath loader](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/jdk/internal/loader/URLClassPath.java)
- JLI is an internal OpenJDK ABI. The proof checked the Windows x64
  DLL's file version and the five exports for argument initialization,
  parsing, argument retrieval and launch. Stage 2 must validate the ABI
  against each exact bundled JDK build and diagnose an incompatible
  image before invocation. Use the stock external Java route for the
  existing missing-image fallback.
  [JLI launch declaration](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/native/libjli/java.h) ·
  [Bundled Java packaging](bundled-java-runtime.md)

The selected bridge retains launcher-level behavior such as
`-XshowSettings:properties` and `JDK_JAVA_OPTIONS`. A public JNI host
would need separate handling for launcher options. The prototype uses
an ordinary Java module for bootstrap loading. The bundled vendor
executable and DLLs retained their original bytes.
[JNI Invocation API](https://docs.oracle.com/en/java/javase/25/docs/specs/jni/invocation.html)

## Observed proof

The host was Windows x64 25H2 build `26200.9711` with system ACP 1252.
The prototype used bundled Oracle OpenJDK `25+36-3489` x64. The
`jli.dll` file version was `25.0.0.0` and its SHA-256 was
`037991792d5d3e20c46982cbe69ddad55f713e5bce3f845e910cf4ca2e26becb`.
The combined install identified standard-library pin
`c79b58ffba07d9e2a70e4acf954a2dca47cf5666`.

The proof built an x64 console host using MSVC 14.51.36231 with a static
C++ runtime and SDK 10.0.26100.0. It compiled the module with `javac --release
25` and built the proof Shuttle frontend with Kotlin/Native 2.4.20 through
`:Shuttle:native:linkReleaseExecutableMingwX64 --offline`. The native
source copy changed the worker executable and private entry selector.
The staged Cloth script sent its JVM startup through the same host.

Both commands ran from a complete proof package named `Cloth 中文 😀`
with an unrelated caller directory named `unrelated caller 中文 😀`.
Both read the correct installed home and Java runtime, kept the caller
directory, and loaded application classes from the relocated JARs.
The normal object commands were exercised through the installed entries.

```powershell
& "$proof/bin/cloth.bat" --emit=object Main.co 'results/cloth-proof 😀.o'
& "$proof/bin/shuttle.exe" build --project . --emit=object -o 'results/shuttle-proof 😀.o'
```

| Check | Observed result |
| --- | --- |
| Stock package controls | Both ASCII installs built 343-byte objects with ASCII temp paths. Both mixed Unicode installs returned 2 with the missing `java.dll` error. |
| Proof help, build and native dependencies | Both help commands returned 0. Both object builds returned 0 and wrote 343-byte objects at caller-relative paths. The cold Unicode JavaCPP caches each extracted six DLLs. |
| Bare commands on PATH | `cloth`, `cloth.bat` and `shuttle` resolved from a pure `😀🧵` package and each built a 343-byte object. The Cloth script resolved its directory through PATH when CMD left its invocation path relative. |
| Library, arguments and streams | Both `run` commands imported the bundled `List`, printed `中 文 😀` and the piped input `input 中文 🧵`, and returned 7. The program checked a spaced value, an empty value, Unicode, a quote and trailing backslash, literal `@literal`, `--debug`, `--` and `*`. The batch exit-control commands ran on separate lines so CMD kept them out of the application arguments. |
| JVM options | Both accepted a quoted Unicode property, `-Xmx256m` and `-XshowSettings:properties`. `JDK_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS` and `_JAVA_OPTIONS` each applied once. The command-specific options overrode `JAVA_OPTS` and the earlier values. An overriding `_JAVA_OPTIONS` value won in a separate pass. |
| Failures | Each failed rebuild returned 1 with one `C3020` on stderr, preserved the prior executable bytes and produced no program completion output. An invalid JVM option returned 1 through both commands. The private host emitted one startup error with status 2 when the image was missing. |
| Runtime selection and integrity | The relocated package built with an invalid `JAVA_HOME` and Java off PATH. Both missing-image controls selected `JAVA_HOME` first and then PATH when it was empty. An invalid `JAVA_HOME` failed before PATH fallback. Hashes for `java.exe`, `jli.dll`, `java.dll` and `jvm.dll` matched the installed originals. `java.exe` retained its valid signature. |

The Ctrl+C probe used separate hidden consoles and verified zero remaining
processes after detaching from each test console. Direct stock Java and the
proof host both returned 130. Stock and proof Shuttle both returned
`0xC000013A`. The proof Cloth batch script prompted for confirmation and
returned 255 after confirmation, while the stock script returned 130 in
this probe. Batch interruption parity remains an integration check.

Stage 2 owns distribution wiring and a repeatable regression fixture.
The native proof ran on Windows x64. Release verification for any other
Windows host architecture needs a matching runner.
[Distribution tests](../../../../Tests/src/test/java/cloth/ToolchainDistributionTest.java) ·
[Stage 2 and host gates](../../../../todo/windows-unicode-launcher-plan.md#stage-2-integrate-both-commands-and-all-distribution-forms)
