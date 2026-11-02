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

## Source and verification

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
