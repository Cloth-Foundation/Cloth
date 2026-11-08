# Bundled standard library Stage 3: command selection controls

Implemented 2026-11-07. The [contract](standard-library.md) owns selected policy;
[Stage 2](standard-library-stage-2.md) owns attachment, roles, source resolution
and protected toolchain inputs. This stage exposes default, override and disabled
selection consistently through both commands.

## Commands and selection

```powershell
cloth run Main.co build/app --stdlib ../Standard-Library
shuttle run --project . --stdlib=../Standard-Library
cloth Main.co build/app --no-stdlib
shuttle build --no-stdlib
```

Put selection flags before the first `--` in `run`. Everything following that
separator goes to the program literally, including either library flag. The
standalone build form retains `--` before literal source/output filenames.
`cloth --help`, `cloth run --help` and Shuttle help list both options without
opening a bundle or project.

| Selection | Source root and validation |
| --- | --- |
| No selection flag | Installed `<home>/stdlib` or development `<repo>/Standard-Library`; retain early structural checks and installed descriptor validation. |
| `--stdlib PATH`, `--stdlib=PATH` | Resolve from invocation cwd, canonicalize physical identity, require exact-case readable `module.toml`, `cloth/`, and `cloth/Library.co`. |
| `--no-stdlib` | Supply no implicit root or namespace role; keep explicitly configured project roots regular. |

Only default mode reads the launcher context and release descriptor. Override
and disable work without valid default properties, metadata or library contents.
An invalid override fails with C0018 and its selected path; never fall back.
An override needs no Git checkout, clean tree, version match or release descriptor.
Shared selection does not interpret its TOML or parse unreachable Cloth files.
Reached incompatible code produces its ordinary source diagnostic and location.

Repeated overrides select the last path and open only that root. Each occurrence
must still have a syntactically valid nonblank path: missing/blank values are
C0002 and invalid path syntax is C0012. Repeating `--no-stdlib` is idempotent.
Combining override and disable in either order, or attaching any value to
`--no-stdlib`, reports C0003 before library/project/source work.

Disabled mode permits configured project `cloth/` replacements and retains
ordinary ambiguity C3099, including when a regular module is named StandardLibrary.
The standalone command still has no project-root discovery; an import with
`--no-stdlib` and no supplied roots reports C3097. Compiler builtins, primitive
operations and their runtime support remain available. `--runtime` independently
selects the C runtime. Selected override metadata/markers retain C0020 output
protection; unconsumed default descriptors are not selected inputs.

## Identifying the selected source snapshot

For installed default selection, inspect `<home>/toolchain.properties` for
`format=1`, `compiler.version`, `stdlib.version`, and `stdlib.revision`. That
revision is the pinned source snapshot packaged under `<home>/stdlib`.
Development default selection uses the current `<repo>/Standard-Library` tree;
an override uses the current explicitly selected checkout. Their local version
and revision can be inspected in that checkout, but a version string does not
identify uncommitted changes. Neither mode claims a matching release snapshot.
No additional information command or compatibility scheme is introduced.

## Source and test routing

- [StandardLibrarySelection](../../../../Compiler/src/main/kotlin/cloth/settings/StandardLibrarySelection.kt)
  represents the mutually exclusive command modes. [CompilerOptions](../../../../Compiler/src/main/kotlin/cloth/settings/CompilerOptions.kt)
  parses both frontends' shared flags without opening source paths.
- [StandardLibrary](../../../../Compiler/src/main/kotlin/cloth/toolchain/StandardLibrary.kt)
  branches on the selected mode before any default-context lookup and shares
  structural validation/output protection between default and explicit roots.
- [CompilerSettings](../../../../Compiler/src/main/kotlin/cloth/settings/CompilerSettings.kt),
  [CompilerDriver](../../../../Compiler/src/main/kotlin/cloth/CompilerDriver.kt),
  [compiler entry](../../../../Compiler/src/main/kotlin/cloth/Entry.kt),
  [ShuttleSettings](../../../../Shuttle/src/main/kotlin/shuttle/command/ShuttleSettings.kt)
  and [ProjectBuild](../../../../Shuttle/src/main/kotlin/shuttle/command/ProjectBuild.kt)
  own adaptation/help. Disabled mode passes the original prepared request.
- [StandardLibraryErrors](../../../../Compiler/src/main/kotlin/cloth/diagnostics/StandardLibraryErrors.kt)
  retains structured, once-rendered failures. The core compilation API still
  discovers no installation or command flags.
- [StandardLibrarySelectionTest](../../../../Compiler/src/test/kotlin/cloth/StandardLibrarySelectionTest.kt)
  adds eight backend-free cases: flags, repetition, invalid values/paths,
  conflicts, literal filenames, cwd resolution, bypass, no fallback and selected
  input protection. It joins `:Compiler:importResolutionTest`.
- [ShuttleBuildTest](../../../../Shuttle/src/test/kotlin/shuttle/ShuttleBuildTest.kt)
  checks shared parsing and literal program arguments. [Distribution tests](../../../../src/test/java/cloth/ToolchainDistributionTest.java)
  exercise both actual launchers with relative/spaced/Chinese/supplementary-Unicode
  override paths, incompatible reached code, invalid selections, input protection,
  broken defaults, replacements, ordinary ambiguity, builtins, help and separators.

## Verification and remaining scope

Full offline regression/distribution verification passed in 8m 55s: 1,644 Compiler tests across 149
suites, 57 Shuttle tests, seven packaged-command tests, 23 backend-free syntax
checks, 54 backend-free resolution checks, 33 native-disabled semantic checks
and 25 backend-free configuration checks. Every suite has zero failures, errors
and skips. The packaged cases run both commands from outside the repository and
execute Windows x64 programs with explicit overrides and disabled selection.
Combined ZIP/TAR archives and both per-command installations are rebuilt. SHA-256
checks confirm that their application JARs match the tested combined installation.
The final Shuttle parser/value-boundary regression is included in this run.

Publication checks pass for 17 documents, 750 local links, 83 heading anchors,
balanced fences, whitespace, 24 source/configuration files and completion of only
Stages 0-3. All five owning skills pass the full bundled validator using temporary
PyYAML installed under workspace `tmp/` and removed after validation. Concurrent
Bootstrap/emitter/string work is preserved; no Git staging or commits are performed
for this stage.

Build outputs use `build/standard-library-stage3/{root,Compiler,Shuttle}` with an
isolated project cache, preserving other work in the shared checkout. To reproduce
that layout, save this temporary init script at `tmp/standard-library-stage3/isolate.gradle`:

```groovy
gradle.beforeProject { project ->
    def relative = project.path == ':' ? 'root' : project.path.substring(1).replace(':', '/')
    project.layout.buildDirectory.set(new File(project.rootDir, 'build/standard-library-stage3/' + relative))
}
```

```powershell
.\gradlew.bat :Compiler:check :Shuttle:check :test :distZip :distTar :Compiler:installDist :Shuttle:installDist --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' -I tmp/standard-library-stage3/isolate.gradle --project-cache-dir tmp/standard-library-stage3/project-cache
```

New packaged native execution targets Windows x64. A Unicode override is an application argument
and works through the existing wide-argument adapter; this does not resolve the
separately documented Windows Unicode installation/classpath limit. No new
Linux/macOS execution, collection API, native-service boundary or library binary
ABI is established. Stages 4-5 remain pending, and the library source/pin is unchanged.
