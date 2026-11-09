# Interface Stage 5 defaults and inherited contracts

Stage 5 completed on 2026-11-09. Class-backed interface calls execute both
method fallback bodies and omitted-argument expressions. Required signatures
dispatch to a checked implementation. Closed generic interfaces use the same
call contract and lifetime rules as non-generic interfaces.
[Implementation plan](../../../../todo/interfaces-plan.md) ·
[Static call binding](stage-3.md#static-call-binding-and-defaults) ·
[Stage 6 value contexts](stage-6.md)

```cloth
typedef<interface> Root<T> {
    mt<T> read()
    mt<T> pick(T supplied, opt<T> result ?: supplied) = default (result)
    mt<T> repeat() = default (pick(self.read()))
}
```

The runnable fixture implements `read` in `Holder<T>` and reaches `Root<T>`
through both parents of a diamond. Calling `repeat` reads the class value,
supplies it to `pick`, and resolves `result` from the static `Root<T>` contract.
The fixture executes the path with `int` and `string`.
[Runnable fixtures](../../../../Compiler/src/test/kotlin/cloth/InterfaceDefaultsTest.kt)

## Static arguments and closed fallback bodies

- The checked call plan supplies the static interface slot, written argument
  order and destination positions. The dispatch entry shares the ordinary
  parameter prelude. It evaluates omitted expressions in declaration order
  with earlier parameters bound and read-only. An explicit nullable `null`
  remains a supplied argument.
- The static contract owns labels and omission expressions even when a more
  specific interface provides the body. An inherited declaration retains its
  defining file and closed declaring interface. Each fallback body uses the
  checked facts, parameters and `self` of its own closed declaration.
- Conformance still selects a class implementation first, then a unique
  most-specific fallback. Unrelated competing defaults require a class
  `override:` implementation. The selected class method receives all
  arguments marked supplied. Fallback bodies take a fully supplied private
  signature. Direct class calls continue to use the class method's own
  labels and omission expressions.
- The emitter declares fallback bodies before emitting any body. It caches
  private dispatch signatures by closed interface and requirement and drains
  their bodies from a worklist. This lets a fallback or omission expression
  call another slot or recurse through the same slot.
  [Callable lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt) ·
  [Dispatch lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitInterfaceDispatch.kt) ·
  [Generic body views](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt)

## Dispatch and lifetime

- Capture the interface identity and check its lifetime before evaluating
  supplied arguments. Snapshot supplied values in written order. The dispatch
  entry checks the receiver before evaluating omission expressions and checks
  it again when reading the initialized class tag. An argument that deletes
  the receiver prevents body entry. Deletion in a supplied argument also
  prevents omitted defaults from running.
- A fallback uses the same checked instance for `self`. Calls to other
  requirements through `self` dispatch normally, including inside a
  destructor with direct `self` access. Passing an alias as an argument
  carries only identity and uses the ordinary destruction check.
- The dispatch frame owns temporary omission values until the selected body
  returns. The body copies indirect results into the original caller's arena
  before dispatch releases its temporaries. This preserves strings, arrays and
  ordered buffered results across a fallback-to-override call.
  [Call frame](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctionFrame.kt) ·
  [Buffer lifetimes](../../cloth-buffer-lifetimes/SKILL.md) ·
  [Checked object lifetime](stage-4.md#membership-and-deletion)

## Native boundary

At this milestone C4035 guarded interface fields and arrays, interfaces
nested in type arguments and native interface metadata, including in fallback
bodies and omission expressions. Stage 6 executes those forms. Source diagnostics for
missing implementations, invalid `override:` markers and conflicting defaults
remain fail-fast. Class implementers and explicit unconstrained generics are
the current scope.
[Stage 6 value contexts](stage-6.md) ·
[Conformance diagnostics](stage-2.md#diagnostics) · [Release scope](design.md#generic-interface-release-scope)

## Verification

The offline regression run passed 822 tests across 75 suites in 3m 53s,
including 50 interface tests. The native-free generic and import tasks passed
86 and 36 tests respectively. All reported zero failures, errors or skips.
The final focused rerun passed all 50 interface tests in 31s with a marker
that detects entry into an omission expression after supplied-argument deletion.

```powershell
.\gradlew.bat :Compiler:genericSyntaxTest :Compiler:importSemanticsTest :Compiler:test --tests 'cloth.Interface*Test' --tests 'cloth.Class*Test' --tests 'cloth.Generic*Test' --tests 'cloth.Null*Test' --tests 'cloth.*Array*Test' --tests 'cloth.MultiReturn*Test' --tests 'cloth.OptionalArguments*Test' --tests 'cloth.StructMethod*Test' --tests cloth.CallResolutionTest --tests cloth.ImportSemanticsTest --tests cloth.TypeInfoTest --tests cloth.DestructuringTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

The run used isolated Gradle outputs and a separate project cache.
`InterfaceDefaultsTest` covers static argument order and omissions, class and
most-specific fallback precedence, explicit resolution of competing defaults,
nullable supplied values, closed generic diamonds and recursion. It also
checks defining-file lookup, buffered result snapshots, deletion during
arguments and destructor `self` dispatch through a fallback. The fixtures
ran natively on Windows x64 and x86. The closed generic and recursive
fixture also verified executable IR and emitted objects for all nine profiles.
[Stage 5 tests](../../../../Compiler/src/test/kotlin/cloth/InterfaceDefaultsTest.kt) ·
[Native boundary tests](../../../../Compiler/src/test/kotlin/cloth/InterfaceNamesTest.kt)
