# Growable arrays Stage 7: collection proof and generic List gate

Status: concrete array-readiness proof complete (2026-11-08). Public generic
`List<T>` delivery is fulfilled by [generic Stage 5](../../cloth-generics/references/stage-5.md),
which owns the actual imported-library API/ownership verification. This record
retains the earlier concrete-proof evidence below. The
[array plan](../../../../todo/growable-arrays-and-lists-plan.md) closes its public
List checkbox; [Stage 8](growable-arrays-stage-8.md) owns final array integration.

The [confirmed collection requirements](growable-arrays.md#collection-integration-requirements-confirmed-2026-11-08)
include independently sized array elements and checked rectangular array export.
The [companion generic-types plan](../../../../todo/generic-types-plan.md) records
the implemented first slice. [Generic Stage 0](../../cloth-generics/references/stage-0.md)
settles its contract and baseline; deferred generic features retain separate
follow-ups. This record preserves the earlier concrete collection proof;
the linked generic Stage 4 record owns native generic support and Stage 5
owns actual public List delivery.

## Concrete collections

[ArrayBackedList.co](../../../../examples/ArrayBackedList.co) maintains StringList
with a private growable `string[]`. All collection behavior is ordinary Cloth:

| Method | Result and behavior |
| --- | --- |
| `append(string value)` | Void; append an independent string value. |
| `get(usize index)` | Independent string at an existing index. |
| `insert(usize index, string value)` | Void; insert at zero through size inclusive. |
| `removeAt(usize index)` | Remove and return an independent string; exclusive bound. |
| `size()` | Current length as exact `usize`. |
| `clear()` | Remove live elements and retain reusable owned capacity. |
| `reserve(usize minimum)` | Reserve capacity without changing length. |
| `toArray()` | Independent growable string array. |

Construction optionally receives an initial array by value. The private field
stays inaccessible to callers. Collection aliases share class identity and see
the same edits; array exports and get/remove results survive clear or collection
deletion. Explicit collection deletion releases owned value buffers.

The source names no allocator, foreign function, pointer, raw address or memory
handle. The compiler has no special case for StringList or RowList. Existing
class creation/deletion, typed array methods and value transport supply the
storage operations. The same source runs with `--no-stdlib`; it is a maintained
proof fixture, not the public generic standard-library implementation.

## Independent array elements

A direct `T[]` backing becomes rectangular when T itself is an array. The user
confirmed that future `List<int[]>` must accept different element lengths.
[ArrayBackedRows.co](../../../../examples/ArrayBackedRows.co) proves a concrete
alternative with private `RowSlot[]`, where `RowSlot` contains `int[] value`.
The outer storage is an array of structs; each field has its own shape contract.
It accepts `[1]`, `[]` and `[2, 3]` in one collection without changing array rules.

Its ordinary methods receive/return independent row values. `copy()` constructs
a new RowList and independently copies its slots. Class identities inside copied
values would still share their objects; value copying never implicitly clones
or deletes a class instance.

The user also confirmed that `toArray()` retains the rectangular array contract.
RowList builds an ordinary `int[][]` through whole-row append: matching rows
export independently; mixed widths fail at the existing runtime shape guard.
The failure terminates before later source effects or normal PEC completion.
`copy()` preserves different lengths and does not perform rectangular conversion.
Clear discards slots without retaining a collection-wide width; a later slot can
contain a different-length row. An exported array establishes its own inner shape.

This concrete proof motivates **generic classes and structs** in the companion
plan, with a proposed private `ListSlot<T>[]`. It does not add generic syntax,
make arrays jagged or turn generated concrete wrappers into generic instances.

## Protocol and ownership boundaries

Generic types alone do not provide class indexing, indexed assignment, direct
foreach or collection-count providers. Current foreach consumes arrays; array
exports retain snapshot traversal, and ordinary collection classes expose
get/size methods. A differently sized row collection can use get/size traversal
without converting to an unrepresentable rectangular array. Any direct collection
iteration/indexing protocol needs separate syntax, dispatch and lifetime design.

Owning the collection's string/array/struct buffers is separate from owning class
identities stored as elements. The concrete class-element test confirms that
clear/container deletion leaves those objects live until explicit deletion.

Bootstrap is unchanged. [TokenList.co](../../../../Bootstrap/Frontend/token/TokenList.co)
owns/deletes nodes and Tokens. TokenCursor borrows those tokens; cursors must be
deleted before the list, and SourceFile remains live until borrowed spans are
destroyed. Replacing it with a default List<Token> would not automatically delete
Tokens or spans. The [migration boundary](../../../../todo/generic-types-plan.md#bootstrap-migration-remains-separate)
requires an owning adapter or explicit element deletion, partial-error cleanup,
ownership transfer and cursor/source lifetime tests in a separate consumer change.

## Verification

[ArrayBackedCollectionTest](../../../../Compiler/src/test/kotlin/cloth/ArrayBackedCollectionTest.kt)
adds 13 source tests for both maintained fixtures, the complete method surface,
private backing, bounds/order, class aliases, independent constructor/get/remove/
export values, snapshot traversal, different row lengths, copy and checked export.
It also verifies explicit class-element lifetime and that custom class instances
do not acquire array indexing/foreach. A located generic-declaration failure
records the current prerequisite before native output is created.

[ArrayBackedCollectionStorageTest](../../../../Compiler/src/test/kotlin/cloth/ArrayBackedCollectionStorageTest.kt)
adds six real allocation/poison probes:

- 1,000 StringList edit/get/remove/export/clear cycles return to reserved live
  bytes after each consuming helper frame exits.
- After reserve(128), 100 string appends allocate exactly 200 argument/payload
  buffers and relocate zero existing bytes; ordinary methods do not repack old
  array contents on every append.
- RowList copies, fetched/removed rows and rectangular exports survive source
  deletion and poisoned source frames.
- 1,000 RowList append/insert/get/remove/clear cycles return to reserved live
  bytes after each helper frame exits.
- Nullable array-field witnesses survive slot removal, null, return and container
  deletion. Balanced probes release every tracked payload.

Both new suites execute on native Windows x64/x86. The source suite verifies IR
and objects for each maintained fixture across all nine existing ABI profiles;
the storage suite verifies its poisoned ownership fixture across the same nine.
This is not native Linux/macOS/Windows ARM64 execution evidence.

The focused run passes **87 tests across nine suites**, including all 19 new tests,
with zero failures, errors or skips (1m 34s). It selects the two new suites,
GrowableArrayEditTest/StorageTest, GrowableArrayRowsTest/StorageTest,
BootstrapLexerTest, ImportPublicationTest and StandardLibraryRuntimeTest:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.ArrayBackedCollectionTest --tests cloth.ArrayBackedCollectionStorageTest --tests cloth.GrowableArrayEditTest --tests cloth.GrowableArrayEditStorageTest --tests cloth.GrowableArrayRowsTest --tests cloth.GrowableArrayRowsStorageTest --tests cloth.BootstrapLexerTest --tests cloth.ImportPublicationTest --tests cloth.StandardLibraryRuntimeTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

XML reports and copy-verified SHA-256 hashes are archived under
`build/growable-arrays-stage7/focused-test-results/` and `focused-summary.json`.
No production compiler code, Bootstrap source or standard-library source changes
are needed for this stage's proof. The last full Compiler/Shuttle regression is
the [Stage 6 snapshot](growable-arrays-stage-6.md#verification); it is not presented
as a fresh Stage 7 full-suite run.

Both maintained examples execute through the current compiler CLI with
`--no-stdlib` on `x86_64-pc-windows-msvc` and `i686-pc-windows-msvc`.
ArrayBackedList prints:

```text
middle
3
first
[middle, last, independent]
[after]

PEC: 0
```

ArrayBackedRows prints:

```text
[1, 9]
[2, 3]
[1]
[]
[2, 3]

PEC: 0
```

Four CLI output logs are archived as
`build/growable-arrays-stage7/cli-ArrayBackedList-x64.txt`,
`cli-ArrayBackedList-x86.txt`, `cli-ArrayBackedRows-x64.txt` and
`cli-ArrayBackedRows-x86.txt`. All seven changed skills pass the full
skill-creator validator with pinned PyYAML 6.0.3. Publication checks pass for
16 documents, 842 relative links, 99 anchors and four source/test/example files,
including balanced fences, final newlines and whitespace. Evidence is archived
in `skill-validation.txt` and `publication-validation.json`. The checks retain
34 completed array-plan tasks, the unchecked public List gate and Stage 8, and
all generic implementation stages unchecked. Temporary validation files and
dependencies are removed before completion.

## Gate status

All five Stage 7 tasks are now complete. The original four-task proof and its
verification snapshot above predate generic implementation. The public List gate
is subsequently fulfilled by generic Stage 5's actual imported library and
multi-type tests. Generic Stage 6 closes first-slice publication; the separate
Bootstrap migration and collection protocols remain deferred.
