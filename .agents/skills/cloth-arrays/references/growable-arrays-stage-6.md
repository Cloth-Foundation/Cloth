# Growable arrays Stage 6: rectangular mutation and value integrations

Status: complete (2026-11-08) for the executable slice below.
The [contract](growable-arrays.md) owns language semantics;
the [plan](../../../../todo/growable-arrays-and-lists-plan.md) owns stage status.
[Stage 7](growable-arrays-stage-7.md) owns the concrete collection proof; public
List delivery is subsequently fulfilled by
[generic Stage 5](../../cloth-generics/references/stage-5.md).
[Stage 8](growable-arrays-stage-8.md) owns the final audit and target evidence.
This record supersedes Stage 5's native rank boundary, preserving its historical
verification snapshot.

## Executable surface

All five array methods execute on independent growable arrays of any supported
rank. Append and insert receive one complete row/plane; removeAt returns an
independent element, including recursive array storage. Clear removes the outer
live prefix while retaining capacity and established inner dimensions. Reserve
changes capacity without changing logical shape. Stored locals, parameters,
class/struct fields, nested fields, `self` and temporary receivers follow the
existing method receiver rules.

```cloth
fn main() {
    def<int[][]> rows := []
    rows.append([1, 2])
    rows.insert(0, [3, 4])
    def removed = rows.removeAt(1)
    rows.clear()
    rows.append([5, 6])
    removed.append(7)
    println(rows)    // [[5, 6]]
    println(removed) // [1, 2, 7]
}
```

[GrowableArrayRows.co](../../../../examples/GrowableArrayRows.co) demonstrates
nullable rows, whole-row edits, independent returned/removed storage and current
size/type providers. `T[]` stays growable; literals still infer fixed dimensions.
Binding-only `:=`/`final` immutability and explicit nullable destinations remain.
No collection type, generic syntax, allocator surface or public reference is added.

- The first present incoming row establishes unknown inner extents. Every later
  row matches those extents recursively. Absent rows supply no witness; present
  empty rows establish a real zero count.
- Established inner dimensions survive removing the last row, all-null rows,
  clear, whole replacement with an empty array and whole-array null assignment.
  Explicit inner extents constrain even the first present value.
- Shape-changing embedded-row methods remain **C3107**. An independent extracted
  growable row can change length. Reserve on an embedded growable row is allowed
  because it preserves every logical extent. Fixed arrays still reject mutators.
- Known incompatible dimensions fail during semantic analysis; dynamic mismatches
  fail at the first executed shape guard. The native **C4032** rank restriction is
  removed. Its code remains reserved for unsupported receiver lowering; all
  currently admitted stored/temporary receiver forms execute.
- Class-valued leaves retain identity. Removing or clearing them does not delete
  the objects. Buffer ownership and explicit class lifetime remain separate.

## Shape publication and ownership

[EmitArray.withElementShape](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArray.kt)
checks an incoming present row against the current parent and merges newly known
dimensions into a descriptor without publishing to borrowed storage. Absent rows
take the unchanged-parent branch. Source append/insert perform this preflight
after argument capture and index checking, before growth allocation. The typed
storage helpers also enforce the incoming shape at their own boundary.

[EmitArrayStorage](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArrayStorage.kt)
publishes the merged constraints into owned nullable-row vectors before linking
the copied row. Initialize/insert materialize a current descriptor after child
sharing; initialize/insert/remove/truncate remember the resulting live count and
retained dimensions. Removing the final present row cannot erase a witness.
Copies allocate independent vectors; removed rows copy parent constraints before
their original slot is freed. No witness points into a destroyed source owner.

The descriptor's outer count is authoritative. `EmitArray.current` refreshes
inner constraints only: a selected older value can share retained binding
constraints with a replacement having a different outer count. Explicit
`share`/`constrain` still check an embedded row's full shape, including its outer
count. Keep this distinction when changing nullable-row sharing.

[EmitBindings](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitBindings.kt)
uses the live binding descriptor for subsequent whole-array reception, including
dimensions established by append after an empty initializer. Publishing a local
nullable mutation also updates its separate retained shape state. Struct fields
remember pointer-free witnesses; nullable class replacement records the current
live witness before releasing its buffers. Mutated witnesses therefore survive
null, aggregate copies, removed containing structs and class deletion.

