# Growable arrays Stage 5: insertion, removal and clear

Status: complete (2026-11-08) for the executable slice below.
[Stage 6](growable-arrays-stage-6.md) supersedes the rank boundary below with
rectangular mutation and retained shape/value integrations. This record preserves
Stage 5's exit scope and verification snapshot. Stages 7-8 remain open. The
[contract](growable-arrays.md) owns intended semantics and the
[plan](../../../../todo/growable-arrays-and-lists-plan.md) owns the remaining gates.

## Executable surface

All five methods now execute on one-dimensional growable arrays: append, insert,
removeAt, clear and reserve. They accept the local/parameter, stored field,
nested aggregate/array-element and temporary receivers admitted by
[Stages 3](growable-arrays-stage-3.md) and [4](growable-arrays-stage-4.md).
`:=` and `final` keep binding-only immutability. Literal-only arrays remain fixed;
exact element identities and ordinary nullable presence proofs are unchanged.

```cloth
fn main() {
    def<string[]> values := ["first", "last"]
    values.insert(1, "middle")
    def removed = values.removeAt(0)
    values.clear()
    values.append(removed)
    println(values)   // [first]
}
```

[GrowableArrayEdits.co](../../../../examples/GrowableArrayEdits.co) is the
maintained ordinary class fixture with a private string-array field, shared class
aliases, insert/remove/clear methods and independent exports surviving deletion.
It uses no allocator, pointer or compiler-specific collection facility.

Insert accepts indices from zero through the current length, inclusive. RemoveAt
requires an existing index below the current length and returns an independent T.
Other methods return void. Indices/counts use exact target `usize` typing.
Clear removes every live value, preserves outer presence and keeps reusable
capacity. An already empty array can be cleared again.

Class-valued elements copy identity. Neither removal nor clear deletes those
objects; explicit class deletion remains required. Nullable removed elements stay
nullable and require the existing receiving annotations/proofs. Removed structs
copy their nested buffers and pointer-free retained field witnesses, including
witnesses in absent nullable array fields.

**C4032** now guards mutators whose receiver array has rank greater than one.
Shape-changing embedded-row operations still fail with **C3107**; ordinary reserve
of a one-dimensional row remains supported. Fixed receivers, read-only defaults,
lost nullable receiver proofs and deleted class owners retain their existing
semantic checks. Whole-row mutation and generic List remain later work.

## Evaluation and mutation order

The stored receiver path/identity and its indices are evaluated once before
explicit arguments. Arguments independently receive values in written order, so
`values.insert(index, values[0])` protects its element before capacity or inline
slots move. After all arguments finish, the selected stored path is revalidated
and its current array is loaded. An argument may grow, clear or replace that
binding. Insert/removal indices are checked against the resulting current length.
An invalid receiver path fails before arguments; an invalid supplied index fails
after their effects and before growth, removal or payload access.

A temporary receiver is independently captured before its arguments. A wanted
removed indirect value copies into the receiving frame before slot cleanup,
shifting or temporary-owner release. Class identity/scalar results use their
ordinary value carriers. Return and ordered-result transport use the existing
private ABI and recursively copy before their creating frame is released.

Direct discarded builtin removeAt calls still execute receiver/argument effects,
all bounds/ownership checks and the removal, but omit an unused result snapshot.
Explicit receiving constraints continue through the ordinary consumption path.
This permits repeated discarded buffered removals without retaining copies that
have no consumer. Wanted removed snapshots retain the normal frame lifetime;
this stage does not add general early cleanup for arbitrary consuming expressions.

## Typed storage and ownership

The Stage 4 array descriptor and four-word owner control are unchanged. Field
mutation uses the array's existing owner head; plain parameter/local mutation
keeps its established frame-ownership boundary. Clear/removal ensure owned storage
without requesting larger capacity. Only insert/append can grow it geometrically.

- Insert checks the inclusive bound and checked length addition, reserves space,
  then memmoves the live suffix right. Buffered slot heads move right in descending
  order into empty heads. Resource callbacks reparent nested owners. The protected
  new value copies into the vacant slot before length publication.
- RemoveAt snapshots a wanted result, releases the selected slot's live payloads
  once, memmoves the remaining live suffix left and moves heads in ascending
  order. The inactive tail head is empty and its inline bytes are zeroed.
- Clear uses typed truncate to release only the live prefix's slot arenas, then
  publishes length zero. Capacity remains allocated for reuse. Inactive slots
  are neither copied nor visited during cleanup.

Shifts move inline value bytes, nullable flags and hidden witnesses without cloning
existing payloads. Class objects are never destroyed by these buffer operations.
Checked target stride, alignment, byte-count and capacity limits remain ahead of
allocation or payload access. Allocation failures terminate at the first executed
failure; no later source effect or normal PEC completion runs.

## Selections and scratch across destructive effects

Stage 4 observations now protect arrays of every rank and fixed extent when a
containing growable collection can destroy their buffers. This extends private
read/place safety; it does not admit rectangular mutators. Owner cleanup clears
observation back-links and zeroes their descriptor views before freeing storage.
Truncate/removal also publish the shortened descriptor to surviving observations.

Indexed reads validate their original bound, then check the current selected live
bound after index effects. They retain live shifted contents when that index still
exists and fail safely when it was cleared or removed. A selected child buffer
survives removal of a different sibling and follows its reparented owner; destroying
that child invalidates its view. Local indexed writes/struct receivers likewise
recheck current bounds. Class-backed paths retain checked current-field replay.
Ordinary copies, saved field reads and foreach source snapshots stay independent.

