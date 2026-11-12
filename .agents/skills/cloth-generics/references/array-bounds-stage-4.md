# GEN-5 Stage 4 · Array bounds and suffix composition

An array bound checks the declared type argument at every layer. `T: int[]`
requires a growable array of `int`, and `T: int[4]` requires exactly four
declared elements. A concrete element bound is invariant, so `T: Base[]`
requires `Base[]`. `T: E[]` uses the exact element type selected for `E`.
An array bound occupies the bound list by itself.

With `T: Number[]`, the type argument determines the exact numeric element.
Indexed reads and removals return that type. Writes and appends use its
ordinary receiving rules. Numeric operations on the element use the existing
`Number` contract. Ordinary array mutation and rectangular shape checks
apply to values, including when a value has a runtime count.
[Array bound design](semantics.md#array-bound-design) ·
[Array reception and mutation](../../cloth-arrays/references/growable-arrays.md#types-inference-and-reception)

Suffixes compose as in value types. `Number?[]` has nullable numeric
elements, and `Number[]?` has a nullable array of numeric elements.
`Number[]?[2]?` admits a nullable growable array of nullable two-element
rows. Use ordinary presence checks and payload access.

```cloth
fn<T> appendRow<T: Number[]?[2]?>(T rows) {
    if (rows == null) return null
    def row = rows[0] ?: exit(1)
    row[1] = row[0] + row[1]
    rows.append(row)
    return rows
}

fn main() {
    println(appendRow<int<8>[]?[2]?>([[1, 2], null]))
    println(appendRow<int<8>[]?[2]?>(null))
}
```

Explicit, inferred, defaulted, and forwarded type arguments use the same
proof. A bare `T` captures the supplied value's type during inference.
A fixed array literal used with `T: Number[]` can be received by an
explicitly selected growable type, as in `edit<int[]>([1, 2])`.
C3135 identifies an argument with the wrong extent, nullable position, or
element type. Ordinary call and mutation diagnostics still cover invalid
values after a type argument satisfies its bound.

## Compiler ownership

GenericNames binds the element name in the declaration's scope. TypeResolver
supplies the ordinary nullable and array suffix order and target extent
checks. GenericBound checks exact array identity or compares the declared
shape and the numeric leaf requirement.
[GenericNames](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericNames.kt) ·
[TypeResolver](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/TypeResolver.kt) ·
[GenericBound](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBounds.kt)

ClosedTypeAnalyzer constructs the symbolic array type from the bound. A
numeric leaf retains the path to its exact element in the closed argument.
GenericBodyViews uses that path when rebinding a method inferred from an
indexed element. The worklist records canonical declaration instances even
when an exact bound already determines every type argument. Existing array
typing, shape analysis, and emission handle reads and writes.
[ClosedTypeAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt) ·
[GenericBodyViews](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt)

## Verification

On 2026-11-12 with Java 25, the Windows run passed 204 native-free generics
tests, 113 import tests, and 998 focused Compiler tests, with zero failures
or skips. Both documentation examples ran through the compiler CLI.

GenericNamesTest covers element names and suffix order. GenericBoundsTest
covers exact extents, invariant elements, dependent shapes, defaults, and
ordinary reception diagnostics. GenericEmissionTest executes narrow integer
and float arrays, fixed writes, nullable elements and rows, and bound method
inference on Windows x64 and x86. An incompatible append after clearing a
nullable row still fails the retained runtime width check.
[GenericNamesTest](../../../../Compiler/src/test/kotlin/cloth/GenericNamesTest.kt) ·
[GenericBoundsTest](../../../../Compiler/src/test/kotlin/cloth/GenericBoundsTest.kt) ·
[GenericEmissionTest](../../../../Compiler/src/test/kotlin/cloth/GenericEmissionTest.kt)
