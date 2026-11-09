# Growable arrays Stage 4: efficient stored field growth

Status: complete (2026-11-08) for the executable slice below.
[Stage 5](growable-arrays-stage-5.md) adds insert/removeAt/clear, independent removed
results and invalidated child bounds. [Stage 6](growable-arrays-stage-6.md) executes
rectangular mutators and retained shape/value integrations. This record preserves Stage 4's exit scope
and verification snapshot. The
[contract](growable-arrays.md) owns intended semantics and the
[plan](../../../../todo/growable-arrays-and-lists-plan.md) owns the remaining gates.

## Executable surface

One-dimensional `append(value)` and `reserve(minimum)` now execute on stored
class/struct array fields, nested aggregate fields, fields inside array elements
and paths through implicit or explicit `self`. Locals, value parameters and
independent temporaries retain [Stage 3](growable-arrays-stage-3.md) support.
Binding-only `:=`/`final`, exact elements and ordinary nullable presence proofs
remain in force. This stage changes native lowering, not the Stage 1 typing rules.

[GrowableArrayFields.co](../../../../examples/GrowableArrayFields.co) is the
maintained collection fixture. Its private field uses ordinary Cloth methods:

```cloth
pub typedef<class> Words (def<string[]> items ?: []) {
    pub mt add(string value) { items.append(value) }
    pub mt reserve(usize minimum) { self.items.reserve(minimum) }
    pub mt<string[]> snapshot() = items
    pub mt<usize> count() = items::size
}
```

Aliases of a Words object observe the same field. An exported array is an
independent value that survives subsequent growth and explicit deletion of the
object. Class identities stored as elements retain explicit deletion; cleaning
up an array does not destroy those objects. Destructors may mutate their own
fields under the existing direct-self destruction permission.

`insert`, `removeAt`, `clear` and growth of an array whose own rank exceeds one
still stop at source-located **C4032** before output/linking. Shape-changing
embedded-row calls remain **C3107**. Reserving a selected one-dimensional embedded
row executes because it changes neither length nor shape. Generic List, removal
semantics and whole-row growth remain their separate planned stages.

## Receiver selection and publication

`EmitPlaces.withArrayMutation` extends the existing stored-place machinery.
It captures a class identity and each source index once before arguments and
checks the selected path. After protected argument capture, it resolves the
selected place again, checking lifetime, nullable presence and saved bounds before
reading the array's current descriptor. Rebinding a variable holding a class
identity cannot redirect an already selected object receiver.

Local indexed paths retain the originally selected array value and original
bounds. Inline projections follow capacity movement; replacing a containing
array value does not select that replacement. Changes to the selected inline
element itself remain visible. Class-backed paths retain their existing current
field replay, including after field replacement/compaction. Ordinary struct
method receivers use these same selections and the existing private callable ABI.

Append/reserve store their resulting descriptor directly into the resolved slot.
They do not call whole-field replacement, recursive snapshot or root-field
compaction. `EmitArrayStorage.ownerArena` obtains a stored field's actual existing
owner head, including a child head moved by parent-array relocation. Growth
therefore preserves spare capacity and transfers existing payload ownership
instead of copying the live field on every call. A borrowed field uses the
enclosing owner as its fallback. Ordinary standalone parameter/local mutation
keeps its Stage 3 frame boundary and initial arena-crossing copy.

Whole-field assignment still independently receives a value and resets capacity
to its live length. Other nested buffered writes still use the established root
compaction path where required. This stage optimizes append/reserve publication;
it does not promise that arbitrary indexed replacement preserves capacity.

## Selections across separately emitted methods

Stage 3's emitter-local views alone cannot follow growth performed inside a
separately emitted struct method. A potentially publishing indexed read, write or
method receiver can now register a private selection observation with the selected
array owner. The view retains the original bound separately and reads live element
contents after index effects. A closed non-publication proof omits observations
for simple writes whose indices and RHS cannot move storage.

The private owner control record is now `{ parentHead, liveLength, capacity,
observations }`. Array value descriptors and the string layout are unchanged.
Each observation contains a next link, its current owner and the address of a
compiler descriptor view. It belongs to an explicit separate invocation arena;
it does not retain the array or change value-copy independence.

Before freeing superseded capacity, reserve transfers observations to the new
owner and updates their views. Initialize updates views only after copying the
new element and publishing the live length. Source mutation also transfers its
selected observations across an initial frame-ownership copy; ordinary private
reserve/copy operations keep independent values and do not transfer observations
across an unrelated arena copy. Fresh copies start with an empty observation list.

Normal scope exit unregisters/frees observations. Frame cleanup handles abrupt
returns. Reusing a static site after a deferred return or loop jump first clears
its previous observation arena. Owner destruction nulls observation back-links
before releasing capacity, so later observer cleanup never follows a freed owner.
Class-backed paths then replay current storage; an independent saved field read
uses its own copied owner. Never use an invalidated view to recover freed contents.
This is explicit scoped resource bookkeeping, not GC or reference counting.

Protected operand arenas and producer scratch retain Stage 3's lifetime rules.
Array mutators and unknown source methods stay outside surrounding arena-suffix
and whole-loop cleanup proofs. Source growth can detach an older allocation;
observations do not authorize reclaiming that enclosing suffix. Unproven captured
publication still retains conservative frame lifetime.

## Source routing

- [EmitPlaces](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitPlaces.kt)
  owns saved paths, selected local versions, class-backed replay and direct
  mutation publication. Its local path walker is shared by stored writes,
  ordinary struct receivers and array mutators.
