# GEN-5 Stage 3 · Nullable bounds

`T: Base?` requires a nullable type argument whose payload satisfies `Base`.
A list such as `T: Base? & Named?` checks both requirements on the same
payload. One class and multiple interface requirements can share the list.
`Number?` stands alone and admits the same numeric payload types as `Number`.

```cloth
typedef<interface> Named { mt<string> name() = default ("player") }
typedef<class> Base (pub def<int> id) {}
typedef<class> Player : Base(7), Named {}

fn<string> label<T: Base? & Named?>(T value) {
    if (value == null) return "none"
    def payload = value
    return #"{payload.name()} {payload.id}"
}

fn main() {
    def player = Player::new
    println(label<Player?>(player))
    println(label<Player?>(null))
    player::delete
}
```

After an ordinary presence proof, `def payload = value` captures the exact
payload type, such as `Player` above. Fields and methods use the bound views.
Specialization preserves the chosen member contract, including its labels
and defaults. A `Number?` payload supports same-type arithmetic, comparison,
casts, and formatting under the existing numeric rules.
[Nullable bound design](semantics.md#nullable-bound-design) ·
[Number operations](number-bound-operations.md)

Check explicit, inferred, defaulted, and forwarded arguments against the
nullable shape and payload requirement. Inference follows the written value
parameter. A bare `T` captures an argument's full type, and a written `T?`
matches its payload. For `read<T: Number?>(T? value)` and an `int?` argument,
write `read<int?>(value)` to satisfy the nullable bound.
[Declared wrapper inference](semantics.md#type-argument-inference-design)

C3134 identifies mixed nullable and non-nullable requirements and repeated
written bounds. C3120 identifies incompatible interface applications, with
substitution-dependent conflicts checked when their arguments are known.
C3135 identifies an argument that fails the nullable shape or payload check.
Stage 4 checks array bounds and composed array and nullable suffixes.
[Array bounds](array-bounds-stage-4.md)

## Compiler ownership

GenericNames resolves the bound's base name in its declaration scope and
preserves the nullable suffix. ClosedTypeAnalyzer represents the symbolic
parameter with the ordinary nullable carrier and an exact symbolic payload.
GenericBound checks the carrier before proving its payload requirements.
[GenericNames](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericNames.kt) ·
[ClosedTypeAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt) ·
[GenericBound](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBounds.kt)

Presence checking and closed emission use the ordinary nullable paths.
GenericSignatureKeys supplies declaration-shaped inference patterns.
GenericBodyViews substitutes the exact closed payload when a bound member
call inferred its type argument from a proven-present expression.
[GenericSignatureKeys](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericFunctionSignatures.kt) ·
[GenericBodyViews](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt)

## Verification

On 2026-11-12 with Java 25, the Windows run passed 201 native-free generics
tests, 113 import tests, and 647 focused Compiler tests, with zero failures
or skips. The combined nominal and numeric documentation examples ran
through the compiler CLI.

GenericNamesTest checks nullable bound names and incompatible bound lists.
GenericBoundsTest checks payload satisfaction, defaulted and forwarded
proofs, declared wrapper inference, and interface conflicts. GenericEmissionTest
executes present and absent values on Windows x64 and x86. Its fixture covers
exact class payloads, static field and method contracts, virtual and interface
dispatch, numeric arithmetic, and function, method, and constructor inference.
[GenericNamesTest](../../../../Compiler/src/test/kotlin/cloth/GenericNamesTest.kt) ·
[GenericBoundsTest](../../../../Compiler/src/test/kotlin/cloth/GenericBoundsTest.kt) ·
[GenericEmissionTest](../../../../Compiler/src/test/kotlin/cloth/GenericEmissionTest.kt)
