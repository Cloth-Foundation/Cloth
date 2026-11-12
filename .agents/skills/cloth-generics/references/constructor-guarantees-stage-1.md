# GEN-5 Stage 1 · Constructor declarations and proof

Write `new(...)` in a type parameter's bound list. Each guarantee requires
an accessible constructor with the exact ordered parameter types and full
parameter count. Primary and secondary constructors both qualify, and an
omitted class parameter list supplies the ordinary zero-argument primary
constructor. A standalone guarantee lets the value widen to `Object`.

```cloth
typedef<class> Player (pub def<float> health) {
    Player() : (100f) {}
}

fn<T> keep<T: new() & new(opt<float> amount ?: 100f)>(T value) {
    def<Object> view = value
    return value
}

fn main() {
    def player = Player::new()
    def<Player> retained = keep(player)
    retained::delete
}
```

The guarantee owns its labels and defaults. Its parameter types resolve in
the generic declaration's scope. Defaults use the ordinary constructor
default scope with earlier parameters available and no instance receiver.
Public signatures check the visibility of all guarantee parameter types.

C3134 labels both occurrences of a repeated written signature. Distinct
guarantees that resolve to the same signature after substitution can share
one concrete constructor. C3135 labels the argument and the missing or
inaccessible guarantee. A symbolic argument forwards its own exact
constructor proof. The scope of each concrete type argument application
determines constructor accessibility, including for caller-private classes.
An already established proof can be forwarded in a generic body or type
default.

Stage 2 implements calls through `T::new(...)`.
[Stage 2 integration](constructor-guarantees-stage-2.md) ·
[Constructor guarantee design](semantics.md#constructor-guarantee-design)

## Compiler ownership

[TypeParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/TypeParser.kt)
reuses the parameter parser for guarantees. [GenericAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericNames.kt)
binds the parameter types and type uses in default expressions in the
original declaration scope. [GenericBodyViews](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt)
checks defaults through the existing callable contract analysis.

[ClosedTypeAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt)
uses the closed constructor signatures for exact matching. Canonical
parameters retain their declared constructor signatures for forwarding.
[ClosedTypeModel](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeModel.kt)
checks access per application, including when a nominal type or a callable
contract is already cached. Inaccessible candidates leave their signatures
uninstantiated until a use with access requests them.

## Verification

On 2026-11-12 with Java 25, the Windows run passed 197 tests across
16 native-free generics suites, 113 tests across the focused import
tasks, and 640 focused Compiler tests across 77 suites. There were
zero failures or skips.

[GenericParsingTest](../../../../Compiler/src/test/kotlin/cloth/GenericParsingTest.kt)
checks guarantee order, spans, and parameter syntax.
[GenericBoundsTest](../../../../Compiler/src/test/kotlin/cloth/GenericBoundsTest.kt)
covers exact signatures, default scope, widening, forwarded proofs,
caller-private access, and candidate filtering. [GenericEmissionTest](../../../../Compiler/src/test/kotlin/cloth/GenericEmissionTest.kt)
executes a specialization that retains the exact class on Windows x64
and x86.
