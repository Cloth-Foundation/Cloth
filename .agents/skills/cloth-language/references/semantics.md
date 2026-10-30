# Confirmed Cloth semantics

These decisions were confirmed directly by the user during the language design
discussion on 2026-10-29. `Test.co` remains the main syntax example; these rules
clarify it and extend it where noted. This document describes intended behavior,
not the compiler's current feature coverage.

## Concise syntax and bodies

- Cloth aims to remove unnecessary ceremony. Use the compact forms established
  by the language design; do not extrapolate unrelated syntax changes from this
  preference alone.
- A transition to a `state<void>` destination needs no empty argument list:
  `player -> Alive`. Parentheses are not required for that transition. Whether
  an explicit empty argument list is also accepted has not been specified.
- A single-line `mt` or `fn` body may use `=` instead of a brace-delimited body.
  The expression's value is returned implicitly, for example
  `fn<int> add(int a, int b) = a + b`.
- An empty class body may be omitted entirely; the declaration can end after its
  header without an empty brace pair.

## Visibility in Cloth source

- `pub` is optional. Declarations default to private when `pub` is omitted.
- Cloth has no `private` keyword. Do not introduce one into Cloth source.
- Interface methods remain callable through the interface without `pub`. The
  implementing class determines whether they are also callable directly on it,
  as described in `Test.co`.
- Struct fields inherit the enclosing struct's visibility without needing `pub`.
  This is an exception to the general default-private rule.

## Type checks, state checks, and equality

- `is` supports both active-state checks, such as `player is Alive`, and runtime
  type checks, such as `entity is Player`. The target declaration determines
  which check is requested.
- `==` is reserved for equality between values. Do not use it as a type-check or
  active-state-check operator.
- `instanceof` is not Cloth's type-check syntax.
- A successful type check narrows the binding's type inside its `if` branch.
  In `if (entity is Player) { ... }`, `entity` is treated as `Player` without an
  explicit cast, subject to ordinary member visibility.

## Bindings

- Explicit primitive bindings use `def<int>`, `def<float>`, or `def<bool>`, with the same `=`
  and `:=` binding behavior as inferred declarations.
- Omitting the type annotation infers the binding's type from its initializer,
  for example `def x = 10` and `def y := x + 2`.
- A binding's declared or inferred type stays fixed. Reassignment must satisfy
  that type; assigning a different type does not change the binding's type.
- `=` creates a mutable binding.
- `:=` creates an immutable binding. It prevents reassignment of that binding;
  it does not freeze the object or its fields.
- An immutable binding can call methods that mutate its object's fields, such as
  `player.applyDamage(...)` when that method is accessible.
- There is no separate `mut` qualifier.

## Booleans and storage

- `bool` stores the boolean values `true` and `false`.
- Boolean bindings support both explicit `def<bool>` annotations and inference
  from boolean initializers, following the ordinary `=` and `:=` binding rules.
- Each `bool` uses its own byte of storage for now. This user decision supersedes
  the earlier one-bit storage goal and shared-byte packing implementation.
- Boolean values still have only two values; byte storage does not introduce
  numeric conversions or change the ordinary binding rules.
- Packed collections, field layout, atomics, and foreign interfaces have not
  been designed by this decision.

## Object lifetime and nullability

- Object lifetime is explicitly managed by the programmer. Cloth has no GC.
- `Type::new(...)` creates an object. `object::delete` explicitly destroys it and
  runs its destructor. Do not assume cleanup on scope exit or automatic cleanup
  of objects the programmer has not deleted.
- Deleting an object invalidates every binding to that object. The bindings do
  not become `null`. This decision supersedes the earlier proposal to null all
  aliases, including bindings whose types lack `?`.
- Access after deletion is a compile-time error wherever the compiler can prove
  it. Otherwise it must raise a runtime error. Use-after-deletion is not undefined
  behavior, and a merely probable crash does not meet this rule.
- `?` permits intentional nullability. It does not express object lifetime or
  whether an object has been deleted.
- Pointer representation, alias tracking, and runtime checking mechanisms remain
  implementation choices. Cloth does not expose pointer or reference syntax to
  the programmer.

## States and method dispatch

- Subclasses inherit the states declared by their base class.
- Inherited states cannot be overridden. Redeclaring an inherited state name in
  a subclass is a compile-time error. Subclasses may add distinctly named states;
  selecting a subclass initial state does not redefine an inherited state.
- Each object has exactly one active state across its inheritance chain.
  Inherited and newly declared states belong to the same state machine. A base
  class and its subclass do not have independently active states.
- An object's permitted states are the states its class explicitly supports,
  including inherited states. A transition target must belong to that supported
  set; public visibility alone does not make an unrelated state a valid target.
- `object is StateName` is a boolean expression that checks whether the object
  is currently in the named state, for example `player is Alive`.
- State methods are separate from ordinary class methods. They do not fulfill
  the hosting class's interface method requirements; those must be implemented
  by ordinary class methods.
- State methods use `object::method(arguments)`, for example
  `player::attack(source)`. Ordinary class method calls use
  `object.method(arguments)`, for example `player.applyDamage(10f)` where visible.
