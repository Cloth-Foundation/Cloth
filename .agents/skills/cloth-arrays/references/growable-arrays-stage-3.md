# Growable arrays Stage 3: executable append and reserve

Status: complete (2026-11-08) for the executable slice below.
[Stage 4](growable-arrays-stage-4.md) extends this slice to stored fields and
cross-method selection observations; [Stage 5](growable-arrays-stage-5.md) adds
insert/removeAt/clear and invalidated child bounds.
[Stage 6](growable-arrays-stage-6.md) executes rectangular mutators and value
integrations. This record preserves Stage 3's exit scope
and verification snapshot. The [contract](growable-arrays.md) owns intended
semantics and the [plan](../../../../todo/growable-arrays-and-lists-plan.md) owns
the remaining stage boundaries.

## Executable surface

`append(value)` and `reserve(minimum)` execute on one-dimensional growable local
bindings, value parameters and temporary receivers. `append` independently receives
one exact element and returns void. `reserve` takes target `usize`, changes neither
contents nor length, and an already satisfied request leaves storage unchanged.
`:=` and `final` retain binding-only immutability. Literal-only inference still
produces fixed arrays, and growable copies/results keep `T[]` identity.

```cloth
fn<int[]> appended(final int[] values, int value) {
    values.append(value)
    return values
}
fn main() {
    def<int[]> values := [1, 2]
    values.reserve(8)
    values.append(3)
    def copy = values
    copy.append(4)
    println(values)                // [1, 2, 3]
    println(copy)                  // [1, 2, 3, 4]
    println(appended(values, 5))   // [1, 2, 3, 5]
}
```

Supported elements include scalars through the 65536-bit integer cap, nullable
values, structs containing buffers/nullable shape witnesses, enums and class
identities. Class copying retains identity; array cleanup does not delete objects.
Nullable whole arrays need the existing presence proof. Read-only default views,
exact element/index identities and fixed receiving extents keep their ordinary
checks. A parameter's first mutation copies across its caller/callee owner boundary;
subsequent growth uses its own capacity. Returns copy into caller storage before
callee cleanup. Broadcast, positional reception, captures and foreach snapshots
retain independent values.

Direct class/struct/array-element field receivers, rectangular array receivers,
`insert`, `removeAt` and `clear` retain source-located **C4032** before output/linking.
An extracted independent growable row is one-dimensional and can append/reserve;
changing an embedded row's shape remains C3107. Efficient direct field growth is
Stage 4, the other mutators Stage 5 and rectangular mutation Stage 6. Generic List
still requires its separate generic-type facility.

## Evaluation, storage and failure order

`EmitArrayMutation.supported` is shared by backend validation and emission. Stored
receivers select a local/parameter slot before explicit arguments, then load its
current value when mutation starts. Argument code may grow or replace that binding;
the outer operation applies to the resulting current value. Nullable receiver
proofs are checked by Stage 1 and runtime guards protect presence before/after
arguments. A temporary receiver is independently materialized before its argument.

All indirect operands, including wide integers and nullable/struct payloads, copy
into a dedicated per-call operand arena before storage can move. Initialization
copies the received value into the new live slot before publishing the length.
This protects `values.append(values[0])`. After publication the operand arena
releases; temporary receiver capacity releases there too. Entry-initialized arena
heads are reusable across loops, and ordinary frame cleanup handles early returns.

A closed non-publication proof permits producer arena/stack scratch to release
after its received value has been copied into the operand arena. An unproven
captured write or source method retains conservative frame lifetime. The mutator
itself remains `MUTATING_RECEIVER`; it never enters the read-only member contract
or a whole-loop temporary suffix proof. In particular, a source-level growth can
detach a node that predates an expression checkpoint. Do not reclaim a surrounding
arena suffix across such publication without a separate proof.

The Stage 2 typed helpers check length addition, target stride/alignment/header
limits and capacity before allocation/payload access. Growth transfers live inline
elements and their element-arena heads, reparents moved heads and immediately
frees superseded capacity. Only the new element payload is independently copied.
Allocation failures and unknown fixed receiving counts terminate at the first
executed failure; later source effects and normal PEC completion do not execute.

## Selected indexed places and ordinary struct receivers

Capacity movement must preserve existing indexed-write and method receiver
semantics. A private `EmitBindings.ArraySelection` holds a descriptor view plus
a flag indicating whether it still follows its binding. `publishArrayMutation`
updates active views after growth; a whole-binding assignment disables following
that binding's subsequent mutations. The selected original value remains alive
under the existing conservative replacement lifetime.

`EmitPlaces` evaluates each source index once and checks the original bound before
the RHS or explicit method arguments. Inline projections rebase through the saved
selection after growth. Once a projection enters a separate child buffer, its
original selected storage stays independent of root capacity. Compound field writes
snapshot the old operand. Struct methods resolve the selected address after their
explicit arguments; their private ABI is unchanged. Class-backed receiver replay
continues through the existing resolver.

Indexed reads with a potentially publishing index retain the same selection and
original bound, then read its live element contents after the index completes.
Changing an element in the index is still visible. Replacing the whole binding
selects the original value, while appending a new index cannot widen the read's
original bound. Selection scopes close during both continuing and abrupt emission,
so another branch cannot inherit an uninitialized view. These are compiler views,
not user-visible references or aliases between array values.

