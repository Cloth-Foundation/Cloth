---
name: cloth-buffer-lifetimes
description: Preserve Cloth compiler buffer lifetimes, recursive value copies, argument and result snapshots, fresh array storage transfer, and proven early temporary cleanup.
---

# Cloth buffer lifetimes

Use this skill for the compiler's internal string/array storage. Read the
[array contract](../cloth-arrays/references/semantics.md) for confirmed copy and
optimization decisions. Ordinary class construction/destruction remains governed
by the [shared language semantics](../cloth-language/references/semantics.md).

## Preserve the storage contract

- Buffered arguments snapshot or transfer their values before later arguments
  execute. Later captured writes must not change a previously received value.
- Return paths copy outgoing strings/arrays recursively into caller storage before
  releasing the callee frame. Nested string bytes must outlive the callee too.
- A fresh, recursively independent array result may transfer its storage once.
  Storing it consumes freshness; ordinary binding reads, including `final`
  bindings, retain copy independence. Shape checks still apply during adoption.
- Conditional and block exits must preserve freshness and snapshot borrowed values
  before merging. Indexing a fresh temporary and extracting a stored row have
  different ownership facts. Keep facts associated with the actual value path.
- Early cleanup needs a closed static proof that no new buffer escapes through
  a live result or captured write. Save an arena checkpoint, reclaim only the
  allocation suffix on normal completion, and use frame cleanup on early return.
  Unrecognized effects keep frame lifetime. Lexical scope exit alone is no proof.
- Keep checked allocation/count arithmetic target-sized. Preserve source failures,
  evaluation order, surviving aliases, and independent arrays. This mechanism
  introduces neither GC, reference counting, nor automatic class destruction.

## Source and evidence

Inspect [EmitFunctionFrame](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitFunctionFrame.kt),
[EmitStringArena](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitStringArena.kt),
[EmitExpression](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt),
[ArrayTemporaryAnalysis](../../../Compiler/src/main/kotlin/cloth/emitter/types/ArrayTemporaryAnalysis.kt),
and the affected [array emitter](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitArray.kt).

Use `ArrayCopyElisionTest`, `ArrayTemporaryCleanupTest`, `StringFunctionTest`, and
the array assignment/shape suites. Allocation counters and poisoned freed memory
test observable independence, bounded temporary storage, and outgoing lifetimes;
an IR allocation count alone cannot establish those properties. Add the
[multiple-result skill](../cloth-multiple-results/SKILL.md) for result lists.

Recorded evidence and exact optimization boundaries are in the
[cleanup milestone](../cloth-language/references/development.md#conservative-early-array-temporary-cleanup-2026-10-31)
and [copy-elision milestone](../cloth-language/references/development.md#fresh-array-result-copy-elimination-2026-10-31).
Those probes executed on Windows. Shared LLVM emission is not evidence of native
execution on every target. Replaced/named storage and unproven captured writes
retain frame lifetime; general escaping computations remain unresolved.
