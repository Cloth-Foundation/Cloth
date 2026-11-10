# Bundled Java 25 runtime

The combined and per-command Gradle `installDist`, `distZip` and `distTar`
distributions include a `runtime/` image shared by their installed commands.
The Unix launchers select `runtime/bin/java` relative to their installation
home. The Windows launchers start the image through the checked JLI bridge.
When the image is absent, `JAVA_HOME` precedes PATH. Keep this selection
order in future launcher work. [Windows launcher contract](windows-unicode-launcher.md)

[The root Gradle build](../../../../build.gradle.kts) creates one image per build
with the selected Java 25 toolchain's `jlink`. The JDK release metadata supplies
the `windows`, `linux` or `macos` and `x64` or `arm64` archive suffix. It describes
the machine running Cloth. Cloth's target setting controls the platform of the
generated program. See the [release commands](../../../../docs/standard-library.md#identify-and-prepare-a-release-snapshot).

Keep the image's legal notices and executable permissions when copying it to an
installation or archive. On Unix, `runtime/lib/jspawnhelper` must be executable
for child programs. Review module roots with `jdeps` when dependencies change.
Exercise both installed commands to cover dynamic library loads and diagnostics.
`jdk.charsets` keeps support for extended platform encodings.

## Verification on 2026-11-09

- Windows x64 with OpenJDK 25 produced a 48.1 MiB runtime. Combined, Compiler and
  Shuttle install, ZIP and TAR tasks passed. The installed-command suite passed
  all 12 tests and checked the relocated runtime with an invalid `JAVA_HOME`.
  Both commands compiled and ran a program from each extracted combined archive
  with `JAVA_HOME` unset and Java off PATH. A repeated `:prepareJavaRuntime`
  reused its configuration cache and image.
- Ubuntu WSL x64 with Temurin 25.0.5+8 built the combined installation, ZIP and
  TAR. Both commands compiled and ran a program from the installation and from
  each extracted archive with `JAVA_HOME` unset and Java off PATH. Both archives
  recorded execute permissions for `java` and `jspawnhelper`.
- Native macOS and ARM64 host verification is pending.
