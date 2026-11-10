# Bundled standard library Stage 1: distribution packaging

Implemented 2026-11-07. The [contract](standard-library.md) owns selection and
namespace policy; the [TODO](../../../../todo/standard-library-toolchain-plan.md)
tracks the remaining stages. This stage packages sources and provenance and
supplies launcher context. Automatic source-module attachment, `--stdlib`,
`--no-stdlib` and namespace enforcement remain pending.

## Commands and installed layout

From the compiler repository root:

```powershell
.\gradlew.bat :installDist --offline --console=plain --no-daemon
.\gradlew.bat :distZip :distTar --offline --console=plain --no-daemon
```

The combined installation is `build/install/cloth/`. Its `bin/` contains `cloth`
and `shuttle`, plus Windows `.bat` equivalents. `lib/` contains both applications
and their existing JVM/native dependencies. `stdlib/` contains the pinned
`module.toml` and `cloth/` sources; `toolchain.properties` records format `1`,
compiler version, library version and the full library Git revision.

Archives are `build/distributions/cloth-<version>.zip` and `.tar`. Both contain
the same layout under `cloth-<version>/`. Dependencies are the existing platform
runtime jars without platform pruning; the observed archives are about 1.6 GB.
An installed toolchain uses a JDK 25 installation and performs no Gradle or Git
operations during ordinary compilation.

Existing per-command distributions remain supported:

```powershell
.\gradlew.bat :Compiler:installDist :Shuttle:installDist --offline --console=plain --no-daemon
```

Their installation roots are `Compiler/build/install/cloth/` and
`Shuttle/build/install/shuttle/`; each also carries sources and provenance.
Compiler's application name is now `cloth`.

## Snapshot preparation and ownership

[Root Gradle wiring](../../../../build.gradle.kts) owns the combined distribution,
common content, launch-script adaptation and development-task properties.
`:prepareToolchain` runs the build-only
[ToolchainBundle](../../../../Shuttle/native/src/jvmMain/kotlin/shuttle/packaging/ToolchainBundle.kt)
exporter using Shuttle's runtime classpath. Keeping module TOML interpretation
there reuses `ManifestLoader` without adding TOML parsing to Compiler.

The exporter requires a mode-160000 `Standard-Library` index pin, the library's
own repository at that path, matching checked-out HEAD and a clean library
working tree. It rejects uncommitted tracked or untracked files; ignored build
artifacts do not contaminate a bundle. It rechecks these conditions on every
packaging invocation instead of accepting a stale up-to-date result.

Export with `git archive` at the recorded revision and `core.autocrlf=false`.
This keeps source bytes independent of the developer's Windows line-ending
setting. Require exact-case `module.toml` and `cloth/Library.co` in the Git tree,
regular source files, module name `StandardLibrary` and a nonblank module version.
Read the version from the archived manifest, not a potentially changing checkout.
Copy only module metadata and `cloth/**/*.co` sources, preserving subdirectories.
Exclude Git metadata, development README/build files and temporary artifacts.

Generate deterministic UTF-8 properties with JDK escaping and no timestamp comment.
Prepare a new staging tree before replacing the generated output, and restrict
recursive cleanup to the repository's `build/` tree. A failed validation preserves
an earlier generated bundle and prevents distribution tasks from consuming it.

The requested scaffold was committed and published in Standard-Library as
`ea48ad55be9577f0b876f04c5aafe4420919597d`; the compiler index now pins that revision.
The compiler's other implementation changes remain in its working tree.
Packaging uses local Git objects and never fetches or downloads library sources.

## Launcher handoff and development execution

Both generated launchers pass `cloth.toolchain.home` from their computed
installation root and `cloth.toolchain.layout=installed`. No checkout path is
baked into installed launchers. Existing Gradle script handling preserves the
invocation directory, native-access JVM option and program-argument forwarding.
The root build checks known template anchors when adapting Gradle-generated
scripts so an incompatible template change fails explicitly.

Compiler/Shuttle `JavaExec` development tasks pass the repository root and
`cloth.toolchain.layout=development`. This includes `run` and Compiler's
`runExample`; properties identify context without selecting or attaching library
sources yet. The API still receives only caller-supplied module roots.

### User-approved Windows limitation and follow-up

The installed JDK 25 on this Windows host loses characters outside its system
code page in JVM options and classpaths. A small probe reproduced `?` replacement
and class-loading failure for Chinese installation paths. This is separate from
Cloth's existing recovery of direct application arguments through the Windows
wide command line. The Java launcher documents its system-encoding constraint
for argument files in the [JDK 25 launcher specification](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html).

The user chose to keep Gradle launchers for Stage 1 and track full Unicode
installation-path support separately. Windows relocation verification covers
spaces and `é`, which is representable by this host's code page. Do not claim
arbitrary Windows Unicode installation paths are supported. The
[launcher follow-up](../../../../todo/standard-library-toolchain-plan.md#windows-launcher-follow-up)
retains the broader requirement.

## Verification (2026-11-07)

```powershell
.\gradlew.bat :test :Shuttle:test :Shuttle:configurationTest :distZip :distTar :Compiler:installDist :Shuttle:installDist --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

Passed in 1m 35s: two root distribution tests, 56 Shuttle tests across seven
suites (including five exporter tests), and 25 backend-free configuration tests.
All have zero failures, errors or skips.

- [Distribution tests](../../../../Tests/src/test/java/cloth/ToolchainDistributionTest.java)
  check commands, metadata and sources, then move a staged bundle to a path with
  spaces and `é`. Both real Windows launchers build nonempty native object files
  from another directory using relative source/project/output paths. JVM settings
  confirm the relocated root and installed layout. Jar hard links keep this test
  from duplicating the large platform jars; other files are copied normally.
  The fixture selects a Unicode character representable by the host's native
  encoding; the final two-test rerun passes in 7s after that portability adjustment.
- [Exporter tests](../../../../Shuttle/native/src/jvmTest/kotlin/shuttle/ToolchainBundleTest.kt)
  verify pinned contents, canonical line endings, deterministic escaped metadata,
  ignored-artifact exclusion, stale-output replacement, rejection of dirty or
  unpinned checkouts, exact filename case and protection of source directories.
- Both ZIP and TAR were inspected: each has 50 entries and all required commands,
  source files and provenance, with no checkout/build contamination.
- Both development commands built objects with explicit development properties.
  The committed library's standalone object is 306 bytes. The installed bundle
  and development builds use cached dependencies offline.
- Both Unix scripts pass `sh -n`. Under Ubuntu WSL, an argument-capture Java stub
  verifies both scripts' handoff after relocation to a path containing spaces and
  Chinese characters. No Linux JDK is installed, so this is shell/argument
  verification, not Linux JVM execution or compilation. macOS execution is untested.

An initial offline test run lacked the root JUnit platform-launcher dependency;
it was fetched once before the successful offline run. An exporter regression
exposed Git's CRLF conversion and passed after disabling it for the archive.
No language syntax, compiler import policy, native ABI or library API changed.

Publication checks pass for 13 documents, 638 local links, 77 heading anchors,
code fences, whitespace, five source/configuration files and completion of only
Stages 0-1. All four owning/routing skills pass the full bundled skill validator,
using PyYAML installed temporarily under workspace `tmp/` and removed afterward.
Root and submodule whitespace checks pass. Temporary probes and validation
dependencies are removed; generated installations, archives and test reports remain.
