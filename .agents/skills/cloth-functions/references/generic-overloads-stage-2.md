# GEN-2 Stage 2 same-count function overloads

Top-level generic functions with the same name and type parameter count can
overload on their declared value-parameter types. Calls supply the type
arguments explicitly.

```cloth
fn<string> describe<T: Number>(T value) = value::str
fn<string> describe<T>(string value, opt<string> suffix ?: "!") = value + suffix

fn main() {
    println(describe<int>(5))
    println(describe<string>("Cloth"))
}
```

`describe<int>(5)` selects the numeric overload. For
`describe<string>("Cloth")`, `string` fails the `Number` bound and the other
overload supplies its suffix default.

- Compare declared parameter types with callable type parameters identified by
  position. For example, `kind<T>(T value)` and `kind<U: Number>(U item)`
  produce C3019. Numeric widths, nullable positions, array extents, and
  nested nominal type arguments use ordinary type normalization.
- Select the visible file-scoped overload group and the explicit type argument
  count. Check each candidate's bounds before resolving its substituted
  signature. If every candidate fails, C3135 identifies the first failing
  bound in declaration order and labels the explicit type argument.
- Bind and rank the surviving signatures with the ordinary value-call rules,
  including named placement, contextual arguments, and omitted defaults.
  `kind<int>(1, 2)` is ambiguous when `kind<T>(T value, T other)` and
  `kind<U>(int value, U other)` both match at the same rank. [Overload design](generic-functions.md#same-count-overload-design)

## Compiler integration

`GenericFunctionSignatures` checks duplicates in the declaring file by resolving
signature types with shared positional parameter identities. Bound constraints
affect candidate eligibility. Imports preserve the defining function symbols
and expose the file's published overloads.
[Signature checking](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericFunctionSignatures.kt)

`ClosedTypeAnalyzer` prepares candidate contracts after the required type and
conformance edges are available. Failed nominal bounds can be diagnosed without
interning their applications. `GenericBodyViews` gives viable candidates
substituted signature views for the existing `CallResolution` trials.
[Type worklist](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt) ·
[Call resolution](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/CallResolution.kt)

A committed call requests a body keyed by the original function symbol and
exact type arguments. The worklist expands selected defaults and bodies, then
checks the new calls they introduce. Recursion reuses the registered key.
Canonical declaration checking still checks every generic template. Callable
views, argument typing trials, and symbolic and closed catalogs retain separate
facts. Compilation stops at the first error.
[Semantic passes](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericSemanticAnalysis.kt) ·
[Trial facts](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionSemanticModel.kt)

Stage 3 applies overload resolution and specialization to class and struct
methods. Their syntax and name binding are covered by Stage 1.
[Stage 1 integration](generic-methods-stage-1.md) ·
[Stage 3 integration](generic-methods-stage-3.md) ·
[GEN-2 plan](../../../../todo/open-questions-plan.md#generics-interfaces-and-nominal-types)

## Verification on 2026-11-11

On Java 25, all 123 `:Compiler:genericSyntaxTest` tests, 54 import resolution
tests, 36 import semantic tests, and 225 focused `:Compiler:test` tests passed.
They cover normalized duplicate signatures, numeric and nominal bound
filtering, contextual argument trials, imported groups, and ambiguity after
substitution. A regression checks that a losing function's
body contributes no specialization dependencies. The Windows x64 and x86
native test runs two winners for the same explicit type arguments and checks
recursive calls, named arguments, and defaults.
[Semantic tests](../../../../Compiler/src/test/kotlin/cloth/GenericOverloadTypingTest.kt) ·
[Native test](../../../../Compiler/src/test/kotlin/cloth/GenericFunctionsTest.kt)
