# Object and class inheritance implementation

Stage 1 of the [Object and operator plan](../../../../todo/object-and-operators-plan.md)
landed on 2026-11-06 and builds on [basic classes](class-publication.md). The
[Object and operator design](object-protocol.md) owns the broader language
direction.

## Supported slice

- `Object` is a built-in class type available without an import. A class with
  no declared base inherits it implicitly. The root is reserved for the
  compiler and serves as a base and binding type. A source program constructs
  instances of its declared classes.
- `: Base(arguments)` selects a base constructor using the ordinary argument
  binder. `: Base` supplies an empty argument list. The base clause can read
  initialized derived constructor parameters. The normal pre-initialization
  rules govern `self`. Base construction completes before initialization of
  the derived class's own fields. Each constructor level initializes its own
  fields in source order. During construction, `is` exposes the last class
  level whose fields have initialized. Deletion requires initialization of
  the concrete class. Final bases, cycles, and unknown or non-class bases fail
  at the first diagnostic.
- Implicit class upcasts preserve the handle in declarations, assignments,
  calls, and returns. The existing nullable carriers also admit class upcasts,
  with absence preserved. Class array element types are invariant. Member
  lookup uses the static class and its bases, with private access checked
  against the declaring class. A selected virtual overload dispatches through
  the initialized class level or the class level currently being destroyed.
- A class-target `is` checks the concrete class and its ancestry. An absent
  nullable class value returns false. A present object uses the standard
  liveness checks. A successful test narrows a stable named binding in the
  proven control-flow region. A binding write invalidates that proof.
  `::type` reads the static type metadata.
- Deletion through a base alias uses the concrete class, invalidates ordinary
  aliases before the first destructor, then runs the destructor chain from
  derived to base. The destructor's direct `self` retains its existing
  permission. Cleanup releases buffers and storage after the chain finishes.

```cloth
typedef<class> Entity (pub def<int> id)
final typedef<class> Player (int id) : Entity(id) {
    pub mt<int> playerId() = self.id
}

fn<int> main() {
    def<Object> object = Player::new(7)
    if (object is Player) println(object.playerId())
    object::delete
    return 0
}
```

## Ordinary class virtual dispatch design

A base method explicitly opts into virtual dispatch with `virtual:`, as in
`virtual: pub mt<string> describe()`. A derived method uses `override:` to
supply the replacement body. After construction, a call on a live `Player`
through an `Entity` binding selects the `Player` override of the selected
virtual method.

Both the virtual base method and its override use `pub`. An override matches
the base method's parameter types and ordered result types exactly.

During construction, a virtual call dispatches through the most-derived
class level whose fields have initialized. In an `Entity` constructor running
for a `Player`, a call after the `Entity` fields initialize selects the
`Entity` implementation. The `Player` override becomes available when the
`Player` fields finish initializing.

During destruction, virtual calls through permitted `self` dispatch through
the class whose destructor is running. After `~Player` finishes, a call in
`~Entity` selects `Entity`'s implementation. A call through a helper on
direct `self` uses the same class level.

The `virtual:` declaration supplies the argument labels and omission defaults
for calls through the base class and its subclasses. An `override:` declares
required parameters whose names are local to its body. The ordinary binder
evaluates supplied arguments in written order and defaults in declaration
order. The selected body receives a value for every parameter.

```cloth
typedef<class> Entity {
    virtual: pub mt<string> describe(opt<string> label ?: "entity") = label
}

typedef<class> Player : Entity {
    override: pub mt<string> describe(string text) = #"player {text}"
}
```

`player.describe(label="scout")` binds `label` from `Entity` and passes
`"scout"` to `Player`'s `text` parameter.

Generic virtual overrides inherit the base method's type parameter bounds
by position. [GEN-6 Stage 1](../../cloth-functions/references/generic-virtual-stage-1.md)
Generic virtual calls select type arguments from the base contract. Runtime
selects the matching specialized body at the same lifecycle class level as
an ordinary virtual call.
[GEN-6 Stage 2](../../cloth-functions/references/generic-virtual-stage-2.md)

## Virtual method implementation

