---
name: cloth-buffer-lifetimes
description: Preserve Cloth compiler buffer lifetimes, recursive value copies, argument and result snapshots, fresh array storage transfer, and proven early temporary cleanup.
---

# Cloth buffer lifetimes

[Arbitrary integer Stage 3](../cloth-numerics/references/arbitrary-width-arithmetic.md)
introduces production wide-operation buffers as stable independent values.
Kernels reclaim bounded scratch on return; caller buffers currently use stack
storage. [Stage 4](../cloth-numerics/references/arbitrary-width-storage.md)
integrates inline binding/element bytes, independent read/argument snapshots,
caller-owned result copies and proven loop cleanup. `ValueType.indirect` classifies
wide transport separately from string/array `buffered` storage. Safe inline wide
stores cannot publish temporary pointers; unknown captures/indexed publication
retain conservative frame lifetime. Poisoned-memory and full-cap loop probes
cover these source paths; arbitrary unproven loops need not have bounded storage.

[Stage 5 integer text](../cloth-numerics/references/arbitrary-width-output.md)
uses stack-only conversion scratch and ordinary outgoing string copies. Array
measure/fill checkpoints must precede wide element decoding, then restore only
after append/traversal. Source and output buffers predate those checkpoints.
The existing closed source-loop proof is unchanged; unknown provider/capture
effects remain conservative. Poisoned-text and 512-element full-cap probes cover
escaping text and per-element scratch reuse.
The [final integer integration record](../cloth-numerics/references/arbitrary-width-integers.md)
publishes measured production cap programs, helper dependencies and native
execution scope without widening the closed source-loop cleanup proof.

Use this skill for the compiler's internal string/array storage. Read the
[array contract](../cloth-arrays/references/semantics.md) for confirmed copy and
optimization decisions. Ordinary class construction/destruction remains governed
by the [shared language semantics](../cloth-language/references/semantics.md).
Read [string member storage cleanup](references/string-member-storage.md) for
opt-in read-only/independent-result contracts, immutable descriptor snapshots,
member copy elision and proven arena/stack reclamation.

## Preserve the storage contract

- [String member calls](../cloth-strings/references/member-calls.md) capture the
  receiver before arguments and each argument before later operands, including
  strings extracted from mutable arrays. Opted-in read-only methods use loaded
  immutable string descriptors without redundant byte copies; unknown contracts
  retain snapshots. Protect storage until consumption, and reclaim arena/stack
  temporaries only through the [closed storage proof](references/string-member-storage.md).
  [Scalar string methods](../cloth-strings/references/scalar-methods.md)
  add independently allocated substrings and fresh character arrays. Array
  adoption consumes freshness once; recursive buffered returns copy into caller
  storage before frame cleanup. Nullable search positions use ordinary scalar
  carriers. The poisoned string-method probe in `ArrayTemporaryCleanupTest`
  verifies outgoing values and independent copies after receiver frames release.
- [String editing methods](../cloth-strings/references/editing-methods.md) preserve
  those operand snapshots across measure/fill and allocate one independent result,
  including unchanged/empty contents. Checked measurement precedes replacement
  payload reads; ordinary recursive outgoing copies precede frame release. The
  poisoned edit-result probe covers receiver/argument frames and captured
  publication. Unproven operand effects still retain conservative frame lifetime.
- [Unicode whitespace methods](../cloth-strings/references/whitespace-methods.md)
  copy trimmed byte intervals into independent storage, even for unchanged/empty
  results. Preserve caller-owned recursive return copies and captured publication;
  the poisoned whitespace-result probe covers source frame release and ordered
  outputs. `isBlank` adds no result buffer, while receiver snapshots retain the
  ordinary protected operand lifetime policy and closed cleanup proof.

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

Nullable strings, whole arrays and scalar/string elements execute under the
[buffer integration contract](../cloth-nullability/references/buffers.md).
Copy/snapshot/return operations must descend through present wrappers and skip
absent payloads. Nullable element storage uses byte presence flags; decode before
copying/formatting and encode before storing. Retained shape storage survives null.
Nullable wrappers conservatively copy rather than inheriting fresh-array ownership.
Use `NullableBuffersTest` and the nullable poisoned-memory probe in
`ArrayTemporaryCleanupTest`. Nullable rows have a
[focused lifetime/shape record](../cloth-arrays/references/nullable-rows.md):
copy live shape vectors independently into the destination arena and attach owned
descendants to the receiving vector. Never attach borrowed source storage or let
outgoing shape metadata point into a released frame.

Loop `break`/`continue` use shared crossed-region cleanup, releasing proven arena
suffixes before restoring stack checkpoints. Continue retains the traversal
snapshot; break finishes the whole-loop region. Buffered publication still
retains frame lifetime. Read [loop control](../cloth-control-flow/references/loop-control.md)
and use `LoopControlTest` plus the jump probes in `ArrayTemporaryCleanupTest`.

While loops apply independent closed proofs to repeated conditions and bodies.
Reclaim condition arena/stack temporaries after producing the bool, including the
final false test; preserve buffers published by condition captures. See
[while loops](../cloth-control-flow/references/while-loops.md), `WhileTest` and the
while probes in `ArrayTemporaryCleanupTest`.

Do-while Stage 4 enters the body first and proves body, trailing test and whole
loop independently. Continue releases safe body storage before testing; the
final false test releases safe condition storage too. Outward condition jumps
use shared crossed-region cleanup, and buffered returns copy before frame
release. Captured publication in either region retains frame lifetime without
preventing independent cleanup of the other. Read the
[execution/cleanup record](../cloth-control-flow/references/do-while-loops.md#stage-4-execution-and-proven-cleanup-2026-11-03)
and use `DoWhileTest` plus six probes in `ArrayTemporaryCleanupTest`: repeated
body/test and outward-jump arena peaks, poisoned publication/returns, and
2,000 full-cap wide-integer iterations preserving pre-loop storage.

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
