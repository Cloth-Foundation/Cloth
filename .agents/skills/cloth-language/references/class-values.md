# Class fields and aggregate values

Stage 4 landed on 2026-11-05. It integrates class identities with ordinary value contexts and gives
each instance ownership of the buffers in its fields. [Object lifetime](semantics.md#object-lifetime-and-nullability) ·
[Class execution](class-lifetime.md) · [Runnable example and publication](class-publication.md)

## Value and storage rules

- Functions, ordered results, nullable values, arrays, structs, and foreach
  snapshots carry class identity. Array and struct copies retain independent
  storage for their own contents. A copy of a class-valued element refers to
  the same instance. Destroying a container's value storage leaves class
  lifetime to explicit deletion. [Class value tests](../../../../Compiler/src/test/kotlin/cloth/ClassValueIntegrationTest.kt)
- Instance fields own recursive string, array and inline struct buffers.
  Ordinary reads snapshot buffered and indirect values before later operands
  can replace the field or delete the instance. Deletion releases the field
  allocations after the destructor. [Class storage](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitClassStorage.kt) ·
  [Recursive copies](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/EmitStringArena.kt)
- Array fields keep their initialized dimensions. A nullable field first
  establishes dimensions when a present value is assigned and retains known
  dimensions across absence. The retained witness stores extents separately
  from live element and shape allocations. Nullable rows can establish a
  previously unknown inner extent. [Array lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/EmitArray.kt)
- A nested write saves its receiver identity and evaluates each index once
  before the right-hand side. It checks the same path in the current field
  before storing. An index that returns or jumps skips the rest of the path and
  the store. A compound write captures its old operand before the
  right-hand side. A struct method backed by a class field uses a private,
  call-scoped resolver for the same captured path, including nested receiver
  calls. The resolver checks lifetime, nullable presence and bounds before
  accessing current storage. [Expression lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt) ·
  [Receiver resolver](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitReceiverAccess.kt)
- Whole-field replacement detaches and frees the old allocation chain after
  copying the new value. Nested buffered writes repack the live root field
  before freeing its previous chain. This can copy the entire field per
  buffered nested write. Batched writes keep their prepared values in the
  call frame until each store publishes. Scalar or identity writes need no repack. Proven
  class-owned writes can release frame temporaries at iteration exit.
  [Class storage](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitClassStorage.kt) ·
  [Temporary analysis](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/ArrayTemporaryAnalysis.kt)
- Null checks establish field proofs on a stable path. Potentially aliasing
  writes and source calls forget those proofs conservatively. A saved local
  value keeps the ordinary binding rules. Metadata queries on mutable fields
  use the current descriptor and its runtime checks. [Presence analysis](../../../../Compiler/src/main/kotlin/cloth/semantic/NullFlowAnalysis.kt) ·
  [Metadata analysis](../../../../Compiler/src/main/kotlin/cloth/semantic/MetadataAnalysis.kt)

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
