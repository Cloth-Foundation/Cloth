---
name: cloth-toolchains
description: Extend or troubleshoot Cloth build and run commands, target layouts, native C ABI profiles, object emission, linker and CRT discovery, or target-specific validation.
---

# Cloth targets and toolchains

Read [build options](../../../README.md#building-and-linking) and inspect current
settings before prescribing commands. The shared
[portability requirement](../cloth-language/references/semantics.md#portability)
requires Windows, Linux and macOS contracts; implementation evidence varies by
target. Use [program entry](../cloth-program-entry/SKILL.md) for startup behavior.

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

Shuttle is Cloth's project build system. TOML manifest loading, schema validation,
project/module discovery and project build orchestration belong in the repository's
`Shuttle` module. Source imports, semantic checking, ABI selection and native
emission belong to Compiler. The [Shuttle and imports plan](../../../todo/shuttle-and-imports-plan.md)
proposes a one-way `Shuttle -> Compiler` dependency through an explicit compiler
request API; Compiler must remain independent of TOML and Shuttle. This is
planned scope, not implemented project commands. The direct single-source CLI
below is the current supported interface.

`cloth run examples/Functions.co build/functions` builds and launches a program.
The first `--` in `run` separates build options from program arguments. The
launcher passes the remaining arguments in order and inherits the caller's
working directory and console streams. The default Windows JVM launcher needs
explicit escaping for literal quotes. Preserve empty, Unicode and spaced
arguments. Treat flags such as `--debug` after the separator as program arguments.

`compileArtifact(CompilerSettings)` is the shared driver for build and run. It
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
