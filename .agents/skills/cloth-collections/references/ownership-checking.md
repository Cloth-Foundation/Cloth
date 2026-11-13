# COLL-2 Stage 3 compile-time ownership checks

After a successful `append`, the list has deletion authority over the
object's identity. Local copies, upcasts, and interface views of that identity
can still use and mutate the object while it is live. `removeAt` returns the
live object and restores ordinary deletion through any live alias.
[Public API](owning-list.md)

```cloth
using cloth.collections.OwningList : OwningList

typedef<class> Item (pub def<int> value)

fn main() {
    def list = OwningList<Item>::new
    def item = Item::new(10)
    list.append(item)
    def alias = list[0]
    alias.value++
    list.removeAt(0)
    alias::delete
    list::delete
}
```

Moving `alias::delete` before `removeAt(0)` raises `C3138`. The same code
reports an insertion when Cloth can prove the element already has an owner
or the insertion closes an ownership cycle. It labels the rejected operation
and, when there is one known acquisition site, the earlier insertion.
A provably destroyed object uses the existing `C3092` lifetime diagnostic.

## Proofs and runtime checks

[ClassLifetimeAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/ClassLifetimeAnalysis.kt)
tracks ownership alongside existing local identity and liveness facts. It
recognizes calls by the selected standard-library class identity. At a branch
join, a fact can still prove that an object is owned when every continuing path
gives it an owner. Known list order links a constant-index read or removal to
its element and identifies the first snapshot element. A successful read or
the first iteration of an owning-list snapshot also establishes ownership
when the exact element identity is unknown.

`clear()` and list deletion prove direct entries dead when the analysis knows
their owner. It can follow nested cleanup through known list order up to a
destructor with unknown effects. Such a destructor may remove an element
from a different list before its cleanup starts.

The analysis drops facts that could change through opaque calls, aliased
receivers, unknown indices, or loop back edges. The
[runtime checks](ownership-runtime.md#runtime-checks) still apply when identity,
current authority, or liveness is uncertain.

## Verification

[StandardOwningListTest](../../../../Compiler/src/test/kotlin/cloth/StandardOwningListTest.kt)
covers acquisition labels, duplicate and cyclic insertion, local and interface
aliases, bracket and snapshot reads, removal through aliased receivers,
branch-dependent ownership, and nested cleanup with a reentrant release.
It also exercises runtime diagnostics when ownership is uncertain.

On 2026-11-13, 507 tests passed across 59 focused compiler suites. The Shuttle
bundle check passed with the pinned library. The owning-list tests ran on
Windows x64 and x86 and verified LLVM IR and object emission on all nine
target profiles. The example above exited with status 0 through `cloth run`.
Moving deletion before removal produced one `C3138` with both source labels.

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Collection*Test' --tests 'cloth.Standard*List*Test' --tests 'cloth.Class*Test' --tests 'cloth.Interface*Test' --tests 'cloth.*State*Test' --tests 'cloth.Generic*Test' --tests cloth.StandardLibraryRuntimeTest --tests cloth.ImportPublicationTest --tests 'cloth.FileIOTest.compiler bodies require a registered declaration origin and exact signature*' :Shuttle:test --tests 'shuttle.ToolchainBundleTest.exporter packages the public collections and their pinned snapshot compiles through the public API*' --offline --console=plain
```
