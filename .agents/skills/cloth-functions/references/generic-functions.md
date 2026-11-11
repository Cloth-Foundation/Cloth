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

At a call, write the type arguments explicitly. In `name<types>(values)`, the
opening angle touches the name and the closing angle touches the value argument
list. This preserves spaced ordering expressions such as `a < b > (c)`.
Arguments use the same exact value types and normalization as generic types.
Value arguments, results, and copies follow ordinary function rules.
[Generic type contract](../../cloth-generics/references/semantics.md)

A file can define one template for each name and type parameter count. A call
with explicit types selects that count before checking value arguments. Calls
without explicit types use the ordinary overloads. The compiler rejects a
duplicate template, a call with an unavailable type argument count, or an
application such as `magic<void>(1)`. Public templates apply the existing
private-type rules for signatures. The entry point uses an ordinary function.
Method-owned parameters, inference, and overloads with the same type parameter
count are tracked separately. [Follow-ups](../../../../todo/generic-follow-ups.md#generic-functions-and-method-owned-parameters)
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

The first method slice covers ordinary class and struct instance methods.
A call supplies method type arguments explicitly, as in
`box.pair<string>("label")`. Virtual, interface, and state methods have
[separate follow-up work](../../../../todo/open-questions-plan.md#generics-interfaces-and-nominal-types).

## Compiler integration

`GenericNames` binds the type annotations in their source files. The existing
generic worklist interns each function by its original file-scoped symbol and
ordered resolved types. It shares the nominal-type resource limits and
registers the key before processing its dependencies, so same-type recursion
reuses the specialization. Function and nominal instances share the
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
