# GEN-5 Stage 2 · Construction through guarantees

A generic body can construct the exact type `T` through its declared
`new(...)` guarantees. Both `T::new` and `T::new()` supply zero arguments.

```cloth
typedef<class> Player (pub def<float> health) {}

fn<T> spawn<T: new(opt<float> amount ?: 100f)>() = T::new()
fn<T> spawnAt<T: new(float amount)>(float health) = T::new(amount=health)

fn main() {
    def<Player> player = spawn<Player>()
    def<Player> other = spawnAt<Player>(80f)
    player::delete
    other::delete
}
```

Ordinary argument binding and overload ranking select a guarantee in the
generic body. Specialization keeps that source contract, even when separate
guarantees resolve to the same concrete signature. Labels come from the
guarantee. Supplied arguments run once in source order. Omitted defaults run
in parameter order in constructor-default scope, where earlier guarantee
parameters and names in the defining scope are available.

The exact matching primary or secondary constructor receives the full
argument list. Construction uses ordinary delegation, initial state
activation after the constructor chain, and explicit lifetime.
[Constructor guarantee design](semantics.md#constructor-guarantee-design)

A type parameter with only nominal bounds still reports C3090 for
`T::new`. Invalid labels and tied guarantees use the ordinary call
diagnostics. Missing or inaccessible concrete signatures are rejected
during bound proof with C3135.

GEN-5 Stage 3 covers nullable bounds, and Stage 4 covers array bounds.
[Nullable bounds](nullable-bounds-stage-3.md) · [Array bounds](array-bounds-stage-4.md)

## Compiler ownership

GenericBodyViews maps the receiver's type parameter to its scoped guarantee
contracts. CallResolution binds the selected contract and finds the proven
exact constructor. BoundOperations reuses the declaration-site choice in
specialized bodies. [GenericBodyViews](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt) ·
[CallResolution](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/CallResolution.kt) ·
[BoundOperations](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/BoundOperations.kt)

EmitFunctions uses the ordinary constructor path for fully supplied calls. With
omissions, a private callable binds the guarantee defaults using the
ordinary parameter and buffer rules. It forwards every bound argument and
activates the initial state after the concrete constructor returns.
[EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)

## Verification

On 2026-11-12 with Java 25, the Windows run passed 198 native-free generics
tests, 113 import tests, and 643 focused Compiler tests, with zero failures
or skips. Both documentation examples ran through the compiler CLI.

GenericBoundsTest checks named binding, argument types, overload ranking,
and ambiguity. GenericEmissionTest runs the imported caller-private
constructor, defaults in source scope, delegation, state activation, and
destruction on Windows x64 and x86. It also covers both zero-argument
spellings and contract selection when types coincide after specialization.
[GenericBoundsTest](../../../../Compiler/src/test/kotlin/cloth/GenericBoundsTest.kt) ·
[GenericEmissionTest](../../../../Compiler/src/test/kotlin/cloth/GenericEmissionTest.kt)
