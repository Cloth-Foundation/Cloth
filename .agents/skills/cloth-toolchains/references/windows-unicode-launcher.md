# Windows installed launchers

BUILD-1 Stage 2 integrates the selected Java Launcher Interface (JLI)
bridge. On 2026-11-10 the user chose `cloth.exe` as the primary Windows
entry and kept `cloth.bat` for compatibility with existing scripts.
Kotlin/Native `shuttle.exe` still handles commands, TOML and native
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
[Bootstrap module](../../../../Launcher/windows/java/cloth/launcher/Bootstrap.java) ·
[Direct Java argument adapter](../../../../Compiler/src/main/kotlin/cloth/util/JavaCommandLine.kt) ·
[Windows process code page](https://learn.microsoft.com/en-us/windows/apps/design/globalizing/use-utf8-code-page)

The host applies the installed context and native-access default, then
`JAVA_OPTS`, then `CLOTH_OPTS` or `SHUTTLE_OPTS`. JLI and HotSpot handle
`JDK_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS` and `_JAVA_OPTIONS` in their normal
order. Application arguments keep their order, empty strings, and literal
`@` values. An incomplete bundled installation fails at startup. When the
image is absent, the existing external Java selection uses `JAVA_HOME`
before PATH. External Java fallback uses the stock Java launcher's path
and encoding behavior. The Unix and development paths retain their
existing Java selection.

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
