# Struct Stage 4 function and traversal integration

Stage 4 completed on 2026-11-05. The [struct contract](semantics.md) defines
source value semantics, and [Stage 3](local-storage.md) defines inline storage.

~~~cloth
typedef<struct> Score { int value, string[1] labels }

fn<Score> bump(final Score score) {
    score.value++
    return score
}

fn<int> main() {
    def<Score> original = { 7, ["first"] }
    def scores = [original, bump(original)]
    for (def score <- scores) {
        println(score.value)
    }
    return 0
}
~~~

This prints 7 and 8. The function may change a field through its final parameter,
and each parameter and foreach binding receives an independent value.

## Implementation and invariants

- [EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitFunctions.kt)
  uses the existing private indirect-value ABI. A struct travels as a pointer
  to its snapshot. A nullable struct carries a presence bit and the payload
  pointer. Array descriptors retain extents and nullable-row shape witnesses.
  Supplied arguments snapshot in written order before placement in parameter
  order. Omission flags guard unused placeholders before decoding. Defaults
  evaluate in declaration order and can read earlier parameters, whose fields
  are read-only in a default. The callee stores each parameter in an inline
  binding and can mutate its independent fields.
- [EmitFunctionFrame](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitFunctionFrame.kt)
  copies every indirect outgoing result slot recursively into the caller's
  arena before freeing the callee's frame. This includes forwarded results,
  nested nullable structs, arrays containing structs and array fields with
  nullable rows. Preserve each result slot before evaluating a later slot.
  Shape witnesses and live buffers belong to the receiving arena. Absent
  nullable payloads are skipped. Existing positional reception and discarded
  array shape checks still apply.
- [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
  evaluates deferred blocks on each read, snapshots the value at the block
  return and merges continuing paths in the containing physical frame.
  Struct arguments, deferred values and explicit results share the existing
  recursive snapshot path.
- [EmitForEach](../../../../Compiler/src/main/kotlin/cloth/emitter/types/loops/EmitForEach.kt)
  takes an independent snapshot of the source contents and shape before
  traversal and copies each element into its binding. Continue keeps the source
  snapshot alive, break exits the traversal, and returns copy outgoing data
  before frame release. [ArrayTemporaryAnalysis](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/ArrayTemporaryAnalysis.kt)
  recognizes aggregate snapshots introduced by source calls, deferred reads,
  branches and result lists. The closed proof retains frame lifetime for
  unproven effects or buffered publication through captures and stored places.

## Scope and verification

Ordinary calls, recursion, defaults, named arguments, ordered results, forwarding,
deferred reads and foreach support structs through the private Cloth ABI.
C3077 applies to struct syntax in the body-only snippet analyzer. Methods,
native C struct ABI, whole-struct equality and whole-struct formatting remain
deferred.

All 284 selected compiler tests passed on Windows with zero failures or skips.
The `StructIntegrationTest` call and traversal fixtures ran natively on Windows
x64 and x86. The call fixture verified LLVM IR and emitted objects across all
nine supported profiles. The shape failure fixture exercised the retained
dimension check on a discarded result on the native host.

The `ArrayTemporaryCleanupTest` probes poisoned released allocations and checked
nested strings and nullable-row shapes after callee release. The closed loop
probe checked live bytes after discarded and scalar struct calls and after
foreach jumps across 1,000 iterations. Both checked final allocation balance.

~~~powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Struct*Test' --tests cloth.ArrayTemporaryCleanupTest --tests cloth.FunctionEmissionTest --tests cloth.FunctionBlocksTest --tests 'cloth.OptionalArguments*Test' --tests 'cloth.MultiReturn*Test' --tests cloth.NullableBuffersTest --tests cloth.NullableRowsTest --tests cloth.ForEachTest --tests cloth.LoopControlTest --offline --console=plain --no-daemon
~~~
