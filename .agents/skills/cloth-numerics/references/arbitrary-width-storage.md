# Arbitrary integer widths: Stage 4 storage and composition

Stage 4 complete, 2026-11-03. This record owns the storage/call/provider stage
of the [plan](../../../../todo/arbitrary-integer-widths-plan.md), following the
[arithmetic kernels](arbitrary-width-arithmetic.md). The
[accepted numeric contract](semantics.md#arbitrary-fixed-integer-widths-design-confirmed-2026-11-03)
and [representation policy](arbitrary-width-backend.md#selected-representation-and-lowering-policy)
remain authoritative. Float formats and 32-bit defaults are unchanged.

Historical text boundary: the C4031 statements below describe Stage 4. Later
[Stage 5](arbitrary-width-output.md) integrates wide decimal output and retires
that code. Storage and lifetime invariants below remain current.

## Source support and remaining boundary

Signed/unsigned `int<N>` for every N in 1..65536 now compose with bindings,
updates, arithmetic, casts, conditionals, ordinary/deferred functions, recursion,
optional/default/named arguments, nullable payloads, multiple results, arrays,
rows, indexing, foreach, numeric ranges, while loops, metadata and signed main.
Exact typed compatibility, contextual literal bounds and first-error behavior
remain unchanged. This example executes and prints `true`:

```cloth
fn<int<129>> next(int<129> value) = value + 1
fn main() {
    def<int<129>> large = 18446744073709551616
    def copy = large
    large = next(large)
    println(copy + 1 == large)
}
```

**Stage 5 still owns decimal text above 64 bits.** Printing a wide numeric
value, wide nullable value, nested wide array, interpolation containing such a
value or its `::str` fails with located C4031 before LLVM/output/linker work.
These types themselves are no longer gated. Printing their boolean comparison,
metadata, sizes or an explicitly narrowed integer works. Skipped/unused text
requests remain checked. Bare type metadata does not request numeric formatting.

## Representation and independent values

- [CInteger](../../../../Compiler/src/main/kotlin/cloth/data/CInteger.kt) uses
  exact scalar iN values/storage through 64 bits. Above 64, value representation
  is a private pointer to stable canonical bytes and physical storage is an inline
  array of native unsigned limbs: ceil(N/64) i64 on 64-bit profiles, ceil(N/32)
  i32 on Windows x86. Padding is zeroed and never part of comparison/value bits.
- [ValueType.indirect](../../../../Compiler/src/main/kotlin/cloth/data/ValueType.kt)
  classifies private transport separately from `buffered`. A wide integer remains
  a fixed scalar, not a source array/string. Arithmetic uses stack scratch, without
  allocating a heap object per operation. Nullable types propagate transport.
- [EmitStoredValue](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitStoredValue.kt)
  bridges inline bytes and immutable expression snapshots. A binding/element read
  copies into independent stack storage before later operand/argument effects.
  Writes copy into the destination bytes. Whole limb arrays are never loaded as
  huge SSA aggregates to pass to helpers. Memcpy uses conservative alignment.
- Nullable wide storage is `{ byte presence, inline limb array }`, with target
  padding. Values carry `{ i1 presence, pointer snapshot }`. Presence dominates
  payload access/copy; absent stored payload bytes are zeroed. Nullable equality
  compares present integer contents, never pointer identity. Existing bool/half
  storage and nullable row shape handling remain unchanged.

## Private calls, results and lifetime

[EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitFunctions.kt)
passes wide arguments as pointers to completed snapshots. Each callee receives
an independent inline parameter binding. This preserves written argument order,
later captured mutations, named placement, recursion and omission defaults; omitted
pointer placeholders are not decoded or dereferenced. Omission flags follow any
hidden result-arena parameter as before.

Wide results use the existing caller-supplied destination arena, returning a
pointer (or small nullable/multiple-result carrier). This refines the Stage 1
private indirect-result design using the established caller-owned copy mechanism.
[EmitFunctionFrame](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitFunctionFrame.kt)
and [EmitStringArena.copyValue](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/EmitStringArena.kt)
copy outgoing bytes before releasing the callee arena/frame. Multiple results
copy each escaping slot independently. No public ABI or C interop is promised.

Array copies include wide integer/nullable payload bytes inline with the array
data; they do not allocate one heap block per integer element. Recursion into
string/nested-array storage continues under existing rules. Row extraction,
assignment, nullable rows and foreach snapshots retain independent values/shapes.

[ArrayTemporaryAnalysis](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/ArrayTemporaryAnalysis.kt)
recognizes wide temporary production while preserving the closed escape proof.
Inline wide writes cannot publish temporary pointers. Safe loop iterations and
conditions release arena suffixes and restore stack checkpoints; range membership
checks and default steppers also reclaim repeated snapshots. Counter bindings
remain in entry storage; saved bounds remain outside iteration checkpoints.
Captured/indexed/buffered publication still conservatively retains frame lifetime.
This stage does not claim bounded memory for arbitrary unproven loops.

## Layout, indices, providers and entry

`::type::width` remains logical N as target usize; `::size` uses the emitted
module's storage allocation size. The same storage type drives nullable layout,
array stride, checked byte allocation and copies. Observed Windows layouts:

| Storage | x64 bytes | x86 bytes |
| --- | ---: | ---: |
| int<65> | 16 | 12 |
| int<65>? | 24 | 16 |
| int<65536> | 8192 | 8192 |
| int<65536>? | 8200 | 8196 |

These are target facts, not packed-bit guarantees. Providers emit full-width
min/max through immutable limb constants; nullable type bounds return plain exact
payload types. Metadata spelling/equality and receiver effects are preserved.
Descriptors never retain context-bound LLVM handles or target-local sizes.

[EmitArray.checkedAddress](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/EmitArray.kt)
compares the entire original wide index against a zero-extended target-sized
bound before narrowing for addressing. Negative signed bit patterns exceed every
such bound. Large indices with low zero bits cannot alias element zero, and
indexed assignment validates before evaluating the RHS. Allocation/count limits
remain target usize and signed pointer index limits, independent of 65536.

[EmitEntryPoint](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitEntryPoint.kt)
supplies a result arena for wide signed main, loads low native C-int bits before
releasing result/argument storage, then emits ordinary PEC and returns that status.
Unsigned main results remain rejected. Object emission requires no main and
introduces no external integer ABI.

## Verification and limits

`ArbitraryIntegerStorageTest` has eight integration tests, executing on native
Windows x64/x86. It covers independent reads/writes, later captured effects,
recursion, defaults/named arguments, ordered/conditional results, nullable values,
array/row/foreach copies, empty arrays, full-cap operations/ranges, size/metadata
effects, wide main normalization and full-bit bounds checks. One reused source
AST verifies and emits full-cap function/storage objects on all nine profiles;
IR checks prohibit wide LLVM scalars and constrain module size. Decimal text
preflight covers direct, nullable, recursive-array, interpolation and unused paths.

Three new `ArrayTemporaryCleanupTest` probes run on native Windows x64. Freed
memory is poisoned; outgoing wide/nullable/array values survive callee release.
A 20000-iteration loop has exactly 20000 result allocations with a 32-byte peak,
ending at zero live bytes; arithmetic itself allocates no heap blocks. Full-cap
range/while counters run 1000 trips each while restoring repeated stack snapshots.
The existing frontend suite retains 13 tests, advancing C4031 fixtures to actual
text requests and verifying that wide signatures/metadata/storage now emit.

Focused verification passes 61 tests across these three suites. The dated
[development entry](../../cloth-language/references/development.md#arbitrary-integer-widths-stage-4-storage-and-composition-2026-11-03)
records the final full regression result: 1069 tests across 84 suites pass with
zero failures/errors/skips (4m39s). Skill metadata/local links and whitespace
checks pass, and workspace scratch is clean. Foreign targets have IR/object evidence,
not native execution at that milestone. Later
[Stage 5](arbitrary-width-output.md) completes decimal output, and the
[final publication](arbitrary-width-integers.md) adds production resource evidence
and Linux x64 GNU execution. Native AArch64/macOS/musl remain untested.
