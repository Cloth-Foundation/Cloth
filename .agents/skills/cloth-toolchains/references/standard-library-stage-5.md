# Bundled standard library Stage 5: publication and runtime independence

Implemented 2026-11-07. This completes the source-bundling slice in the
[plan](../../../../todo/standard-library-toolchain-plan.md). The
[contract](standard-library.md) and Stages 1-4 retain packaging, selection,
namespace and allocation rules. This stage adds maintained examples, packaged
runtime-independence checks, target publication and the
[toolchain guide](../../../../docs/standard-library.md).

## Maintained source example

[StandardLibrary](../../../../examples/StandardLibrary/README.md) supplies a small
stand-in library. Its project lists only App; it needs no library manifest entry.
The application's explicit import reaches Greeting.co, which imports Words.co.
Greeting uses a private class with an owned string, snapshots its result and
deletes the instance before returning that result. Success prints:

```text
Hello from bundled Cloth

PEC: 0
```

The public fixture entry point is `greeting() -> string`. It is a demonstration,
not a promised standard-library API. For a source-checkout run, select the included
fixture library explicitly, as documented in its README. Packaged tests copy its
`cloth/` sources into a disposable installation's default `stdlib/`; the real
installed snapshot remains the pinned scaffold. The production standard-library
repository, its version and gitlink do not change in this stage.

## Packaged execution without the toolchain or sources

[ToolchainDistributionTest](../../../../Tests/src/test/java/cloth/ToolchainDistributionTest.java)
now has nine cases. The new runtime-independence case:

1. Copies the built installation with its normal scripts, JARs and provenance,
   then relocates that disposable tree to a path with spaces.
2. Copies the maintained project and overlays its fixture library sources into
   that tree. It adds a deliberately invalid unreachable `.co` file.
3. Invokes both actual launchers from an existing working directory outside the
   checkout, passing absolute source/project/output paths and no library override
   or project library entry. On Windows x64, each command builds native x64 and
   x86 executables. Installation context and invocation cwd are checked.
4. Deletes only the disposable bundle and project copies, after checking their
   resolved absolute paths belong to the test-owned temporary directory. The
   executable directory is separate. It asserts that the original installation,
   library-source and project paths no longer exist.
5. Runs the native executables directly from the external cwd, with compiler/Java
   environment entries removed and PATH restricted to normal system locations.
   All four Windows programs return zero and print the exact expected output.

All created scratch files stay under the workspace's `tmp/`; the external cwd is
an existing directory and receives no test files. Recursive deletion never targets
the original installation, maintained example, standard-library checkout or other
workspace work. JAR hard links keep copies small; removing a copy removes its link.

The initial attempt to rename a just-used bundle is persistently denied on this
Windows host. The final test deletes the validated disposable trees, as its normal
cleanup would, and requires absence before running a binary. It does not weaken
the unavailable-path assertion or rely on an arbitrary delay.

## Selection, diagnostics and import boundaries

The same packaged suite rechecks default/relative/Unicode override selection,
last-value behavior, disabled defaults, ordinary project replacements, class-free
builtins and literal run arguments. Reached incompatible code keeps its original
C3020 and source filename; invalid overrides retain C0018 without fallback.
Malformed flags fail before selection. Help works with a broken bundle.

Existing cases verify promotion of an explicitly configured matching root, C3106
for reserved collisions, C0020 for selected-input output aliases, and early C0018
before malformed source/project input. Library declarations remain unavailable
without an explicit import. Both maintained and cyclic fixture tests ignore
unreachable malformed files. Backend-free request/graph suites retain duplicate
identity, physical-alias, role, ambiguity, exact-case and first-diagnostic coverage.
The compilation API still discovers no installation or manifests.

## Target coverage

| Verification | Observed scope |
| --- | --- |
| Both packaged commands build from external cwd, then binaries run after deletion of bundle and sources | Windows x64 and x86; four executables in one independence case. |
| Both packaged commands emit the maintained graph with `--emit object` and an unavailable linker path | All nine supported ABI profiles; 18 nonempty objects. |
| Maintained example runs through explicit public compiler requests | Windows x64 and x86. |
| Maintained example executable IR verifies and emits startup objects | All nine supported ABI profiles. |
| GNU Linux startup object links with cc and executes from `/` under Ubuntu WSL | x86_64 GNU; exact greeting, PEC 0 and exit zero. |
| Allocation ownership/failure probes from Stage 4 | Native Windows x64/x86, all-profile executable IR/objects and separate GNU Linux x64 native execution. |

