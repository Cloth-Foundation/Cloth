# Interface Stage 6 value contexts and flow

Stage 6 completed on 2026-11-09. It integrates class-backed interface values with aggregate storage,
generic holders, narrowing, object operations and native metadata. The
maintained example copies interface identities into a generic struct and an
array, dispatches through an `Object` view, and formats the array after its
former owner is deleted. The imported example uses a private implementation
and defaults defined in another file. [Interfaces.co](../../../../examples/Interfaces.co) ·
[Imported Shuttle project](../../../../examples/Interfaces/build.toml) ·
[Implementation plan](../../../../todo/interfaces-plan.md)

## Storage and lifetime

- Class and struct fields, fixed and growable arrays, nested aggregates,
  nullable values, foreach snapshots, results, positional receptions and
  deferred blocks carry the checked class identity. Copying an aggregate
  makes its storage independent and preserves the object aliases in its
  elements. Compiler buffer cleanup and element removal release storage.
  A live object is destroyed by explicit deletion through any live view.
- An array literal under an interface array annotation adapts each supplied
  element to the declared view. A preexisting array keeps its element type,
  including nullability and the exact application of a generic interface.
  Array extents still follow the ordinary shape contract.
- Storage uses the existing private 64-bit slot and generation carrier.
  The nullable carrier adds the ordinary presence flag. A stale identity
  stays distinct from a replacement instance that reuses the slot.
  [Representation](../../../../Compiler/src/main/kotlin/cloth/data/CInterface.kt) ·
  [Storage transport](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitStoredValue.kt) ·
  [Buffer lifetimes](../../cloth-buffer-lifetimes/SKILL.md)

## Object operations and proofs

- A class-backed interface can widen to `Object` implicitly or with `->`.
  The same conversion lifts to nullable views and preserves absence. Use a
  successful nominal `is` guard to establish a concrete class or another
  interface view. The test checks the initialized class tag and the exact
  closed interface application. Direct casts follow declared widenings.
- Positive interface tests establish membership in the target and its
  ancestors. Compound guards retain facts guaranteed on every continuing
  path, so `value is Left || value is Right` can narrow to their shared
  interface. A stable field path can establish presence. Local assignments
  and loop writes discard affected binding facts. Calls that may execute user
  code discard proofs about class-owned or otherwise aliased fields.
- `==` and `!=` use the initialized class's `Object` equality protocol for
  two present values, including mixed interface, class and root views.
  A comparison involving an interface view uses raw identity for `===`.
  Raw identity includes the generation and is valid for stale views.
  Nullable comparisons decide absence before accessing a present object.
- `::str`, output, interpolation and recursive array text use the class
  formatter or the initialized class name. Each value runs its formatter
  once per occurrence. Direct `::str` requires a present receiver. Implicit
  text prints an absent value as `null`. Immediate destructor `self` keeps
  its access permission through a widening cast. A copied view follows the
  usual liveness checks during destruction.
  [Presence analysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/NullFlowAnalysis.kt) ·
  [Class equality](../../cloth-language/references/class-equality.md) ·
  [Class formatting](../../cloth-language/references/class-formatting.md)

## Closed metadata and imports

- Closed interface applications are ordinary type arguments, including in
  `Slot<Value<string>>`. `::type` preserves the declared static type of a
  named binding inside a guard. Metadata includes the nullable and array
  wrappers and uses canonical closed nominal descriptors. Same-spelled
  types from different source declarations retain distinct identities.
- Named type receivers with a nullable marker or an empty array suffix use
  ordinary type syntax, including `Read?::type`, `Read[]::type` and
  `Read?[][]::type`. `items[0]::type` continues to query the indexed value.
- The imported fixture reaches two distinct `Value<T>` declarations through
  separate source files. It executes a private class implementation, resolves
  the omission helper in the interface's declaring file, and compares two
  `Slot<Value<string>>` descriptors whose displayed names coincide.
  [Type receiver parsing](../../../../Compiler/src/main/kotlin/cloth/parser/subs/TypeParser.kt) ·
  [Native metadata](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitTypeInfo.kt) ·
  [Compiler request tests](../../../../Compiler/src/test/kotlin/cloth/InterfaceIntegrationTest.kt)

## Release boundary

The staged native interface gate C4035 is retired. Invariant array elements,
declared casts, visibility, exact conformance and lifetime use the ordinary
fail-fast diagnostics. Class implementers and explicit unconstrained generic
interfaces are the current scope. The full release checks and portability
matrix are recorded in Stage 7. [Stage 7 release verification](stage-7.md)
[Conformance diagnostics](stage-2.md#diagnostics) · [Release scope](design.md#generic-interface-release-scope)

## Verification

The offline regression run passed 1,006 tests across 100 suites, including
all 58 interface tests. The standalone generic and import tasks passed 86 and
36 tests respectively. The installed Shuttle fixture also passed. The combined
run finished in 4m 29s with zero failures, errors or skips.

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Interface*Test' --offline --console=plain --no-daemon
.\gradlew.bat :test --tests cloth.ToolchainDistributionTest.installedShuttleRunsImportedGenericInterfaceFixture --offline --console=plain --no-daemon
```

The verification used isolated Gradle outputs. The Standard-Library checkout
had local edits during verification, so the real bundle exporter used a
temporary clean checkout of the exact Git pin. The installed Shuttle launcher
then compiled and ran the maintained project from an external working directory.

`InterfaceIntegrationTest` covers the maintained example, aggregate copies,
growable and fixed shapes, receiver lifetimes, formatting and comparison
effects, nominal metadata and imported fallbacks. The native fixtures ran on
Windows x64 and x86. The maintained example verified executable LLVM IR and
emitted objects on all nine profiles.
[Integration tests](../../../../Compiler/src/test/kotlin/cloth/InterfaceIntegrationTest.kt) ·
[Installed Shuttle test](../../../../src/test/java/cloth/ToolchainDistributionTest.java)
