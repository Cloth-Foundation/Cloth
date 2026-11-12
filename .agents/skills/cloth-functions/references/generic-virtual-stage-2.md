# GEN-6 Stage 2 · Generic virtual calls

Calls select and specialize the virtual base contract using the receiver's
static type. Explicit type arguments, inference from supplied values,
`_` slots, and type defaults use the ordinary generic method rules.
The base declaration supplies the same labels and defaults through base
and derived receivers. Each override receives the selected type arguments
by position.
[Generic virtual override design](generic-functions.md#generic-virtual-overrides)

```cloth
typedef<class> Entity {
    virtual: pub mt<T> echo<T: Number = int>(opt<T> value ?: 2 -> T) = value
}

typedef<class> Player : Entity {
    override: pub mt<U> echo<U>(U item) = item + item
}

fn main() {
    def<Entity> entity = Player::new
    println(entity.echo())
    println(entity.echo<float>(value=1.5f))
    entity::delete
}
```

The caller evaluates supplied arguments in source order. The dispatch
entry checks the receiver and evaluates omitted value defaults in
parameter order in the base declaration's scope. It checks liveness again
and selects the body using the initialized class level or the current
destructor's class. The target receives every bound value and uses the
same ordered results and buffer lifetimes as an ordinary call.
[Class virtual dispatch](../../cloth-language/references/class-inheritance.md#ordinary-class-virtual-dispatch-design)

## Compiler ownership

VirtualMethods records each generic family and the effective body for
each class. ClosedTypeAnalyzer expands a committed call by its exact base
owner, method declaration, and ordered method arguments. It instantiates
the effective overrides with the same arguments and revisits requested
families as the worklist discovers more closed classes. Descendants
without another override share their inherited body.
[VirtualMethods](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/VirtualMethods.kt) ·
[ClosedTypeAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt)

GenericBodyViews maps each class to the checked method specialization.
EmitFunctions gives each selected base specialization one dispatch entry
and uses the existing class dispatch lowering for arguments, defaults,
results, and receiver lifetime checks.
[GenericBodyViews](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt) ·
[EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)

GEN-6 also covers [generic interface calls](../../cloth-interfaces/references/generic-interface-stage-4.md)
and [generic state methods](../../cloth-language/references/generic-state-stage-5.md).

## Verification

On 2026-11-12 with Java 25, the Windows run passed 209 native-free generics
tests, 113 import tests, and 661 focused Compiler tests, with zero failures
or skips. The CLI example printed `4` and `3`.

GenericMethodTypingTest checks base and derived lookup, shared specializations,
bound receivers, and inherited targets. GenericMethodEmissionTest checks
recursive dispatch and a subclass discovered after the first call was
specialized. VirtualMethodsTest covers argument order, defaults, constructor
and destructor levels, liveness after argument effects, imported constructor
proofs, and buffered ordered results on Windows x64 and x86. Its lifecycle
fixture emits object files for all nine ABI profiles.
[GenericMethodTypingTest](../../../../Compiler/src/test/kotlin/cloth/GenericMethodTypingTest.kt) ·
[GenericMethodEmissionTest](../../../../Compiler/src/test/kotlin/cloth/GenericMethodEmissionTest.kt) ·
[VirtualMethodsTest](../../../../Compiler/src/test/kotlin/cloth/VirtualMethodsTest.kt)