`ClassParser` retains the `virtual:` prefix on a class method. The Kotlin and
Bootstrap lexers recognize `virtual` as a keyword. `VirtualMethods` checks
public visibility and exact signatures after generic substitution. A matching
method in a derived class requires `override:`. C3133 reports an invalid
virtual declaration or override. [Class parser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ClassParser.kt) ·
[Virtual method checks](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/VirtualMethods.kt)

The compiler resolves overloads visible on the receiver's static type. For a
virtual overload it uses the `virtual:` declaration for argument binding. The
dispatch entry evaluates defaults in that declaration's scope and checks the
captured receiver again before selecting the body for the initialized or
destructor class level. The runtime stores the current destructor's class
after deletion validates the concrete class.
Type tests and interface calls read the initialized class tag. Class and
interface dispatch share the native argument and result handling.
[Call lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt) ·
[Object slots](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassRuntime.kt)

On 2026-11-10, 66 focused compiler tests passed on JDK 25. The virtual method
fixture exercised constructor dispatch, base-typed and implicit calls, generic
specialization, interface conformance and ordered results natively on Windows
x64 and x86. It generated objects for all nine target profiles. Bootstrap's
lexer matched Kotlin on 126 inputs on both native profiles. [VirtualMethodsTest](../../../../Compiler/src/test/kotlin/cloth/VirtualMethodsTest.kt) ·
[BootstrapLexerTest](../../../../Compiler/src/test/kotlin/cloth/BootstrapLexerTest.kt)

The destructor stage passed 48 focused compiler tests on JDK 25 on 2026-11-10.
The fixture checks that `~Player` can reach its override through an inherited
helper and `~Entity` calls the base body. A type test on destructor `self`
observes the initialized class. The fixture ran on Windows x64 and x86 and
generated objects for all nine profiles. [VirtualMethodsTest](../../../../Compiler/src/test/kotlin/cloth/VirtualMethodsTest.kt)

The argument stage verifies named and omitted calls through both class types,
closed generic defaults, one evaluation of each omitted expression, and the
liveness check when a default deletes the receiver. It ran on Windows x64 and
x86 and generated objects for all nine profiles. The final JDK 25 run on
2026-11-10 passed all 2,086 compiler tests and three Kotlin/Native Shuttle
publication tests. [VirtualMethodsTest](../../../../Compiler/src/test/kotlin/cloth/VirtualMethodsTest.kt) ·
[ShuttlePublicationTest](../../../../Shuttle/native/src/jvmTest/kotlin/shuttle/ShuttlePublicationTest.kt)

## Implementation ownership

- [ClassParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ClassParser.kt)
  records the constructor clause. [NamedTypes](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/NamedTypes.kt)
  establishes the nominal base chain before resolving field types.
  [FunctionAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt)
  binds the constructor clause and applies subtype compatibility to the normal
  value paths. [NullFlowAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/NullFlowAnalysis.kt)
  tracks class narrowing alongside presence.
- [CClass](../../../../Compiler/src/main/kotlin/cloth/data/CClass.kt) uses a
  zero-offset base prefix so a base member can address its own layout within
  any derived instance. [EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
  forwards the concrete allocation size and class tag through constructor
  delegation. The first source base allocates the concrete storage once.
- [EmitClassRuntime](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassRuntime.kt)
  stores the concrete and initialized class tags in the validated slot.
  Type checks and destructor selection check the slot and generation. Field cleanup
  resolves the storage again after destructor calls. Ordinary virtual dispatch,
  states, and the operator stages have their own implementation contracts.

## Verification

[ClassInheritanceTest](../../../../Compiler/src/test/kotlin/cloth/ClassInheritanceTest.kt)
covers concrete layout and destructor order, typed and nullable upcasts,
class tests and flow invalidation, incomplete construction, stale aliases,
invalid inheritance, and target object emission. The final regression run
passed 448 tests across 42 suites with zero failures, errors, or skips.
Inheritance cases executed natively on Windows x64 and x86. The inheritance
fixture verified IR and emitted objects for all nine target profiles.

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Class*Test' --tests 'cloth.Struct*Test' --tests 'cloth.Nullable*Test' --tests 'cloth.Nullability*Test' --tests 'cloth.Function*Test' --tests 'cloth.OptionalArguments*Test' --tests 'cloth.MultiReturn*Test' --tests cloth.CallResolutionTest --tests cloth.TypeInfoTest --tests cloth.TargetLayoutTest --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon
```
