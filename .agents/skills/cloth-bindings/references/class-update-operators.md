# OP-1 class update operators

```cloth
typedef<class> Meter (pub def<int> value) {
    operator +=(int delta) { value += delta }
    operator ++() { value++ }
}

fn main() {
    def meter = Meter::new(10)
    def values := [meter]
    values[0] += 2
    values[0]++
    println(meter.value)
    meter::delete
}
```

The example prints `13`. The array slot and `meter` hold the same checked
identity. The [binding contract](semantics.md#compound-assignment-overloads)
covers all five compound assignments, postfix updates, and the fallback.

## Stage 1 declarations, locals, and fields

- Class declarations use implicit `self`. A compound operator takes one
  required argument. A postfix operator takes zero. Both return `void`,
  and a block or a single void statement can supply the body.
- A writable local or field captures the object before the right operand
  runs. The dispatch checks the captured object again after that operand.
  A return or loop jump during evaluation bypasses the call.
- The compiler uses ordinary operator lookup, inheritance, and overload
  ranking. An ambiguous dedicated overload fails compilation. If there is
  no applicable compound overload, the corresponding binary operation
  supplies a value for ordinary write-back. Postfix falls back to the
  ordinary numeric update.

## Stage 2 indexed targets

- Array elements use the existing checked selection, including nested
  array and field paths and temporary arrays. Selection and capture finish
  before the right operand. Once captured, the class identity can outlive a
  removed slot under ordinary class lifetime rules.
- A class bracket must supply `[]` and a compatible `[]=` with one shared
  validator. Capture the collection and index once, validate, then read the
  object once. On a dedicated update, the captured object is the receiver.
  The setter and second index check belong to write-back fallback.
  [Collection indexing](../../cloth-collections/references/indexing.md#confirmed-design)

## Compiler integration

[BodyTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/BodyTyping.kt)
records the selected update separately from the synthetic binary expression
used by fallback. Indexed targets reuse the ordinary writable place and
setter checks. [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
captures the handle and invokes the ordinary class callable ABI. The lifetime
pass checks known stale captures and invalidates ownership and state facts
after effectful dispatch. Presence analysis invalidates aliased field proofs
when an update can call user code.

Dedicated operators use class receivers. Struct indexing and additional
operator families remain open design items.

## Verification

[ClassUpdateOperatorsTest](../../../../Compiler/src/test/kotlin/cloth/ClassUpdateOperatorsTest.kt)
checks all seven declaration forms, local and field aliasing, indexed
capture and early exit, immutable bindings, overload ambiguity, and shared
validator requirements. It exercises the imported `List<T>` after slot
removal and collection deletion, and checks that stale elements fail before
dispatch. It also checks nullable field proofs and owning-list effects.
The native fixtures run on Windows x64 and x86. LLVM IR verification and
object emission cover all nine target profiles.

On 2026-11-13, 1,131 focused compiler tests passed across 102 suites.
The example above printed `13` through `cloth run` and exited with status 0.

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.*Operator*Test' --tests 'cloth.*Assign*Test' --tests 'cloth.*Binding*Test' --tests 'cloth.*Array*Test' --tests 'cloth.*Null*Test' --tests 'cloth.Class*Test' --tests 'cloth.*State*Test' --tests 'cloth.Collection*Test' --tests 'cloth.Standard*List*Test' --tests 'cloth.Generic*Test' --tests 'cloth.Interface*Test' --tests 'cloth.*Loop*Test' --tests 'cloth.*ParsingTest' --tests cloth.ImportPublicationTest --offline --console=plain
```
