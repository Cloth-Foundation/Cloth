# COLL-2 Stage 1 runtime deletion authority

The [owning collections contract](../SKILL.md#owning-collections) defines the
public behavior. Stage 1 implements the private runtime operations used by the
source list in Stage 2. Stage 3 adds diagnostics for provable violations.

## Private bindings

The selected standard library can declare these bindings on `OwningList<T>`
in `cloth/collections/OwningList.co`. The class is final. Each binding is a
private ordinary method with one required `Object` argument and a void result.
The receiver supplies the list identity. The `Object` argument preserves the
underlying identity when an element widens from a class or an interface view.
[Binding checks](../../../../Compiler/src/main/kotlin/cloth/semantic/LibraryIntrinsics.kt)

```cloth
mt acquire(Object item) = intrinsic "collections.ownership.acquire"
mt release(Object item) = intrinsic "collections.ownership.release"
mt deleteChild(Object item) = intrinsic "collections.ownership.delete"
```

## Runtime checks

- When the source graph uses an ownership binding, each object slot carries
  the full generation-checked owner identity. Zero means no collection owns
  the object. Fresh and recycled slots start with zero. The existing
  liveness, state and read-only index validation checks still apply.
- Acquisition requires live identities and an unowned object. Walk the
  receiving list's chain of owners before recording the new owner. Finding
  the item in that chain rejects a cycle, including insertion of the list
  into itself. A duplicate or second owner fails before any ownership change.
- Release requires the recorded owner and clears the owner identity. The
  object stays live and every live alias can use ordinary `::delete` again.
- Ordinary `::delete` requires an unowned object. The private owned deletion
  operation requires the recorded owner and can use direct destructor `self`
  while the list is destroying. Authority is checked before the object's
  destructor runs. Both paths dispatch the concrete destructor chain, release
  fields and active state, and recycle the slot through ordinary deletion.

The [object table](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassRuntime.kt)
owns the checked links and fails at the first violation. The [callable emitter](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
shares destructor dispatch and slot release between the two deletion paths.

## Verification

[CollectionOwnershipRuntimeTest](../../../../Compiler/src/test/kotlin/cloth/CollectionOwnershipRuntimeTest.kt)
imports a selected-library fixture with the three private bindings and calls it
through ordinary Cloth source. Its native cases cover same-list and cross-list
duplicates, direct and indirect cycles, incorrect deletion and release,
reacquisition after release, class and interface aliases, nested destruction,
and stale identities after slot reuse. The fixture also checks source origin,
private access, and exact signatures.

On 2026-11-13, 491 tests passed across 58 focused compiler suites, with zero
failures or skips. The run covered collection operations, List, classes,
interfaces, states, generics, and standard-library runtime and binding checks.
The ownership fixture ran natively on Windows x64 and x86. It verified LLVM IR
and emitted objects for all nine target profiles.

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Collection*Test' --tests 'cloth.StandardList*Test' --tests 'cloth.Class*Test' --tests 'cloth.Interface*Test' --tests 'cloth.*State*Test' --tests 'cloth.Generic*Test' --tests cloth.StandardLibraryRuntimeTest --tests cloth.ImportPublicationTest --tests 'cloth.FileIOTest.compiler bodies require a registered declaration origin and exact signature*' --offline --console=plain
```
