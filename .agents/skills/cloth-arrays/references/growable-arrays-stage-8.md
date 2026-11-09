# Growable arrays Stage 8: portability, audit and publication

Status: complete (2026-11-08); all 39 array-plan tasks are checked.
Array Stage 6's rectangular
integration was already complete. The generic detour fulfills Stage 7's public
List gate; this record owns the final array-plan closure, rather than a second
implementation of Stage 6. The [contract](growable-arrays.md) owns semantics.

## Delivered behavior and examples

Explicit `T[]` has a growable outer length; `T[N]` and unannotated literal
inference retain fixed extents. All five methods execute on independent stored
and temporary growable receivers. `:=`/`final` remain binding-only immutability.
Rectangular arrays grow by complete rows/planes and retain established inner
shape through remove, clear, replacement and null. Embedded rows may reserve
capacity but cannot change length; extracted growable values are independent.

```cloth
fn main() {
    def<int[]> values := [1, 2]
    values.reserve(8)
    values.append(3)
    values.insert(1, 9)
    def removed = values.removeAt(0)
    def saved = values
    values.clear()
    values.append(4)
    println(removed)
    println(saved)
    println(values)
}
```

This complete example prints `1`, `[9, 2, 3]`, `[4]`, then normal PEC 0.
Its extracted source executes on all three available native profiles below.

| Maintained example | Public behavior |
| --- | --- |
| [GrowableArrayFields.co](../../../../examples/GrowableArrayFields.co) | Private array field, ordinary methods, shared collection identity and independent exports after deletion. |
| [GrowableArrayEdits.co](../../../../examples/GrowableArrayEdits.co) | All five operations and independent removed/exported strings. |
| [GrowableArrayRows.co](../../../../examples/GrowableArrayRows.co) | Whole-row edits, nullable rows, retained inner shape, current size and stable type identity. |
| [ArrayBackedList.co](../../../../examples/ArrayBackedList.co), [ArrayBackedRows.co](../../../../examples/ArrayBackedRows.co) | Concrete source collection proofs and independent row slots. |
| [Collections](../../../../examples/Collections/README.md) | Actual imported public List, all nine methods, differently sized array elements, copy and checked rectangular export. |

The public [List contract](../../../../Standard-Library/cloth/collections/README.md)
and [collection skill](../../cloth-collections/SKILL.md) own its API. Generic
classes/structs and List are delivered; no generic implementation gate remains
in the array plan. Collection protocols and later generic features remain in
the [follow-up record](../../../../todo/generic-follow-ups.md).

## Migration and caller audit

| Area | Finding and retained coverage |
| --- | --- |
| Binding tests | [ArrayBindingShapeTest](../../../../Compiler/src/test/kotlin/cloth/ArrayBindingShapeTest.kt) already replaces obsolete fixed-initial-outer-count assertions: growable function/block results, parameters, branches, copies and replacements accept other outer counts. Inferred literal bindings and explicit fixed extents still reject mismatches. |
| Reception and typing | [GrowableArrayTypingTest](../../../../Compiler/src/test/kotlin/cloth/GrowableArrayTypingTest.kt) and [GrowableArrayReceptionTest](../../../../Compiler/src/test/kotlin/cloth/GrowableArrayReceptionTest.kt) retain explicit `T[]` identity, independent fixed/growable reception and fields. Known fixed-count failures are valid destination checks. |
| Rectangular and nullable tests | Inner-width and embedded-row failures in ArrayBindingShapeTest, ArrayCopyElisionTest, RectangularArraysTest and NullableRowsTest remain valid. They do not forbid independent outer growth. The Stage 6 suites cover retained empty/null witnesses and dynamic row failures. |
| Maintained examples | Arrays, ArrayAssignment, Arr, nullable rows, producer/default/foreach examples and the five growable/concrete fixtures retain literal/fixed and inner-shape contracts. ArrayAssignment already labels a changed growable outer count as permitted. No caller requires an obsolete fixed initial `T[]` length. |
| Standard library | Actual List uses `ListSlot<T>[]`, keeping independent element shapes. Checked `toArray()` retains ordinary rectangular reception. Existing Library/import fixtures require no migration. |
| Bootstrap | The linked TokenList owns nodes and Tokens; its same-file TokenCursor borrows them, and spans borrow SourceFile identity. Default List<Token> would not implement that deletion/transfer policy. Preserve partial-error cleanup, cursor-before-list cleanup and source lifetime. Its older availability comment is historical, not an array shape requirement. |

The [generic caller audit](../../cloth-generics/references/caller-audit.md) owns
the detailed Bootstrap lifetime map. Host Kotlin collections are not Cloth
consumers. No compiler, test, SDK, Bootstrap or maintained example source changes
are required for this final stage. A SHA-256 baseline preserves all 524 current
implementation/test/example inputs, including intended Test.co and user math work.

The historical array-semantics initializer wording now distinguishes current
count from declared type. Current contract/plan links acknowledge delivered List;
historical Stage 0-7 verification counts remain historical evidence.

## Storage, performance and failures

[Stage 2](growable-arrays-stage-2.md) owns capacity/descriptor layout and typed
storage operations. [Stage 4](growable-arrays-stage-4.md) owns direct field
publication and relocated selections; [Stage 5](growable-arrays-stage-5.md) owns
ordered shifts and removed snapshots; [Stage 6](growable-arrays-stage-6.md) owns
rectangular witnesses and existing value integrations. Preserve recursive value
copies, protected operands before relocation, live-prefix traversal and explicit
class lifetime. Clear releases live value payloads and retains reusable capacity;
removing class identities does not delete objects.

