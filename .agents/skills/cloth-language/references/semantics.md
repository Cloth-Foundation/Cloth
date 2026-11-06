# Confirmed Cloth semantics

These decisions were confirmed directly by the user during the language design
discussion from 2026-10-29 through 2026-11-01. `../../../../examples/Test.co` remains the main syntax example; these rules
clarify it and extend it where noted. This document describes intended behavior,
not the compiler's current feature coverage.

Implemented feature decisions now live in feature-owned references linked below.
Read the relevant feature skill for current implementation rules and tests.

Numeric range membership with `==` / `!=`, either operand order and contextual
literal widths is owned by the [membership contract](../../cloth-numerics/references/range-membership.md)
and [numeric skill](../../cloth-numerics/SKILL.md).
Shared portability and class/state design remain here; nullable decisions live in
their feature-owned reference.

## Portability

- Windows, Linux and macOS are all required targets. On 2026-10-30 the user
  reaffirmed that implementation choices must support all three without
  compromising behavior on the others.
- Portable Cloth features must expose a shared language contract across these
  systems. Platform-specific startup, ABI and encoding adapters are implementation
  details used to fulfill that contract, not a reason to make an entire feature
  depend on one operating system's mechanism.
- `main(string[] args)` keeps the same Cloth interface across all three targets.
  A Windows Unicode startup adapter must coexist with the Linux/macOS startup
  paths; `wmain` is not a proposed universal native entry point.
- On 2026-10-31 the user confirmed Unicode-preserving command-line arguments
  on Windows, Linux and macOS. Native arguments become well-formed UTF-8 Cloth
  strings without replacing characters or normalizing their Unicode sequence.
  Windows receives UTF-16 through a wide-character startup adapter; Linux and
  macOS validate native argument bytes as UTF-8.
- Validate user arguments in order before entering Cloth's `main`. The first
  invalid argument produces a controlled startup error and a nonzero exit
  status; do not continue to later arguments or execute user code. This also
  applies when Cloth's `main()` has no argument parameter. Preserve empty
  arguments, argument order and exclusion of the executable name.

## Concise syntax and bodies

- Semicolons are optional at valid statement terminator positions unless
  explicitly required by a particular syntax. A brace-bodied function ends at
  `}` and cannot be followed by `;`; semicolons also cannot intervene before
  `else`. Read [optional semicolons](../../cloth-semicolons/SKILL.md) for the
  placement contract and supported grammar.

- Cloth aims to remove unnecessary ceremony. Use the compact forms established
  by the language design; do not extrapolate unrelated syntax changes from this
  preference alone.
- A transition to a `state<void>` destination needs no empty argument list:
  `player -> Alive`. Parentheses are not required for that transition. Whether
  an explicit empty argument list is also accepted has not been specified.
- A single-line `mt` or `fn` body may use `=` instead of a brace-delimited body.
  For a non-void function, the expression's value is returned implicitly, for
  example `fn<int> add(int a, int b) = a + b`. A void function's expression
  must yield void; `expression -> void` explicitly discards a produced value.
- An empty class body may be omitted entirely; the declaration can end after its
  header without an empty brace pair.

## Nested type declarations

Any `typedef` body may contain another `typedef` declaration. The nested type
belongs to the enclosing type's scope. Inside `Outer`, use `Inner` in type
positions. From outside `Outer`, use `Outer.Inner` where that type is visible.

Declaring a nested type adds no instance storage. An explicit field can store
a value of that type. Nested `typedef` declarations are pending compiler support.

## Callable declarations in type bodies

