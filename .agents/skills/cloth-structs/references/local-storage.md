# Local struct execution and storage (Stage 3)

Integrated 2026-11-04. The [struct contract](semantics.md) and
[semantic record](semantic-analysis.md) describe the source rules and checked
plans. Stage 3 executes the local slice.

```cloth
pub typedef<struct> Point {
    int x,
    int[2] samples
}

fn<int> main() {
    def<Point> original := { 1, [2, 3] }
    def copy = original
    original.x += 4
    original.samples[0] = 9
    if (copy.x != 1 || copy.samples[0] != 2) return 1
    return 0
}
```

## Representation and transfers

- [CStruct](../../../../Compiler/src/main/kotlin/cloth/data/CStruct.kt) uses a
  private LLVM pointer for expression transport and an inline aggregate for
  binding, field and element storage. Field types use their own stored
  representations, including byte booleans, nullable presence bytes and inline
  wide integer bytes. Empty structs have a private byte so arrays have positive
  stride. Storage layouts are materialized for the supplied LLVM context.
- [StructStorageLayout](../../../../Compiler/src/main/kotlin/cloth/emitter/types/StructStorageLayout.kt)
  checks inline sizes with exact arithmetic and target alignment before laying
  out oversized LLVM aggregates. Array fields contribute descriptor bytes.
  Stack and arena allocations use the existing checked target count arithmetic.
- [EmitStoredValue](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitStoredValue.kt)
  loads borrowed struct addresses. Local value transfers snapshot inline storage
  before publishing it in a destination. Wide integer reads preserve their
  existing inline-byte snapshots. Nullable struct loads, stores and copies guard
  the payload with the presence flag.
- [EmitStringArena](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitStringArena.kt)
  copies aligned inline bytes and replaces buffered descriptors with independent
  copies. It descends through live nullable fields and copies strings, array data
  and retained shape witnesses. Arrays of structs repair buffered fields in each
  owned element after the raw byte copy. Struct transport is indirect even when
  its fields have no buffers. The buffered fact describes transitive publication.
- [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
  snapshots each literal field before evaluating the next. Field projection uses
  the stored layout. A write walks the semantic place plan from the actual
  binding, evaluates each index once and checks bounds before evaluating the RHS.
  Compound writes read the old operand before the RHS and use the ordinary
  arithmetic emitter. Indexed row writes retain existing shape constraints.
- [ArrayTemporaryAnalysis](../../../../Compiler/src/main/kotlin/cloth/emitter/types/ArrayTemporaryAnalysis.kt)
  recognizes struct allocation and field effects. It permits loop cleanup for a
  field write that copies inline bytes without publishing nested buffers, with
  closed index and RHS effects. Buffered or unproven publication retains the
  owning frame. Outgoing primitive, string and array fields use the existing
  function result copy path.

## Scope and diagnostics

Local literals, copies, field reads and writes, nullable locals and arrays
containing structs execute. C3052 rejects oversized inline layouts. Dynamic
field shape and bounds checks fail before later source expressions run.
C3077 still gates struct-bearing function signatures, struct results from
deferred blocks and foreach over arrays containing structs. Stage 4 covers
those boundaries. Snippet analysis continues to require declaration source
units for structs.

## Verification

All 50 focused tests passed on Windows. The mixed field and evaluation order
fixtures ran on Windows x64 and x86. The mixed field fixture verifies LLVM IR
and objects across all nine supported profiles. The storage probes cover
nullable fields and rows,
recursive copies, string and array mutation, full-width integer operands,
ordered index and RHS effects, first-error guards, and 1,000 wide field
updates with loop cleanup. The poisoned-memory probe checks outgoing arrays
and captured field publication, with final allocation balance.

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Struct*Test' --tests 'cloth.ArrayTemporaryCleanupTest.struct field buffers*' --tests cloth.NullableRowsTest --tests cloth.ArbitraryIntegerStorageTest --offline --console=plain --no-daemon
```

The full compiler suite passed all 1,328 tests on Windows.

~~~powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
~~~
