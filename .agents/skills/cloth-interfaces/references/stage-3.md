# Interface Stage 3 values and call binding

Stage 3 completed on 2026-11-08. It added semantic checking for class-backed
interface values and calls. At this stage C4035 guarded native emission after
whole-graph checking. [Stage 4 native dispatch](stage-4.md) ·
[Stage 5 defaults](stage-5.md) · [Stage 6 value contexts](stage-6.md) ·
[Implementation plan](../../../../todo/interfaces-plan.md) ·
[Conformance records](stage-2.md)

```cloth
typedef<interface> Read {
    mt<int> read(opt<int> offset ?: 1)
    mt<int> again() = default (read())
}

typedef<class> Box (def<int> value) : Read {
    override: mt<int> read(int add) = self.value + add
}

fn<int> get(Box box) {
    def<Read> view = box
    return view.again()
}
```

The checked call for `view.again()` names the static `Read` slot. The fallback
body's call to `read()` keeps the interface's omitted `offset`. The class
implementation declares its own parameter label, `add`.

## Value contracts

- A class adapts to a declared or inherited interface application. A child
  interface adapts to its declared parent application. Nullable destinations
  apply the same rule to their payload. Type arguments match exactly.
- Bindings, assignments, receiving slots, parameters, results and fields use
  their existing type contracts. An expected interface can join different
  conforming class branches. Inference uses the expression types and does not
  search for a shared interface. Contextual array literals can adapt individual
  elements. Existing array values keep invariant element types.
- Explicit casts admit the same widening steps. An unproven downcast or an
  interface-to-`Object` conversion is outside this stage. General interface
  equality, identity and formatting remain later work.
  [Nominal acceptance](../../../../Compiler/src/main/kotlin/cloth/data/CInterface.kt) ·
  [Expression checking](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)

## Static call binding and defaults

- The ordinary overload resolver compares exact types first and uses the usual
  ambiguity rule when more than one adaptation matches. The argument binder
  keeps supplied arguments in written order and records their destination
  parameter indices and omitted parameter indices.
- A `ResolvedCall.Interface` holds the static interface, declaring contract,
  checked receiver, ordered result shape and binding plan. An inherited method
  can use a parent contract through a child slot. The Stage 2 conformance
  record provides each class's body target for that slot.
- A private class implementation is callable through the interface. Direct
  class lookup applies ordinary visibility and exposes declared class methods.
  Interface fallback methods are available through interface views.
- A required signature has parameter symbols and omission expressions. Only
  explicit fallbacks have a body to check. Those bodies see interface-typed
  `self` and can call other interface members, including by implicit receiver.
  Omitted argument expressions use the defining file, earlier read-only
  parameters, and the existing default-expression receiver restrictions.
- Generic interface signatures and both kinds of defaults use isolated symbolic
  and closed body views. Unused templates are checked with opaque parameters.
  Effective public contracts are checked for private type exposure, including
  inherited signatures. Caller-private type arguments remain usable at the
  caller's specialization.
  [Call resolution](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/CallResolution.kt) ·
  [Dispatch plans](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ResolvedCall.kt) ·
  [Generic views](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt)

## Presence, metadata and lifetime

- A class-backed operand with a non-nullable class or interface target uses a
  nominal membership plan when either type is an interface. It retains exact
  generic identities and whether the source may be absent. Successful tests
  refine stable binding reads. Assignment removes stale proofs. A class proof
  takes precedence when both class and interface proofs are available. Related
  interface proofs select the most-specific view.
- Nullable receivers need the usual presence proof. A call through an
  interface invalidates presence proofs on potentially aliased fields.
  `::type` preserves the declared type of a stable name, including inside a
  narrowed branch.
- Local interface views share the tracked class identity. Deletion and
  membership are checked against proven local liveness. Member call receiver
  liveness is checked before and after supplied argument evaluation. The
  semantic deletion plan records a class-backed view and its nullability.
  Field initialization and base constructor arguments retain the existing
  restrictions on exposing incomplete `self`.
  [Presence and narrowing](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/NullFlowAnalysis.kt) ·
  [Local lifetime checks](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/ClassLifetimeAnalysis.kt)

Stage 4 implements the runtime identity carrier, initialized-class dispatch
and lifetime checks for class implementation targets. Stage 5 executes
fallback bodies and omitted interface arguments. [Stage 4](stage-4.md) ·
[Stage 5](stage-5.md)

## Verification

The isolated offline run passed 714 tests across 62 suites in 3m 46s,
including 33 interface tests. The native-free generic and import tasks passed
86 and 36 tests respectively. All tasks reported zero failures, errors or
skips.

```powershell
.\gradlew.bat :Compiler:genericSyntaxTest :Compiler:importSemanticsTest :Compiler:test --tests 'cloth.Interface*Test' --tests 'cloth.Class*Test' --tests 'cloth.Generic*Test' --tests 'cloth.Null*Test' --tests 'cloth.*Array*Test' --tests 'cloth.*Result*Test' --tests cloth.OptionalArgumentsTest --tests cloth.CallResolutionTest --tests cloth.ImportSemanticsTest --tests cloth.StructMethodTypingTest --tests cloth.TypeInfoTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

The verification run used separate Gradle outputs and a separate project cache.
Interface coverage includes generic inheritance and base construction,
bindings and ordered reception, nominal membership, nullable proofs,
argument placement and omissions, private implementations, fallback body
checking, defining-file defaults and compiler request diagnostics. Existing
class and generic fixtures ran on Windows x64 and x86 and verified IR and
objects across all nine target profiles.
[Typing tests](../../../../Compiler/src/test/kotlin/cloth/InterfaceTypingTest.kt) ·
[Call tests](../../../../Compiler/src/test/kotlin/cloth/InterfaceCallResolutionTest.kt) ·
[Compiler requests](../../../../Compiler/src/test/kotlin/cloth/InterfaceNamesTest.kt)