A callable member of a `typedef` uses `mt` and has an implicit receiver
named `self`.
A `fn` declaration inside any `typedef` body is a compile-time error,
including in classes, interfaces, structs and enums. See the
[method contract](../../cloth-functions/references/semantics.md#user-defined-methods)
for the shared function rules and receiver contract. See
[struct parser support](../../cloth-structs/references/syntax.md) for the
current diagnostic and parser scope.

## Function return contracts

Read [Cloth functions and function blocks](../../cloth-functions/references/semantics.md#function-return-contracts) and its
[feature skill](../../cloth-functions/SKILL.md).

## Multiple function and method results

Read [Cloth multiple results](../../cloth-multiple-results/references/semantics.md#multiple-function-and-method-results) and its
[feature skill](../../cloth-multiple-results/SKILL.md).

## Function blocks

Read [Cloth functions and function blocks](../../cloth-functions/references/semantics.md#function-blocks) and its
[feature skill](../../cloth-functions/SKILL.md).

## Program entry and executable statements

Read [Cloth program entry](../../cloth-program-entry/references/semantics.md#program-entry-and-executable-statements) and its
[feature skill](../../cloth-program-entry/SKILL.md).

## Compiler data access (phase 3 implemented for supported types)

Read [Cloth compiler data access](../../cloth-compiler-data/references/semantics.md#compiler-data-access-phase-3-implemented-for-supported-types) and its
[feature skill](../../cloth-compiler-data/SKILL.md).

Recursive array contents for printing, interpolation and `::str` are owned by
the [output contract](../../cloth-output/references/semantics.md#recursive-array-text)
and [output skill](../../cloth-output/SKILL.md#recursive-array-text). `::addr` remains deferred.

## Function parameters

Read [Cloth arrays](../../cloth-arrays/references/semantics.md#array-construction-and-value-semantics) and its
[feature skill](../../cloth-arrays/SKILL.md).

Ordinary parameter rules are in [function parameters](../../cloth-functions/references/semantics.md#function-parameters).

## Function scopes

Read [Cloth functions and function blocks](../../cloth-functions/references/semantics.md#function-scopes) and its
[feature skill](../../cloth-functions/SKILL.md).

## Function recursion

Read [Cloth functions and function blocks](../../cloth-functions/references/semantics.md#function-recursion) and its
[feature skill](../../cloth-functions/SKILL.md).

## Function overloads

Read [Cloth functions and function blocks](../../cloth-functions/references/semantics.md#function-overloads) and its
[feature skill](../../cloth-functions/SKILL.md).

## Visibility in Cloth source

Read [Cloth functions and function blocks](../../cloth-functions/references/semantics.md#visibility-in-cloth-source) and its
[feature skill](../../cloth-functions/SKILL.md).

## Type checks, state checks, and equality

Read [Cloth control flow](../../cloth-control-flow/references/semantics.md#type-checks-state-checks-and-equality) and its
[feature skill](../../cloth-control-flow/SKILL.md).
The [operator design](object-protocol.md) covers general overloading for
classes, structs, and enums, including `==`, `===`, and `is` operand forms.
[Struct and enum operators](value-operators.md) records explicit struct
equality, nominal enum case identity, and enum formatting.
[Operand contexts and value-target checks](operand-contexts.md) records
operator context ranking, captured numeric widths, and narrowing boundaries.
[Operator publication](operator-publication.md) links the runnable examples
and target verification.

## Conditional expressions and statements

Read [Cloth control flow](../../cloth-control-flow/references/semantics.md#conditional-expressions-and-statements) and its
[feature skill](../../cloth-control-flow/SKILL.md).

## For loops and ranges

Read [while loops](../../cloth-control-flow/references/while-loops.md) for repeated
strict-bool conditions, body scope, jumps and completion.

Read [do-while loops](../../cloth-control-flow/references/do-while-loops.md) for
the accepted body-first syntax, terminator boundaries, scopes and jump behavior.
Stages 0-5 integrate syntax, scopes, strict conditions, jump resolution,
first-entry completion/nullable/metadata joins, execution and proven cleanup.
The [completed plan](../../../../todo/do-while-loops-plan.md) and
[Stage 5 record](../../cloth-control-flow/references/do-while-loops.md#stage-5-integration-portability-and-publication-2026-11-03)
link the maintained example, full-suite evidence and native/IR target limits.

Read [loop jumps and shared control](../../cloth-control-flow/references/loop-control.md)
for break/continue, lexical body targets and reusable completion/cleanup rules.

Read [confirmed loop design](../../cloth-control-flow/references/semantics.md#for-loops-and-ranges-design-confirmed-implementation-deferred):
integer/float range and array foreach loops use `<-`; ranges default to a step of one, and an
explicit stepper requires a separating semicolon. Stage 1 parses loop syntax and
retains its AST. Stage 2 resolves loop scopes, strict types and repeated flow.
Stage 3 executes numeric ranges with saved bounds, default/explicit updates,
finite/progress guards, integer overflow exits and early returns. Stage 4 executes
array foreach with independent snapshots/element values and conservative arena/
stack cleanup. C3067 is retired; nullable scalar/string elements now execute with
explicit receiving types under the [buffer contract](../../cloth-nullability/references/buffers.md).
Nullable rows execute with shared rectangular constraints under the
[row contract](../../cloth-arrays/references/nullable-rows.md).
See [implementation scope](../../cloth-control-flow/references/for-implementation.md).
The [semicolon skill](../../cloth-semicolons/SKILL.md) distinguishes
this required header separator from optional statement terminators.
The completed [publication/portability record](../../cloth-control-flow/references/for-implementation.md#stage-5-regression-portability-and-feature-publication-2026-11-02)
links maintained executable examples and native Windows x64/x86 verification.
Other supported profiles have verified loop IR/objects, not new native evidence.

## Bindings

Read [Cloth bindings and assignments](../../cloth-bindings/references/semantics.md#bindings) and its
[feature skill](../../cloth-bindings/SKILL.md).

## Compound assignments and update operators

Read [Cloth bindings and assignments](../../cloth-bindings/references/semantics.md#compound-assignments-and-update-operators) and its
[feature skill](../../cloth-bindings/SKILL.md).

## Target-sized unsigned integer (usize)

Read [Cloth target-sized integers](../../cloth-target-integers/references/semantics.md#target-sized-unsigned-integer-usize) and its
[feature skill](../../cloth-target-integers/SKILL.md).

## Target-sized signed integer (isize)

Read [Cloth target-sized integers](../../cloth-target-integers/references/semantics.md#target-sized-signed-integer-isize) and its
[feature skill](../../cloth-target-integers/SKILL.md).

## Primitive attachments

Integer `abs`, `clamp`, `signum`, zero-bit counts and radix formatting are covered
by the [confirmed attachment contract](../../cloth-numerics/references/semantics.md#integer-attachments-design-confirmed-2026-11-04)
and [implementation record](../../cloth-numerics/references/integer-attachments.md).

Float `abs`, `clamp` and signed-int `signum` at widths 16/32/64 are covered by
the [confirmed float contract](../../cloth-numerics/references/semantics.md#float-attachments-design-confirmed-2026-11-04)
and [implementation record](../../cloth-numerics/references/float-attachments.md).

Boolean `toInt()` is an explicit false-to-0/true-to-1 conversion to signed int32,
covered by the [confirmed boolean contract](../../cloth-numerics/references/semantics.md#boolean-attachments-design-confirmed-2026-11-04)
and [implementation record](../../cloth-numerics/references/bool-attachments.md).

Character classification and code-point `toInt()` use pinned Unicode 17.0.0
under the [confirmed character contract](../../cloth-strings/references/semantics.md#character-attachments-design-confirmed-2026-11-04)
and [implementation record](../../cloth-strings/references/character-attachments.md).

Character `toUpperCase()` and `toLowerCase()` return one char using pinned
Unicode 17.0.0 simple mappings, independent of locale and context. Unmapped
scalars remain unchanged; the [character casing record](../../cloth-strings/references/character-case.md)
owns the integrated behavior, data and verification.

## Integer signedness and widths

The [arbitrary-width decision](../../cloth-numerics/references/semantics.md#arbitrary-fixed-integer-widths-design-confirmed-2026-11-03)
confirms signed/unsigned widths 1..65536 with a common x86_64/AArch64 cap,
unchanged 32-bit defaults and unchanged 16/32/64-bit floats. The
[implementation plan](../../../../todo/arbitrary-integer-widths-plan.md) records
the completed stages. [Stage 2](../../cloth-numerics/references/arbitrary-width-types.md)
integrates semantic types/contexts across the full interval.
[Stage 3](../../cloth-numerics/references/arbitrary-width-arithmetic.md) enables
source scalar widths 1..64 and production wide kernels.
[Stage 4](../../cloth-numerics/references/arbitrary-width-storage.md) integrates
source values/storage/calls through the cap.
[Stage 5](../../cloth-numerics/references/arbitrary-width-output.md) integrates
full-width decimal text and retires C4031. The
[final integration record](../../cloth-numerics/references/arbitrary-width-integers.md)
owns Stage 6 examples, measured target/resource evidence and native-runner limits.

Read [Cloth numeric and boolean operations](../../cloth-numerics/references/semantics.md#integer-signedness-and-widths) and its
[feature skill](../../cloth-numerics/SKILL.md).
Numeric literal prefixes/suffixes and contextual floating widths are covered by
the same [numeric contract](../../cloth-numerics/references/semantics.md#decimal-literal-suffixes-design-confirmed-2026-11-03),
including integer literal forms in type widths and fixed extents. The
[literal implementation record](../../cloth-numerics/references/numeric-literals.md)
tracks supported syntax and verification with declared widths/ranges preserved.

## Explicit numeric casts

Read [Cloth numeric and boolean operations](../../cloth-numerics/references/semantics.md#explicit-numeric-casts) and its
[feature skill](../../cloth-numerics/SKILL.md).

## Booleans and storage

Read [Cloth numeric and boolean operations](../../cloth-numerics/references/semantics.md#booleans-and-storage) and its
[feature skill](../../cloth-numerics/SKILL.md).

## Characters and storage

Read [Cloth strings and characters](../../cloth-strings/references/semantics.md#characters-and-storage) and its
[feature skill](../../cloth-strings/SKILL.md).

## Built-in functions

Read [Cloth output and built-in functions](../../cloth-output/references/semantics.md#built-in-functions) and its
[feature skill](../../cloth-output/SKILL.md).

## Strings

The approved string attachments and ordinary value member-call groundwork are
owned by [the member-call contract](../../cloth-strings/references/member-calls.md).
`isEmpty()`, `contains(string)`, `startsWith(string)` and `endsWith(string)` are
implemented. [The scalar-method extension](../../cloth-strings/references/scalar-methods.md)
adds zero-based scalar `charAt`, exclusive-end `substring`, nullable-position
`indexOf`/`lastIndexOf` and independent `toChars`. Unregistered primitive methods
and class/static member dispatch remain deferred.

[Literal string editing](../../cloth-strings/references/editing-methods.md) adds
`replace`, `replaceFirst`, `removePrefix` and `removeSuffix`, with independent
results and user-selected scalar-boundary empty-search matches.

[Unicode whitespace methods](../../cloth-strings/references/whitespace-methods.md)
add `trim`, `trimStart`, `trimEnd` and `isBlank`, pinning Unicode 17.0.0 White_Space
with independent trimmed values and empty strings considered blank.

Read [Cloth strings and characters](../../cloth-strings/references/semantics.md#strings) and its
[feature skill](../../cloth-strings/SKILL.md).

## Nullable values and optional parameters

Read [confirmed nullability semantics](../../cloth-nullability/references/semantics.md#nullable-values-and-optional-parameters)
and the [cloth-nullability skill](../../cloth-nullability/SKILL.md). Optional
argument omission remains distinct from nullable value absence. Read
[confirmed optional-call semantics](../../cloth-optional-arguments/references/semantics.md)
and [optional-argument implementation](../../cloth-optional-arguments/SKILL.md)
for `opt<T>` / `final opt<T>`, named calls, defaults, evaluation order and overload ranking.

## Class declarations and constructors

- A class may declare primary constructor parameters in parentheses after its
  name. A parameterless class header may omit the parentheses.
- A plain header parameter such as `int seed` is a constructor input. It can
  be used by field initializers and does not declare an instance field.
- A header parameter declared as `def<T> name` also declares an instance field
  initialized by that parameter. Adding `?: expression` makes the parameter
  optional. The fallback runs on omission. For a nullable type, an explicitly
  supplied `null` initializes the field to `null`.
- Header and body fields are private unless declared `pub`. The primary
  constructor and named secondary constructors inherit the class's visibility.
- `Type::new` and `Type::new()` are equivalent calls with zero supplied
  arguments. Calls that supply arguments use `Type::new(...)`.
- Defaults and delegation arguments resolve before instance initialization.
  Defaults can read earlier resolved parameters, and the delegation arguments
  can read the secondary constructor's parameters. `self` becomes available
  during instance initialization.
- Initialize header fields first, then body fields in source order. A body
  field initializer can read constructor inputs and already initialized fields.
  While a field initializer runs, `self` may only select an initialized field
  to read. Passing the instance or calling its methods requires complete
  initialization.
- Array fields establish their shape on initialization. A nullable array
  field establishes its shape on the first present assignment and retains
  known dimensions across absent values. A nested field write selects the
  object and indices before its right-hand side, then applies the write to
  that path in the current field. [Class values](class-values.md)
- An additional constructor uses the class name in the body. Its `: (...)`
  clause supplies arguments to the primary constructor. For example,
  `Counter(float value) : (value -> int) {}` delegates to a primary constructor
  with an integer parameter. The primary constructor finishes before the
  secondary body runs. The body can access the initialized instance as `self`.
- `pub final typedef<class> MyClass` declares a public class that cannot be
  subclassed. Inheritance enforcement belongs to the inheritance feature.

See the [basic classes plan](../../../../todo/basic-classes-plan.md) for the
implementation sequence and the [class publication record](class-publication.md)
for the maintained example and verification.

The [Object design](object-protocol.md) describes the implicit root and
operator protocols. The [class formatting record](class-formatting.md) covers
the shared `::str`, interpolation, and output behavior.

## Object lifetime and nullability

Struct syntax, inherited field visibility and the user-confirmed independent
value-copy design are owned by [the struct contract](../../cloth-structs/references/semantics.md).
The [publication record](../../cloth-structs/references/publication.md) covers
the completed compiler slice. Class-object fields in structs preserve
object identity and remain subject to the deletion rules below.

- Object lifetime is explicitly managed by the programmer. Cloth does not and
  will not have a garbage collector, as reaffirmed by the user on 2026-10-31.
- Compiler-managed temporary value buffers may be released at statically
  proven safe points, with function-lifetime storage as the conservative
  fallback. This introduces no runtime reachability analysis or reference
  counting and does not implicitly destroy class objects or run destructors.
- `Type::new(...)` creates an object. `object::delete` explicitly destroys it and
  runs its destructor. Do not assume cleanup on scope exit or automatic cleanup
  of objects the programmer has not deleted.
- Deleting an absent nullable class value is a no-op. Deleting a present
  nullable class value follows the ordinary deletion rules.
- Deletion invalidates ordinary aliases before the destructor runs. The
  destructor can read and write fields through `self` and call methods on
  `self`. Those calls retain the same receiver access. Copies of the identity
  carry ordinary alias access. A second or recursive deletion is an error.
- Field access, method calls and deletion require a live instance, subject to
  the destructor receiver rule. Copying or passing an identity and comparing
  it with `null` remain valid after deletion.
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
- As demonstrated in `../../../../examples/Test.co`, `self -> Dead(payload)` activates the destination
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
- Within a top-level state, `self` has the declared host-contract type. Extra
  members of a particular hosting class are not directly available merely
  because that class hosts the state.
- Top-level state declarations use `typedef<state<Payload>>`, followed by
  `pub StateName for HostContract` and the state body. `pub` is optional under
  Cloth's default-private visibility rule. `for` introduces the host contract.
- Nested state declarations use the `state<Payload> StateName` form in `../../../../examples/Test.co`.
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
        self.die()
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
added to `../../../../examples/Test.co`.

## Compiler diagnostics

Read [Cloth diagnostics](../../cloth-diagnostics/references/semantics.md#compiler-diagnostics) and its
[feature skill](../../cloth-diagnostics/SKILL.md).
