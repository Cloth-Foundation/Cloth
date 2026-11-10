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
- Top-level states can use an interface as their host contract. The attaching
  class satisfies that interface through ordinary methods and interface
  defaults. [State attachments](../../cloth-language/references/state-attachments.md)
- On 2026-11-07 the user selected **classes first; structs and enums later** for
  interface implementation. This is implementation ordering, not a permanent
  prohibition on value types implementing interfaces.

The user's confirmations below settle required-method syntax, explicit
expression/block defaults, class/interface parent lists, required `override:`
on declared class implementations, reuse of matching inherited public methods
and default precedence, together with static interface call-contract ownership.
The Stage 2 record distinguishes the implemented matching rules from the
remaining design decisions. [Stage 2 conformance](stage-2.md)

## Initial implementation scope (confirmed 2026-11-08)

The user originally selected non-generic interfaces as the first milestone.
The basic acceptance fixture keeps that order. The release scope now includes
generic interfaces after delivery of the generic class and struct foundation.
Class implementers come first, with struct and enum implementers in a later slice.
[Generic interface release scope](#generic-interface-release-scope)

## Generic interface release scope

The user asked to include generic support in the interface plan after class and
struct generics landed. The release slice covers explicit, unconstrained
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

The first release uses explicit, invariant applications. Keep the closed
interface and its member contract in the checked call plan, and use the closed
class identity to resolve the runtime body. The one-application rule below
governs declared and inherited conformance. Stage 2 checks exact signatures
after substitution, including collisions between different declarations.
[Stage 2 conformance](stage-2.md)

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
[Conformance stage](stage-2.md)

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

The class base retains its constructor clause and may appear anywhere in the
list. Interface entries use type names without constructor parentheses. A class
that names only interfaces retains its implicit Object base. Stage 2 rejects
repeated direct interface clauses after substitution. [Stage 2 conformance](stage-2.md)

## Multiple parent interfaces (confirmed 2026-11-07)

The user confirmed multiple parent interfaces, using a comma-separated list
after `:` on an interface declaration:

```cloth
typedef<interface> IPlayer : IHealth, INamed {
    mt<int> playerId()
}
```

Stage 2 resolves inherited requirements and shared ancestors. Default
precedence is confirmed separately below. [Stage 2 conformance](stage-2.md)

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
`override:`. Stage 2 carries an inherited private target for the original
requirement it already satisfies and accepts a marked subclass replacement.
[Stage 2 conformance](stage-2.md)

The prefix is a method declaration prefix rather than a persistent section.
Reuse ordinary block/expression body parsing. Stage 1 retains the prefix on
the declaration. Stage 2 diagnoses missing and unmatched prefixes, checks
signatures, and records the selected target. [Stage 1](stage-1.md) · [Stage 2](stage-2.md)

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
inheritance path as a separate body. Stage 2 records the exact signature
compatibility rules. Re-abstraction and syntax for selecting a particular
ancestor default remain open. [Stage 2 conformance](stage-2.md)

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
interface fallback. Stage 2 matches implementation parameter types and ordered
results exactly. Class labels and optionality belong to the direct class call
contract. Stage 5 applies that separation to native omission evaluation and
fallback execution. [Stage 2 conformance](stage-2.md) · [Stage 5 defaults](stage-5.md)

## Class-backed value contracts

The first release uses the existing class object protocol for interface views.
An interface value can widen to `Object`, including through nullable lifting.
A successful `is` test establishes another class or interface view on a stable
binding. Static casts use the declared class-to-interface, parent-interface and
`Object` widenings. Compound guards retain inherited membership facts shared
across their continuing branches.

Equality and inequality use the checked class protocol for present values.
Identity comparisons involving an interface view compare slot and generation.
Formatting uses the initialized class formatter or name. The static view and
its exact generic arguments determine `::type`, even inside a narrowing guard.
Aggregate values preserve interface identities, and arrays are invariant in
element type. [Stage 6 value contexts](stage-6.md)

## Decisions before dependent implementation

The table covers open design for later work. The completed records distinguish
implemented choices from confirmed rules. [Stage 2 conformance](stage-2.md) ·
[Stage 3 typing and calls](stage-3.md) · [Stage 4 dispatch and lifetime](stage-4.md) ·
[Stage 5 defaults and inherited contracts](stage-5.md) ·
[Stage 6 value contexts](stage-6.md)

| Decision | Recommendation for the first release | Dependent stages |
| --- | --- | --- |
| Member surface | Stage 1 accepts empty braced bodies and plain methods. Decide empty-body omission and whether redundant `pub` is accepted. | Future syntax |
| Default extensions | Decide re-abstraction and any explicit default-body selection syntax. Preserve the class-first and most-specific precedence rules. | Future default extensions |

Subsequent questions can be settled as their dependent stages approach; the plan
is usable before all future extension design is complete.

## Implementation invariants

- For class implementers, reuse the private 64-bit slot/generation handle and
  generate interface dispatch using the validated initialized class tag. The
  static interface descriptor identifies the requirement. This avoids copying
  an object or allocating a wrapper solely to view it through an interface.
- Compute conformance using nominal declaration identities and store the chosen
  class method or interface fallback in the checked model. Keep import cycles
  legal while rejecting interface-inheritance cycles.
- Separate contract signatures from bodies in syntax, symbols and body analysis.
  Required signatures acquire an implementation through checked conformance.
- Keep static contract and default ownership separate from dynamic
  dispatch information, retaining source-order argument evaluation, snapshots,
  omitted-argument handling and ordered result transport.
- Keep class-backed interface representation private. Later struct/enum
  implementers will need a value-copy/mutation/lifetime design and may require
  a different erased representation. No stable external ABI is being promised.

## Current support and evidence

The verified release slice includes class-backed views in aggregate values,
object operations, metadata and imported generic holders. Calls retain the
static interface's omitted-argument defaults and the checked initialized
class's selected body. [Stage 7 release evidence](stage-7.md) ·
[Stage 6](stage-6.md) · [Stage 5](stage-5.md) ·
[Stage 4](stage-4.md) · [Stage 3](stage-3.md) ·
[Stage 2](stage-2.md) · [Stage 1](stage-1.md)

The [planning baseline](../../../../todo/interfaces-plan.md#foundation-verification--2026-11-07)
passed 106 existing tests across six suites, zero failures/errors/skips, on
2026-11-07. It includes Windows x64/x86 class execution and all-nine-profile
class IR/object checks, with no interface or new foreign-native execution claim.
## Future interface work

- Constraints, interface variance and inferred type arguments have separate
  generic design tasks. [Generic follow-ups](../../../../todo/generic-follow-ups.md)
- Struct and enum implementers need representation, value-copy, mutation and
  lifetime rules. Their conformance will use the same one-application rule.
- State contracts need host, visibility and active-state behavior. Ordinary
  class virtual dispatch has its own override and call rules to settle.
  [State design](../../cloth-language/references/semantics.md#states-and-method-dispatch) ·
  [Object and operator design](../../cloth-language/references/object-protocol.md)
- Separate compilation needs a cross-module identity, slot and metadata
  contract. Current source imports compile through the checked whole graph.
- Future syntax and default-body extensions follow the open decisions above.
