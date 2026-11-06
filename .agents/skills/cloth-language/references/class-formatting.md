# Class formatting with `::str`

Stage 4 of the [Object and operator plan](../../../../todo/object-and-operators-plan.md)
integrates class formatting. The [Object protocol](object-protocol.md#class-formatting-with-str)
owns the broader design.

```cloth
typedef<class> Player (def<string> name) {
    operator ::str() = self.name
}

fn<string> describe(Object value) = value::str
```

## Supported slice

- A class can declare one formatter with implicit `self`, empty parentheses,
  and a non-nullable `string` result. Both expression and block bodies work.
  The closest inherited or local override at the initialized class level
  supplies the text, including through a base binding or `Object`. The default
  uses the initialized class's declared name with its original spelling.
- `value::str`, interpolation, `print`, and `println` use the same checked
  dispatch. The provider requires a value receiver and requires a presence
  proof for direct access to a nullable class. Implicit formatting and array
  text write absent payloads and rows as `null`.
- The runtime checks the receiver before invoking an override or returning
  its default name. Direct destructor `self` uses the existing destructor
  access rule. A provably stale checked access fails during semantic analysis.
- A formatter can have side effects. It runs once per present value formatted
  and sees the fields at that point. Interpolation evaluates and formats holes
  in order. Array formatting captures the array's element identities and
  formats present leaves in traversal order. It preserves each formatter's
  resulting text before advancing to the next leaf.
- A formatter call can invalidate aliased nullable presence proofs. An
  optional default with a read-only receiver rejects formatting when a
  reachable override could run. Struct and enum formatters are outside this
  stage.

## Implementation ownership

- [OperatorRules](../../../../Compiler/src/main/kotlin/cloth/semantic/OperatorRules.kt)
  enforces the declaration form. [CClass](../../../../Compiler/src/main/kotlin/cloth/data/CClass.kt)
  supplies the class text recipe. [DeclarationModel](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationModel.kt)
  looks up inherited overrides and potential formatter effects.
- [EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
  passes class runtime services and the caller's string arena to provider
  emission. [EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
  emits checked dispatch through the ordinary callable ABI.
- [EmitArrayText](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitArrayText.kt)
  takes an independent snapshot and captures class leaf strings during
  measurement in a temporary arena. The writing pass uses the captured text
  before releasing the arena. The resulting string has its own storage.

## Verification

[ClassFormattingTest](../../../../Compiler/src/test/kotlin/cloth/ClassFormattingTest.kt)
covers default names, inherited and local overrides, output and interpolation,
nullable rows and leaves, stale receivers, effects, and array snapshots.
On 2026-11-06, the full compiler run passed 1,417 tests in 124 suites.
The final focused run passed 235 tests in 20 suites, including the compile-time
diagnostic for printing a provably present stale nullable alias.
The formatting fixtures ran natively on Windows x64 and x86. The target
test verified LLVM IR and object emission for all nine supported profiles.

```powershell
.\gradlew.bat :Compiler:test '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --offline --console=plain --no-daemon
.\gradlew.bat :Compiler:test --tests 'cloth.Class*Test' --tests cloth.OperatorDeclarationsTest --tests cloth.ArrayTextTest --tests cloth.ArrayTemporaryCleanupTest --tests cloth.StringInterpolationTest --tests cloth.BuiltinFunctionsTest --tests cloth.BufferProvidersTest --tests 'cloth.Nullable*Test' --tests cloth.ProviderIntegrationTest --tests cloth.CallResolutionTest --tests cloth.TargetLayoutTest '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --offline --console=plain --no-daemon
```
