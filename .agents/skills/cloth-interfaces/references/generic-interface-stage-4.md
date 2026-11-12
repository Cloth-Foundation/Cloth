# GEN-6 Stage 4 · Generic interface calls

An interface call selects its contract from the receiver's static view.
Generic interface methods accept explicit type arguments, inference from
supplied values, `_` slots, and declared type defaults. An ordinary overload
can match a call with no type argument list before inference runs. The
selected interface declaration supplies the parameter labels, type defaults,
and omitted value defaults. [Static interface call contracts](design.md#static-interface-call-contracts-confirmed-2026-11-08)

```cloth
typedef<interface> Scale {
    mt<T> scale<T: Number = int>(opt<T> value ?: 2 -> T) = default (value)
}

typedef<class> Double : Scale {
    override: mt<U> scale<U>(U item) = item + item
}

typedef<class> Plain : Scale {}

fn main() {
    def<Scale> doubled = Double::new
    def<Scale> fallback = Plain::new
    println(doubled.scale())
    println(fallback.scale<float>(value=1.5f))
    doubled::delete
    fallback::delete
}
```

When several inherited declarations would share a child's static contract,
Cloth compares their type defaults after substitution and positional
matching. Different defaults require a child declaration. Each parent view
keeps its declaration's defaults. C3121 labels both inherited declarations.

Calls on interface-bounded type parameters keep the interface view and
overload selected at the declaration site. The closed call reuses those
choices with the specialized arguments. Generic candidates from class and
interface bounds use the same overload ranking. Equal ranks from different
bounds require an explicit view. [Combined bound operations](../../cloth-generics/references/combined-bounds-stage-3.md)

Dispatch uses the checked initialized class. The runtime captures and
checks the receiver, evaluates supplied expressions in source order, then
evaluates omitted value defaults in parameter order. It rechecks liveness
before entering the target. The chosen class implementation or interface
fallback receives the same ordered type arguments and all bound values.
Ordered results and buffered values use the ordinary interface call ABI.

## Compiler ownership

GenericCallInference and CallResolution select the specialized static
contract. ClosedTypeAnalyzer interns the declaration, declaring interface
application, and ordered method arguments as a callable instance. A
committed call also requests dispatch through its static view. The worklist
adds the matching class bodies and interface fallback specializations and
revisits those requests when it discovers more implementing classes.
[Call inference](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/GenericCallInference.kt) ·
[Call resolution](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/CallResolution.kt) ·
[Specialization worklist](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt)

GenericBodyViews rebinds declaration-site bound selections and maps each
target to its checked specialization. EmitInterfaceDispatch reuses the
ordinary dispatch lowering and caches one entry per static view and
specialized contract. Fallback body calls use the same checked entries.
[Body views](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt) ·
[Dispatch lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitInterfaceDispatch.kt)

GEN-6 Stage 5 covers [method-owned state contracts](../../cloth-language/references/generic-state-stage-5.md).
[Open questions plan](../../../../todo/open-questions-plan.md#generics-interfaces-and-nominal-types)

## Verification

On 2026-11-12 with Java 25, 970 tests passed across 84 Compiler suites.
The native-free runs passed 222 generics tests and 113 import tests. All
runs had zero failures, errors, or skips. The example ran through the
compiler CLI on Windows x64 and printed `4` and `1.5` with exit code 0.

GenericInterfaceMethodsTest covers ordinary overload precedence, positional
type defaults, bound overload selection, diagnostics, and nullable results.
GenericMethodEmissionTest verifies independent parent and child contracts,
source-order arguments, buffered ordered results, constructor proofs across
files, recursive fallback calls, late class discovery, and liveness after
argument and default effects. These fixtures ran on Windows x64 and x86.
The defaults and ordered-results fixture emitted objects for all nine ABI
profiles.
[Semantic fixtures](../../../../Compiler/src/test/kotlin/cloth/GenericInterfaceMethodsTest.kt) ·
[Native fixtures](../../../../Compiler/src/test/kotlin/cloth/GenericMethodEmissionTest.kt)