- [EmitArrayStorage](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArrayStorage.kt)
  owns actual owner lookup, typed growth, observation registration, relocation,
  notification and cleanup. Existing checked target size/alignment limits include
  the enlarged control record before allocation or payload access.
- [EmitBindings](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitBindings.kt)
  and [EmitFunctionFrame](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctionFrame.kt)
  own entry-allocated views and separate reusable observation arenas.
- [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
  captures operands, revalidates stored targets and observes publishing indexed
  reads while preserving their original bounds.
- [EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
  enables selection movement wherever the checked program contains array mutation,
  including callers whose only effect is invoking a mutating method.
- [EmitReceiverAccess](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitReceiverAccess.kt)
  and [EmitClassStorage](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassStorage.kt)
  retain checked object replay, ordinary copies, replacement and compaction.
  [EmitArrayMutation](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArrayMutation.kt)
  shares the remaining native support boundary with validation.

## Verification

The new source suites are
[GrowableArrayFieldTest](../../../../Compiler/src/test/kotlin/cloth/GrowableArrayFieldTest.kt)
and [GrowableArrayFieldStorageTest](../../../../Compiler/src/test/kotlin/cloth/GrowableArrayFieldStorageTest.kt).
Native fixtures use ordinary Cloth compilation. The shared Stage 2 probe harness
only instruments real allocations, live bytes, relocation memcpy and poisoned
frees, and replaces source metric stubs with counter reads.

Measured probes establish:

- 4,096 scalar appends through a private class field use 13 capacity growths
  after initialization and move 16,380 bytes (4,095 existing int32 elements).
- After reserve, 512 string appends through an ordinary class method use exactly
  1,024 received-payload allocations. After every call only the new live payload
  remains; neither previous strings nor the array are copied on each append.
- Reserving a child string array, moving its class-owned parent and then appending
  100 strings uses exactly 200 received-payload allocations. Existing payloads and
  child spare capacity survive reparenting.
- 1,024 calls through local array-element struct receivers use one short-lived
  selection observation per call and reuse the already reserved child field.
- Separately emitted methods preserve live indexed reads, selected writes and
  receiver addresses through poisoned capacity movement. Class root replacement
  invalidates old observations before freeing its buffers.
- Early method returns unregister observations in surviving caller-owned arrays.
  Deferred returns, continue and break safely reuse static observation sites.
  Injected failures cover initial values, observation registration and growth
  before later source effects.

The final focused selection passes **86 tests across seven suites**, including
**25 new tests** (15 source behavior tests and ten storage/failure probes), with
zero failures/errors/skips. Its XML is retained under
`build/growable-arrays-stage4/focused-test-results/`.
The cleanup/layout selection additionally passes **103 tests across four suites**,
including `ArrayTemporaryCleanupTest` and `BufferCountsTest`, with zero
failures/errors/skips. Fixed byte/peak expectations now include the fourth
array-control word; allocation counts and cleanup assertions retain their checks.
Its XML is retained under `layout-cleanup-test-results/` and matched the source
XML hashes when archived.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.GrowableArrayFieldTest --tests cloth.GrowableArrayFieldStorageTest --tests cloth.GrowableArrayTypingTest --tests cloth.GrowableArrayAppendTest --tests cloth.GrowableArrayAppendStorageTest --tests cloth.ClassValueIntegrationTest --tests cloth.StructMethodEmissionTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

| Profile | Evidence |
| --- | --- |
| `x86_64-pc-windows-msvc` | Native source/probe execution, verified IR and objects. |
| `i686-pc-windows-msvc` | Native source/probe execution, verified IR and objects. |
| `aarch64-pc-windows-msvc` | Verified IR and objects. |
| `x86_64-unknown-linux-gnu` | Verified IR and objects. |
| `aarch64-unknown-linux-gnu` | Verified IR and objects. |
| `x86_64-unknown-linux-musl` | Verified IR and objects. |
| `aarch64-unknown-linux-musl` | Verified IR and objects. |
| `x86_64-apple-darwin` | Verified IR and objects. |
| `aarch64-apple-darwin` | Verified IR and objects. |

The maintained example also executes through the current compiler CLI at both
Windows widths, printing `[first]` and `[first, second, independent]` followed by
PEC 0. Native binaries are retained under
`build/growable-arrays-stage4/GrowableArrayFields-x64*` and `GrowableArrayFields-x86*`.

No native Linux, macOS or Windows ARM64 execution is claimed.

Full offline regression passed on 2026-11-08 in **8m 36s**:

| Project | Tests | Suites | Failures / errors / skips |
| --- | ---: | ---: | --- |
| Compiler | 1,744 | 159 | 0 / 0 / 0 |
| Shuttle | 57 | 7 | 0 / 0 / 0 |

```powershell
.\gradlew.bat :Compiler:test :Shuttle:test --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

Full XML reports are retained under
`build/growable-arrays-stage4/compiler-test-results/` and `shuttle-test-results/`.
Archived full reports match their final source XML hashes. Earlier focused and
cleanup selections remain separately identified above; final full regression
includes all of them plus the existing compiler and Shuttle integrations.

All 12 changed skills pass the full skill-creator validator using temporary
PyYAML 6.0.3. Publication checks pass for 24 documents, resolving 1,031 relative
links and 131 anchors with balanced fences and clean whitespace. The 14 changed
source/test/example files also pass whitespace checks. All 22 plan tasks through
Stage 4 are checked; Stages 5-8 remain unchecked. Validation dependencies and
scratch files are removed before completion. Evidence logs remain under
`build/growable-arrays-stage4/`.
