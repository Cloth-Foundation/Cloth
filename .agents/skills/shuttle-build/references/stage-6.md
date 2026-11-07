# Shuttle Stage 6: packaged project build and run

Implemented 2026-11-07. Shuttle now loads the selected TOML project, prepares a
plain compiler request, builds its reachable source graph and optionally launches
the actual returned executable. The [manifest/build contract](semantics.md) owns
configuration and path rules; [compiler Stage 6](../../cloth-imports/references/stage-6.md)
owns graph entry selection and whole-program emission.

## Commands and paths

```text
shuttle build [--project PATH] [-o PATH] [build options]
shuttle run [--project PATH] [-o PATH] [build options] [-- program arguments]
```

`--project` accepts a directory or its build.toml file, including an equals-attached
value. Without it, select the nearest ancestor build.toml. `-o`/`--output` overrides
the requested artifact path; equals-attached values are also accepted. Command
path overrides resolve from invocation cwd. Configured module/output paths retain
their manifest-root anchor. No command changes the process cwd.

Shared build flags are `--target`, `--emit executable|object`, `--debug`, `--linker`,
`--linker-driver lld|msvc|cc`, `--runtime default|static|dynamic`,
`--subsystem console|windows`, `-L`/`--library-path`, `-l`/`--library`, `--sysroot`,
`--msvc-tools`, `--windows-sdk` and `--windows-sdk-version`. Repeated scalar flags
use the last value; library/path lists retain order. Run's first -- ends build
parsing; all following values, including flags and another --, are program args.
Build rejects program args. `shuttle --help` and build/run --help print usage.

Successful build returns status 0 without launching, even if the program would
return nonzero. The internal build function returns Compiler's actual artifact
path, including a target-specific .exe suffix. Run rejects object output before
project/source work, builds successfully before launching, inherits streams and
invocation cwd, and returns the child's status. It preserves empty, Unicode,
spaced, combining-character and quoted arguments in order. PEC comes only from
Compiler's generated startup. A failed rebuild never launches a previous artifact.

## Ownership and diagnostics

- [ShuttleSettings](../../../../Shuttle/src/main/kotlin/shuttle/command/ShuttleSettings.kt)
  parses only project/command choices and delegates common build flags to
  [CompilerOptions](../../../../Compiler/src/main/kotlin/cloth/settings/CompilerOptions.kt).
  Standalone CompilerSettings retains its source/output defaults and two-position
  limit through that same parser. The request API still receives paths/options,
  never raw CLI arrays or TOML types.
- [ProjectBuild](../../../../Shuttle/src/main/kotlin/shuttle/command/ProjectBuild.kt)
  loads the project, resolves command override paths, prepares the immutable request
  and calls ClothCompiler.compile. It never reads ASTs, chooses an ABI or resolves
  source imports. Compiler owns all those phases and native linking.
- [Entry](../../../../Shuttle/src/main/kotlin/shuttle/Entry.kt) owns main, reporting,
  statuses and build-before-run ordering. [NativeProgram](../../../../Compiler/src/main/kotlin/cloth/util/NativeProgram.kt)
  supplies shared launch transport with the standalone compiler: inherited I/O,
  cwd, Windows literal-quote encoding, child status and interrupted-child cleanup.
- [Gradle](../../../../Shuttle/build.gradle.kts) applies the application plugin,
  selects shuttle.EntryKt, enables native access and packages shuttle/shuttle.bat.
  `:Shuttle:run` uses the root working directory and inherited stdin. Build an
  installable distribution with `:Shuttle:installDist`; normal output is under
  Shuttle/build/install/shuttle. Use Cloth's wrapper; Shuttle remains a submodule.

The common DiagnosticReporter now accepts Yvette Diagnostic, so it renders both
Shuttle failures and the original ClothDiagnostic without conversion or duplicate
reporting. Shared compiler-option failures keep C codes and use Shuttle's usage
text. New Shuttle codes in
[ShuttleDiagnostic](../../../../Shuttle/src/main/kotlin/shuttle/diagnostics/ShuttleDiagnostic.kt):