The [Stage 5 storage contract](growable-arrays-stage-5.md#typed-storage-and-ownership)
is unchanged: shifts transfer inline descriptors and owned slot heads, clear
releases live payloads, and reserve relocates without cloning existing children.
Descriptor layout, four-word owner control and private callable ABI are unchanged.
Scoped observations cover growable roots of every rank. Destroyed rows invalidate
their views; sibling removal preserves selected surviving child storage. Whole
binding replacement leaves a local selection attached to the old value version.
Class-backed paths continue replaying the current checked field.

## Existing value integrations

- `::size` reads the current outer length. Row reads expose retained inner counts.
  Growth/removal/clear do not change declared rank, canonical type spelling or
  typeinfo equality. Stored scalar metadata keeps its received value.
- Print, interpolation and `::str` traverse live values recursively, including
  Unicode strings and absent rows. Spare capacity never contributes output.
- Literals, copies, parameters, function/method results, enum enumeration, string
  split, conditionals, Elvis, omission defaults, deferred captures and ordered
  result destructuring use existing independent reception/transport. Row exports
  remain valid after source clear, frame exit and class deletion.
- Foreach captures an independent entry snapshot. Appending/removing/clearing the
  original during traversal changes neither that traversal's values nor count.
  Each received growable row is independently mutable.

Mutators remain barriers to enclosing arena-suffix/whole-loop cleanup proofs.
Wanted result snapshots and unproven indexed replacements retain their ordinary
conservative frame lifetime. The bounded reuse probes below apply to their
measured source paths, not arbitrary captured publication.

## Verification

[GrowableArrayRowsTest](../../../../Compiler/src/test/kotlin/cloth/GrowableArrayRowsTest.kt)
adds 18 source tests. They cover whole-row order/independence, nullable witnesses,
dynamic failures after remove/clear/null/replacement, explicit/zero extents,
ranks three/four, embedded-row rejection, field/self mutation, providers, producers,
defaults, captures, ordered results, output, foreach, temporaries and selections
across growth/replacement. Former rank-boundary tests now verify object emission.

[GrowableArrayRowsStorageTest](../../../../Compiler/src/test/kotlin/cloth/GrowableArrayRowsStorageTest.kt)
adds eight probes using the existing real allocator/poison harness:

- 1,000 buffered nullable-row append/null-insert/discarded-remove/clear cycles
  return to exactly reserved live bytes after each iteration.
- 200 null-row front inserts followed by 200 removals allocate no additional
  payloads or capacity and preserve the two buffered sibling rows.
- Removed all-null planes retain known inner widths after source class deletion
  and frame exit; incompatible later rows fail under poisoned frees.
- Selected surviving rows follow sibling removal/growth; destroyed selections
  fail the current bound before reading freed buffers.
- Nullable struct-field witnesses survive mutation, null, copying, removal of
  the containing struct, class destruction and return transport.
- 1,000 class-backed add/take cycles with consuming helper frames return to the
  same reserved live bytes after each cycle. Balanced probes free every payload.

Native execution covers Windows x64/x86. Both new suites additionally verify LLVM
IR and emit nonempty objects across all nine existing ABI profiles. This is not
native Linux/macOS/Windows ARM64 execution evidence.

The final focused run passes **248 tests across 15 suites**, with zero failures,
errors or skips (2m 18s Gradle wall time). It includes all 26 new tests. Exact XML
reports and copy-verified SHA-256 hashes are archived in
`build/growable-arrays-stage6/focused-test-results/` and `focused-summary.json`.
The source fixture and probe fixture both verify IR and emit objects on each of
the nine target triples listed in `structTargetTriples`.

The maintained example executes through the current compiler CLI for
`x86_64-pc-windows-msvc` and `i686-pc-windows-msvc`, producing:

```text
[[1, 2], null, [3, 4]]
[[5, 6]]
[1, 2, 9]
1
true

PEC: 0
```

CLI output is archived in `build/growable-arrays-stage6/cli-windows-x64.txt` and
`cli-windows-x86.txt`.

The minimal Cloth example in this record also executes through both Windows CLI
targets; outputs are archived as `documented-example-x64.txt` and
`documented-example-x86.txt`. Its extracted source is temporary validation input.

Full regression command:

```powershell
.\gradlew.bat :Compiler:test :Shuttle:test --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

The focused command uses `:Compiler:test` and the same flags, selecting
GrowableArrayRowsTest, GrowableArrayRowsStorageTest, GrowableArrayTypingTest,
GrowableArrayAppendTest, GrowableArrayAppendStorageTest, GrowableArrayFieldTest,
GrowableArrayFieldStorageTest, GrowableArrayEditTest, GrowableArrayEditStorageTest,
GrowableArrayStorageTest, NullableRowsTest, ArrayTemporaryCleanupTest,
RectangularArraysTest, ArrayBindingShapeTest and GrowableArrayReceptionTest with
one `--tests cloth.<SuiteName>` per suite.

Full offline regression passes **1,802 Compiler tests across 163 suites** and
**57 Shuttle tests across seven suites**, with zero failures, errors or skips
(9m 10s Gradle wall time). Exact XML reports are archived in
`build/growable-arrays-stage6/compiler-test-results/` and `shuttle-test-results/`.
`test-summary.json` records suite counts and SHA-256 hashes, verified against
the source XML when copied. These totals include all 26 new tests.

All 13 changed feature skills pass the full skill-creator validator with pinned
PyYAML 6.0.3. Publication checks pass for 28 documents, 1,140 relative links,
139 anchors and 11 source/test/example files, including balanced fences, final
newlines and whitespace. Results are archived in `skill-validation.txt` and
`publication-validation.json`. All five Stage 6 tasks are checked, for 30 completed
tasks through Stage 6; Stages 7-8 remain unchecked. Temporary probes, validation
scripts and dependencies are removed before completion.

## Later delivery and retained boundaries

The verification totals above are the historical Stage 6 snapshot. Subsequent
generic stages deliver public `List<T>` with independent array-valued elements;
Stage 8 records final array integration. General jagged arrays, resize, bulk
append, shrinking and public capacity inspection remain outside the array API.
