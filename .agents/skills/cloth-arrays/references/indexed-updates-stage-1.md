# ARRAY-1 Stage 1 indexed updates

The [indexed update contract](semantics.md#indexed-compound-assignment) applies
to compound assignments and standalone postfix updates on array elements.
The array and indices evaluate once, and the checked element read precedes
the right-hand side. The write uses the selected place after evaluating the
operator.

```cloth
fn main() {
    def values := [[10]]
    def<int> rhs = {
        values[0][0] = 20
        return 1
    }
    values[0][0] += rhs
    println(values[0][0])
    values[0][0]++
    println(values[0][0])
}
```

This prints `11` and `12`. Plain `=` and indexed updates permit final array
bindings and temporary array results. Operators use ordinary type rules,
including numeric literal context, checked division, and explicit binary
overloads. An indexed `++` inside a binding initializer is a syntax error.
An indexed `++` on a boolean element is an operand type error.

## Compiler integration

- [Parser](../../../../Compiler/src/main/kotlin/cloth/parser/Parser.kt) retains
  the target, source operator, and optional right operand in an indexed or
  field write. [BodyTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/BodyTyping.kt)
  checks the underlying binary expression or numeric update and records the
  destination with the existing stored and class-backed place machinery.
- [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
  loads and captures the old value before emitting the binary operation.
  The operation consumes that capture, including when it calls an overload.
  Wide integers are loaded into independent bytes. Buffered values use the
  ordinary snapshot rules before later effects can invalidate them.
- [EmitPlaces](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitPlaces.kt)
  follows growth in the selected local array version. A whole-binding
  replacement keeps that selection on the old version. Class-backed writes
  replay the current checked field of the captured object. Computed array
  descriptors also observe live storage when evaluation can move it.
  Invalidated selections fail through ordinary bounds and lifetime checks.
- Completion, nullability, state effects, and generic body views account for
  the original target and optional right operand. A return or loop jump
  during evaluation bypasses the store.

[Stage 2](array-equality-stage-2.md) implements array equality.

## Verification

On 2026-11-12 the focused compiler run passed 96 tests in seven suites on
Windows x64 with Java 25. [IndexedUpdateTest](../../../../Compiler/src/test/kotlin/cloth/IndexedUpdateTest.kt)
uses the production driver and covers once-only nested selection, all compound
operators, postfix updates, wide and half-precision arithmetic, buffered
operands, temporary and borrowed array results, relocation, rebinding, and
bounds and receiver lifetime failures. The generic and early-return fixture
ran on Windows x64 and x86. [ArrayAssignmentTest](../../../../Compiler/src/test/kotlin/cloth/ArrayAssignmentTest.kt)
checks original operator spans, statement-only syntax, and existing plain writes.

`Compiler:runExample` compiled and ran a separate fixture that printed `11`
after the right operand replaced a class array field. A generic `int<128>`
update printed `9223372036854775811`. The program exited with `PEC: 0`.

The full `:Compiler:check` run passed 2,267 of 2,269 main-suite tests and
all 342 cases across the four dedicated import and generic syntax tasks.
Two [file I/O tests](../../../../Compiler/src/test/kotlin/cloth/FileIOTest.kt)
failed on imports of `cloth.io.File`. The bundled module lives at
`cloth.io.fs.File`. Both failures reproduced on an archived copy of
compiler revision `f18ecdf` and its pinned standard library.
