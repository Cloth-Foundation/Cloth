# Class fields and aggregate values

Stage 4 landed on 2026-11-05. It integrates class identities with ordinary value contexts and gives
each instance ownership of the buffers in its fields. [Object lifetime](semantics.md#object-lifetime-and-nullability) ·
[Class execution](class-lifetime.md) · [Runnable example and publication](class-publication.md)

## Value and storage rules

[Generic Stage 4](../../cloth-generics/references/stage-4.md) applies these rules
to closed classes/structs, including private generic slot arrays. Destruction
releases each container's recursive field buffers while class-valued elements
retain caller-managed identity. Constructors, generic bases and destructor chains
use the canonical closed owner and its source-linked callable view.

- Functions, ordered results, nullable values, arrays, structs, and foreach
  snapshots carry class identity. Array and struct copies retain independent
  storage for their own contents. A copy of a class-valued element refers to
  the same instance. Destroying a container's value storage leaves class
  lifetime to explicit deletion. [Class value tests](../../../../Compiler/src/test/kotlin/cloth/ClassValueIntegrationTest.kt)
- Instance fields own recursive string, array and inline struct buffers.
  Ordinary reads snapshot buffered and indirect values before later operands
  can replace the field or delete the instance. Deletion releases the field
  allocations after the destructor. [Class storage](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassStorage.kt) ·
  [Recursive copies](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitStringArena.kt)
  [Growable arrays Stage 2](../../cloth-arrays/references/growable-arrays-stage-2.md)
  routes array field copies through typed owned capacity and live element arenas,
  including unsized array fields inside stored structs. Existing nested buffered
  writes still repack the root field.
  [Stage 4](../../cloth-arrays/references/growable-arrays-stage-4.md) executes
  one-dimensional field append/reserve directly through the existing owner,
  preserving capacity and previous payloads without per-call repacking. Nested
  class-backed paths still recheck saved identities, presence and bounds after
  explicit argument effects. Ordinary field reads/exports remain independent.
  [Stage 5](../../cloth-arrays/references/growable-arrays-stage-5.md) adds direct
  field insert/removeAt/clear with slot-head shifts and reusable capacity. Removed
  buffered values receive independent storage before field slot cleanup. Clearing
  or removing class identities does not delete their objects.
  [Stage 6](../../cloth-arrays/references/growable-arrays-stage-6.md) executes
  whole-row rectangular field edits. Removed rows retain independent buffers and
  parent shape witnesses after owner deletion; nullable field witnesses learned
  by mutation survive null and aggregate copies.
  The [final array audit](../../cloth-arrays/references/growable-arrays-stage-8.md)
  retains direct mutator publication and independent field exports, including
  source List methods. List clear/destruction releases value buffers while
  class-valued elements still require their explicit deletion policy.
- Array fields keep explicit extents and initialized inner dimensions. A nullable
  field first establishes inner dimensions when a present value is assigned and
  retains them across absence. [Growable arrays Stage 1](../../cloth-arrays/references/growable-arrays-stage-1.md)
  permits whole replacement of `T[]` with another outer count. The retained witness
  stores extents separately
  from live element and shape allocations. Nullable rows can establish a
  previously unknown inner extent. [Array lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArray.kt)
- A nested write saves its receiver identity and evaluates each index once
  before the right-hand side. It checks the same path in the current field
  before storing. An index that returns or jumps skips the rest of the path and
  the store. A compound write captures its old operand before the
  right-hand side. A struct method backed by a class field uses a private,
  call-scoped resolver for the same captured path, including nested receiver
  calls. The resolver checks lifetime, nullable presence and bounds before
  accessing current storage. [Place lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitPlaces.kt) ·
  [Receiver resolver](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitReceiverAccess.kt)
- Whole-field replacement detaches and frees the old allocation chain after
  copying the new value. Nested buffered writes repack the live root field
  before freeing its previous chain. This can copy the entire field per
  buffered nested write. Batched writes keep their prepared values in the
  call frame until each store publishes. Scalar or identity writes need no repack. Proven
  class-owned writes can release frame temporaries at iteration exit.
  [Class storage](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassStorage.kt) ·
  [Temporary analysis](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/ArrayTemporaryAnalysis.kt)
- Null checks establish field proofs on a stable path. Potentially aliasing
  writes and source calls forget those proofs conservatively. A saved local
  value keeps the ordinary binding rules. Metadata queries on mutable fields
  use the current descriptor and its runtime checks. [Presence analysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/NullFlowAnalysis.kt) ·
  [Metadata analysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/MetadataAnalysis.kt)

## Verification

The final focused Windows run passed 287 tests across 28 suites, including a native
poisoned-free probe for repeated replacement and escaped field snapshots. Class
identity and buffered-field fixtures execute on Windows x64 and x86. Both
fixtures verify IR and emit objects across all nine target profiles.
[Class value tests](../../../../Compiler/src/test/kotlin/cloth/ClassValueIntegrationTest.kt) ·
[Poisoned-memory probe](../../../../Compiler/src/test/kotlin/cloth/ArrayTemporaryCleanupTest.kt)

The full Windows run passed 1,387 tests across 119 suites in 5m 47s with zero
failures, errors or skips before the batched-write follow-up. The final focused
run covers that fix, container deletion, and both target matrices.

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Class*Test' --tests 'cloth.Struct*Test' --tests 'cloth.Nullable*Test' --tests 'cloth.OptionalArguments*Test' --tests cloth.ArrayTemporaryCleanupTest --tests cloth.ArraysTest --tests cloth.ForEachTest --tests cloth.CallResolutionTest --tests cloth.TypeInfoTest --offline --console=plain --no-daemon
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```
