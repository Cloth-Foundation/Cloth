# GEN-6 Stage 3 · Generic interface declarations and conformance

An interface method can declare its own type parameters in a required
signature or a `= default` implementation. Its parameters and the enclosing
interface parameters are both in scope. The compiler checks the signature,
argument defaults, and any fallback body even when the method is unused.
[Confirmed bound rules](design.md#generic-interface-implementation-bounds)

```cloth
typedef<interface> IMeasure {
    mt<T> echo<T: Number>(T value)
}

typedef<interface> IScaled : IMeasure {
    mt<U> echo<U: Number>(U value) = default (value + value)
}

typedef<class> Measure : IScaled {
    override: pub mt<V> echo<V>(V item) = item + item
}

fn main() {
    def measure = Measure::new
    println(measure.echo(3))
    measure::delete
}
```

A child interface repeats the bounds when it redeclares a method. An
`override:` on a class declares its own type parameter names and inherits
the bounds by position. Conformance compares method type parameter count,
exact parameter and ordered result types, and applicable bounds after
substituting enclosing type arguments. A public generic base method with
matching declared or inherited bounds can satisfy a new interface
requirement.

An override shared by multiple interface requirements needs matching
bounds. For constructor guarantees used by the shared body, matching
signatures also need matching labels and a single defining declaration for
each optional default. A diamond through one shared requirement keeps that
declaration's constructor guarantee and its defining scope. An override
that also belongs to a virtual family checks the interface bounds against
the virtual base contract.

Direct calls through a class use its visible method contract, including its
labels and value or type argument defaults. The inherited method bounds
still constrain the selected type arguments.
[Specialized interface calls](generic-interface-stage-4.md)

## Compiler ownership

FunctionParser retains the method type parameter list. GenericAnalyzer
binds its names throughout the signature, defaults, and body.
GenericSignatureKeys compares callable parameters and bounds by position
using each declaration's enclosing arguments. InterfaceConformanceAnalyzer
applies the existing class and fallback precedence to these requirements.
C3121 labels incompatible requirements and C3123 reports invalid class
overrides. C3122 reports a missing eligible implementation or fallback.
[Parser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/FunctionParser.kt) ·
[Signature keys](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericFunctionSignatures.kt) ·
[Conformance analyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/interfaces/InterfaceConformanceAnalyzer.kt)

ClosedTypeAnalyzer registers symbolic interface signatures and fallback
bodies and binds class override scopes using the selected requirement.
GenericBodyViews feeds those source-linked bodies through the ordinary
semantic checks. Direct class specialization also checks the inherited
bounds and retains constructor defaults from the requirement's scope.
[Worklist](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt) ·
[Body views](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt)

## Verification

On 2026-11-12 with Java 25, 964 tests passed across 84 related Compiler
suites. The native-free runs passed 219 generics tests and 113 import tests.
All runs had zero failures, errors, or skips. The example above ran through
the compiler CLI on Windows x64 and printed `6` with exit code 0.

GenericInterfaceMethodsTest covers positional matching, child fallbacks,
shared overrides, public inherited implementations, signature visibility,
type defaults, virtual family compatibility, and call diagnostics.
GenericMethodEmissionTest ran an imported constructor guarantee inherited
through a diamond on Windows x64 and x86 with Java 25. It printed `7`,
`7`, and `3` from the interface's two private default expressions and an
ordinary interface fallback.
[Semantic fixtures](../../../../Compiler/src/test/kotlin/cloth/GenericInterfaceMethodsTest.kt) ·
[Native fixture](../../../../Compiler/src/test/kotlin/cloth/GenericMethodEmissionTest.kt)
