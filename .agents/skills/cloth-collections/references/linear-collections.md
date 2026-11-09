# Deque, Queue, and Stack

The public classes use explicit invariant element types and empty construction.
Import each class from its own `cloth.collections` file. The shared collection
guide gives signatures and the example uses all three types.
[API](../../../../Standard-Library/cloth/collections/README.md#deque-queue-and-stack) ·
[Example](../../../../examples/LinearCollections.co)

Use `isEmpty()` with ordinary control flow when an empty collection is expected.
Removal and peek return T. Empty access terminates through the existing array
bounds diagnostic with status 1. A nullable element can store null as an ordinary
value. The initial API consists of the end operations, `size()`, `isEmpty()`,
`clear()`, and `reserve(usize minimum)`. A future conditional removal API
remains a separate design choice.

All three use struct slots to preserve independent element buffers and individual
array shapes. Removal and peek return value snapshots. Copying a class element
carries its identity. Clear releases value storage while retaining capacity.
The caller explicitly deletes the collection and remains responsible for the
lifetime of class-valued elements.

## Source storage

Stack uses a private growable slot array and edits its tail. Deque stores two
growable slot arrays in two private side records. The logical sequence reads
the front side in reverse order followed by the back side in storage order.
Pushes append to the corresponding side. When a removal finds its side empty,
move about half of the opposite side to that side in reverse order. Refill
copies complete slots and restores the saved half with tail operations.
Each refill reduces the imbalance between the two sides, giving amortized
constant work in the element count. Peek reads a tail or the opposite side's
first slot. Reserve ensures capacity on either side and a refill may allocate
temporary storage. Queue delegates to its private Deque and deletes it in its
destructor.
[Deque](../../../../Standard-Library/cloth/collections/Deque.co) ·
[Queue](../../../../Standard-Library/cloth/collections/Queue.co) ·
[Stack](../../../../Standard-Library/cloth/collections/Stack.co)

## Immediate class array reads

The native emitter projects directly from checked class storage when it can
consume an array size immediately or snapshot a single indexed element.
The projection accepts struct fields and indices that are integer literals,
parameters, or ordinary local bindings. A deferred local initializer or an
index that can call code uses the existing snapshot path. A class receiver
is evaluated and checked before reading its field. Once the field is loaded,
the selected path runs without user code that could change the storage.
Size consumes the count without publishing storage. A selected element goes
through ordinary value snapshotting before it can escape. This preserves class
identity, recursive copies, bounds checks, and ordinary source evaluation order.
Existing write and buffer cleanup paths retain their own proofs.
[Emitter](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)

## Verification (2026-11-08)

The focused Gradle run passed 214 Compiler tests across 19 suites and 9 Shuttle
tests across 2 suites, with zero failures or skips. The maintained example and
the value and lifetime tests execute on Windows x64 and x86. The example emits
verified objects for all nine ABI profiles. The standalone library root also
emits an object using the development library selection.

The focused tests use the real library files through the public compiler request
API. They exercise both rebalance directions, array elements of different
lengths, stored null, class identity after collection deletion, and one fatal
diagnostic for each empty access. The poison probe compares 32 reserved cycles
with zero and 128 stored values, verifies equal allocation counts, and checks
that both drains return to reserved live bytes. It measures zero live slot
shifts and zero reserved storage relocation.
[Collection tests](../../../../Compiler/src/test/kotlin/cloth/StandardLinearCollectionsTest.kt)

The class value regression exercises an effectful index that replaces the field,
an immediate nested size query, and a selected value that outlives its class.
The packaging fixture exports a temporary pinned snapshot, checks the collection
files byte for byte, and emits an AArch64 Linux object through installed layout
selection.
[Class value tests](../../../../Compiler/src/test/kotlin/cloth/ClassValueIntegrationTest.kt) ·
[Packaging tests](../../../../Shuttle/src/test/kotlin/shuttle/ToolchainBundleTest.kt)
