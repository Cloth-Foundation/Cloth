# Growable arrays Stage 2: owned capacity and typed relocation

Status: Stage 2 complete (2026-11-07). [Stage 3](growable-arrays-stage-3.md) adds
source append/reserve. [Stage 4](growable-arrays-stage-4.md) adds stored field
growth and enlarges private owner controls with scoped observations.
[Stage 5](growable-arrays-stage-5.md) adds source insertion/removal/clear and
destructive selection checks. [Stage 6](growable-arrays-stage-6.md) adds rectangular
mutation and retained witnesses through all public edits. This record preserves the
Stage 2 storage and verification snapshot.
The [confirmed contract](growable-arrays.md) and [implementation plan](../../../../todo/growable-arrays-and-lists-plan.md)
own the language decisions and stage boundaries.

## Representation and ownership

`CArray` uses an array-only descriptor: data pointer, live outer length, the
existing inner count/known fields, an optional shared nullable-row shape pointer,
then target `usize` capacity and a private owner pointer. Fixed and growable
arrays share this private layout. A borrowed descriptor has null owner and
capacity equal to length. Strings retain their two-field data/byte-count layout;
raw byte spans use `EmitMemory.bytes`, never a fabricated array descriptor.

An owned allocation contains an aligned three-word control record (arena head
address, live length, capacity), capacity for inline elements, and one arena head
per slot when the concrete element type contains buffers. Only the live prefix
owns values. Arena nodes carry a next pointer and an optional resource callback.
The callback either reparents an owner after a head cell moves or destroys the
live element arenas before freeing the node. This is deterministic ownership,
without GC, reference counting, automatic class deletion or a public memory ABI.

`EmitArrayStorage` specializes copy, reserve, slot initialization, suffix release
and cleanup for the concrete element layout. It checks target count, stride,
alignment, owner/header overhead and signed LLVM pointer-index limits before
allocation or payload access. Capacity grows geometrically within those limits.
No helper calls `realloc` on borrowed, stack or arena-interior memory.

Reserve moves only live inline bytes and their element-arena chains. It reparents
resource owners whose head cells move, disables cleanup of the old live prefix,
detaches the superseded allocation from its arena and frees it immediately.
Detachment walks arena links; the stage makes a bound on element movement and
growth allocations, not a constant-time bound on this link search. Child strings,
arrays and structs are transferred during relocation; class identities are copied
as identities. Ordinary value copies recursively create independent live payloads
and shape vectors, and start with capacity equal to their length.

Borrowed input and explicit arena crossings copy before reserve. Frame, outgoing
result and class-field copies all use the same typed storage path. Fresh results
still transfer once within their proven owner domain. A fresh non-nullable-row
subarray can move its owner into an element arena without copying its payload;
nullable-row adoption conservatively copies shared shape storage.

Private truncation releases only removed live slots, retains capacity and inner
shape, and leaves inactive bytes unexamined. Private initialization receives a
new independent value into the next available slot before publishing its length.
Existing indexed buffered writes retain superseded element chains in the enclosing
owner because their borrowed-path lifetime is not a closed early-release proof.
Their current slots still own the newly received values. Unproven named-array
replacement and captured publication retain the established conservative frame
lifetime. This stage does not claim bounded memory for those source operations.

Stored indexed string reads snapshot independently when their values can escape
or later operands can publish. Read-only consumers may borrow when later operands
have no publication, and potential snapshot allocation participates in the existing
closed temporary cleanup proof.

## Unsized struct array fields

Unsized fields now execute through literals, stored field/index writes, struct
methods, private arguments/results, independent copies and nested class-backed
receivers. Only an unsized outer count can change on whole-field replacement;
explicit extents and established inner shape still constrain reception.

Each nullable array field has a separate inline, pointer-free witness in the
containing struct. Null preserves it, copies carry it independently, and a live
field refreshes it before becoming absent. Replacing the containing struct receives
the incoming value's witnesses. `StructStorageLayout` includes the hidden witness
bytes in target size validation. C4032 no longer gates unsized struct fields.

```cloth
typedef<struct> Rows { int[]?[]? values }
fn main() {
    def<Rows> rows = { [null, [1, 2]] }
    def saved = rows
    rows.values = []
    rows.values = null
    rows.values = [[3, 4], null, null]
    println(saved.values)
    println(rows.values)
}
```

An unknown incoming row width is checked at runtime after absence; an incompatible
established width fails before the field is published. Stage 1's compile-time
shape and receiver checks remain in force.