## Source routing

- [EmitArrayMutation](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArrayMutation.kt)
  owns the native support boundary and append/reserve dispatch to Stage 2 helpers.
- [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt),
  [EmitFunctionFrame](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctionFrame.kt)
  own receiver/operand capture, scoped scratch and reusable operand-arena cleanup.
- [EmitBindings](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitBindings.kt),
  [EmitPlaces](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitPlaces.kt),
  [EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
  own selected versions, mutation publication, projection replay and method dispatch.
- [EmitArray.validateIndex](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArray.kt)
  checks original bounds without retaining an interior address. The committed
  `SemanticModel.storedElementWrites` plans cover ordinary stored array roots as
  well as struct receiver paths and participate in candidate fact copies.
- [Stage 2](growable-arrays-stage-2.md) retains the descriptor, resource callback,
  typed reserve/initialize/copy/release and independent hidden-witness invariants.

## Verification

The 25 new source-level tests are
[GrowableArrayAppendTest](../../../../Compiler/src/test/kotlin/cloth/GrowableArrayAppendTest.kt)
and [GrowableArrayAppendStorageTest](../../../../Compiler/src/test/kotlin/cloth/GrowableArrayAppendStorageTest.kt).
They use normal Cloth source compilation. The shared Stage 2 probe harness only
adds real allocator/failure/poison instrumentation, live-byte observers and memcpy
movement counters; production mutation lowering is exercised by source calls.

Focused source probes verify:

- 4,096 scalar appends use one initial owner plus 13 growth allocations and move
  16,380 existing int32 bytes, with balanced cleanup after function exit.
- 1,000 string-producing appends retain exactly the live slot payloads after every
  call: 3,002 total allocations including the initial owner and reserve, without
  accumulating producer or argument snapshots.
- Self-element string append copies two received payload snapshots per new element,
  moves only live descriptors during growth and preserves a saved string after
  later replacement. Existing string payloads are not cloned on relocation.
- Repeated temporary append/reserve calls return to zero live bytes on every
  iteration even though their enclosing loop keeps conservative lifetime.
- 512 full-cap integer arguments restore temporary scratch after each protected
  snapshot; only reserved live array capacity remains, and all 514 allocations
  balance at function exit.
- Buffered struct arguments, absent-field witnesses, enums and nullable class
  identities survive poisoned source frames and capacity movement. Selected writes,
  nested child-buffer writes and ordinary struct receivers survive poisoned roots.
- Injected failure covers initial array ownership, producer results, operand
  snapshots, capacity growth and new slot payloads. Reserve overflow stops before
  the injected allocator failure or later source effects.

The native fixtures and the complete example above compile and execute on Windows
x64 and x86. The example prints the three documented independent values and exits
with PEC 0 at both widths; its source and binaries are retained under
`build/growable-arrays-stage3/documented-example*`.

Both new suites also verify LLVM IR and emit nonempty objects for the same source
fixtures on all nine existing profiles:

| Profile | Evidence |
| --- | --- |
| `x86_64-pc-windows-msvc` | Native execution, verified IR and objects. |
| `i686-pc-windows-msvc` | Native execution, verified IR and objects. |
| `aarch64-pc-windows-msvc` | Verified IR and objects. |
| `x86_64-unknown-linux-gnu` | Verified IR and objects. |
| `aarch64-unknown-linux-gnu` | Verified IR and objects. |
| `x86_64-unknown-linux-musl` | Verified IR and objects. |
| `aarch64-unknown-linux-musl` | Verified IR and objects. |
| `x86_64-apple-darwin` | Verified IR and objects. |
| `aarch64-apple-darwin` | Verified IR and objects. |

No native Linux, macOS or Windows ARM64 execution is claimed.

Full offline regression passed on 2026-11-08 in 8m 31s:

| Project | Tests | Suites | Failures / errors / skips |
| --- | ---: | ---: | --- |
| Compiler | 1,719 | 157 | 0 / 0 / 0 |
| Shuttle | 57 | 7 | 0 / 0 / 0 |

```powershell
.\gradlew.bat :Compiler:test :Shuttle:test --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

The 25 new tests also passed the focused selection with `ArrayAssignmentTest` and
`StructMethodEmissionTest` before this full run. XML reports are preserved under
`build/growable-arrays-stage3/compiler-test-results/` and `shuttle-test-results/`;
`focused-test-results/` contains the two new suites' XML from the final full run.
Archived full reports match their source XML hashes. Existing typing, nullable,
callable, producer, provider, output, foreach, field and storage regressions pass.

An initial full run failed only because two design-only `override` sketches in
[ISize.co](../../../../examples/ISize.co) were executable source. Their text is
preserved as comments; the final run includes that example's successful CLI test
on both Windows widths. The earlier report remains separately identified under
`pre-example-fix/compiler-test-results/`.

All 12 changed skills pass the full skill-creator validator with temporary
PyYAML 6.0.3. Publication checks pass for 22 documents, resolving 972 relative links
and 130 anchors with balanced code fences and clean whitespace. The 14 changed
source/test/example files also pass whitespace checks. All 18 tasks through
Stage 3 are checked, while Stages 4-8 remain unchecked. Validation dependencies
and scratch files are removed before completion; the evidence files remain under
`build/growable-arrays-stage3/`.
