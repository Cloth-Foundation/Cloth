---
name: cloth-toolchains
description: Extend or troubleshoot Cloth build and run commands, bundled standard-library selection, target layouts, native C ABI profiles, object emission, linker and CRT discovery, or target-specific validation.
---

# Cloth targets and toolchains

The class-backed interface release verifies both maintained fixtures with
installed Windows x64 and x86 launchers and GNU Linux x64 executables linked
in Ubuntu WSL. Both fixtures emit verified executable IR and startup objects
on all nine target profiles.
[Interface release matrix](../cloth-interfaces/references/stage-7.md#native-execution-and-cross-target-emission)

[Generic Stage 4](../cloth-generics/references/stage-4.md) uses each compilation's
selected layout for closed generic values and the existing private ABI. Windows
x64/x86 execute its generic fixtures and ownership probes; all nine ABI profiles
verify IR and emit objects. Foreign Windows ARM64, Linux and macOS evidence is
IR/object-only. [Generic Stage 5](../cloth-generics/references/stage-5.md) adds the
actual public List, command-selection tests and a byte-identical temporary pinned
snapshot export proof. Working release preparation still needs clean/pinned
submodule history; development selection accepts the edited checkout. This adds
no public binary ABI.

[Growable arrays Stage 8](../cloth-arrays/references/growable-arrays-stage-8.md)
adds final Windows x64/x86 and GNU Linux x64 execution of the maintained array,
concrete collection, imported List and Bootstrap examples. Linux objects include
verified program startup and are linked with Ubuntu WSL's C driver. All nine
profiles have verified IR/objects; other native runners remain unavailable.

Read [build options](../../../README.md#building-and-linking) and inspect current
settings before prescribing commands. The shared
[portability requirement](../cloth-language/references/semantics.md#portability)
requires Windows, Linux and macOS contracts; implementation evidence varies by
target. Use [program entry](../cloth-program-entry/SKILL.md) for startup behavior.

Read the [bundled standard-library contract](references/standard-library.md)
before implementing library packaging, shared command discovery, overrides or
reserved `cloth.*` paths. Its Stage 0 selects those rules and an explicit module
role. [Stage 1](references/standard-library-stage-1.md) implements combined and
per-command distributions, pinned sources/provenance and launcher context.
[Stage 2](references/standard-library-stage-2.md) adds shared default selection,
automatic attachment, explicit module roles and reserved-path diagnostics.
[Stage 3](references/standard-library-stage-3.md) implements `--stdlib PATH` and
`--no-stdlib`, shared validation, default bypass and command diagnostics. Preserve the documented
Windows Unicode installation-path limit. Keep source-library selection separate
from the existing `--runtime` C runtime option.

The [Windows Unicode launcher plan](../../../todo/windows-unicode-launcher-plan.md)
tracks the requested fix for both installed commands: Chinese/emoji installation
paths, unchanged locale/cwd, preserved JVM context and actual installed-command
tests. The native-host design is a candidate pending its startup proof; the
current Windows limitation remains until implementation and host verification.

[Stage 4 allocation boundary](references/standard-library-stage-4.md) records the
user-selected owning-collection consumer and verifies existing typed class
`::new`/`::delete`, private target-aware helpers, owned field cleanup and fatal
allocation behavior. Portable collection logic remains in Cloth. Its fixture is
not a shipped collection API; later buffer/I/O/clock/thread services retain their
own design prerequisites. Use `StandardLibraryRuntimeTest` for this boundary.

[Stage 5 publication](references/standard-library-stage-5.md) adds the
[maintained bundling example](../../../examples/StandardLibrary/README.md) and
[toolchain guide](../../../docs/standard-library.md), with packaged external-cwd
builds and native execution after deleting disposable compiler/source trees.
ToolchainDistributionTest verifies both commands' nine-profile object matrix;
StandardLibraryPublicationTest owns the exact example graph and executable IR.
Keep native Windows/GNU execution distinct from other profiles' object evidence.

## Development rules and supported scope

- Select an explicit ABI profile from the normalized LLVM target triple and reject
  unsupported architecture/OS/environment combinations. Never substitute the host
  C data model. Keep TargetLayout per compilation; reusing an AST across target
  widths must not leak host-size facts or mutate source annotations.
- Separate Cloth fixed-width types, target-sized integers/counts, native C types,
  and linker configuration. Windows and Unix `long` widths differ. Native APIs
  use their own C/WinAPI signatures and required calling conventions.
- Windows x86/MSVC is the implemented 32-bit profile, with SSE2 baseline. Other
  32-bit platforms remain unsupported. Preserve verified existing 64-bit profile
  selection rather than accepting an unvalidated target spelling by analogy.
- `--emit object` validates bodies without requiring main or discovering a linker.
  It adds no startup adapter; it does not establish a public cross-module ABI,
  exported library symbols, or an archive format.
- Linker discovery respects explicit overrides, architecture-matched CRT/SDK paths,
  runtime linkage, GUI/console startup, sysroots, and ordered user libraries.
  Preserve paths with spaces as arguments; resolve dependencies before creating
  output artifacts. Static system CRT linking on macOS is rejected.
- The emitter checks the semantic target and callable signatures once before
  executable linker discovery. Executable IR and binary emission share startup
  generation and verify the module after startup is added.
  [LLVM emitter](../../../Compiler/src/main/kotlin/cloth/emitter/LLVM.kt)
- Keep compilation separate from launch. The shared driver returns the emitted
  path. Build mode reports compilation status and `run` reports the child
  process status after a successful build and launch.
- The no-argument example source is a developer convenience, not a production
  compiler contract (user clarification, 2026-11-02). Do not pin its filename in
  tests; verify explicit source/output arguments and supported CLI options.

## Build and run commands

Use [shuttle-build](../shuttle-build/SKILL.md) for project TOML, discovery and
build/run orchestration, and [cloth-imports](../cloth-imports/SKILL.md) for source
imports and file scopes. Stage 0 publishes their contracts and the
[compiler request boundary](../cloth-imports/references/compiler-api.md): a one-way
`Shuttle -> Compiler` dependency, with Compiler independent of TOML and Shuttle.
Stage 1 implements the one-file public facade and a Shuttle consumer fixture;
its [evidence](../cloth-imports/references/stage-1.md) records the verified scope.
Stage 2 implements [manifest loading and request conversion](../shuttle-build/references/stage-2.md)
without choosing an ABI or discovering a linker. Stage 3 parses
[import headers](../cloth-imports/references/stage-3.md). Stage 4 discovers
[reachable source graphs](../cloth-imports/references/stage-4.md), including cycles,
without native target selection. It protects every loaded source from output
aliases. Stage 5 adds [per-file binding and graph checking](../cloth-imports/references/stage-5.md):
selection errors precede ABI work, while signatures/bodies use the selected target.
[Stage 6](../cloth-imports/references/stage-6.md) emits the checked graph as one
LLVM program/object and selects only the primary file's own main. C3105 is
retired; direct single-unit analysis retains C3096. [Shuttle Stage 6](../shuttle-build/references/stage-6.md)
adds packaged project build/run commands; the direct source CLI remains supported.
[Stage 7](../cloth-imports/references/stage-7.md) verifies the maintained
[nested project](../../../examples/Shuttle/README.md) and Bootstrap on Windows
x64/x86 and GNU Linux x64 under WSL, with executable IR/startup objects across
all nine profiles. The developer default source is a single-file example;
project sources with imports use Shuttle or explicit roots through the API.

`cloth run examples/Functions.co build/functions` builds and launches a program.
The first `--` in `run` separates build options from program arguments. The
launcher passes the remaining arguments in order and inherits the caller's
working directory and console streams. The default Windows JVM launcher needs
explicit escaping for literal quotes. Preserve empty, Unicode and spaced
arguments. Treat flags such as `--debug` after the separator as program arguments.

`ClothCompiler.compile(CompilationRequest)` is the public graph build facade.
The `compileArtifact(CompilerSettings)` CLI/test adapter resolves absolute paths
and forwards to it, preserving direct build/run commands. The request driver
parses the source, selects the ABI, protects the input from output aliases and
passes the ABI's `TargetLayout` explicitly to `ProgramAnalyzer`. Frontend
analysis can use a synthetic layout without resolving the host. The emitter
checks that the analyzed layout agrees with its ABI and returns the actual
`Path`. CLI option parsing, diagnostics and launch
belong to `Entry.kt`. Keep the existing `cloth [source.co] [output]` build form,
including its use of `--` before literal paths. `run --emit object` reports
C0015 before source or output work. A failed build stops launch even when the
destination already holds an executable. A launch failure reports C0016. The
host must be able to execute the selected ABI.
[Driver](../../../Compiler/src/main/kotlin/cloth/CompilerDriver.kt) ·
[CLI](../../../Compiler/src/main/kotlin/cloth/Entry.kt)

Both CLI adapters use [CompilerOptions](../../../Compiler/src/main/kotlin/cloth/settings/CompilerOptions.kt)
for shared build flags and [NativeProgram](../../../Compiler/src/main/kotlin/cloth/util/NativeProgram.kt)
for ordered arguments, inherited streams/cwd and interrupted-child cleanup.
[JavaCommandLine](../../../Compiler/src/main/kotlin/cloth/util/JavaCommandLine.kt)
recovers direct Windows Java-main arguments through GetCommandLineW and
CommandLineToArgvW, using JDK 25's foreign API and freeing the native argv with
LocalFree. Match the explicit main class or -jar application boundary; do not
reinterpret in-process arrays or independently expand JVM argument files.
This avoids the installed JDK launcher's native-code-page replacement before
main receives Unicode. The adapter passes UTF-16 units through unchanged;
Cloth's generated startup still owns program-argument Unicode validation.
The generated Gradle launchers enable native access. [Stage 6 evidence](../shuttle-build/references/stage-6.md)
distinguishes native host execution from foreign-object coverage.

## One-click example execution

Run `.\gradlew.bat :Compiler:run --args="run examples/Functions.co build/functions"`
or use `:Compiler:runExample` in IntelliJ's Gradle `application` group. The
example task prepends `run` to its `--args` and uses the same compiler entry
point. `CompilerSettings.getSourceFile()` owns the default example, and ABI
selection defaults to the native target. A nonzero child status fails JavaExec.
[Compiler Gradle tasks](../../../Compiler/build.gradle.kts)

## Source and verification

Use `ProgramEntryTest` for `run` argument forwarding, exit status, failed
rebuilds and object rejection. Use `DiagnosticsTest` for output aliases and
first-error precedence. The [run command record](../cloth-language/references/development.md#shared-compiler-driver-and-run-command-2026-11-06)
tracks native launch and Gradle task verification.

[Structs.co](../../../examples/Structs.co) executes on Windows x64/x86 and on
GNU Linux x64 under WSL. `StructPublicationTest` verifies executable IR and emits
objects across all nine profiles. Its foreign CLI object case requires no linker.
See the [struct publication record](../cloth-structs/references/publication.md).

[StructMethods.co](../../../examples/StructMethods.co) runs on the same native
targets. `StructMethodPublicationTest` verifies executable IR and objects across
all nine profiles and linker-free foreign CLI object emission. See the
[method publication record](../cloth-functions/references/method-publication.md).

Arbitrary integer widths have a [Stage 1 backend record](../cloth-numerics/references/arbitrary-width-backend.md)
and opt-in `:Compiler:probeIntegerWidths` task. Raw LLVM cases run in timed child
JVMs; a successful supervisor task can contain recorded crashes, link failures
and timeouts. Read the TSV statuses before making support claims. Preserve the
selected <=64-bit scalar / wider limb policy across targets, and distinguish
foreign objects from native oracle execution. Stage 2 now
[integrates new widths semantically](../cloth-numerics/references/arbitrary-width-types.md);
[Stage 3](../cloth-numerics/references/arbitrary-width-arithmetic.md) enables
source scalars 1..64 and implements production loop kernels through 65536 bits
(131072 internally), processing i32 chunks with i64 intermediates. They do not
rely on huge LLVM scalars or new compiler-rt wide-integer functions. Native
Windows x64/x86 oracle execution and all nine profiles' kernel objects pass;
foreign native execution remains untested.
[Stage 4](../cloth-numerics/references/arbitrary-width-storage.md) integrates
source wide storage/private calls, nullable/array carriers, providers and signed
main. [Stage 5](../cloth-numerics/references/arbitrary-width-output.md) adds full
decimal formatting with one private bounded i32/i64 helper, retaining native
small-integer/float fast paths; C4031 is retired. Full-cap recursive formatting
objects verify across all profiles and execute on Windows x64/x86. Counts and
native C widths remain unchanged.
The [final integer record](../cloth-numerics/references/arbitrary-width-integers.md)
adds 18 integrated cap measurements with all-profile object/assembly success and
supplementary GNU Linux x64 execution under Ubuntu WSL. Native AArch64/macOS/musl
remain untested. Use `:Compiler:probeIntegerProduction` for this source-to-object
matrix; raw Stage 1 probes deliberately remain separate historical experiments.

Inspect [TargetLayout](../../../Compiler/src/main/kotlin/cloth/target/TargetLayout.kt),
[NativeCABI](../../../Compiler/src/main/kotlin/cloth/emitter/NativeCABI.kt),
[CompilerSettings](../../../Compiler/src/main/kotlin/cloth/settings/CompilerSettings.kt),
[ToolchainResolver](../../../Compiler/src/main/kotlin/cloth/emitter/ToolchainResolver.kt),
and [NativeLinker](../../../Compiler/src/main/kotlin/cloth/emitter/NativeLinker.kt).

Use `NativeCABITest`, `TargetLayoutTest`, `CompilerSettingsTest`,
`ToolchainResolverTest`, and `WindowsX86Test` as relevant.
Distinguish fixture-based discovery, verified IR, object generation, native
linking, actual execution, and assembly inspection in reports. See
[Windows x86 validation](../cloth-language/references/usize-x86-validation.md)
for reproduced native ABI/assembly evidence and commands. Use
[target integers](../cloth-target-integers/SKILL.md) for width-sensitive semantics.

`ForIntegrationTest` runs maintained range/foreach examples as Windows x64 and
verified PE32 executables, checks x86 wide counter overflow/failures, and emits
verified loop IR plus objects for all nine supported ABI profiles. Its CLI object
test preserves linker-free publication. See the
[completed loop record](../cloth-control-flow/references/for-implementation.md#stage-5-regression-portability-and-feature-publication-2026-11-02)
for commands and limits; foreign objects do not establish native execution.

`DoWhileTest` reads maintained [DoWhile.co](../../../examples/DoWhile.co), checks
exact output and zero status on Windows x64/MSVC and x86/MSVC, and verifies it
plus a shared nested do/foreach AST as IR and nonempty objects across all nine
profiles. Native Windows ARM64/Linux/macOS do-while execution remains untested.
See the [completed do-while record](../cloth-control-flow/references/do-while-loops.md#stage-5-integration-portability-and-publication-2026-11-03)
for the example, full-suite evidence and publication boundaries.
