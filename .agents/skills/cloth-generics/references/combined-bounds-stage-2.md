# GEN-4 Stage 2 · Combined bound satisfaction

The compiler checks every bound of a type parameter during declaration
checking and specialization. A symbolic argument can prove each requirement
through any of its own bounds, including inherited class and interface
relationships. The exact type argument is preserved.

```cloth
typedef<interface> INamed {}
typedef<class> Entity<T> {}
typedef<class> Player : Entity<Player>, INamed {}

fn<T> keep<T: Entity<T> & INamed>(T value) = value

fn main() {
    def player = Player::new
    def<Player> kept = keep(player)
    kept::delete
}
```

The same checks apply to explicit, inferred, and defaulted type arguments.
C3135 labels the argument and the first unsatisfied bound in the written
list. A written duplicate produces C3134 and labels both occurrences.
Duplicate checks use resolved identities in the original declaration.

An interface requirement includes its parent interfaces. A class bound
contributes its entire implemented interface set, including interfaces
inherited from its base classes. Matching applications of the same
interface merge across inherited paths and after substitution.
C3120 labels both contributing bounds when their interface applications
conflict. Fixed differences in type arguments are checked even in unused
declarations. Differences that depend on type arguments are checked again
after substitution.

## Compiler ownership

[ClosedTypeAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt)
resolves the ordered bound list in the argument environment and checks it
after building ancestry and conformance. Canonical symbolic parameters retain
their own guarantees for forwarding. Each specialization checks the resolved
list in its own environment. The Stage 1 C4033 boundary is retired.

[GenericBounds](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBounds.kt)
checks duplicates, compares the interface requirements by their original
declaration identities and exact arguments, and checks satisfaction. When an
overload candidate needs an application outside the argument's ancestry,
the worklist reads its parent clauses without interning it in the output model.
Candidate rejection preserves the diagnostic and its original bound spans
until the call resolver selects a diagnostic for the current call site.

[Stage 3](combined-bounds-stage-3.md) adds member lookup, widening, and
dispatch through a combined bound list.
[Confirmed combined-bound rules](semantics.md#combined-bounds-design)

## Verification

On 2026-11-12 with Java 25, the Windows run passed 186 tests across
16 native-free generics suites, 113 tests across the focused import
tasks, and 625 focused Compiler tests across 77 suites. There were
zero failures or skips.

[GenericBoundsTest](../../../../Compiler/src/test/kotlin/cloth/GenericBoundsTest.kt)
covers symbolic forwarding, exact argument identity, duplicate diagnostics,
conflicting inherited applications, and explicit, inferred, and defaulted
specializations. [GenericEmissionTest](../../../../Compiler/src/test/kotlin/cloth/GenericEmissionTest.kt)
executes a combined-bound fixture through function and constructor inference
on Windows x64 and x86.
