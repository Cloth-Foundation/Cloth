---
name: cloth-target-integers
description: Design, explain, or extend Cloth usize and isize identity, target-dependent literal bounds and casts, buffer counts, metadata measurements, or signed main integration.
---

# Cloth target-sized integers

Read [confirmed usize/isize semantics](references/semantics.md). The completed
[usize migration](../cloth-language/references/usize-implementation.md) and
[isize implementation](../cloth-language/references/isize-implementation.md)
retain detailed stage evidence and boundaries. Use
[toolchains](../cloth-toolchains/SKILL.md) for target selection and ABI validation.

## Preserve the integrated contract

- usize is unsigned and isize signed, each matching the compilation target's
  pointer width. Both remain distinct from fixed integers of the same machine
  width and from each other. Explicit casts cross those identities.
- Derive literal ranges, operations, casts, LLVM storage and printing from the
  selected TargetLayout. One AST can serve multiple compilations; do not cache
  host-width type facts or turn target-sized types into fixed-width aliases.
- Measurements/counts remain usize. Bounds retain the receiver's own numeric type.
  Native descriptors, allocation arithmetic, loops and metadata use target-sized
  counts; known flags for inner dimensions are separate from count values.
- Every static extent fits target usize, including type-only queries. Buffer
  allocation also respects checked byte arithmetic and LLVM's pointer-index
  bounds. Representable dimensions do not promise an allocation will succeed.
- Signed arithmetic/division/casts retain shared integer rules. isize is allowed
  as a main result; usize and all other unsigned main results are rejected. The
  older usize-main extension is superseded. See
  [program entry](../cloth-program-entry/SKILL.md) for native C int normalization.

## Source and verification

Inspect [CUSize](../../../Compiler/src/main/kotlin/cloth/data/CUSize.kt),
[CISize](../../../Compiler/src/main/kotlin/cloth/data/CISize.kt),
[CInteger](../../../Compiler/src/main/kotlin/cloth/data/CInteger.kt),
[BufferLayout](../../../Compiler/src/main/kotlin/cloth/data/BufferLayout.kt),
and [TargetLayout](../../../Compiler/src/main/kotlin/cloth/target/TargetLayout.kt).
Examples: [USize.co](../../../examples/USize.co),
[USizeX86.co](../../../examples/USizeX86.co), [ISize.co](../../../examples/ISize.co).

Relevant suites: `USizeTest`, `USizeIntegrationTest`, `ISizeTest`,
`ISizeBackendTest`, `ISizeIntegrationTest`, `BufferCountsTest`, `TargetLayoutTest`,
`SharedIntegersTest`, and `WindowsX86Test`. Recorded integration includes real
Windows x64/x86 execution and verified IR for supported foreign 64-bit profiles.
See [x86 ABI and assembly evidence](../cloth-language/references/usize-x86-validation.md).
Other 32-bit targets remain unsupported; IR checks do not establish foreign native
execution. Recheck source and rerun relevant suites for fresh verification claims.
