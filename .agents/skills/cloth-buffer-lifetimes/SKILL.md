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
- Stage 4 loop body, stepper and whole-loop proofs permit safe local definitions
  and scalar writes, but reject buffered/indexed stores, unknown operations and
  captured publication. Reclaim only proven regions: arena suffix first, then
  stackrestore dynamic literals. Foreach body checkpoints precede receiver copying;
  source snapshots remain alive throughout traversal. Buffered/unknown effects
  retain frame lifetime. Early exits use existing copy/return cleanup, without
  claiming arbitrary-loop bounded memory. See the
  [stage record](../cloth-control-flow/references/for-implementation.md#stage-4-array-foreach-and-lifetime-integration-2026-11-02).
- For array foreach, preserve the confirmed once-per-entry independent
  contents/shape snapshot across traversal. Original-source changes must not alter
  later elements visited; copy elision requires the same independence and lifetime
  proofs. Each element binding receives a mutable independent value, initialized
  anew; row writes cannot mutate the snapshot, and saved rows must survive later
  iterations. Read the
  [loop contract](../cloth-control-flow/references/semantics.md#for-loops-and-ranges-design-confirmed-implementation-deferred)
  before extending iteration cleanup or source transfer.

## Source and evidence

Inspect [EmitFunctionFrame](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitFunctionFrame.kt),
[EmitStringArena](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitStringArena.kt),
[EmitExpression](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt),
[ArrayTemporaryAnalysis](../../../Compiler/src/main/kotlin/cloth/emitter/types/ArrayTemporaryAnalysis.kt),
and the affected [array emitter](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitArray.kt).

Use `ForEachTest`, `ArrayCopyElisionTest`, `ArrayTemporaryCleanupTest`, `StringFunctionTest`, and
the array assignment/shape suites. Allocation counters and poisoned freed memory
test observable independence, bounded temporary storage, and outgoing lifetimes;
an IR allocation count alone cannot establish those properties. Add the
[multiple-result skill](../cloth-multiple-results/SKILL.md) for result lists.

The completed [loop stage record](../cloth-control-flow/references/for-implementation.md#stage-5-regression-portability-and-feature-publication-2026-11-02)
records native Windows x64/x86 example execution and all-profile loop IR/objects.
These extend portability evidence without expanding the closed cleanup proof
or claiming bounded storage for unproven buffered/captured writes.

Recorded evidence and exact optimization boundaries are in the
[cleanup milestone](../cloth-language/references/development.md#conservative-early-array-temporary-cleanup-2026-10-31)
and [copy-elision milestone](../cloth-language/references/development.md#fresh-array-result-copy-elimination-2026-10-31).
Those probes executed on Windows. Shared LLVM emission is not evidence of native
execution on every target. Replaced/named storage and unproven captured writes
retain frame lifetime; general escaping computations remain unresolved.
