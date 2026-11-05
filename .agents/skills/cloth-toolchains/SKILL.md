---
name: cloth-toolchains
description: Extend or troubleshoot Cloth target layouts, native C ABI profiles, CLI build options, object emission, linker and CRT discovery, or target-specific validation.
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
- Keep build/runtime execution separate. A successful compiler status does not
  describe the emitted program's later status.
- The no-argument example source is a developer convenience, not a production
  compiler contract (user clarification, 2026-11-02). Do not pin its filename in
  tests; verify explicit source/output arguments and supported CLI options.

## One-click example execution

Run `:Compiler:runExample` from IntelliJ's Gradle `application` group, or use
`.\gradlew.bat :Compiler:runExample` from the repository root. No source path or
target argument is required: the development launcher calls `runCompiler` with
the supplied arguments, and `CompilerSettings.getSourceFile()` owns the current
default example. Native target selection remains the compiler's existing default.
Optional `--args` accepts normal compiler arguments, including source/output
overrides; output must be executable and runnable on the host.

[Compiler/build.gradle.kts](../../../Compiler/build.gradle.kts) owns the task and
its separate `development` source set;
[RunExample.kt](../../../Compiler/src/development/kotlin/cloth/development/RunExample.kt)
owns execution. The development Kotlin compilation is associated with main for
internal ABI access, inherits its dependencies/JVM native-access option, and stays
outside the production compiler JAR/distribution. Keep source/output selection in
sync with `Entry.kt` and `CompilerSettings`, including default source-adjacent
output and ABI-specific executable extensions. Launch only after compiler status
zero; a stale binary must never run following compilation failure. Forward console
input/output and the native exit status through JavaExec (nonzero status fails the
Gradle task). Object output is rejected without executing it. Normal `run` remains
compile-only. See the [verification record](../cloth-language/references/development.md#one-click-example-execution-2026-11-02).

## Source and verification

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
