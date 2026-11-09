# Growable arrays Stage 1: types, receivers and effects

Status: Stage 1 complete. [Stage 2](growable-arrays-stage-2.md) supersedes the
storage boundary described below. [Stage 3](growable-arrays-stage-3.md) adds the
supported source append/reserve slice; [Stage 4](growable-arrays-stage-4.md) adds
stored field growth and cross-method selection observations.
[Stage 5](growable-arrays-stage-5.md) executes the remaining one-dimensional
mutators and independent removed results. [Stage 6](growable-arrays-stage-6.md)
executes rectangular mutators and value integrations. This record
preserves Stage 1's implementation and verification snapshot.
Implemented 2026-11-07 against the current working tree. The
[confirmed contract](growable-arrays.md) owns intended semantics and the
[plan](../../../../todo/growable-arrays-and-lists-plan.md) owns later storage,
execution, collection and publication stages.

## Supported slice

Explicit `T[]` annotations retain their growable type. An unannotated literal
still infers `T[N]`; copying a growable binding or receiving a result declared
`T[]` retains `T[]`. Exact element identity, nullable wrappers and numeric literal
context remain unchanged. Fixed/growable reception keeps explicit fixed extents
and runtime checks for unknown counts.

Whole-array replacement can execute with another outer count for a growable
local, parameter or class field, including nullable arrays and nullable rows.
This uses the existing independent-value transfer and descriptor layout. It
does not require capacity or implement append. Explicit fixed extents and
established inner shape remain constrained through replacement and absence.

```cloth
fn<int[]> replaced(int[] values) {
    values = [3, 4, 5]
    return values
}

fn main() {
    def<int[]> original = [1, 2]
    def copy = original
    copy = replaced(copy)
    println(original) // [1, 2]
    println(copy)     // [3, 4, 5]
}
```

All five mutator signatures type-check with stored or temporary growable
receivers: append, insert, removeAt, clear and reserve. Only removeAt returns T;
index/count arguments use exact target usize. `:=` and `final` permit these
mutations. Read-only defaults, fixed receivers, unproven nullable receivers,
read-only enum payloads and shape-changing embedded-row operations are checked.
An independent extracted growable row is a distinct receiver.

Native emission of these mutators stops at **C4032**, before output/linker work.
Their bodies do not execute. Unsized struct array fields now type-check and can
participate in receiver plans, but native emission of their declarations also
uses C4032 until their retained-shape storage is implemented. Existing fixed-size
struct array fields remain supported. No capacity descriptor, in-place growth,
public generic List or generic-type facility is introduced in Stage 1.

## Source ownership and invariants

- [CArray](../../../../Compiler/src/main/kotlin/cloth/data/PrimitiveTypes.kt)
  treats omitted extents as declared type properties. `inferArraySizes` no longer
  fills them from an initializer. `commonArrayShape` keeps an unspecified extent
  when merging fixed and unspecified types. Literal construction separately
  preserves contextual element identity and actual literal extents, including
  nullable elements/rows and class element contexts.
- [ArrayShapeAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/ArrayShapeAnalysis.kt)
  owns definite current counts and retained rectangular witnesses, separate from
  type identity. It follows source-order snapshots for bindings, grouped results,
  arguments and returns; checks known mismatches; and conservatively merges
  branches or forgets current outer counts across loops and deferred captures.
  First present rows can establish inner witnesses. Class fields use declared
  extents rather than optimistic facts about aliased instances. Unknown dimensions
  retain runtime checks. Shape facts are not a public constant-expression feature.
- [SemanticModel](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/SemanticModel.kt)
  owns per-occurrence array shape facts and committed mutation plans. New tables
  participate in `FactCopies`, so rejected callable candidates cannot publish
  receiver or argument facts. Operator body specializations run the same shape
  checks in their own body model.
- [ArrayAttachments](../../../../Compiler/src/main/kotlin/cloth/builtin/attachments/ArrayAttachments.kt)
  specializes signatures by concrete array element type and compilation target.
  User nominal types are not retained in a global attachment cache. The
  `MUTATING_RECEIVER` storage contract cannot opt into read-only member copy
  elision or closed temporary cleanup. The reusable definition retains no LLVM
  handles and the placeholder body must never be reached by native emission.
- [BodyTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/BodyTyping.kt)
  uses ordinary primitive argument resolution, then publishes an
  [ArrayMutationPlan](../../../../Compiler/src/main/kotlin/cloth/semantic/ArrayMutationPlan.kt).
  Its receiver is a stored place, a class-owned stored path, or a temporary,
  separate from the explicit value arguments. Preserve that distinction during
  storage lowering; passing the receiver as an ordinary copied array would lose
  writes to the original destination.
- [NullFlowAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/NullFlowAnalysis.kt)
  checks receiver presence after arguments, records transitive captured/loop
  writes, and forgets indexed descendant proofs for length-changing operations,
  including possible class aliases. Reserve preserves those logical proofs.
  Nullable removeAt results acquire no invented presence guarantee. Receiver
  hints distinguish nominal methods with the same names, including constructor,
  alias, source-function and chained method results; the array catalogue cannot
  change their ordinary source-method presence contracts.
