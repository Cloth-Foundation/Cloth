# State activation and dispatch

Nested and attached states execute default activation, explicit transitions, predicates and
shared-contract method dispatch.
[State semantics](semantics.md#states-and-method-dispatch) ·
[Construction order](semantics.md#initial-state-and-construction) ·
[Syntax and checking](state-syntax.md) ·
[Maintained examples and verification](state-publication.md)

```cloth
typedef<class> Session (def<string> name) {
    pub state<string> ::label(opt<string> suffix ?: "!")

    default: (self.name)
    pub state<string> Active {
        init { println(state) }
        mt<string> label(string suffix) = state + suffix
    }
    pub state<void> Closed {}
}

fn main() {
    def session = Session::new("Alex")
    if (session is Active) println(session::label())
    session -> Closed
    session::delete
}
```

## Activation and access

- The outer constructor call selects the concrete class's effective default
  after delegation and the entire constructor body return. Inherited defaults
  evaluate their payload in the declaring class's scope, with the constructed
  receiver. The initial payload runs before publication and the entry hook
  runs after it. [Constructor and entry hook lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
- A transition evaluates the receiver once, captures its identity and evaluates
  the payload once. It then resolves the identity with ordinary live access
  and requires an active state. It copies the payload into the new activation,
  publishes it and invokes `init` before resuming the caller. Same-state
  transitions install a fresh activation.
  [Transition lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
- A state predicate reads the current activation after evaluating and checking
  the receiver. Before activation, it returns `false`, including for direct
  `self` in a field initializer. An explicit transition on an inactive receiver
  fails with `Class object has no active state`.
- Direct destructor `self` can test the active state through the ordinary
  destruction permission. An explicit transition always requires ordinary live
  access. Detach the activation after the destructor chain completes.
  [Class lifetime](class-lifetime.md#representation-and-lowering)

## Activation storage

- The checked object table carries an activation pointer in modules with
  concrete states. New and recycled slots start empty. A predicate
  compares the activation's state identity against the checked target. The
  slot address stays within the storage operation. Source evaluation and
  entry hooks can grow or recycle the object table.
  [Class table](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassRuntime.kt)
- Each activation holds a state tag, its ownership count, inline payload
  storage and an arena for owned buffers. Copies use the ordinary value
  representation and recursively copy buffers. Class values retain checked
  identity and explicit lifetime.
  [Activation storage](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitStateRuntime.kt)
- The object slot owns the active activation. Each running entry hook owns a
  second reference to the activation it entered. State methods retain the
  selected activation in the same way. Each invocation's `state` binding
  uses that activation's storage, including when a nested transition replaces
  it or deletion detaches it. The last release frees the arena and activation.
  [Payload binding](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitBindings.kt)
- A transition can release independent payload scratch after copying it into
  the activation. The surrounding cleanup proof still treats transitions as
  effectful. [Buffer lifetimes](../../cloth-buffer-lifetimes/SKILL.md)

## State-method dispatch

- Capture the receiver once and evaluate supplied arguments in source order.
  The shared contract binds their positions and supplies omitted defaults in
  parameter order. Defaults run with the declaring host's read-only `self`
  and earlier parameters. Field reads check the object when they occur.
  [Argument rules](../../cloth-optional-arguments/references/semantics.md#scope-evaluation-and-lifetimes)
- After both sets of arguments finish, the dispatch entry resolves the
  captured identity and reads its current activation. The activation tag
  selects the body for the checked overload. A subclass can supply that
  body through an inherited contract. Direct destructor `self` carries
  its existing access permission through defaults and the selected body.
  [State dispatcher](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitStateDispatch.kt)
- Pin the selected activation through the call. The body receives complete
  parameter values and copies buffered results into the original caller's
  arena before release of the activation. Ordered results use the ordinary
  callable ABI. State and interface entries share a worklist so their
  defaults can invoke each other's dispatch.
  [Callable lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)

## Flow checks and errors

- Successful state predicates add facts to the lifetime pass's tracked
  identities. Simple aliases share a fact. A rebind changes the identity
  selected by a later read. An argument that rebinds a variable leaves the
  already captured identity in place for that dispatch.
- Short-circuit conditions and branch joins carry facts along continuing
  paths. Successful creation and transition can establish a fact when the
  entered state has no entry hook. Calls with unknown effects, possible
  alias mutations, and repeating regions with state effects discard carried
  facts. Omitted defaults participate in this check, with conservative
  handling when a specialized body cannot see their effect facts.
- Proof of access through direct `self` carries its permission restriction.
  A copied alias still uses ordinary object access. C3130 labels a call and
  state when valid receiver access and a missing body are both proved at
  dispatch. Uncertain calls use the runtime lookup. A live receiver with
  an absent activation or missing implementation fails with
  `Invalid state for 'signature'`. Stale or inaccessible receivers use
  the ordinary class lifetime diagnostic.
  [Lifetime and state facts](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/ClassLifetimeAnalysis.kt) ·
  [State effects](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/StateEffects.kt)

[Stage 5 attachments](state-attachments.md) share this runtime with nested
states. Each attachment supplies a state tag and checked bodies for its host.
A subclass that inherits the state uses the original attachment's tag.

## Stage 3 verification

The 2026-11-09 offline regression run passed 478 tests across 49 suites and
203 test runs in the dedicated generic and import tasks. All tasks finished
with zero failures, errors or skips.

`StateRuntimeTest` ran the lifecycle, generic specialization, table-growth,
stale identity, destructor access and allocation-failure fixtures on Windows
x64 and x86. Its poisoned-free allocator probe checked re-entry and deletion
during an entry hook and returned to its live-byte baseline after each of
1,000 transition cycles. The lifecycle fixture verified executable LLVM IR
and emitted objects for all nine profiles. The activation example compiled
and ran through the Windows x64 CLI.
[Runtime fixtures](../../../../Compiler/src/test/kotlin/cloth/StateRuntimeTest.kt) ·
[Poisoned-free harness](../../../../Compiler/src/test/kotlin/cloth/GrowableArrayStorageTest.kt)

`StateSemanticsTest` covered checked declarations and calls. The Stage 3
`ClassParsingTest` fixtures covered the former C4036 gate and one semantic
diagnostic before output publication.
[Semantic fixtures](../../../../Compiler/src/test/kotlin/cloth/StateSemanticsTest.kt) ·
[Compiler boundary fixtures](../../../../Compiler/src/test/kotlin/cloth/ClassParsingTest.kt)

## Stage 4 verification

The 2026-11-09 offline regression run passed 707 tests across 63 suites and
203 test runs in the dedicated generic and import tasks. All tasks finished
with zero failures, errors or skips.

`StateDispatchTest` executed on Windows x64 and x86. It checks operand and
default order, state changes before selection, default field access on a
stale identity, destructor permission, and composition with interface defaults
and closed generic inheritance. The poisoned-free probe runs 1,000 cycles of
argument snapshots, state replacement and deletion with escaped payloads.
The dispatch fixture verified executable LLVM IR and emitted objects on all
nine target profiles. The example above compiled and ran through the Windows
x64 CLI.
[Dispatch fixtures](../../../../Compiler/src/test/kotlin/cloth/StateDispatchTest.kt)

`StateSemanticsTest` covers branches, aliases, rebinds, possible effects and
defaults referenced by width-specialized bodies. `ClassParsingTest` verifies
one C3130 and no output publication for a proved missing implementation.
[Semantic fixtures](../../../../Compiler/src/test/kotlin/cloth/StateSemanticsTest.kt) ·
[First-error fixture](../../../../Compiler/src/test/kotlin/cloth/ClassParsingTest.kt)
