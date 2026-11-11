# GEN-1 Stage 2 · Bound satisfaction

[Stage 1](constraints-stage-1.md) supplies the syntax and source identities.
Stage 2 checks bounds in generic declarations and closed specializations of
classes, structs, interfaces, and functions.
[Stage 3](constraints-stage-3.md) covers operations on a value through its bound.
[Constraint design](semantics.md#checking-bound-satisfaction)

```cloth
typedef<interface> IHealth<N: Number> {}
typedef<class> Entity<T: Entity<T>> : IHealth<float> {}
typedef<class> Player : Entity<Player> {}
fn<T> keep<T: Entity<T>>(T value) = value
fn observe<N: Number>(IHealth<N> value) {}
fn main() {
    def player = Player::new
    def kept = keep<Player>(player)
    kept::delete
}
```

- `Number` admits fixed-width signed and unsigned integers, `float`, `isize`,
  and `usize`. The check uses the exact type argument, including any nullable
  or array wrapper.
- Class and interface bounds use existing ancestry and conformance with exact
  type arguments. `Player` proves `T: Entity<T>` through `Entity<Player>`.
  Interfaces can satisfy `Object` through ordinary widening. A proof retains
  the argument's exact type for signatures, storage, and emission.
- A symbolic parameter proves a bound from its own declaration. The `observe`
  declaration above proves `IHealth<N>` from `N: Number`. A declaration using
  `IHealth<T>` with unconstrained `T` fails even when it is unused.
- Public bounds follow public signature visibility, including types nested
  inside their arguments. The existing C3117 diagnostic labels the private
  reference and its declaration.

## Compiler ownership and first errors

[ClosedTypeAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt)
resolves each bound in its owner's substitution environment. Canonical symbolic
owners establish guarantees for their own parameters. Explicit applications
record checks with the source span of each supplied argument. The same exact
specialization key reuses its resolved requirements.

The worklist builds and validates ancestry and interface conformance before
checking the recorded requirements. A self-bound can refer to the same
provisional specialization as a class's base. Inheritance cycles and expanding
applications use the existing diagnostics and resource limits. Symbolic and
closed models maintain separate proof facts.

C3135 reports the first unsatisfied bound with labels on the supplied argument
and the declared bound. The check uses the substituted bound in its message,
such as `Entity<Veteran>` when `Veteran` inherits `Entity<Player>`. The Stage 1
C4037 compilation boundary is retired.

[GenericSemanticAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericSemanticAnalysis.kt)
checks public bound references in the defining file before building the
symbolic and closed views.

## Verification

On 2026-11-11 with Java 25, the final shared-worktree run passed 108 native-free
generics tests across 11 suites and 202 focused Compiler tests across 26 suites.
Both runs had zero failures or skips. [GenericBoundsTest](../../../../Compiler/src/test/kotlin/cloth/GenericBoundsTest.kt)
covers forwarding, exact nominal matches, public visibility, imported
self-bounds, cycles, and first-error spans. The closed `Player` and `IHealth`
fixture in [GenericEmissionTest](../../../../Compiler/src/test/kotlin/cloth/GenericEmissionTest.kt)
executed on Windows x64 and x86.

```powershell
.\gradlew.bat :Compiler:genericSyntaxTest :Compiler:test --tests 'cloth.Generic*Test' --tests 'cloth.Interface*Test' --tests cloth.NestedTypesTest --offline --console=plain --no-daemon
```