- [ClassLifetimeAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/ClassLifetimeAnalysis.kt)
  remembers evaluated object handles to recheck a captured array field owner
  after argument effects without reevaluating the owner expression. Provably
  deleted owners produce C3092. Unknown identity/lifetime cases remain runtime
  obligations for later receiver lowering.
- `value::type` retains declared `T[]` identity even when a current count is
  known; `value::size` reads its runtime count. Metadata and type extents cannot
  accidentally cache a growable receiver's initial length.
- [RetainedArrayShape](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArray.kt)
  distinguishes a binding constraint from a rectangular child constraint.
  Binding constraints omit only an unsized outer count. Inner axes and explicit
  fixed outer extents still match. Owned nullable-row root publication updates
  the root count while retaining inner witnesses; ordinary row publication keeps
  all dimensions constrained. Never apply the binding exception to embedded rows.
- [EmitValueTransfer](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitValueTransfer.kt)
  and [class storage](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassStorage.kt)
  apply that receiving distinction during whole replacement. Independent copies,
  saved nullable-row vectors and outgoing buffers retain their original owner.
- [EmitFunctions.validate](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
  enforces the storage-stage boundary for every checked body, including operator
  specializations. Do not remove it until real lowering supports the selected
  operation/receiver slice. A discarded or unreachable mutating call must not
  silently become a read-only no-op.

## Diagnostics

- **C3107 / INVALID_ARRAY_MUTATION:** fixed receiver or a shape-changing embedded
  row operation. Original source spans identify the invalid receiver/method.
- **C4032 / UNSUPPORTED_ARRAY_MUTATION:** valid mutator typing or unsized struct
  array field declaration reaches native emission before the required storage
  stage. The message distinguishes operation lowering from struct field storage.
- Existing argument/type, shape (C3038), default (C3066), nullable receiver
  (C3062) and deleted-owner (C3092) diagnostics retain their contracts. All paths
  stop at the first detected error; there is no recovery or diagnostic collection.

## Verification

[GrowableArrayTypingTest](../../../../Compiler/src/test/kotlin/cloth/GrowableArrayTypingTest.kt)
passes 22 tests for inference/current-count separation, five signatures, receiver
owners and temporaries, fixed/embedded rejections, defaults, nullable proofs,
class aliases and lifetime, rectangular witnesses, exact target arguments,
reused 32/64-bit ASTs, candidate isolation, branch/loop/capture effects, metadata,
grouped reception, fixed argument/result constraints, nominal method-name
collisions and located stage boundaries.

[GrowableArrayReceptionTest](../../../../Compiler/src/test/kotlin/cloth/GrowableArrayReceptionTest.kt)
passes five tests. Four execute on Windows x64 and x86, checking changing outer
counts, nullable row/whole-array witnesses, independent saved values, grouped
copies, parameters/results and class field replacement. The fifth verifies
executable IR and emits objects across all nine existing target profiles.

Both feature suites pass in the final full regression, with zero failures,
errors or skips. To rerun just those suites:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.GrowableArrayTypingTest --tests cloth.GrowableArrayReceptionTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

Their final XML is retained at `build/growable-arrays-stage1/focused-test-results/`.
Existing array, nullable, foreach, split and class tests migrate only obsolete
fixed-initial-outer-count assumptions; fixed extents and inner-shape failures
retain explicit coverage. Native Linux/macOS/AArch64/musl execution is not claimed
for the new Stage 1 fixtures.

The final full offline run passes in **7m 44s**:

```powershell
.\gradlew.bat :Compiler:test :Shuttle:test --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

All **1,679 Compiler tests across 153 suites** and **57 Shuttle tests across
seven suites** pass, with zero failures/errors/skips. This includes all 27 new
feature tests and the migrated array, grouped-result, foreach, nullable and member
storage regressions. Compiler XML spans 2026-11-08 04:44:03-04:50:56 UTC, still
2026-11-07 in America/Phoenix. Full XML is preserved under
`build/growable-arrays-stage1/compiler-test-results/` and
`build/growable-arrays-stage1/shuttle-test-results/`.

Publication checks pass for all nine changed skills with the full skill-creator
validator, 22 documents and 847 local link targets, balanced fences, whitespace
in 40 source/test/example files, and scoped Git diffs. The plan records only
Stages 0-1 complete. Temporary PyYAML validation dependencies are removed after
checking; the repository's existing changes are preserved.

## Later stages

Stage 2 owns capacity/owner representation and typed relocation. Stages 3-6
implement the mutators, efficient class/aggregate publication, editing operations
and rectangular integration. Preserve typed receiver plans, checked argument
snapshots, explicit class deletion, first-error behavior and independent foreach
snapshots. Generic List retains its separate generic-types dependency.
