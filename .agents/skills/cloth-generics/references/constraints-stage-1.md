# GEN-1 Stage 1 · Bound declarations

Generic classes, structs, interfaces, and top-level functions can parse and
resolve one optional `: Bound` per parameter. The [constraint design](semantics.md#initial-constraint-scope)
owns the confirmed rules for satisfaction and operations.

```cloth
typedef<class> Entity<T: Entity<T>> {}
typedef<class> Player : Entity<Player> {}
typedef<interface> IHealth<T: Number> {}
typedef<struct> HealthView<T: IHealth<float>> { T value }
fn inspect<T: Object>(T value) {}
```

These declarations pass parsing and name resolution. At this milestone, C4037
marked the boundary before symbolic or closed type analysis.
[Stage 2](constraints-stage-2.md) checks whether the type arguments satisfy
their bounds. Stage 3 will check and lower the operations available through
each bound.

## Syntax and name resolution

- The parameter and its bound retain their own source spans. The parameter
  span extends through the bound, including any generic arguments.
- Plain `Number` in a bound resolves to the built-in constraint. `Number` in
  ordinary type positions and qualified names such as `Family.Number` use the
  ordinary type namespace.
- A nominal bound names a class or interface, including `Object`. All
  parameters of the declaration are in scope in its bounds. `T: Entity<T>`
  resolves the inner `T` to that same parameter. Qualified and imported names
  use the existing visibility and source rules.
- Ordinary name and arity errors apply to nominal bounds and their type
  arguments. C3134 rejects a resolved bound outside the three supported forms,
  including direct parameter bounds, primitives, structs, enums, and nullable
  or array bounds. Nested type arguments use the ordinary type syntax.

## Compiler ownership

- [TypeParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/TypeParser.kt)
  parses the optional bound through the shared type grammar.
  [TypeParameterDeclaration](../../../../Compiler/src/main/kotlin/cloth/parser/type/TypeParameters.kt)
  retains the original reference.
- [GenericAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericNames.kt)
  registers owner parameters before binding names. It visits bounds before
  each owner's other type uses and records their identities in `GenericNameModel`.
- [ClosedTypeAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt)
  used C4037 as the Stage 1 boundary after name checking. Stage 2 retired that
  diagnostic when it integrated bound satisfaction.

## Verification

On 2026-11-11, 97 tests across 10 suites passed on Windows with Java 25 in the
native-free generics task. [GenericParsingTest](../../../../Compiler/src/test/kotlin/cloth/GenericParsingTest.kt)
checks all four owner forms, spans, and malformed bounds.
[GenericNamesTest](../../../../Compiler/src/test/kotlin/cloth/GenericNamesTest.kt)
checks self-bounds, references to other parameters, contextual `Number`,
qualified imports, name and arity failures, and the compilation boundary.

```powershell
.\gradlew.bat :Compiler:genericSyntaxTest --offline --console=plain --no-daemon
```

The focused `:Compiler:test` rerun passed 189 tests across 25 suites with
zero failures or skips. It covered `cloth.Generic*Test`, `cloth.Interface*Test`,
and `cloth.NestedTypesTest`, including their native regressions on Windows.
The full compiler run ended in a Gradle `EOFException` during concurrent
testing with shared results. The focused rerun used separate binary, XML,
and HTML result directories.