The nine profiles are Windows x86/x64/AArch64 MSVC, Linux x64/AArch64 GNU and
musl, and macOS x64/AArch64. Objects/IR do not establish native execution on
ARM64, musl or macOS. A full Linux/macOS JVM-launcher run is not claimed: the GNU
program is linked from Windows-generated target objects with Ubuntu's native cc.
The approved full-Unicode Windows installation/classpath limitation remains open.

## Source and build ownership

- [Distribution tests](../../../../Tests/src/test/java/cloth/ToolchainDistributionTest.java)
  own both real-command matrices, isolated movement/deletion, external cwd and
  native output assertions. Windows moves/deletes validate their owned absolute
  targets before moving or removing any tree.
- [StandardLibraryPublicationTest](../../../../Compiler/src/test/kotlin/cloth/StandardLibraryPublicationTest.kt)
  checks the exact three-file graph, native execution and all-profile executable
  IR/startup objects through the real graph/analyzer/emitter pipeline.
- [Root Gradle](../../../../build.gradle.kts) and
  [Compiler Gradle](../../../../Compiler/build.gradle.kts) track the maintained
  example as a test input. Editing its external `.co`/manifest files invalidates
  the relevant tests. Existing distribution wiring and pinned export are retained.
- [Release guide](../../../../docs/standard-library.md),
  [example README](../../../../examples/StandardLibrary/README.md) and owning skills
  describe selection, provenance, release preparation, relocation and limits.
  Git ignore exceptions retain the example README and Stage 4 fixture README.

Stage 5 changes tests, fixtures, input tracking and documentation. It adds no
production compiler behavior, public collection implementation, package lookup,
runtime archive or binary compatibility promise.

## Verification evidence

The full offline regression/distribution command passes in 9m 30s: 1,652 Compiler
tests across 151 suites, 57 Shuttle tests, nine packaged-command tests, 23 native-free
syntax checks, 54 native-free resolution checks, 33 native-disabled semantic checks
and 25 backend-free configuration checks. Every suite has zero failures, errors
and skips. This includes both compiler publication tests, all 18 packaged object
builds, the final four-executable independence proof and Stage 4's allocation probes.

Combined ZIP/TAR archives and both per-command installations are rebuilt under
the isolated build tree below. SHA-256 checks confirm that both application JARs
and all three library/provenance inputs match the tested combined installation.
The descriptor reports compiler 1.0-SNAPSHOT, standard library 0.0.1 and the
unchanged published `ea48ad5` scaffold revision. Installed Shuttle also builds the
library's own project with an explicit override, producing a 306-byte object.

Publication checks pass for 24 documents, 876 local links, 92 heading anchors,
balanced fences, whitespace, 38 source/configuration files and completion of
Stages 0-5 while the Windows launcher follow-up stays pending. All five changed
skills pass the full bundled validator using temporary PyYAML under workspace
`tmp/`, removed after validation. Scoped Git checks pass. Unrelated Bootstrap,
emitter and string work is preserved; no Git staging or commits are performed
for this stage.

The verification build uses an isolated init script setting per-project build
directories to `build/standard-library-stage5/{root,Compiler,Shuttle}` and temporary
project cache under `tmp/standard-library-stage5/project-cache`. It also sets both
`cloth.test.standardLibraryPublicationArtifacts` and
`cloth.test.standardLibraryRuntimeArtifacts` to the retained `evidence/` directory.

```powershell
.\gradlew.bat :Compiler:check :Shuttle:check :test :distZip :distTar :Compiler:installDist :Shuttle:installDist --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' -I tmp/standard-library-stage5/isolate.gradle --project-cache-dir tmp/standard-library-stage5/project-cache
wsl.exe -d Ubuntu -- cc /mnt/f/Cloth/build/standard-library-stage5/evidence/example-linux-x64.o -lm -o /mnt/f/Cloth/build/standard-library-stage5/evidence/example-linux-x64
wsl.exe -d Ubuntu --cd / -- /mnt/f/Cloth/build/standard-library-stage5/evidence/example-linux-x64
```

The library stays pinned to published scaffold commit
`ea48ad55be9577f0b876f04c5aafe4420919597d`, version 0.0.1. Generic collection and
native-service APIs, package downloads, compiled caches/archives, a stable binary
ABI and arbitrary-Unicode Windows installation paths remain future work.
