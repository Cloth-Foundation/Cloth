# GEN-2 Stage 3 ordinary generic methods

Ordinary class and struct methods can use both the enclosing type's parameters
and their own. GEN-2 Stage 3 integrates explicit method calls.

```cloth
typedef<class> Box<T> (def<T> item) {
    pub mt<T, U> pair<U>(U value, opt<T> first ?: self.item) = first, value
}

fn main() {
    def box = Box<int>::new(7)
    def<int, string> first, label = box.pair<string>("Cloth")
    println(first)
    println(label)
    box::delete
}
```

An explicit `self.method<U>(...)` call selects the receiver's group. An
unqualified `method<U>(...)` call uses ordinary lexical lookup, then the
implicit receiver when lexical lookup finds no match. The selected declaration
supplies argument labels and defaults. Defaults can read earlier parameters
and the read-only receiver.
[Method scope](method-syntax.md) · [Stage 1 syntax](generic-methods-stage-1.md)

Lookup uses the static receiver type and the explicit type argument count.
For class inheritance, compare declared parameter types with enclosing
arguments substituted and method parameters identified by position. A
nearer declaration with the same pattern hides the inherited one before
visibility and bound checks. For example, when `Child.read<U: Number>`
hides `Base.read<U>`, `child.read<string>("label")` reports C3135. A
`Base` binding still uses the base declaration and its call contract.
[Class inheritance](../../cloth-language/references/class-inheritance.md#supported-slice)

Viable candidates use ordinary argument binding and overload ranking after
substitution. Distinct declared signatures can tie once both sets of arguments
are substituted. Calls through a type parameter's class bound keep the
template selected during declaration checking. [Overload resolution](generic-overloads-stage-2.md) ·
[Bound operations](../../cloth-generics/references/constraints-stage-3.md)

## Compiler integration

- `ClosedTypeAnalyzer` records template signatures separately from ordinary
  method bodies and registers a canonical body for each declaration. A
  specialization key combines the original method, the exact application
  of its declaring owner, and the ordered method arguments. Inherited
  calls share that declaring owner's specialization.
  [Type worklist](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt)
- `DeclarationModel` applies inherited hiding. `GenericMethodCalls` checks
  arity, visibility, and bounds, then hands the substituted contracts to the
  shared overload resolver. A signature that introduces a nominal definition
  goes through the existing type worklist before typing continues.
  [Method lookup](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationModel.kt) ·
  [Candidate selection](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/GenericMethodCalls.kt)
- Nullable flow uses the candidate contracts when the receiver and type
  arguments are known. When the contract depends on receiver typing or a
  captured numeric width, the call invalidates proofs about receiver fields.
  [Nullable flow](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/NullFlowAnalysis.kt)
- `GenericBodyViews` links each selected body and default to its source and
  retains separate facts for each specialization. Requests from overload
  trials travel with the trial's facts. The existing receiver plans and
  private callable ABI carry ordinary class and struct calls, including
  stored struct mutations, class lifetime checks, and ordered results.
  [Body views](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt) ·
  [Native method calls](method-execution.md)

C4038 was retired. GEN-3 Stage 2 adds ordinary method inference. GEN-6 tracks
generic virtual, interface, and state dispatch contracts.
[Method inference](../../cloth-generics/references/method-inference-stage-2.md) ·
[Open questions plan](../../../../todo/open-questions-plan.md#generics-interfaces-and-nominal-types)

## Verification on 2026-11-11

On Java 25, all 133 `:Compiler:genericSyntaxTest` tests, 113 import checks,
and 425 focused `:Compiler:test` tests passed. The semantic tests cover
separate owner and method arguments, inherited
hiding, declaration-site bound calls, contextual receiver types, nullable
flow, captured widths, visibility, diagnostics, and specialization limits. The native test
runs Windows x64 and x86 class and struct calls with inherited defaults,
ordered results, recursion, named arguments, guarded access, and stored
receiver mutation.
[Semantic tests](../../../../Compiler/src/test/kotlin/cloth/GenericMethodTypingTest.kt) ·
[Native test](../../../../Compiler/src/test/kotlin/cloth/GenericMethodEmissionTest.kt)
