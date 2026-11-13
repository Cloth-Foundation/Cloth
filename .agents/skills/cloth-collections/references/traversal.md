# Collection foreach

## Confirmed design

Direct foreach over `List<T>` evaluates the source once per loop entry and
captures its elements in list order before the first iteration. For a list
starting with `[10, 20]`, appending `30` in the body still leaves two visits,
to `10` and `20`.

The snapshot uses ordinary List value rules. Array elements retain independent
shapes, so a list holding `[1, 2]` and `[3]` can be traversed directly. Class
elements preserve checked object identity and explicit lifetime. Each iteration
receives its element under the ordinary foreach binding rules.

A collection opts into direct foreach by declaring an operation that supplies
its ordered snapshot. Cloth uses that operation to prepare traversal. The
collection can declare its bracket operators independently.

For `List<T>`, the declaration is `pub operator foreach() -> Snapshot<T> { ... }`.
The operation returns a `Snapshot<T>` value with one ordered slot per element.
Each slot preserves its element's own shape. The loop manages the snapshot's
value storage for the duration of traversal.

The source standard library defines `Snapshot<T>` as a public value with a
`priv` slot array. Collection authors import `Snapshot` and `emptySnapshot`
from `cloth.collections.Snapshot` and append elements in traversal order.
The factory initializes the empty array with a brace literal in the struct's
defining file. Field reads and writes belong to the declaring struct's methods.
[Struct visibility](../../cloth-structs/references/semantics.md#established-syntax-and-visibility)

```cloth
using cloth.collections.Snapshot : Snapshot, emptySnapshot

typedef<class> Countdown {
    pub operator foreach() -> Snapshot<int> {
        def snapshot = emptySnapshot<int>()
        snapshot.append(2)
        snapshot.append(1)
        return snapshot
    }
}

fn main() {
    def countdown = Countdown::new
    for (def value <- countdown) println(value)
    countdown::delete
}
```

## Stage 3 implementation

An accessible zero-parameter `operator foreach()` on a class or struct supplies
the snapshot. Classes use the ordinary checked receiver and inherited operator
lookup. Struct operators inherit the struct's visibility and work on an
independent receiver value. `foreach` stays an identifier in ordinary code.
Each declaration explicitly returns the nominal `Snapshot<T>` from the selected
standard library. Another struct with the same name receives C3093. A source
without an accessible traversal contract fails loop checking. The source
`Snapshot<T>` value supplies `append` for building the snapshot. `size()`
remains List's count method.

[Snapshot layout and loop plan](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/CollectionForeachPlan.kt)
validates the standard-library source identity and typed slot layout. Loop
typing uses the slot element's exact type with ordinary foreach reception.
State and lifetime analysis treat the operator as a source call.
[Loop emission](../../../../Compiler/src/main/kotlin/cloth/emitter/types/loops/EmitForEach.kt)
uses the same index loop as array foreach and copies each slot's element into
its iteration binding. The operator returns into a dedicated arena. Exhaustion,
`break`, crossed loop exits, and deferred returns release that arena. Function
return copies outgoing values before frame cleanup. Published iteration values
retain their ordinary lifetimes.

### Verification

On 2026-11-12, [CollectionForeachTest](../../../../Compiler/src/test/kotlin/cloth/CollectionForeachTest.kt)
ran the real library and custom operators on Windows x64 and x86. It covers
mixed-length and nullable rows, source re-entry and deletion, checked class
identities, the nominal result requirement, and private field access.
The same tests verified executable LLVM IR and emitted objects for all nine
supported ABI profiles.

[StandardListStorageTest](../../../../Compiler/src/test/kotlin/cloth/StandardListStorageTest.kt)
checked repeated traversal against the allocator's live-byte baseline and
poisoned frees. It covers exhaustion, `continue`, `break`, and return from a
deferred block or function, including a returned array that survives cleanup.
[StandardListTest](../../../../Compiler/src/test/kotlin/cloth/StandardListTest.kt)
ran the maintained [Collections project](../../../../examples/Collections/README.md)
and confirmed that rectangular export still fails for mixed-length rows.

The full compiler suite completed 2,304 tests across 218 suites, with 2,302
passing. The two existing `FileIOTest` failures import `cloth.io.File`, while
the selected library exposes `cloth.io.fs.File`. Bootstrap lexer parity passed
for 136 inputs on both native profiles after adding the `priv` keyword.

See the [array foreach rules](../../cloth-control-flow/references/semantics.md#for-loops-and-ranges-design-confirmed-implementation-deferred)
and the [open questions plan](../../../../todo/open-questions-plan.md#collections-operators-and-control-flow).
