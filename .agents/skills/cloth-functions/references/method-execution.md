# Struct methods Stage 4 execution and ownership

Stage 4 integrated on 2026-11-05. The [method contract](semantics.md#user-defined-methods)
owns the source rules and [method typing](method-typing.md) owns the receiver plans.

~~~cloth
typedef<struct> Meter {
    int amount,
    int step;

    mt<int> add(opt<int> delta ?: step) {
        amount += delta
        return amount
    }
}

fn<int> main() {
    def<Meter> meter := { 10, 2 }
    if (meter.add() != 12 || meter.amount != 12) return 1
    return 0
}
~~~

## Implementation

[Growable arrays Stage 3](../../cloth-arrays/references/growable-arrays-stage-3.md)
adds selected local-array views for ordinary struct receivers. Their indices and
original bounds are captured before arguments; inline addresses rebase after
argument growth. Whole-array replacement stops following the new binding and
keeps the selected old value. Explicit argument and temporary receiver snapshots,
class-backed replay and the private callable ABI remain in force.
[Stage 4](../../cloth-arrays/references/growable-arrays-stage-4.md) extends selected
paths to array fields nested in ordinary struct values and array elements.
Scoped owner observations update a caller's descriptor views before a separately
emitted method frees superseded capacity. Method field append/reserve uses the
array's existing owner head and publishes directly; it does not repack the field
on each call. Existing class-backed resolver/permission operands are unchanged.
[Stage 5](../../cloth-arrays/references/growable-arrays-stage-5.md) extends those
receivers to insert/removeAt/clear. Removed indirect values copy before source
slot cleanup or temporary-owner release. Destroyed child arrays invalidate
selected views of every rank/fixed extent, and current bound checks prevent later
access. Class-backed receivers continue replaying the current checked field.
[Stage 6](../../cloth-arrays/references/growable-arrays-stage-6.md) executes
rectangular whole-row edits through the same receiver/call ABI, including
temporary removed rows and nullable class/struct fields. Shape witnesses copy
with outgoing values before slot cleanup, frame exit or class deletion.
The [final array audit](../../cloth-arrays/references/growable-arrays-stage-8.md)
retains this receiver and result contract for ordinary source List methods;
generic collection storage does not add a caller-visible reference ABI.

- [EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
  prepends two private operands to struct method entries. They carry the
  receiver address and its owner arena. Explicit arguments, any result arena
  and omission flags follow. Functions and methods share the existing
  argument placement, decode, default prelude and result-copy paths.
- [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
  resolves the stored receiver once before arguments using the stored place
  walker. It evaluates each index and checks its bounds before moving on.
  The method reads the selected address after arguments run, so replacing
  the contents of a selected inline slot is visible through `self`. A
  temporary receiver gets an independent caller-owned snapshot before arguments.
- [EmitBindings](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitBindings.kt)
  binds `self` to the supplied address. Implicit field symbols project into
  that same storage. Whole field writes, positional assignments and nested
  field or element writes copy live buffers and shape data into the receiver's
  owner arena. A method calling another method through `self` forwards that
  owner. Outgoing results copy separately into the caller's result arena.
- [ArrayTemporaryAnalysis](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/ArrayTemporaryAnalysis.kt)
  treats source method calls conservatively. A scalar or discarded method
  result can still accompany a stored write, so its surrounding region
  retains the owning frame's lifetime.

## Verification and scope

`StructMethodEmissionTest` executes the call fixture on Windows x64 and x86.
It checks receiver-before-argument order, live replacement of an inline
receiver, recursive and named calls, defaults, temporary calls, and ordered
results including wide integers and half precision. The same fixture verifies
LLVM IR and emits objects across all nine supported profiles. The native-host
bounds test confirms that a bad receiver index fails before the argument runs.

The `ArrayTemporaryCleanupTest` poisoned-memory probe verifies buffer
publication through nested calls, implicit and explicit field writes, nullable
row shapes and outgoing result copies. It checks final allocation balance.
The Windows regression selection passed 570 tests across 45 suites
with zero failures, errors or skips.

~~~powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Struct*Test' --tests cloth.ArrayTemporaryCleanupTest --tests cloth.ArrayCopyElisionTest --tests cloth.ArrayAssignmentTest --tests 'cloth.Function*Test' --tests cloth.StringFunctionTest --tests 'cloth.OptionalArguments*Test' --tests 'cloth.MultiReturn*Test' --tests cloth.DestructuringTest --tests 'cloth.Nullability*Test' --tests cloth.NullableBuffersTest --tests cloth.NullableRowsTest --tests cloth.ForEachTest --tests cloth.LoopControlTest --tests cloth.DoWhileTest --tests cloth.ProgramEntryTest --tests cloth.BuiltinFunctionsTest --tests 'cloth.*AttachmentsTest' --offline --console=plain --no-daemon
~~~

C3085 is retired. Class, interface, enum and state dispatch remain later slices.
The [Stage 5 publication record](method-publication.md) covers the maintained
example, native execution and full compiler regression.
