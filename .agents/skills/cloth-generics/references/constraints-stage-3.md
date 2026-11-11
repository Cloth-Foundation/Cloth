# GEN-1 Stage 3 · Operations through bounds

[Stage 2](constraints-stage-2.md) proves each type argument meets its bound.
[Number operations](number-bound-operations.md) cover explicit numeric casts,
same-type arithmetic and comparisons, updates, and numeric formatting.

```cloth
typedef<interface> IHealth<N: Number> {
    mt<N> getHealth() = default (0 -> N)
}
typedef<class> Entity<T: Entity<T>> : IHealth<int> {
    pub mt<bool> isAlive() = true
}
typedef<class> Player : Entity<Player> {}

fn<bool> alive<T: Entity<T>>(T value) = value.isAlive()
```

A class-bound value can read and write public fields and call public methods
through its bound. An interface bound exposes the interface method contract.
The value retains its exact type `T`, including in returns and metadata.
A receiving class or interface type can use the bound's ordinary widening.
An explicit `T?` requires the usual presence proof for a direct member call.

Declaration checking selects the method overload and its call contract.
Specialization uses the corresponding declaration with substituted types.
Labels and omitted-argument defaults come from that contract. Virtual and
interface dispatch select the body at runtime.

`==` and `!=` use checked class dispatch. `===` retains the selected
overload or the ordinary instance identity comparison. An interface bound
compares instance identity for `===` even when the concrete class supplies
an overload. Formatting and explicit deletion use the ordinary checked
object operations. Construction through `T::new` requires a separate
constructor guarantee.

## Compiler ownership

- [ClosedTypeAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt)
  retains each substituted nominal bound with its owner's bindings. The
  canonical parameter carries its guaranteed nominal type for widening,
  presence, and checked object operations.
- [GenericBodyViews](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt)
  links a specialization's expression to the canonical declaration view.
  [FunctionAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt)
  checks the canonical bounded bodies before their specialized copies.
  [OperatorBodies](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/OperatorBodies.kt)
  obtains those facts when result inference reaches a specialized operator early.
- [BoundOperations](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/BoundOperations.kt)
  projects the static receiver and rebinds a selected method, requirement, or
  identity overload to its substituted declaration. Typing checks the
  specialized receiver against that bound. Presence analysis uses the same
  call contracts when accounting for defaults and comparison effects. The
  existing emitter lowers the resolved call and checked object plans.

## Verification

[GenericBoundsTest](../../../../Compiler/src/test/kotlin/cloth/GenericBoundsTest.kt)
covers visibility, nullable receivers, invalid casts to `T`, use after deletion,
and declaration-site selection during operator inference.
[GenericEmissionTest](../../../../Compiler/src/test/kotlin/cloth/GenericEmissionTest.kt)
executes numeric operations and the `Entity<T>`, `Player`, and `IHealth<N>`
forms on Windows x64 and x86. Its fixtures exercise class and interface labels
and defaults, runtime dispatch, field shadowing, comparisons, formatting, and
destruction.

On 2026-11-11 with Java 25, the shared-worktree run passed 111 native-free
generics tests across 11 suites and 344 focused Compiler tests across 40 suites.
Both runs had zero failures or skips.

```powershell
.\gradlew.bat :Compiler:genericSyntaxTest :Compiler:test --tests 'cloth.Generic*Test' --tests 'cloth.Interface*Test' --tests 'cloth.Class*Test' --tests cloth.VirtualMethodsTest --tests cloth.GuardedAccessTest --tests 'cloth.OptionalArguments*Test' --tests cloth.NestedTypesTest --tests cloth.ProviderResultsTest --tests cloth.FunctionReturnsTest --offline --console=plain --no-daemon
```
