# Generic types Stage 4: native values, storage and metadata

This record retains Stage 4's native baseline. [Stage 5](stage-5.md) now supplies
the actual public List source and its imported-library verification.

Stage 4 lowers checked closed generic classes and structs through Cloth's existing
private callable ABI, value storage and explicit class lifetime. The
[contract](semantics.md) retains the confirmed first slice;
[Stage 3](stage-3.md) owns symbolic and closed semantic checking. Public shipped
List delivery remains Stage 5. Bootstrap migration is separate.

## Supported execution

This source builds and runs without concrete source generation:

```cloth
typedef<class> Box<T> (def<T> value) {
    pub mt<T> get() = value
    pub mt<typeinfo> argument() = T::type
}

fn main() {
    def words = Box<string>::new("kept")
    def saved = words.get()
    println(words.argument())
    words::delete
    println(saved)
}
```

It prints `string`, `kept` and normal completion. Explicit arguments remain
invariant. Unused templates are checked symbolically but have no open native
layouts or bodies. Raw names still fail as C3110; an unconstrained `T + 1` still
fails at declaration even when unused. Private helpers remain legal storage and
illegal public signature leaks. Native support adds no capability to unknown T.

The maintained [imported List and Maybe fixtures](acceptance-fixtures.md) execute
as real generic source. The List fixture uses a private `ListSlot<T>[]` field,
ordinary methods and no compiler List intrinsic. `List<int[]>` preserves independent
element lengths. `copy()` preserves those values; `toArray()` receives into an
ordinary rectangular array and fails on mixed row lengths. These are compiler
acceptance fixtures, not the shipped `cloth.collections.List` module.

Primary/secondary constructors, generic bases, destructor chains, class checks,
struct operators, optional defaults, deferred blocks, ordered results and owner
type receivers use their checked closed signatures and body views. Nullable
substitution uses one carrier, including nullable wide values. Arrays retain fixed
or growable identity and suffix placement. Target `usize`/`isize`, enums, typeinfo,
classes, structs, nested generics and ordinary scalar arguments keep exact types.

## Native integration and invariants

[EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
now accepts actual closed models. C4033 remains for symbolic models and legacy
type/signature APIs without a closed context. Callable symbols and closed owner
descriptors key emitted bodies and concrete class tags. Never key them by the
shared original declaration AST or by displayed text. Primary construction reads
the source-linked owner view; field layout and field ownership use the canonical
closed descriptor. Existing dispatch, deletion and checked generation handles
apply to every specialization.

The shared storage pipeline consumes substituted field/element/result types:

- Strings, arrays and structs receive recursive independent buffers. Get, remove,
  export and function results survive the source frame or class being destroyed.
- Typed relocation transfers live struct-slot payloads and rehomes owners. It does
  not recursively clone old slots just because the container grows. Insert/remove
  preserve order and ownership; reserved append cost stays independent of old size.
- Nullable array fields retain complete hidden shape witnesses while absent,
  across slot copies, relocation, removal and source destruction. Incompatible
  reappearance fails at the existing shape guard.
- Class-valued elements copy identity. Clearing or destroying a container releases
  its buffers without deleting those objects. Callers still explicitly delete them.

[CStruct](../../../../Compiler/src/main/kotlin/cloth/data/CStruct.kt) memoizes its
buffered-value classification after field discovery. Shared closed field DAGs
therefore take one classification per descriptor instead of exponential traversal.
Fields and cycle checks must be finalized before consuming this fact. Re-defining
that descriptor's fields clears its cached classification. No LLVM handles enter
ValueType recipes. The existing exact-arithmetic
[StructStorageLayout](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/StructStorageLayout.kt)
rejects oversized inline generic field DAGs as C3052 before asking LLVM for layout.

The scope/suffix cleanup proof is unchanged. Poisoned frees and 1,000 helper-frame
cycles establish the tested paths, not bounded memory for arbitrary caller loops.
The process-lifetime class table remains separate from balanced payload/field arenas.
No GC, public pointer/reference syntax or exported cross-module ABI is added.

## Exact metadata and bounded expansion

[EmitTypeInfo](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitTypeInfo.kt)
shares a module-local `Types` registry through every callable's
[EmitString](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitString.kt).
Canonical nominal descriptors already encode original declaration plus ordered
exact normalized arguments. Registry ordinals give them short module-local keys;
array/nullable wrappers include their structure and extents. Primitive identities
retain their separate canonical keys. Display names, source-name strings and
truncated diagnostics never decide descriptor equality. Same-spelled private
actuals in separate files therefore produce different `Box<Hidden>` typeinfo even
though their public text matches. Repeated uses of one closed type share a descriptor.

Canonical text retains explicit numeric widths and original type names. Human
display remains bounded at 4,096 UTF-16 units with surrogate-safe ellipsis.
[GenericTypeMetadata](../../../../Compiler/src/main/kotlin/cloth/data/GenericTypeMetadata.kt)
measures the expanded canonical UTF-8 length on the shared argument DAG before
allocating flattened text. The implementation budget is **1,048,576 UTF-8 bytes
per generic nominal metadata spelling**, excluding the string terminator. Exactly
that many bytes are accepted; the next byte fails as **C4034 /
GENERIC_METADATA_SIZE**, with the original generic declaration label. Intermediate
lengths saturate above the budget. Metadata is never silently truncated. This is
a compiler resource policy, separate from the existing depth/key-count limits.
Object emission fails before creating its output directory/artifact on this guard.

## Verification (2026-11-08)

[GenericEmissionTest](../../../../Compiler/src/test/kotlin/cloth/GenericEmissionTest.kt)
executes the maintained imported List/Maybe fixtures, two simultaneous owners,
defaults/deferred/results/operators, generic inheritance/secondary construction/
destruction, recursive class identities, same-spelled private actual metadata,
unused templates and the ordinary value-argument ABI matrix.

[GenericStorageTest](../../../../Compiler/src/test/kotlin/cloth/GenericStorageTest.kt)
uses the existing allocator/poison harness for independent exported/removed/copied
values, 1,000 buffered helper cycles, constant-cost reserved string slots, moved
wide/nullable/buffered struct fields, retained absent witnesses and explicit class
identity. Runtime negatives cover incompatible nullable reappearance and mixed-row
rectangular export; fatal paths produce no ordinary completion report.

[GenericNativeLimitsTest](../../../../Compiler/src/test/kotlin/cloth/GenericNativeLimitsTest.kt)
checks symbolic-model refusal, exact UTF-8 budget including supplementary Unicode,
shared-DAG metadata rejection on all nine profiles and oversized inline generic
layouts on Windows x64/x86. All guard failures retain one located diagnostic and
no output artifact. The updated
[GenericStageBoundaryTest](../../../../Compiler/src/test/kotlin/cloth/GenericStageBoundaryTest.kt)
emits closed generic objects on all nine profiles while retaining early name/body
errors and the unresolved legacy API guard.

Windows **x86_64-pc-windows-msvc** and **i686-pc-windows-msvc** execute native
programs and poisoned-memory probes. Verified executable IR and nonempty object
emission cover all nine supported ABI profiles:

| OS/environment | Architectures | Evidence |
| --- | --- | --- |
| Windows MSVC | x86_64, i686 | Native execution, verified IR and objects. |
| Windows MSVC | aarch64 | Verified IR and objects; no native execution. |
| Linux GNU | x86_64, aarch64 | Verified IR and objects; no native execution. |
| Linux musl | x86_64, aarch64 | Verified IR and objects; no native execution. |
| macOS Darwin | x86_64, aarch64 | Verified IR and objects; no native execution. |

The broad focused command is:

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Generic*' --tests cloth.TypeInfoTest --tests cloth.ISizeTest --tests cloth.ArrayBackedCollectionTest --tests cloth.ArrayBackedCollectionStorageTest --tests cloth.StructEmissionTest --tests cloth.ClassInheritanceTest :Compiler:genericSyntaxTest :Compiler:importSemanticsTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

The final focused run passes **155 tests in 20 suites** (58s), with zero failures,
errors or skips. It includes the revised
[ArrayBackedCollectionTest](../../../../Compiler/src/test/kotlin/cloth/ArrayBackedCollectionTest.kt),
which now executes an ordinary generic collection rather than expecting the old
compilation boundary. Native-free tasks pass **77 generic tests in nine suites**
and **33 import tests in one suite**; those repeat existing Compiler suites.

Nineteen CLI probes execute the unspecialized generic collection and nullable
fixtures on Windows x64/x86, reject mixed-row exports at runtime, emit objects
for all nine ABI profiles, and reject raw arguments, opaque arithmetic, invariance
violations and excessive metadata with one expected diagnostic and no object.
Runtime failures produce no ordinary completion report. XML copies, SHA-256
manifests, CLI source/logs/binary hashes and publication checks live under
`build/generic-types-stage4/`.

The complete regression uses:

```powershell
.\gradlew.bat :Compiler:test :Compiler:genericSyntaxTest :Compiler:importSemanticsTest :Shuttle:test --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

The final full regression passes in **9m 34s** on the final production/test sources:

| Task | Suites | Tests | Result |
| --- | ---: | ---: | --- |
| Compiler test | 179 | 1,929 | Fresh execution; zero failures/errors/skips. |
| Compiler genericSyntaxTest | 9 | 77 | Up to date from the final focused run; zero failures/errors/skips. |
| Compiler importSemanticsTest | 1 | 33 | Up to date from the final focused run; zero failures/errors/skips. |
| Shuttle test | 7 | 57 | Fresh execution; zero failures/errors/skips. |

The three new suites add 23 tests. Existing generic/collection boundary tests now
expect successful closed emission; symbolic/unresolved models retain C4033.
`full-run.log` records four executed and eight up-to-date actionable Gradle tasks.
`focused-summary.json` and `full-summary.json` preserve task counts, timestamps,
archived XML copies and per-suite SHA-256 hashes.

All 11 affected skills pass the bundled metadata validator with temporary pinned
PyYAML 6.0.3. Publication validation covers 24 documents, 1,123 local links, 128
anchors, 16 stage source/build files and 226 archived XML hashes. Whitespace,
fences, final newlines, CLI artifacts/hashes and plan scope checks pass. Eleven
generic tasks are checked (Stages 0-4); the array plan stays at 34, with public
List absent. Temporary probes, scripts and dependencies are removed before
completion. Stages 5-6 and the public List gate remain open. No Bootstrap or
standard-library source is migrated.