Wide removed-value loads copy to receiving storage before restoring their local
stack scratch. Wide index checks separately restore the temporary wide bound
after producing a target-sized checked index, bounding repeated original/current
checks. Neither mechanism releases an enclosing arena suffix across mutation.
Mutators keep `MUTATING_RECEIVER` and stay outside read-only/whole-loop cleanup
proofs. Unproven captured writes, wanted result snapshots and superseded buffers
from unproven indexed replacement retain the established conservative lifetime.

## Source routing

- [EmitArrayMutation](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArrayMutation.kt)
  owns operation dispatch, source index bounds, protected removed reception and
  the shared native rank boundary.
- [EmitArrayStorage](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArrayStorage.kt)
  owns typed insert/remove, ordered head movement, truncate publication and
  invalidation of destroyed child views.
- [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
  captures arbitrary argument lists, publishes stored descriptors, emits wanted
  results and handles direct discarded builtin results.
- [EmitArray](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArray.kt)
  preserves all original index bits and scopes wide bound scratch.
  [EmitBindings](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitBindings.kt)
  permits observation of fixed/rectangular child storage as well as growable roots.
- [Stage 4](growable-arrays-stage-4.md) retains stored-place/class-backed replay,
  separate operand/observation arenas and actual owner-head publication.
  Existing semantic signatures/effects remain owned by Stage 1; no new public
  type, callable ABI or diagnostic code is introduced.

## Verification

[GrowableArrayEditTest](../../../../Compiler/src/test/kotlin/cloth/GrowableArrayEditTest.kt)
adds 20 source behavior tests;
[GrowableArrayEditStorageTest](../../../../Compiler/src/test/kotlin/cloth/GrowableArrayEditStorageTest.kt)
adds 12 storage/failure tests. The shared probe harness instruments real
allocations, poisoned frees, live bytes, reserve memcpy and the live-suffix memmove
in typed insert/remove. It does not replace source mutation lowering.

Measured native probes establish:

- 1,000 append/insert/discarded-remove/clear cycles retain exactly reserved capacity
  after each iteration: 6,002 total allocations including initialization/reserve,
  with all 6,000 producer/received payload allocations reclaimed appropriately.
- 1,000 reserved front insertions make 2,000 new received-payload allocations.
  Their 1,000 discarded front removals allocate no result copies. Together the
  shifts move exactly 999,000 live string descriptors without cloning old payloads.
- Parent shifts reparent nested array owners; 100 later scalar child appends use
  already reserved capacity without extra allocations.
- Removed buffered structs, nullable/wide values, enum values, class identities
  and absent field witnesses survive poisoned original storage and frame release.
  Invalidated growable, fixed and rectangular child views fail before freed reads;
  a different surviving child's view remains usable after a sibling removal.
- Repeated consuming function frames release independent removed class-field
  results. 512 full-cap removed values use 1,025 allocations including the initial
  array, retaining only wanted frame snapshots plus live capacity. Full-cap
  mutating index producers retain their normal result snapshots while both bounds
  checks restore scratch, avoiding repeated wide stack growth.
- Failure injection covers initial ownership, producers, operand snapshots,
  capacity, new slot payloads and removed-result snapshots. Invalid insertion
  indices stop before the injected growth allocation or later source effects.

The final focused selection passes **183 tests across nine suites**, including all
32 new tests, with zero failures/errors/skips. XML reports are retained under
`build/growable-arrays-stage5/focused-test-results/` and matched source hashes
when archived.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.GrowableArrayEditTest --tests cloth.GrowableArrayEditStorageTest --tests cloth.GrowableArrayAppendTest --tests cloth.GrowableArrayAppendStorageTest --tests cloth.GrowableArrayFieldTest --tests cloth.GrowableArrayFieldStorageTest --tests cloth.GrowableArrayTypingTest --tests cloth.GrowableArrayStorageTest --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
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

The maintained example also executes through the current compiler CLI on Windows
x64 and x86, printing `first`, `[middle, second]`, `[after]` and PEC 0. Binaries are
retained under `build/growable-arrays-stage5/GrowableArrayEdits-x64*` and
`GrowableArrayEdits-x86*`. No native Linux, macOS or Windows ARM64 execution is
claimed.

Full offline regression passed on 2026-11-08 in **8m 46s**:

| Project | Tests | Suites | Failures / errors / skips |
| --- | ---: | ---: | --- |
| Compiler | 1,776 | 161 | 0 / 0 / 0 |
| Shuttle | 57 | 7 | 0 / 0 / 0 |

```powershell
.\gradlew.bat :Compiler:test :Shuttle:test --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

Full XML reports are retained under
`build/growable-arrays-stage5/compiler-test-results/` and `shuttle-test-results/`.
Archived full reports match their final source XML hashes. The focused archive
remains separately identified above; the full run includes those tests and the
existing compiler/Shuttle regression suites.

All 12 changed skills pass the full skill-creator validator using temporary
PyYAML 6.0.3. Publication checks pass for 25 documents, resolving 1,072 relative
links and 131 anchors with balanced fences and clean whitespace. The 12 changed
source/test/example files also pass whitespace checks. All 25 plan tasks through
Stage 5 are checked; Stages 6-8 remain unchecked. Validation dependencies and
scratch files are removed before completion. Evidence logs remain under
`build/growable-arrays-stage5/`.
