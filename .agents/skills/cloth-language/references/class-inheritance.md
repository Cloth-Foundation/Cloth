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
  against the declaring class. Ordinary method calls use the selected static
  declaration.
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
