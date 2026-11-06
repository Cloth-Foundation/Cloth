# Nullable rectangular rows (2026-11-03)

The [confirmed row contract](semantics.md#nullable-rectangular-rows) is executable.
C# marker placement composes nullable arrays at every level while Cloth retains
outer-to-inner extent order, rectangularity and independent values.

```cloth
def<int[]?[]> rows = [null, null]
rows[0] = [1, 2] // First present row fixes the width at two.
rows[0] = null
rows[1] = [3, 4] // The established width survives null.
```

`int[]?[]` has nullable rows; `int[]?[]?` also permits a null outer array.
`int?[2]?[3]?` permits a null two-by-three array, null rows and null ints.
Null rows supply no shape witness. Present empty rows supply length zero.
Explicit extents constrain present rows before any witness. Known literal/type
mismatches fail during compilation; unknown extents use first-error runtime
guards. Jagged rows, implicit nullable inference, unchecked payload reads and
array equality remain unsupported.

## Source ownership and invariants

- `TypeResolver` applies nullable markers from the innermost suffix to the
  outermost wrapper, preserving C extent order. `CNullable.arrayDimensions`
  exposes payload dimensions; `CArray.arraySpelling` renders nullable row
  positions without losing widths or duplicating dimensions.
- `CArray.hasNullableRows` identifies trees needing live inner-shape witnesses.
  Only these array descriptors gain an internal shape pointer after their
  existing data/count/extent fields. Ordinary array descriptors stay unchanged.
  The pointed-to vector stores target-sized counts and separate known flags.
- `EmitArray.descriptor` initializes literal shape storage; `current` merges
  descriptor witnesses with live constraints. Reading/validating borrowed values
  does not publish constraints into their source. `share` attaches owned
  subtrees to a receiving array's vector, offset by depth, recursively matching
  and retaining dimensions. Present row writes can establish unknown dimensions
  shared by all siblings, including through nested non-nullable arrays.
- `EmitValueTransfer` snapshots replacement values before attaching them. Binding
  replacements retain the original constraint vector; whole-array null assigns
  retain the existing separate nullable binding shape state. Null row assignments
  skip payload/shape operations and never clear witnesses.
- `EmitStringArena` independently copies shape vectors into the destination
  arena along with recursive array/string storage, then links copied descendants
  to the copied vector. Outgoing metadata must outlive the callee. Copy-loop phis
  use the actual block after all allocation guards. Fresh independent arrays may
  retain storage under existing proofs; nullable wrappers remain conservative.
- `EmitArray.index` presence-guards nullable payloads and applies parent shape
  constraints to extracted rows. Extracted present rows and foreach receivers
  retain ordinary independent copy semantics. Null rows require a nullable
  receiving annotation; indexing an unproven nullable row remains a type error.
- `EmitArrayText` branches on row presence in both sizing/filling passes. Nested
  present rows write directly into the shared output buffer; absent rows write
  `null`. No intermediate row string or public unchecked nullable `str` provider
  is introduced.

The shape vector is compiler-managed buffer metadata, not a programmer-visible
pointer, GC, reference counting or object lifetime mechanism. Stored shapes have
the same conservative frame lifetime and copy-before-return requirements as data.

## Verification

`NullableRowsTest` checks all-null construction, first witnesses, explicit/zero
extents, retained constraints after all rows become null, whole-binding replacement,
independent copies and source-order argument snapshots, optional defaults, returned
buffers, nested writes, row foreach, canonical metadata and recursive Unicode text.
Its mixed fixture verifies LLVM IR and emits objects across all nine target
profiles; executable cases run on Windows x64/x86. No native Linux/macOS execution
is claimed. `ArrayTemporaryCleanupTest` counts outstanding allocations and poisons
freed memory while checking returned row constraints and nested Unicode bytes.

Run `NullableRowsTest`, the nullable/optional suites, rectangular/binding/assignment/
copy/text/foreach suites and `ArrayTemporaryCleanupTest`, followed by the full
compiler suite. See [development history](../../cloth-language/references/development.md#nullable-row-implementation-2026-11-03)
for observed results. Runnable example: [NullableRows.co](../../../../examples/NullableRows.co).
The full `:Compiler:test --offline --console=plain --no-daemon` run passed all
1,007 tests across 75 suites, with zero failures, errors or skips.
