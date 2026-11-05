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

Integer literal prefixes and decimal suffixes also receive `usize` /
`isize` context. Read the [numeric literal plan](../../../todo/numeric-literals-plan.md)
and [confirmed literal rules](../cloth-numerics/references/semantics.md#integer-literal-prefixes-design-confirmed-2026-11-03)
when changing this syntax. Existing target-dependent range checks and distinct
type identities remain intact. The [numeric literal implementation record](../cloth-numerics/references/numeric-literals.md)
and `NumericLiteralsTest` / `NumericLiteralsIntegrationTest` cover fixed/target
bounds, reused ASTs, Windows x64/x86 execution and all-profile object emission.

## Preserve the integrated contract

[Integer attachments](../cloth-numerics/references/integer-attachments.md)
specialize lazily for exact `isize`/`usize` receivers and the selected target.
Abs/clamp preserve those identities; signum returns fixed signed int32; bit counts
return target usize. Radix takes fixed int32 and preserves all receiver bits.
`IntegerAttachmentsTest` covers reused ASTs, native Windows x64/x86 and all-profile
objects; do not alias these receivers to same-width `CInt` registrations.

[Scalar string methods](../cloth-strings/references/scalar-methods.md) use exact
target `usize` positions and nullable search results. The attachment registry
specializes count signatures by width and every lookup selects the compilation
target. Reused x86/x64 ASTs must retain contextual literal bounds and distinct
`usize` identity without caching host-width facts.

Arbitrary fixed widths have a completed
[implementation record](../cloth-numerics/references/arbitrary-width-integers.md)
covering values/text through 65536, full-bit index validation and measured target
layouts. Source fixed-integer support does not expand target/native counts.
`MACHINE_INTEGER_WIDTHS` and `MAX_TARGET_INTEGER_BITS` preserve target/C ABI
validation and the largest target count independently of the fixed 65536-bit cap.
Never substitute `MAX_INTEGER_BITS` for usize bounds or admit arbitrary pointer
widths by using `FIXED_INTEGER_WIDTHS`. The new suite checks reused 32/64-bit ASTs.

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

Loop publication adds `ForIntegrationTest`: native PE32 checks every integer
counter identity/width and dynamic uint64 multiplication overflow, while one AST
emits loop IR/objects across all supported 32/64-bit ABI profiles. Target usize
foreach counts and counter types must stay local to that compilation. See the
[loop portability evidence](../cloth-control-flow/references/for-implementation.md#stage-5-regression-portability-and-feature-publication-2026-11-02).
