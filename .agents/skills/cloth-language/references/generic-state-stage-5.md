# GEN-6 Stage 5 · Generic state methods

A shared state contract can declare method type parameters after the
overload name. The explicit host declaration supplies bounds, labels,
visibility, and type and value defaults. A state body declares its own
type parameter names and inherits the bounds by position. Its value
parameters are required and can use local names. [State syntax](state-syntax.md)

```cloth
typedef<class> Player (def<int> power) {
    pub state<T> ::attack<T: Number = int>(opt<T> amount ?: self.power -> T)

    default: pub state<void> Alive {
        mt<U> attack<U>(U value) = value + value
    }

    pub state<void> Tired {
        mt<U> attack<U>(U value) = -value
    }
}

fn main() {
    def player = Player::new(5)
    println(player::attack())
    player -> Tired
    println(player::attack<float>(amount=1.5f))
    player::delete
}
```

Without an explicit contract, state bodies can infer a required signature.
Matching bodies agree on labels, visibility, bounds, value parameter types,
and ordered results after enclosing type substitution and positional
matching. A shared constructor guarantee needs matching labels and one
defining declaration for each default. Use an explicit host signature to
supply method type defaults or omitted value arguments. Subclasses retain
the original contract and can implement it in additional states.

Calls select the contract from the receiver's static class. A call without a
type argument list tries ordinary overloads before inference. Explicit
arguments, inferred arguments, `_` slots, and declared type defaults use
the ordinary generic rules. The selected overload keeps its declared
identity when distinct templates specialize to identical parameter types.
[Generic arguments](../../cloth-generics/references/partial-arguments-and-defaults-stage-4.md)

The dispatcher captures the receiver and evaluates supplied arguments in
source order. Omitted value defaults run in parameter order with the host's
read-only `self`. The dispatcher checks the captured object after those
effects and selects the specialized body for the active state. It pins that
activation during the call, including when the body transitions the object.
The existing checks for missing bodies and destructor access also apply.
[State dispatch and lifetime](state-runtime.md#state-method-dispatch)

A reusable state is checked for each attaching class. Its own lexical names
and ordinary `self` access follow the state definition's scope. Its inherited
bounds and constructor guarantee defaults come from the attaching class's
selected host contract. [State attachments](state-attachments.md)

## Compiler ownership

ClassParser and ExpressionParser retain the type parameters and call
arguments. StateCatalog matches templates by arity, normalized parameter
types, and enclosing type arguments. It records generic conformance and
the specialized symbols for each supported state.
[State catalog](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/StateCatalog.kt) ·
[State parser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ClassParser.kt) ·
[Call parser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ExpressionParser.kt)

GenericCallInference selects type arguments from the shared signature.
ClosedTypeAnalyzer checks symbolic contracts and bodies and expands
committed calls to the corresponding state bodies. It uses the current
catalog on each pass to include newly discovered classes. GenericBodyViews
keeps a separate canonical body scope for each attaching class.
[Call inference](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/GenericCallInference.kt) ·
[Specialization worklist](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt) ·
[Body views](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt)

ClassLifetimeAnalysis uses the selected template to check a proven active
state. EmitStateDispatch uses the closed body selected by that same template
and argument list. State payloads and ordered results use the existing
activation and callable ABI.
[Lifetime checks](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/ClassLifetimeAnalysis.kt) ·
[State dispatch lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitStateDispatch.kt)

## Verification

On 2026-11-12 with Java 25, 1,125 tests passed across 98 Compiler suites.
The native-free runs passed 229 generics tests and 113 import tests. All
runs had zero failures, errors, or skips. The example ran through the
compiler CLI on Windows x64 and printed `10` and `-1.5` with exit code 0.

GenericStateMethodsTest checks exact base applications, positional bounds,
defaults, inferred contracts, ordinary precedence, visibility, and first
diagnostics. GenericStateEmissionTest verifies argument order, retained
payloads, destructor self, recursion, late state targets, and separate
attachment scopes. The native fixtures ran on Windows x64 and x86. The
argument-order and payload fixture emitted objects for all nine ABI
profiles.
[Semantic fixtures](../../../../Compiler/src/test/kotlin/cloth/GenericStateMethodsTest.kt) ·
[Native fixtures](../../../../Compiler/src/test/kotlin/cloth/GenericStateEmissionTest.kt)
