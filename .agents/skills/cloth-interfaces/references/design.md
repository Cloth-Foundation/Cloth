# Interface design and implementation boundary

Planning started on 2026-11-07. This record owns interface decisions and pending
language proposals. The plan owns implementation stages and verification.
[Implementation plan](../../../../todo/interfaces-plan.md)

## Established behavior and confirmed scope

- [Test.co](../../../../examples/Test.co) declares a public generic interface
  `IHealth<T: Number>` using `typedef<interface>`, class conformance after `:`, an
  `override: pub mt<float>` implementation, and
  `mt<T> getHealth() = default (0 -> T)`. The confirmed prefix below replaces the
  earlier `@Override` spelling.
- Interface methods do not need visibility modifiers and are always callable
  through the interface. An implementing class decides whether the corresponding
  method is also directly callable on its class type. This also appears in the
  [visibility contract](../../cloth-functions/references/semantics.md#visibility-in-cloth-source).
- Callable members of a `typedef` use `mt` with implicit `self`; `fn` inside a
  type is an error. [Shared declaration rule](../../cloth-language/references/semantics.md#callable-declarations-in-type-bodies)
- State methods are separate from ordinary class methods and cannot satisfy the
  hosting class's interface requirements. A future top-level state's host
  contract may be an interface. [Shared state rules](../../cloth-language/references/semantics.md#states-and-method-dispatch)
- On 2026-11-07 the user selected **classes first; structs and enums later** for
  interface implementation. This is implementation ordering, not a permanent
  prohibition on value types implementing interfaces.

The user's confirmations below settle required-method syntax, explicit
expression/block defaults, class/interface parent lists, required `override:`
on declared class implementations, reuse of matching inherited public methods
and default precedence, together with static interface call-contract ownership.
The other matching and dispatch rules below remain open.

## Initial implementation scope (confirmed 2026-11-08)

The user originally selected non-generic interfaces as the first milestone.
The basic acceptance fixture keeps that order. The release scope now includes
generic interfaces after delivery of the generic class and struct foundation.
Class implementers come first, with struct and enum implementers in a later slice.
[Generic interface release scope](#generic-interface-release-scope)

## Generic interface release scope

The user asked to include generic support in the interface plan after class and
struct generics landed. The planned release covers explicit, unconstrained
interface parameters, concrete class conformance to a closed application,
dependent conformance such as `ValueBox<T> : IValue<T>`, and generic interface
parents and default methods. [Implementation plan](../../../../todo/interfaces-plan.md#generic-conformance-fixture)

Use the existing generic model as the starting point for the interface rules.
Each interface template owns its lexical parameters. An application has the
original declaration identity and exact ordered arguments. Substitute parents
and signatures before resolving closed conformance. Check default bodies and
parameter fallbacks in the defining scope with opaque type parameters, then
check each required closed instance with its own facts.
[Generic contract](../../cloth-generics/references/semantics.md)

The proposed first rules use explicit, invariant applications. Keep the closed
interface and its member contract in the checked call plan, and use the closed
class identity to resolve the runtime body. The one-application rule below
governs declared and inherited conformance. Signature compatibility between
different interface declarations remains open.

An unconstrained generic default can return a requirement's `T` result with
`= default (self.read())`. The intended `IHealth<T: Number>` and its
`0 -> T` default depend on the later constraint and capability rules.
[Generic follow-ups](../../../../todo/generic-follow-ups.md) · [Test.co](../../../../examples/Test.co)

## One application per interface declaration

The user confirmed that each class, struct, interface, or enum may implement or
inherit at most one application of each interface declaration. The rule covers
the full interface set, including transitive parents and conformance inherited
from a class base. A type that already has `IValue<int>` cannot add
`IValue<string>`, regardless of whether their method signatures match.

Compare applications by original declaration identity and resolved ordered
arguments after substitution. Two paths to the same canonical application
contribute one entry. A diamond through two parents that both extend
`IValue<int>` therefore shares that application. Same-spelled interface
declarations from different files keep their separate nominal identities.
Direct duplicate-clause diagnostics belong to the header rules.

Report conflicting applications as a single conformance error with source
labels for the contributing clauses or inheritance paths. The class-first
release checks this rule for class conformance and interface inheritance.
[Conformance stage](../../../../todo/interfaces-plan.md#stage-2--resolve-interface-graphs-and-class-conformance-pending)

## Required methods and explicit defaults (confirmed 2026-11-07)

The user confirmed a signature with no body for a required interface method and
Test.co's explicit `= default (...)` form for a fallback implementation. The user
also confirmed `= default { ... }` for a block fallback, retaining the explicit
marker in both forms:

```cloth
typedef<interface> IHealth {
    mt<float> getHealth()
    mt<float> initialHealth() = default (0f)
    mt<float> maximumHealth() = default {
        return 100f
    }
}
```

`getHealth` declares a requirement without supplying an implementation.
`initialHealth` supplies an expression fallback and `maximumHealth` supplies a
block fallback. Both forms require the explicit `default` marker. Stage 1
preserves the signature and fallback separately. [Stage 1](stage-1.md)

## Class base and interface lists (confirmed 2026-11-07)

The user confirmed one class base plus multiple implemented interfaces in a
class header, using a comma-separated list after `:`:

```cloth
typedef<class> Player : Entity(), IHealth, INamed
```

The class base retains its constructor clause; interface entries name contracts.
A class that names only interfaces retains its existing implicit Object base.
This does not authorize multiple class bases. Alternative clause ordering,
duplicate-interface handling and redundant interface parentheses still need
their explicit grammar/diagnostic rules.

## Multiple parent interfaces (confirmed 2026-11-07)

The user confirmed multiple parent interfaces, using a comma-separated list
after `:` on an interface declaration:

```cloth
typedef<interface> IPlayer : IHealth, INamed {
    mt<int> playerId()
}
```

The implementation plan must handle inherited requirements, shared ancestors
and competing inherited default implementations. This confirmation establishes
the parent-list syntax and multiple inheritance of interface contracts; it does
not by itself settle re-abstraction or duplicate-parent handling. Default
precedence is confirmed separately below.

## Override declaration prefix (confirmed 2026-11-07)

The user selected a declaration prefix with the visibility modifier after it,
then confirmed that it is required on each newly declared class implementation
of an interface method:

```cloth
override: pub mt<int> getMyInt() {
    return 0
}

override: mt<float> myFloat(float f) {
    return f
}
```

Use `override:` in interface planning and examples. It replaces `@Override`,
uses the existing colon token plus an override keyword, and preserves the
independent visibility choice for direct class calls. An unmarked newly declared
method is not an implicit interface implementation.

On 2026-11-08 the user additionally confirmed that a newly declared matching
method in a class implementing the interface must report a missing-prefix error
when `override:` is omitted, even if the interface supplies a default. The
compiler must not silently select the interface fallback in that situation.

The user also confirmed that a matching public method inherited from a base
class can satisfy a newly named interface even when the base class does not
implement it. The derived class does not need a forwarding declaration:

```cloth
typedef<interface> IHealth {
    mt<float> getHealth()
}
typedef<class> Base {
    pub mt<float> getHealth() = 100f
}
typedef<class> Player : Base(), IHealth
```

`Base.getHealth` is an ordinary base method; `Player` reuses it to satisfy
`IHealth`. If `Player` declares its own implementation, that declaration requires
`override:`. This reuse decision covers public inherited methods; private base
methods and subclass replacement/dispatch still need their own rules.

The prefix is a method declaration prefix rather than a persistent section.
Reuse ordinary block/expression body parsing. Exact signature/label/default
matching, unmatched-prefix diagnostics, interface-to-interface redeclarations
and subclass replacement remain to be specified. The missing-prefix rule above
does not require a forwarding declaration for inherited public-method reuse.
Stage 1 retains the prefix on the class method declaration. [Stage 1](stage-1.md)

## Default precedence (confirmed 2026-11-07)

The user confirmed this precedence for overlapping interface defaults:

1. Use an eligible class implementation first, including a matching inherited
   public implementation under the rule above.
2. Otherwise use the most-specific applicable interface default. A child
   interface's replacement default takes precedence over its ancestor's default.
3. Unrelated competing defaults require an explicit class `override:`
   implementation to resolve the conflict. Interface-list and import order do
   not select a winning body.

A shared ancestor default reached through both arms of a diamond remains the
same declaration; resolve using declaration identity rather than treating each
inheritance path as a separate body. Exact signature compatibility, re-abstraction
and any future syntax to select an inherited default body remain separate rules.

## Static interface call contracts (confirmed 2026-11-08)

The user confirmed that calls through an interface use parameter labels and
omitted-argument defaults from the interface declaration. Runtime dispatch
selects only the method body. A runtime implementation's labels or defaults do
not change how the interface call was bound.

```cloth
typedef<interface> IHealth {
    mt<float> scaledHealth(opt<float> factor ?: 1f)
}
```

For an `IHealth` view, `view.scaledHealth()` uses the interface's `1f` fallback,
and `view.scaledHealth(factor=2f)` binds the interface's `factor` label. The selected
implementation receives the complete resolved argument list; it must not execute
its own parameter defaults again on that call.

Preserve the [ordinary optional-call evaluation and scope rules](../../cloth-optional-arguments/references/semantics.md#scope-evaluation-and-lifetimes):
supplied expressions run once in source order, omitted defaults run once in
parameter order using the declaration's scope, and the body follows completed
argument resolution. Existing read-only default-parameter/receiver restrictions
continue to apply. Keep receiver capture and liveness revalidation around effects.

Parameter fallbacks introduced by `?:` are distinct from a method's
`= default (...)` or `= default { ... }` fallback implementation. Static contract
ownership governs argument binding even when the body selected by dispatch is an
interface fallback. Detailed implementation signature/optionality compatibility
and direct class-view call contracts remain separate conformance decisions.
This contract guides the later call typing and dispatch stages.

## Decisions before dependent implementation

All recommendations in this table are proposals. Confirm the applicable row
before implementing behavior that depends on it.

| Decision | Recommendation for the first release | Dependent stages |
| --- | --- | --- |
| Member surface | Methods only, with no instance fields, constructors, destructors, states or operators in an interface. Decide marker-only interfaces, empty-body omission and whether redundant `pub` is accepted. | 1, 2 |
| Class header details | One class base plus multiple interfaces is confirmed above. Decide alternative clause ordering, duplicates and redundant empty interface parentheses. | 1, 2 |
| Interface graph details | Multiple parent interfaces are confirmed above. Specify cycle/duplicate rejection and compatible inherited requirements/defaults. | 1, 2 |
| Generic applications | Start with explicit, invariant owner arguments. The confirmed one-application rule applies after parent substitution. Resolve signature collisions between different interface declarations separately. | 1, 2, 3 |
| Nominal conformance | Require declared/inherited conformance rather than structural matching. Match ordered parameter and result types exactly, including nullability and array contracts; return types do not distinguish overloads. | 2, 3 |
| Private and inherited implementations | Matching inherited public methods can satisfy newly named interfaces without forwarding. Decide inherited private methods, hiding, and result/label/optionality mismatch diagnostics explicitly; direct class visibility remains independent. | 2 |
| Subclass replacement and prefix validation | Declared class implementations require `override:`, including when a default exists. Decide inherited conformance/subclass replacement, interface redeclarations and unmatched prefixes. Interface calls should use the accepted implementation for the runtime class. General class virtual dispatch remains separate work. | 1, 2, 4 |
| Default extensions | Class-first, most-specific-interface-next precedence and explicit class overrides for unrelated competing defaults are confirmed above. Decide re-abstraction, exact inherited signature compatibility and any future default-body selection syntax. | 2, 5 |
| Implementation call-contract compatibility | Static interface label/default ownership is confirmed above. Define implementation signature/optionality compatibility and direct class-view contracts; implementation metadata must not redirect an interface call or run defaults twice. | 2, 3, 5 |
| Default body scope and self | Analyze an interface fallback in its defining file with interface-typed self; calls to other requirements dispatch on the same instance. Define whether defaults appear in direct class lookup or only through an interface view. | 3, 5 |
| Conversions and overloads | Implicitly adapt a declared implementing class to its interface and a child interface to a parent. Decide interface-to-Object adaptation, competing interface overloads, explicit casts and unrelated interface views. No unproven downcasts. | 3, 4, 6 |
| Runtime tests and narrowing | Support class/Object-to-interface membership and interface-to-concrete-class tests using validated runtime identity. Define interface-to-interface tests, successful stable-binding narrowing and absent nullable behavior. Preserve static ::type outside narrowing. | 3, 4, 6 |
| Lifetime and deletion | Preserve the class identity and generation through interface values; recommend deletion through a class-backed interface view with concrete destructor dispatch and nullable-absence no-op. Define Object conversion as an alternative if direct interface deletion is deferred. | 3, 4 |
| Construction/destruction | Prevent interface conversion or calls from exposing a partially initialized derived object. Define dispatch from base constructor bodies and from destructor self using existing initialized tags and privileges; copied aliases remain ordinary aliases. | 3, 4, 5 |
| Value contexts and arrays | Support interface identities in normal parameters/results, nullable carriers, fields and arrays; retain array invariance and ordinary snapshots. Specify contextual array element adaptation and conditional/inference joins. | 3, 6 |
| Equality, formatting and metadata | Specify available Object behavior through an interface view, null comparison, equality/identity overload lookup, class formatting and static nominal metadata. Preserve compiler lifetime tracking by raw identity. | 3, 6 |
| Effects and flow | Treat dispatch as potentially reentrant user code. Conservatively invalidate reachable mutable proofs unless all possible implementations justify a tighter rule; recheck liveness after operand/default effects. | 3, 4 onward |

Re-abstraction and the remaining prefix matching/dispatch rules need their
semantics.
Subsequent questions can be settled as their dependent stages approach; the plan
is usable before all future extension design is complete.

## Implementation choices to evaluate

- For class implementers, reuse the private 64-bit slot/generation handle and
  generate interface dispatch using the validated initialized class tag. The
  static interface descriptor identifies the requirement. This avoids copying
  an object or allocating a wrapper solely to view it through an interface.
- Compute conformance using nominal declaration identities and store the chosen
  class method or interface fallback in the checked model. Keep import cycles
  legal while rejecting interface-inheritance cycles.
- Separate contract signatures from bodies in syntax, symbols and body analysis.
  The current callable model requires a body; an empty synthetic implementation
  would conceal a missing requirement.
- Extend the shared call plan with static contract/default ownership and dynamic
  dispatch information, retaining source-order argument evaluation, snapshots,
  omitted-argument handling and ordered result transport.
- Keep class-backed interface representation private. Later struct/enum
  implementers will need a value-copy/mutation/lifetime design and may require
  a different erased representation. No stable external ABI is being promised.

These are implementation recommendations rather than confirmed source semantics.

## Current support and evidence

Stage 1 parses interface declarations, defaults, parent clauses and `override:`.
Nominal name binding covers ordinary and generic interfaces across source files.
C4035 guards full semantic compilation. [Stage 1](stage-1.md)

The [planning baseline](../../../../todo/interfaces-plan.md#foundation-verification--2026-11-07)
passed 106 existing tests across six suites, zero failures/errors/skips, on
2026-11-07. It includes Windows x64/x86 class execution and all-nine-profile
class IR/object checks, with no interface or new foreign-native execution claim.
Stage 0 remains open for the conformance and runtime decisions used by later
stages.
