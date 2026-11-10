# Generic types Stage 5: imports and the public List

[Stage 6](stage-6.md) now completes first-slice integration, caller audit and
publication. This record retains Stage 5's actual-library verification baseline.

Stage 5 adds the real public
[List.co](../../../../Standard-Library/cloth/collections/List.co) and verifies it
through explicit imports from a separate source root. Read the
[API contract](../../../../Standard-Library/cloth/collections/README.md) for the
nine methods, empty construction and element/lifetime behavior. The focused
[collections skill](../../cloth-collections/SKILL.md) owns future collection work;
the [generic contract](semantics.md) and [Stage 4](stage-4.md) retain language and
native storage rules. Generic plan Stage 6 remains open.

## Public source API

```cloth
using cloth.collections.List : List

fn main() {
    def values := List<int>::new
    values.append(1)
    values.insert(0, 2)
    def copied = values.copy()
    def saved = values.removeAt(1)
    values::delete
    println(saved)
    println(copied.toArray())
    copied::delete
}
```

This prints `1`, `[2, 1]` and normal completion. The maintained
[Collections project](../../../../examples/Collections/README.md) also demonstrates
independent exports and differently sized row elements. It uses only an App module
and explicitly imports the selected standard library.

The initial API uses append/get/insert/removeAt/size/clear/reserve/toArray/copy,
matching the maintained generic fixture. Index/count arguments and size results
are exact `usize`. Insert permits index equal to size; get/remove require an
existing index. Array guards retain their first runtime failure and fatal paths
produce no ordinary PEC report. Nullable arguments/results retain ordinary
explicit receiving annotations; no inference exception is added.

List uses a private `ListSlot<T> { T value }` and private `ListSlot<T>[] items`.
Methods are ordinary Cloth code. There is no List-specific compiler intrinsic,
native allocator binding, pointer API or generated concrete source implementation.
Slot values let array-valued elements have independent dimensions. Internal copy
assigns complete slots so absent nullable-field witnesses survive copying/removal
and source destruction. `toArray()` builds ordinary rectangular `T[]` with checked
receiving; mixed present row lengths fail. Class-valued elements keep identity and
remain caller-managed when a list is cleared/deleted. Every copy is a separate
list identity requiring explicit deletion.

The root [Library.co](../../../../Standard-Library/cloth/Library.co) imports List
so standalone library object validation checks its declarations. Imports do not
reexport: consumers still select `cloth.collections.List : List`. Public List
does not export its slot helper or field. T stays invariant and unconstrained;
unsupported generic interfaces/functions/method-owned parameters and constraints
remain deferred. Direct class indexing/assignment, foreach and collection `::size`
are not introduced. No Bootstrap consumer is migrated.

## Import, selection and implementation boundaries

Actual-library tests supply the original standard-library root with an explicit
`STANDARD_LIBRARY` role. Compiler discovery/binding uses original source identities
and defining-file private names. Repeated selective/wildcard routes, circular
imports and configured root aliases reuse one closed List/slot pair. A caller's
private same-spelled `ListSlot<T>` cannot capture the library's helper. Caller-private
actual types are allowed; same-spelled types in separate files remain distinct
in closed List identity and runtime typeinfo. Raw/wrong-arity arguments, invalid
ordinary value types, invariant conversion failures, private storage access and
non-exported helper imports retain their existing single source diagnostics.

Default development selection, explicit override, configured-root promotion,
namespace reservation and `--no-stdlib` keep the existing policies. Disabled
commands report the missing List import without writing an artifact. Explicit
ordinary API roots retain ordinary ambiguity/replacement behavior. The public
compiler facade still discovers no installation or project modules itself.

Compiler semantics and native lowering are unchanged. The existing checked-model
`LLVMEmitter.withFunctions` composition hook is now internal so test probes can
consume an already checked source graph. The shared poison harness accepts that
model; it does not concatenate imported source. It still validates the target and
closed-only emission guard. The original single-file probe path is preserved.
Gradle watches the actual `.co` library inputs and maintained example sources,
including in the native-free generic task and Shuttle consumer tests.

## Verification (2026-11-08)

[StandardListImportsTest](../../../../Compiler/src/test/kotlin/cloth/StandardListImportsTest.kt)
(9 tests) checks the actual source/root, substituted private slot, exact public
method set, circular/repeated routes, class/configuration aliases, same-spelled
private templates, caller-private arguments, privacy/non-reexports, raw/arity/type
errors, invariance, absent indexing/traversal intrinsics and selection roles.
It also runs without native platform binaries.

