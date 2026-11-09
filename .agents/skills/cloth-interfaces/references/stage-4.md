# Interface Stage 4 native dispatch and lifetime

Stage 4 completed on 2026-11-08. It executes class-backed interface values and
calls whose selected slot has class implementations and whose arguments are
all supplied.
[Implementation plan](../../../../todo/interfaces-plan.md) ·
[Conformance](stage-2.md) · [Call binding](stage-3.md) ·
[Stage 5 defaults](stage-5.md) · [Stage 6 value contexts](stage-6.md)

```cloth
typedef<interface> Read { mt<int> read() }
typedef<class> Box (def<int> value) : Read {
    override: mt<int> read() = value
}

fn<int> main() {
    def<Read> view = Box::new(42)
    println(view.read())
    view::delete
    return 0
}
```

The private interface value carries the class's 64-bit slot and generation
identity. Scalar widening, including a child-to-parent view, copies that
identity without allocation. Nullable views use the existing presence flag.
The class-backed value retains explicit deletion and shares lifetime with
every alias. [Interface representation](../../../../Compiler/src/main/kotlin/cloth/data/CInterface.kt) ·
[Class runtime](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassRuntime.kt)

## Checked dispatch

- The emitter caches one private entry per closed interface and requirement.
  A switch on the initialized class tag selects the target from the checked
  conformance model, including inherited methods and subclass replacements.
  The selected method's closed signature is checked against the slot.
  Ordinary class method calls still use their static receiver type.
- The call site captures the receiver once and validates its liveness before
  evaluating any arguments. Supplied values are evaluated and snapshotted in
  written order, then passed in static parameter order. The entry looks up the
  same identity after those effects and reads the current tag. No slot address
  crosses a source call.
- The ordinary private ABI transports the arguments and ordered results.
  Buffered results use the caller's result arena. A dispatch entry passes all
  implementation arguments as supplied, so their own omission expressions
  stay inactive on that call. Native dispatch remains a possible source-code
  effect for presence proofs and temporary-storage analysis.
  [Dispatch entry](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitInterfaceDispatch.kt) ·
  [Call lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt) ·
  [Buffer lifetimes](../../cloth-buffer-lifetimes/SKILL.md)

## Membership and deletion

- Class-backed `is` uses the validated initialized tag for class-to-interface,
  interface-to-class and interface-to-interface tests. Closed generic targets
  match their exact applications. An absent nullable receiver returns false
  for a non-nullable membership target.
- A base constructor's interface view dispatches against the initialized base
  while the concrete subclass is under construction. Tests also see that
  initialized base. Deletion requires the concrete class to have finished
  initialization.
- Deletion through a live interface view runs the concrete destructor chain
  and releases the original identity. Absent nullable deletion skips lookup.
  Direct destructor `self` can dispatch through an immediate interface cast.
  Values copied to a binding or passed as arguments carry the identity alone.
- C3092 still reports provably dead local aliases. Runtime lookup reports
  `Class object is no longer live` for stale views, including after slot reuse
  or deletion in a supplied argument. Fatal failures exit with status 1.
  [Local lifetime analysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/ClassLifetimeAnalysis.kt) ·
  [Class lifetime](../../cloth-language/references/class-lifetime.md)

## Native boundary

Stage 4 executes scalar and nullable views in ordinary bindings, parameters,
results and calls. Other types can use the ordinary ABI alongside them, including
buffered arguments, ordered results, and nullable half-precision values.

At this milestone C4035 guarded calls that omitted interface arguments or could
select an interface fallback. Stage 5 executes both forms.
The gate also covered stored interface fields, arrays with interface elements,
interface values as generic arguments, and native interface metadata.
Stage 6 executes these value contexts. [Stage 6](stage-6.md)

## Verification

The broad offline run covered 815 tests across 74 suites in 3m 52s. Windows
refused to launch the x86 fixture named `dispatch_32.exe` with error 740.
The test uses `views_32.exe` now. The focused rerun passed all 58 tests across
nine suites, including the x86 fixture, in 30s. The native-free generic and
import tasks passed 86 and 36 tests respectively.

```powershell
.\gradlew.bat :Compiler:genericSyntaxTest :Compiler:importSemanticsTest :Compiler:test --tests 'cloth.Interface*Test' --tests 'cloth.Class*Test' --tests 'cloth.Generic*Test' --tests 'cloth.Null*Test' --tests 'cloth.*Array*Test' --tests 'cloth.MultiReturn*Test' --tests 'cloth.OptionalArguments*Test' --tests 'cloth.StructMethod*Test' --tests cloth.CallResolutionTest --tests cloth.ImportSemanticsTest --tests cloth.TypeInfoTest --tests cloth.DestructuringTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat :Compiler:test --tests 'cloth.Interface*Test' --tests cloth.ClassLifetimeTest --tests cloth.ClassInheritanceTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

The verification run used separate Gradle outputs and a separate project cache.
The dispatch and lifetime fixtures executed on Windows x64 and x86. They cover
class and generic targets, buffered results, argument order, nullable operations,
initialized construction, direct destructor self, runtime stale views,
receiver reassignment and table growth. The dispatch and transport fixtures
verified executable IR and emitted objects on all nine profiles.
[Dispatch tests](../../../../Compiler/src/test/kotlin/cloth/InterfaceDispatchTest.kt) ·
[Lifetime tests](../../../../Compiler/src/test/kotlin/cloth/InterfaceLifetimeTest.kt) ·
[Compiler request tests](../../../../Compiler/src/test/kotlin/cloth/InterfaceNamesTest.kt)