## Producers, consumers and source routing

- [BufferLayout](../../../../Compiler/src/main/kotlin/cloth/data/BufferLayout.kt),
  [CArray](../../../../Compiler/src/main/kotlin/cloth/data/PrimitiveTypes.kt),
  [CStruct](../../../../Compiler/src/main/kotlin/cloth/data/CStruct.kt): private
  layout and hidden witnesses. `EmittedCallable`/`EmitResults` consume those
  shared types for arguments, results, nullable carriers and ordered transport.
- [EmitArray](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArray.kt),
  [EmitArrayStorage](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArrayStorage.kt),
  [EmitStringArena](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitStringArena.kt):
  descriptors, shape operations, concrete storage helpers and registered cleanup.
- [EmitValueTransfer](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitValueTransfer.kt),
  [EmitFunctionFrame](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctionFrame.kt),
  [EmitClassStorage](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassStorage.kt):
  independent copies, one-use adoption, frame/result and field owners.
- [EmitStructFields](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitStructFields.kt),
  [EmitPlaces](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitPlaces.kt),
  [EmitBindings](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitBindings.kt):
  field reception, witnesses, saved projections and receiver publication.
- Literals and enum `::values` use shared descriptor construction. String
  `split`/`toChars`, startup arguments, providers, recursive formatting and foreach
  keep length/extent indices and use the array-only layout. Read-only producers
  may return borrowed frame storage; a later reserve receives ownership first.

## Verification

The 15 new tests are owned by
[GrowableArrayStorageTest](../../../../Compiler/src/test/kotlin/cloth/GrowableArrayStorageTest.kt)
and [GrowableStructArrayStorageTest](../../../../Compiler/src/test/kotlin/cloth/GrowableStructArrayStorageTest.kt).
Their nine private storage tests cover concrete scalar, full-cap integer,
nullable wide integer, string, nullable string, struct, enum, class-identity and
nullable-row layouts. Six source tests cover field copies/calls, retained absent
witnesses, nested array/class receivers, containing-struct replacement and saved
indexed string operands. The earlier unsized-field gate test now executes a
successful replacement instead.

Private probes instrument real allocations, poison frees, inject allocation failure
and count live-byte movement. Their source factories and consumers use the normal
compiler; only helper composition and fault instrumentation use direct LLVM.
The payload/object malloc/free counter excludes the existing process-lifetime
class table, which uses its separate runtime allocation path.

The scalar probe reaches 4,096 live values with **13 growth allocations** and
**16,380 moved bytes (4,095 prior int32 elements)**. Only final capacity remains
live after each growth. A satisfied owned reserve keeps the same owner. Buffered
initialization/truncation reuses capacity across 1,000 iterations and restores the
same live-byte baseline after every release. Child growth after parent movement
uses one growth allocation without cloning its payload. Borrowed input and arena
crossings remain independent. Invalid capacity/live counts fail before fake-payload
reads; injected failures cover initial owner, capacity replacement and element
payload allocation, before later initialization or publication.

All new native fixtures pass on **Windows x64/MSVC and x86/MSVC**. Typed storage
and unsized struct fields also verify IR and nonempty objects on all nine existing
ABI profiles. No new native Linux, macOS, AArch64 or musl execution is claimed.

Final full regression command:

```powershell
.\gradlew.bat :Compiler:test :Shuttle:test --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

**BUILD SUCCESSFUL in 8m 24s.** All **1,694 Compiler tests across 155 suites** and
**57 Shuttle tests across seven suites** pass, with zero failures/errors/skips.
Compiler suite timestamps run from `2026-11-08T06:14:39.753Z` through
`2026-11-08T06:22:08.166Z`; the local publication date is still 2026-11-07.
XML reports are preserved under `build/growable-arrays-stage2/compiler-test-results/`,
`shuttle-test-results/` and `focused-test-results/`. Archived full reports match
their final source XML hashes. Existing literal, enum/string producer, nullable,
provider, formatting, foreach, callable and field-consumer regression is included.

All nine changed skills pass the full skill-creator validator using temporary
PyYAML 6.0.3. Relative-link/fence/document checks and whitespace checks for the
28 changed source/test files pass. Temporary validation dependencies are removed.

Source mutator calls still stop at C4032 before output/linking. Passing private
helper probes does not establish executable append, insert, removeAt, clear or
reserve in Cloth source, efficient repeated class-field growth, or generic List.
