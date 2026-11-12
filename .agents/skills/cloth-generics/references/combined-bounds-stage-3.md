# GEN-4 Stage 3 · Operations through combined bounds

A value with combined bounds can use each bound's public methods, the class
bound's fields, and ordinary widening to any proven class or interface view.
The value keeps its exact type `T`. An explicit `T?` uses the ordinary
presence rules for member access.

```cloth
typedef<interface> INamed {
    mt<string> label() = default ("player")
}
typedef<class> Entity<T> (pub def<int> health) {}
typedef<class> Player : Entity<Player>(100), INamed {}

fn<string> describe<T: Entity<T> & INamed>(T value) {
    return #"{value.label()} {value.health}"
}

fn main() {
    def player = Player::new
    println(describe(player))
    player::delete
}
```

Class and interface methods share ordinary overload ranking, including
exact matches and omitted parameter counts. Inherited paths to the same
interface contract contribute one candidate. When two distinct contracts tie,
select a receiver view with a binding such as `def<INamed> named = value`
or a cast such as `value -> INamed`. The selected contract supplies the
labels and defaults through specialization. Virtual and interface calls
dispatch through that contract on the live receiver.

Formatting, deletion, `==`, and `!=` use the checked object rules.
`===` uses a matching overload from the class bound. The identity fallback
checks both present handles before comparing their instance identities.
Nullable comparisons inspect presence before either handle check.
[Class equality](../../cloth-language/references/class-equality.md)

## Compiler ownership

[BoundOperations](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/BoundOperations.kt)
uses the declaration's receiver views in the specialized environment.
[CallResolution](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/CallResolution.kt)
ranks ordinary contracts across those views. Class method inference uses
the existing fallback after the ordinary candidates fail to match.
The selected interface contract can dispatch through its declaring
interface if substitution merges inherited slots in the original view.
[GenericCallInference](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/GenericCallInference.kt)
uses matching applications through the proven views as type evidence.

[NullFlowAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/NullFlowAnalysis.kt)
uses the same views and selected contracts for presence facts.
[ClassComparisonPlan](../../../../Compiler/src/main/kotlin/cloth/semantic/ClassModel.kt)
records the handle check for the identity fallback, which is shared by the
lifetime pass and native emission.
[Confirmed combined-bound rules](semantics.md#combined-bounds-design)

## Verification

On 2026-11-12 with Java 25, the Windows run passed 189 tests across
16 native-free generics suites, 113 tests across the focused import
tasks, and 681 focused Compiler tests across 82 suites. There were
zero failures or skips.

[GenericBoundsTest](../../../../Compiler/src/test/kotlin/cloth/GenericBoundsTest.kt)
checks equal-rank ambiguity, explicit receiver views, widening and inference,
guarded access, and compile-time rejection of provably stale identities.
[GenericEmissionTest](../../../../Compiler/src/test/kotlin/cloth/GenericEmissionTest.kt)
executes the selected class and interface defaults, virtual dispatch,
field shadowing, generic method fallback, and merged interface slots on
Windows x64 and x86. Its identity fixture checks overload selection, the
nullable presence path, formatting, destruction, and the runtime error
for an escaped stale handle.
