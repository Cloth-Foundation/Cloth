# Explicit generic functions

A top-level function can declare ordered type parameters after its name. The
parameters are in scope throughout its signature, defaults, and body. The
standard-library `magic` marks a deliberately inline value and returns it as
the requested type. [NumberUtilities.co](../../../../Standard-Library/cloth/lang/num/NumberUtilities.co)

```cloth
pub fn<T> magic<T>(T value) = value
```

```cloth
using cloth.lang.num.NumberUtilities : magic

fn main() {
    def<int> count = magic<int>(64) + 1
    println(count)
}
```

To supply type arguments explicitly, write `name<types>(values)`. The
opening angle touches the name and the closing angle touches the value argument
list. This preserves spaced ordering expressions such as `a < b > (c)`.
Arguments use the same exact value types and normalization as generic types.
Value arguments, results, and copies follow ordinary function rules.
[Generic type contract](../../cloth-generics/references/semantics.md)

A file can overload same-count templates with distinct declared value-parameter
types. A call with explicit types selects that count, filters candidates by
bounds, and ranks the substituted signatures. When a call omits the type
arguments, ordinary matches take precedence. If they have no match, Cloth
can infer all type arguments from supplied values. Public templates apply
the existing private-type rules for signatures. The entry point uses an
ordinary function.
[Function inference](../../cloth-generics/references/function-inference-stage-1.md) ·
[GEN-2 Stage 2](generic-overloads-stage-2.md) ·
[Generic bounds](../../cloth-generics/references/semantics.md#initial-constraint-scope)

## Method-owned parameter design

A method-owned type parameter must have a name distinct from the enclosing
type's parameters. In `Box<T>`, a method can declare `pair<U>` and refer to
both `T` and `U` in its signature, defaults, and body.

```cloth
typedef<class> Box<T> (def<T> item) {
    pub mt<T, U> pair<U>(U value) = self.item, value
}
```

Ordinary class and struct instance methods support explicit type arguments,
as in `box.pair<string>("label")`. GEN-3 Stage 2 also infers method arguments
from supplied values, as in `box.pair("label")`.
[Explicit method specialization](generic-methods-stage-3.md) ·
[Method inference](../../cloth-generics/references/method-inference-stage-2.md)

Virtual, interface, and state methods have
[separate follow-up work](../../../../todo/open-questions-plan.md#generics-interfaces-and-nominal-types).

## Same-count overload design

Templates with the same name and type parameter count need distinct
declared value-parameter signatures. Compare callable type parameters by
position when checking duplicates. For example, `kind<T>(T value)` and
`kind<T: Number>(T value)` are duplicates. Bounds filter distinct candidates
before matching the value arguments.

Apply ordinary overload ranking after substituting the explicit type arguments.
If the candidates tie, report an ambiguous call. For example,
`kind<T>(T value, T other)` and `kind<T>(int value, T other)` tie on
`kind<int>(1, 2)`. Top-level functions use these rules in GEN-2 Stage 2.
Ordinary class and struct methods use them in Stage 3.
[Function overloads](generic-overloads-stage-2.md) · [Method overloads](generic-methods-stage-3.md)

Class method lookup applies ordinary hiding before checking bounds. A matching
child signature hides the inherited signature. If `Child.read<U: Number>`
hides `Base.read<U>`, then `child.read<string>("label")` reports that `string`
fails the `Number` bound. [Class member lookup](../../cloth-language/references/class-inheritance.md#supported-slice)

## Compiler integration

`GenericNames` binds the type annotations in their source files. The generic
worklist supplies substituted contracts and expands selected bodies by original
file-scoped symbol and ordered resolved types. It registers each selected
key before processing dependencies, so same-type recursion reuses the
specialization. Function and nominal instances share the
4,096-key budget, with separate budgets for symbolic and closed keys. The
symbolic pass checks even unused declarations.
Each required closed specialization gets its own source-linked body and
semantic facts. Normal call resolution and private ABI lowering use the closed
signature and body, including defaults, ordered results, and value transport.
Compilation stops at the first detected error.
[Generic analysis](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt) ·
[Body views](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt)

## Verification on 2026-11-10

The focused Gradle `:Compiler:test` run passed all 51 tests across `GenericFunctionsTest`,
`GenericParsingTest`, `GenericCallableImportsTest`, and `CallResolutionTest`.
`GenericFunctionsTest` compiles the actual standard-library import through
`ClothCompiler` and runs it on Windows x64 and x86. It verifies `65` from the
arithmetic example, once-only evaluation, exact numeric widths, optional
defaults, recursive specialization, nullable values, and independent array
copies. The Gradle generic syntax and three import checks also passed. The
full `:Compiler:check` run reached the Windows paging-file limit during
`StringCaseTest`.
[Native and semantic test](../../../../Compiler/src/test/kotlin/cloth/GenericFunctionsTest.kt) ·
[Parser test](../../../../Compiler/src/test/kotlin/cloth/GenericParsingTest.kt)