The focused run repeats the real allocator/poison probes: geometric scalar
growth, no copying of old payloads during relocation, no whole-field repacking,
independent results after source destruction and bounded repeated helper-frame
edits. Existing counter assertions include 14 allocations for the scalar-growth
probe of 4,096 appends (16,380 relocated bytes), 6,002 for 1,000 buffered edit
cycles, and exactly 999,000 descriptor shifts
for 1,000 front insertions/removals. Row and imported List helper-frame probes
return to reserved live bytes. These are source-path bounds; wanted snapshots,
unproven indexed replacements and captures keep their conservative lifetime.

C3107 rejects fixed receivers and shape-changing embedded-row mutation. Known
shape mismatches retain existing C3038 array diagnostics; other type/binding
failures keep their own codes. Runtime bounds, dynamic shape,
checked count/byte overflow and allocation failures terminate at the first error.
Receiver selection and arguments retain once-only source order, followed by
the existing current-place checks. This stage adds no diagnostic code or recovery.

## Verification and target scope

The focused command selects `cloth.*Array*`, NullableRowsTest,
NullableBuffersTest, ForEachTest, BufferCountsTest, BufferProvidersTest,
TypeInfoTest, ClassValueIntegrationTest, StructMethod*Test, StandardList*Test and
BootstrapLexerTest through `:Compiler:test --tests`, with the offline flags below.
It passes **463 tests across 38 suites**, zero failures/errors/skips, in 3m 8s.
Exact XML reports and copy-verified hashes are archived under
`build/growable-arrays-stage8/focused-Compiler-test-results/` and
`focused-summary.json`.

Full project verification command:

```powershell
.\gradlew.bat :Compiler:test :Shuttle:test --rerun :Compiler:check :Shuttle:check --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

Both project checks pass in **10m 2s**, with 15 actionable tasks: two executed
and 13 up to date. The two native test tasks execute freshly; the unchanged
native-free generic/import/configuration tasks retain their passing reports.

| Task | Tests / suites | Execution |
| --- | --- | --- |
| Compiler test | 1,950 / 182 | Fresh unfiltered regression. |
| Shuttle test | 61 / 8 | Fresh full regression (`--rerun`). |
| Generic syntax | 86 / 10 | Up to date. |
| Import syntax | 23 / 2 | Up to date. |
| Import resolution | 54 / 4 | Up to date. |
| Import semantics | 33 / 1 | Up to date. |
| Shuttle configuration | 25 / 2 | Up to date. |

All tasks report zero failures, errors and skips. Exact copies of all 209 XML
reports and verified SHA-256 hashes are archived in `full-*-results/` and
`full-summary.json` under the Stage 8 evidence directory. `check-run.log` records
actual task execution. The focused reports are preserved separately.

| Target profile | Observed final evidence |
| --- | --- |
| `x86_64-pc-windows-msvc` | Native regression/probes, eight fresh example executions, verified IR/startup objects. |
| `i686-pc-windows-msvc` | Native regression/probes, eight fresh example executions, verified IR/startup objects. |
| `x86_64-unknown-linux-gnu` | Eight fresh native example executions under Ubuntu WSL x64, linked with cc 13.3.0; verified IR/startup objects. |
| `aarch64-pc-windows-msvc` | Verified IR/startup objects; native runner unavailable. |
| `aarch64-unknown-linux-gnu` | Verified IR/startup objects; native runner unavailable. |
| `x86_64-unknown-linux-musl`, `aarch64-unknown-linux-musl` | Verified IR/startup objects; native runners unavailable. |
| `x86_64-apple-darwin`, `aarch64-apple-darwin` | Verified IR/startup objects; macOS runners unavailable. |

All **24 fresh native runs** pass: the five growable/concrete examples, actual
Collections project, unchanged Bootstrap sample and complete documented example
on each available native profile. Linux runs execute Windows-generated objects
from checked executable models, linked with `cc -no-pie` in Ubuntu; no Linux JVM
is assumed. This is Linux x64 execution evidence, not a full Linux regression or
native ARM64/macOS/musl claim.

Both GrowableArrayRows and the actual imported Collections project additionally
verify executable IR and nonempty startup objects for all nine profiles. Six
other fixtures do so for Linux x64, for **24 archived IR/object pairs**. The
temporary helper follows the same checked-model, IR-parse/verify and emitObject
path as ImportPublicationTest; it changes no compiler behavior. `objects.tsv`,
`object-summary.json`, `probes.json`, individual output/link logs and binaries
retain the target/source evidence under `build/growable-arrays-stage8/`.

Publication checks pass for **27 documents, 955 relative links, 82 anchors and
seven feature skills**, including balanced fences, final newlines, whitespace
and the full skill-creator validator with temporary pinned PyYAML 6.0.3. The
documented complete example matches the executed source exactly. All 524
implementation/test/example baseline hashes remain unchanged; current primary
probe sources, outputs and artifacts have archived hashes. Evidence is recorded
in `publication-validation.json`, `preservation-baseline.json` and
`skill-validation.txt`. Temporary helpers and validation dependencies are removed
before completion.

All four Stage 8 tasks and all **39 array-plan tasks** are complete. The companion
generic plan retains its 17 completed tasks; later generic/protocol work remains
unchecked in its separate follow-up record. No release export, Git pin update or
consumer ownership migration is part of this array closure.