- The state-call spelling selects the active state's implementation, not a
  particular named state's implementation. Missing implementations still raise
  the established invalid-state error.
- Different states can implement the same method signature.
- Calls to state methods select the implementation in the object's active state.
- If the active state has no matching implementation, raise an invalid-state
  error. Do not silently ignore the call or execute another state's body.
- An ordinary class method and a state method may share the same signature.
  `object.method(...)` selects the ordinary method; `object::method(...)` selects
  state dispatch. Sharing a signature does not establish overriding or fallback
  between those two kinds of methods.
- Overloading is supported when argument lists distinguish the overloads. Do not
  assume additional overload-resolution rules that have not been specified.
- As demonstrated in `Test.co`, `this -> Dead(payload)` activates the destination
  state and runs its `init` before the calling method continues. A transition
  does not itself terminate the method that performed it.
- Transitioning into the already-active state replaces its payload and reruns
  its `init` block. It is a re-entry, not a no-op or an error.
- States have no `exit` block or state-exit hook.
- Outside code may transition an object using `object -> TargetState(payload)`
  when the target state is public. Transitions to non-public states must happen
  internally. Do not infer protected or module-scoped transition permissions.
- Within a running state method, `state` remains bound to the payload of the
  state invocation that started that method. A transition does not change the
  payload or its type underneath that call.
- Newly called state methods dispatch through the currently active state and
  use that state's current payload.
- Re-entering a state installs a new payload for the new activation. Calls from
  the previous activation retain their original payload.
- The compiler retains old state payload storage until calls using it finish.
  This does not require copying the payload or introduce GC. Explicitly created
  objects referenced within a payload still follow normal deletion rules.

## Top-level states

- States may be declared at the top level, separately from their host classes.
- A top-level state is reusable by unrelated classes, provided each host class
  satisfies the state's declared host contract.
- A host contract may be an interface or a class. A class contract permits that
  class and its subclasses to host the state, subject to explicit state support.
- Top-level states obey normal member visibility rules. Hosting a state grants
  it no special access to the host class's private members.
- Within a top-level state, `this` has the declared host-contract type. Extra
  members of a particular hosting class are not directly available merely
  because that class hosts the state.
- Top-level state declarations use `typedef<state<Payload>>`, followed by
  `pub StateName for HostContract` and the state body. `pub` is optional under
  Cloth's default-private visibility rule. `for` introduces the host contract.
- Nested state declarations use the `state<Payload> StateName` form in `Test.co`.
  Do not substitute that nested spelling for the top-level `typedef` form.
- A class attaches top-level states using a class-level `@State(StateName, ...)`
  decorator, for example `@State(Dead, Stunned)`. Each attached state is added to
  the class's permitted states, alongside inherited and nested states.
- The class must satisfy every attached state's host contract. State-name
  conflicts are compile-time errors under the existing non-overriding rules.
- If an attached state contains a transition to another state, the hosting class
  must explicitly support that target too, either directly or through inheritance.
  Attaching `Dead` when it transitions to `Alive` is a compile-time error if the
  class does not support `Alive`. Referencing a target never attaches it implicitly.
- Attaching a public top-level state automatically permits external transitions
  into that state on the hosting object. There is no separate host-class opt-in
  for external transition permission beyond attaching the public state.
- Importing a state name into a file does not itself attach that state to a class.
  `use state StateName` is not Cloth's state-attachment syntax.

Accepted top-level declaration example, with `Player` as a class host contract:

```cloth
typedef<state<DeathInfo>>
pub Dead for Player {
    init {
        this.die()
        println(state.message)
    }
}
```

## Initial state and construction

- For an attached state, the class selects its initial state with the class-level
  decorator `@Initial(StateName, payloadExpression)`, for example
  `@Initial(Alive, { name })`. An attached `state<void>` uses `@Initial(Alive)`.
- The selected attached state must be in the class's supported state set. Its
  initial payload follows the same post-construction evaluation order as a
  nested initial state's payload.
- Every class that declares states must declare exactly one `@Initial` state.
- When constructing a subclass object, the subclass's declared `@Initial` state
  replaces the base class's initial state as the selected initial state.
- After the entire base-to-subclass constructor chain finishes, the selected
  initial state becomes active and its `init` block runs automatically.
- The initial state may require a payload; it is not restricted to `state<void>`.
- On a nested state declaration, the accepted payload syntax is
  `@Initial(payloadExpression)`. Constructor arguments provide the inputs; the
  class builds its initial-state payload using the constructed object's fields.
- Construction order is: finish the entire constructor chain, evaluate the
  selected initial payload, activate that state with its payload, then run its
  `init` block.
- A nested `state<void>` initial state can use plain `@Initial`.
- The payload expression is evaluated during object construction. This special
  syntax does not imply that arbitrary annotations accept runtime expressions.

Accepted extension to the syntax examples, assuming `SessionInfo` contains a
`name` field:

```cloth
typedef<class>
pub Player (def<string> name) {

    @Initial({ name })
    pub state<SessionInfo> Alive {
        init {
            println(state.name)
        }
    }

}

def player := Player::new("Alex")
```

`@Initial(payloadExpression)` was accepted in the discussion and has not yet been
added to `Test.co`.
