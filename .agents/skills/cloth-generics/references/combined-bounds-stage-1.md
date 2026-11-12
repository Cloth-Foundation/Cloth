# GEN-4 Stage 1 · Combined bound declarations

Use a single `&` between bounds on a type parameter. The shared parser
accepts the list on generic classes, structs, interfaces, top-level
functions, and ordinary class and struct methods.

```cloth
typedef<class> Entity<T> {}
typedef<interface> INamed {}

fn inspect<T: Entity<T> & INamed>(T value) {}
```

This example passes parsing and name resolution. A bound list may contain
multiple interfaces and at most one class, in any order. Each bound uses
the declaring scope, including imports, qualified names, and the owner's
type parameters. In the example, the inner `T` resolves to `inspect`'s own
parameter. A default follows the entire list, as in
`T: Entity<T> & INamed = Player`.

Each bound retains its own source span. C3134 identifies a second class
bound and labels the first class bound. It also identifies bare `Number`
in a combined list, since `Number` stands alone. Public declarations check
signature visibility for each bound and its type arguments.

## Compiler scope

[TypeParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/TypeParser.kt)
records the ordered list on `TypeParameterDeclaration`. Kotlin and Bootstrap
both tokenize `&`, with longest matching preserving `&&` for boolean
conjunction. [GenericAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericNames.kt)
resolves each bound. At this milestone, C4033 marked the boundary before
bound checking.

[Stage 2](combined-bounds-stage-2.md)
checks every bound, written duplicates, and incompatible interface
applications. Stage 3 implements operations through the combined views
and preserves the selected call contract during specialization.
[Confirmed combined-bound rules](semantics.md#combined-bounds-design)

## Verification

On 2026-11-12, `:Compiler:genericSyntaxTest` passed all 180 tests across
16 suites on Windows with Java 25. [GenericParsingTest](../../../../Compiler/src/test/kotlin/cloth/GenericParsingTest.kt)
covers separators, spans, defaults, and `&&`. [GenericNamesTest](../../../../Compiler/src/test/kotlin/cloth/GenericNamesTest.kt)
covers imported and recursive names, class and interface forms, public
signature visibility, and the compilation diagnostic.

The three focused import tasks passed 113 tests. The focused
`:Compiler:test` run passed 563 tests across 69 suites. It covered
generic calls, class and interface dispatch, state methods, and source
publication. [BootstrapLexerTest](../../../../Compiler/src/test/kotlin/cloth/BootstrapLexerTest.kt)
compared tokens, spans, and first errors for 136 inputs on Windows x64
and x86, including the operator list with `&` and `&&`.