| Code | Meaning |
| --- | --- |
| S0014 | Invalid command, project/output CLI path or argument shape |
| S0015 | Run requested object output |
| S0016 | Artifact launch or interrupted wait failed |
| S0017 | Command I/O failed outside the compiler boundary |
| S9000 | Unexpected Shuttle failure |

Expected failures return 1, unexpected internal failures return 2, and successful
build returns 0. A successful launch returns the actual child status instead.
Configuration retains S0001-S0013. Manifest/source alias guards still run before
output writes, including physical aliases and possible executable suffixes.

## Windows Java command-line transport

A real child-JVM test exposed native-code-page replacement before Java main on
the installed Windows JDK 25 (native encoding Cp1252). Setting file.encoding did
not preserve nonrepresentable arguments. Both compiler and Shuttle main now use
[JavaCommandLine](../../../../Compiler/src/main/kotlin/cloth/util/JavaCommandLine.kt)
to recover direct application arguments from GetCommandLineW/CommandLineToArgvW
through JDK 25's foreign API, freeing the allocated argv with LocalFree. The
[Windows API contract](https://learn.microsoft.com/en-us/windows/win32/api/shellapi/nf-shellapi-commandlinetoargvw)
specifies the returned argument layout and ownership. The
[JDK foreign-memory API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/MemorySegment.html)
supplies scoped access; the adapter copies original UTF-16 units without decoding
replacement or normalization.

Recovery requires the explicit main class or -jar boundary to match the direct
OS argument suffix. In-process runCompiler/runShuttle arrays remain authoritative;
they are never replaced from the current JVM command line. JVM argument-file
expansion remains with Java, including its input-encoding rules. The test harness
puts the long JVM classpath in an argument file but keeps the main class and
application arguments on the direct command line. It separately handles Windows
literal quotes at the Java-launch boundary. Generated application launchers keep
the explicit main class and enable native access. This adapter adds no native
LLVM/TOML work to configuration loading.

## Verification

[ShuttleBuildTest](../../../../Shuttle/src/test/kotlin/shuttle/ShuttleBuildTest.kt)
covers real multi-module builds, actual artifact names, foreign object output
without a linker/main, common flags, nearest-project cwd rules, unchanged original
compiler rendering, first configuration failure, CLI failures and manifest/source
physical aliases. [ShuttleRunTest](../../../../Shuttle/src/test/kotlin/shuttle/ShuttleRunTest.kt)
executes the actual Java main in child JVMs: build versus run status, one PEC,
Unicode paths and exact arguments, negative statuses, runtime failure, stale
artifact rejection, launch failure cause and the standalone compiler's shared
Unicode transport. A separate shared-launch probe reads stdin and reports cwd,
stdout/stderr and its exit status.

The final full `:Compiler:check :Shuttle:check :Shuttle:installDist` run succeeds
in 7m 34s, using isolated task-local build directories. It passes 1,600 Compiler
tests, 47 Shuttle tests, 23 syntax, 27 resolution, 33 native-disabled semantic
tests and 24 backend-free configuration tests, with zero failures/errors/skips.
The [compiler record](../../cloth-imports/references/stage-6.md#verification)
retains the complete task/suite table and reproducible check command.

The generated shuttle.bat was then invoked directly on a temporary TOML project
in a directory containing spaces and lambda, with selective imports and a
terminating even/odd cycle across files. Build returned 0. Run received a Unicode
argument, printed `packaged λ 🚀`, emitted exactly one `PEC: 7`, and returned 7.
A second packaged build emitted Linux ARM64 object output with a nonexistent
linker override and returned 0. These smoke checks use the actual distribution
scripts and jars, independently of the in-process APIs and child-JVM tests.

All seven changed skills pass the bundled validator with task-local PyYAML 6.0.3.
Publication checks validate local links/anchors, TOML examples, fences and whitespace;
the final counts are recorded in the development history. The 24 touched source/
build files pass whitespace/final-newline checks, and root/submodule diff whitespace
checks pass. Temporary scripts, dependencies, fixtures and outputs are removed.

Native process evidence is Windows x64. Foreign object
checks do not establish native foreign-host execution. Bootstrap's larger source
scaffold, portable examples and publication remain Stage 7; no submodule commit
or pin is created by this implementation stage.
