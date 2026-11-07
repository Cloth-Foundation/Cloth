# Compiler request API: Stage 1 integration

Completed 2026-11-07. The public request API is implemented for one primary
source file. Source imports, source graphs, TOML loading and Shuttle project
commands remain unimplemented.

## Implemented surface and ownership

- [ClothCompiler](../../../../Compiler/src/main/kotlin/cloth/api/ClothCompiler.kt)
  exposes compile(request), validates inputs and returns the actual artifact.
- [CompilationRequest](../../../../Compiler/src/main/kotlin/cloth/api/CompilationRequest.kt)
  and SourceModule hold explicit paths/options. Modules, libraryPaths and
  libraries are defensively copied and exposed as unmodifiable lists.
- [CompilationResult](../../../../Compiler/src/main/kotlin/cloth/api/CompilationResult.kt)
  contains the actual absolute emitted filename.
- [CompilerDriver](../../../../Compiler/src/main/kotlin/cloth/CompilerDriver.kt)
  retains compileArtifact(CompilerSettings) as the CLI/test adapter. It resolves
  source/output/link paths to absolute paths and delegates to the public facade.
  The request overload reuses the existing parse, target selection, alias check,
  program analysis and emitter path.
- [CompilationErrors](../../../../Compiler/src/main/kotlin/cloth/diagnostics/CompilationErrors.kt)
  owns C0017 / INVALID_COMPILATION_REQUEST. Invalid absolute-path requirements,
  entry/output combinations, blank/duplicate module IDs and inaccessible,
  nondirectory or duplicate physical roots fail before source work.

The facade neither reports diagnostics nor launches programs. Existing structured
syntax/type/target/toolchain diagnostics propagate unchanged. Source I/O retains
C0010; uncaught output I/O becomes C0013, with original causes retained. Final
filename/source alias protection remains C0014. Existing one-file parse-before-
target and target-before-body ordering is unchanged for well-formed requests.

Roots are validated but do not yet enable imports or implicit directory scans.
An API request never reads build.toml/module.toml, and supplying a source root
does not cause unused files to be compiled. EntryFunction is main for executable
requests and null for object requests. Alternate entry names are rejected.
Cross-file entry selection remains a later stage.

## Real Gradle consumer boundary

The [Compiler build](../../../../Compiler/build.gradle.kts) now uses java-library
alongside the existing application plugin. Yvette and Prettier4j are API
dependencies because the original public ClothDiagnostic inherits/returns those
types. LLVM/JavaCPP stay implementation/runtime dependencies.

The [Shuttle build](../../../../Shuttle/build.gradle.kts) has a test-scoped
Compiler dependency and JUnit 5 setup. Its
[CompilerApiBoundaryTest](../../../../Shuttle/src/test/kotlin/shuttle/CompilerApiBoundaryTest.kt)
compiles as a separate module, with no Compiler internal visibility or test
helpers. It emits an AArch64 Linux object using only public request types,
inspects/renders original diagnostics and checks structured request failures.
The .gitignore exception makes Shuttle's test sources reviewable.

The testCompileClasspath dependency report contains the Compiler project,
diagnostic API libraries and Kotlin/JUnit dependencies, with no LLVM/JavaCPP or
TOML compile dependency. Native libraries are available at test runtime through
Compiler's normal runtime variant. Shuttle has no production compiler dependency,
manifest loader or application entry point yet; Stage 2 promotes the integration
to production use.

## Verification

Executed from the repository root:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.CompilationRequestTest --tests cloth.CompilerSettingsTest --tests cloth.ProgramEntryTest --tests cloth.DiagnosticsTest --tests cloth.TargetLayoutTest --tests cloth.NativeCABITest :Shuttle:test --tests shuttle.CompilerApiBoundaryTest --offline --console=plain --no-daemon
```

Result: **71 tests across seven suites, zero failures, errors or skips**, in
17 seconds on the final run. These include 13 new CompilationRequestTest cases and three new
Shuttle boundary cases. Existing settings, entry, diagnostics and target/layout
regressions pass through the new CLI adapter.

The request tests verify immutable snapshots, invalid request/roots/link paths,
output/entry consistency, absence of manifest/unreferenced-file reads, silent
original syntax diagnostics and retained source snapshots, failure ordering,
source/output I/O mapping, stale-artifact preservation and source/hard-link output
protection. A Windows x64 executable with Unicode/spaced paths prints the exact
expected text, returns 7 and preserves PEC output. Object output without main or
a usable linker is verified for all nine existing targets using architecture
fields in COFF, ELF and Mach-O headers.

Native execution evidence for this API change is Windows x64 only. Windows x86,
ARM64, Linux and macOS native API execution were not newly tested; their verified
objects are not native execution evidence. The complete compiler suite was not
rerun; Stage 7 retains the full integration/portability milestone.

Dependency boundary check:

```powershell
.\gradlew.bat :Shuttle:dependencies --configuration testCompileClasspath --offline --console=plain --no-daemon
```

It succeeds with the compile dependency boundary described above. The bundled
skill validator passes for all five changed skills: cloth-imports, shuttle-build,
cloth-language, cloth-toolchains and cloth-diagnostics. Publication checks pass
for 12 documents, 380 local links, 53 Markdown anchors and two TOML examples,
with balanced fences and clean whitespace, including new Kotlin sources.
The development-history check covers the new Stage 1 record rather than
unrelated older milestones. Temporary validator dependencies are removed after
the checks. Stage 1 is complete; Stage 2 begins production Shuttle configuration.
