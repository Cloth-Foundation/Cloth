# Collection indexing

## Confirmed design

On 2026-11-12 the user chose explicit indexing operators. A type opts in by
declaring the read or write operator. `List<T>` shares the bodies with
`get(usize index)` and `set(usize index, T value)`.

The read operator is `[]` and the write operator is `[]=`. The confirmed
forms for `List<T>` are `pub operator [](usize index) -> T { ... }` and
`pub operator []=(usize index, T value) { ... }`.

Replacing a `List<T>` element receives the whole value under the ordinary
value rules for `T`. A `List<int[]>` slot holding `[1, 2]` can receive an
independent `[3, 4, 5]`. A fixed element type such as `int[2]` still requires
its declared extent. Replacing one slot preserves the list's count.

A `List<T>` bracket assignment evaluates and captures the receiver and index
once, in that order. It checks receiver liveness and index bounds before
evaluating the right-hand side. A failed check ends the assignment there.
After evaluating the right-hand side once, it rechecks liveness and bounds
on the captured receiver before writing.

The write uses the captured numeric index in the list's current contents.
If `list` starts as `[10, 20, 30]` and `rhs()` removes index `0` and returns
`99`, then `list[1] = rhs()` leaves `[20, 99]`. If the captured index is
outside the current bounds, the second check fails.

A type that declares `[]=` supplies an index validator using `operator []?`.
The setter pairs with a validator on its declaring class with the same index
parameter type. The setter takes the index and the incoming value and returns
`void`. Plain assignment resolves the setter from both operands and can use a
write-only class.
For `List<T>`, the declaration is `operator []?(usize index) -> bool { ... }`
and checks `index < size()`. Cloth runs the validator at both write checks.
A `false` result fails the assignment with an invalid-index error. Other
types define which indices are valid for their own storage.

The validator has read-only access to the receiver. It can inspect the
collection, including its current count through `size()`. Changing or deleting
the receiver during validation is an error.

A bracket read invokes `[]?` once before `[]` when the type supplies a
validator. A `false` result raises the invalid-index error before `[]` runs.
A type that offers only reads may omit `[]?` and handle indices in `[]`.
The selected getter pairs with a validator of the same index parameter type
on its declaring class. An inherited getter keeps that class's check.
The read operator has ordinary method effects, including receiver mutation.
An indexed update rechecks the captured receiver and index before writing.

Writing into an array value returned by `[]` requires explicit write-back.
For `List<int[]>`, `list[0][1] = 9` is a compile-time error. Bind the result
with `def row = list[0]`, edit `row[1]`, then assign `list[0] = row`.

An indexed target with both `[]` and `[]=` supports compound assignment and
standalone postfix updates under the ordinary operator type rules. The getter
and setter must share one validator. If they select different validators, use
an explicit read and write. Capture the receiver and index once, check
liveness and `[]?`, and read `[]` once before
the right-hand side. Apply the operator to that saved value, then recheck
liveness and `[]?` before calling `[]=` with the result. Postfix `++` and `--`
use the same order with their ordinary increment or decrement and no explicit
right-hand side.

## Stage 1 implementation

Class indexing uses ordinary class visibility and operator overload ranking.
The index receives context from the selected parameter. Capture the checked
class receiver, evaluate the index once, and recheck receiver liveness before
invoking the optional validator and getter. Each call receives the captured
index under ordinary parameter value rules.

The validator can inspect `self` and call methods such as `size()`. Direct
mutations and deletion of `self` in the validator are compile-time errors.
During the validator call, writes, transitions, and deletion through aliases
fail at runtime. Nested validation restores the previous read-only state.
`List<T>` validates `index < size()` and returns `get(index)`. Its result is an
independent value, with ordinary checked class identity for class elements.

```cloth
using cloth.collections.List : List

fn main() {
    def values = List<int>::new
    values.append(7)
    println(values[0])
    values::delete
}
```

The parser assembles bracket operator names in declarations from ordinary
bracket tokens. `OperatorResolution` binds the getter and validator and
keeps the ordinary operator facts for state, lifetime, and temporary analysis.
`EmitFunctions` captures operands and uses the ordinary callable ABI. The
class runtime protects the receiver and its active payload at write sites.

### Stage 1 verification

On 2026-11-12, 738 tests passed across 68 compiler suites on the final Stage 1
changes. The run covered collection indexing, imported List, arrays, classes,
operators, nullability, generics, and states. The indexing suite ran native
programs on Windows x64 and x86 and verified LLVM IR and object emission for
all nine supported target profiles. Native execution evidence is from Windows.
[CollectionIndexingTest](../../../../Compiler/src/test/kotlin/cloth/CollectionIndexingTest.kt)

The broader compiler check found two FileIO fixtures still importing
`cloth.io.File`. The pinned library exposes `cloth.io.fs.File`.
[FileIOTest](../../../../Compiler/src/test/kotlin/cloth/FileIOTest.kt) ·
[File source](../../../../Standard-Library/cloth/io/fs/File.co)

## Stage 2 implementation

```cloth
using cloth.collections.List : List

fn main() {
    def values := List<int>::new
    values.append(10)
    values.set(0, 20)
    values[0] = 10
    values[0] += 2
    values[0]++
    println(values[0])
    values::delete
}
```

This prints `13`. `List<T>.set()` receives a `T` and constructs a new
`ListSlot<T>`. Replacing the slot gives an array-valued element fresh shape
witnesses. `set()` uses ordinary method evaluation, with bounds checked in
its body after the caller supplies the index and value.

[OperatorResolution](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/OperatorResolution.kt)
binds the setter using ordinary operator ranking and verifies the shared
validator for updates. [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
reuses the saved operand path for compound and postfix updates. The second
check uses the captured object and index and runs before entering the setter.
A return or loop jump during operand evaluation bypasses the setter. Lifetime
and state flow treat the write as a source call.

### Stage 2 verification

On 2026-11-12, 855 tests passed across 78 compiler suites. The Stage 2 tests
ran bracket writes and updates on Windows x64 and x86. The imported List
fixture produced verified LLVM IR and object files for all nine profiles.
Coverage includes write-only classes, the shared-validator diagnostic, early
returns, getter effects, index shifts, receiver deletion, and replacement of
independently sized and nullable arrays. The CLI ran the example above and
printed `13` with `PEC: 0`.
[CollectionIndexWriteTest](../../../../Compiler/src/test/kotlin/cloth/CollectionIndexWriteTest.kt)

[Stage 3](traversal.md) implements direct foreach. Struct indexing remains a
follow-up for stored struct receivers.

The [open questions plan](../../../../todo/open-questions-plan.md#collections-operators-and-control-flow)
tracks the remaining decisions. The
[current List API](../../../../Standard-Library/cloth/collections/README.md#list)
defines value reception, bounds, and explicit lifetime for the methods that
exist today.