[StandardListTest](../../../../Compiler/src/test/kotlin/cloth/StandardListTest.kt)
(7 tests) executes the maintained example through the public facade. Its element
matrix uses int, string, nullable wide int, usize/isize, buffered structs, enums,
class identities, typeinfo and independently sized arrays. Additional cases check
same-spelled private actual metadata, empty/nullable/fixed row export, copy versus
mixed-row export failure, insertion at size and get/remove/insert bounds. The
matrix verifies executable IR and object emission on all nine ABI profiles.

[StandardListStorageTest](../../../../Compiler/src/test/kotlin/cloth/StandardListStorageTest.kt)
(5 tests) loads that same actual module into the allocator/poison harness. It
verifies escaping get/remove/export/copy results, 1,000 buffered helper-frame cycles
returning to reserved live bytes, reserved append cost independent of old element
count, complete absent nullable witnesses and incompatible reappearance failure.
Export/witness probe modules also verify IR and objects on all nine profiles.
These tests preserve the existing cleanup proof and explicit class lifetime.

[StandardListConsumerTest](../../../../Shuttle/native/src/jvmTest/kotlin/shuttle/StandardListConsumerTest.kt)
(3 tests) consumes the library through the public compiler API and real child
command processes. Both cloth and Shuttle run the actual example with default
and explicit library selection on Windows x64/x86. Disabled commands fail once
with no artifact. An AArch64 Linux object verifies the plain request boundary.

The added [ToolchainBundleTest](../../../../Shuttle/native/src/jvmTest/kotlin/shuttle/ToolchainBundleTest.kt)
exports a temporary clean pinned snapshot containing the actual List/Library/module
bytes, checks byte-identical List export, then compiles an importing AArch64 Linux
object through the installed-layout selection and public facade. Existing dirty/
pin-mismatch rejection tests continue to pass. This is packaging evidence for a
temporary snapshot, not a release from the working checkout. The existing untracked
`Standard-Library/cloth/math/` files are preserved, and no working submodule commit,
Git pin update or release publication is performed. Release preparation still
requires intended clean/pinned library history.

Windows x64/x86 execute native programs and poisoned-memory probes. Windows
ARM64, Linux GNU/musl x64/ARM64 and macOS x64/ARM64 have verified IR/object evidence;
they are not claimed as native runs.

The final broad focused command is:

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.StandardList*' --tests 'cloth.Generic*' --tests 'cloth.StandardLibrary*' --tests cloth.GrowableArrayStorageTest --tests cloth.ArrayBackedCollectionTest --tests cloth.ArrayBackedCollectionStorageTest :Compiler:genericSyntaxTest :Compiler:importSemanticsTest :Shuttle:test --tests shuttle.StandardListConsumerTest --tests shuttle.ToolchainBundleTest :Shuttle:configurationTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

It passes **191 Compiler tests in 25 suites**, **9 focused Shuttle tests in two
suites**, **86 native-free generic tests in ten suites**, **33 native-free import
tests**, and **25 native-free Shuttle configuration tests in two suites**, with
zero failures/errors/skips (1m 37s). Repeated task suites are not additional unique
Compiler/Shuttle tests. Reports, hashes and CLI evidence are archived under
`build/generic-types-stage5/`.

The full regression passes in **10m 3s** on the final production/test sources:

| Task | Suites | Tests | Result |
| --- | ---: | ---: | --- |
| Compiler test | 182 | 1,950 | Fresh execution; zero failures/errors/skips. |
| Compiler genericSyntaxTest | 10 | 86 | Fresh execution; zero failures/errors/skips. |
| Compiler importSemanticsTest | 1 | 33 | Up to date from the focused run; zero failures/errors/skips. |
| Shuttle test | 8 | 61 | Fresh execution; zero failures/errors/skips. |
| Shuttle configurationTest | 2 | 25 | Up to date from the focused run; zero failures/errors/skips. |

```powershell
.\gradlew.bat :Compiler:test :Compiler:genericSyntaxTest :Compiler:importSemanticsTest :Shuttle:test :Shuttle:configurationTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

The new suites add 21 Compiler tests and four Shuttle tests. Eighteen CLI probes
verify the real example through both commands on Windows x64/x86, all-nine-profile
objects, one-error raw/private/field rejection, mixed-row fatal export and the
actual standard-library root's standalone object validation. Logs, original source
copies and artifact/source hashes are retained with the XML summaries.

The new collections skill and seven updated skills pass the bundled validator
with temporary pinned PyYAML 6.0.3. Publication checks pass for 27 documents,
1,008 local links, 101 anchors, 15 stage source/build files and 243 archived XML
hashes, with clean whitespace, balanced fences and final newlines. Fourteen
generic tasks are checked (Stages 0-5); the array plan has 35 checked tasks and
its actual public List gate is closed. Temporary scripts, dependency files and
probes are removed before completion. Generic Stage 6, collection protocols,
working release preparation and Bootstrap migration remain separate.
