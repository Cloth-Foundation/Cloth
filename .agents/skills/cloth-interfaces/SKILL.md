---
name: cloth-interfaces
description: Design or implement Cloth class interfaces, requirements and default methods, conformance, interface inheritance, visibility, dispatch and checked object lifetime.
---

# Cloth interfaces

The first class-backed interface slice supports aggregate storage, arrays,
generic arguments and metadata. Interface views use checked membership,
`Object` widening, class comparison and formatting, and explicit deletion.
Calls evaluate omitted arguments in the static interface contract.
[Stage 7 release verification](references/stage-7.md) ·
[Stage 6 value contexts and flow](references/stage-6.md) ·
[Stage 5 defaults and inherited contracts](references/stage-5.md) ·
[Stage 4 dispatch and lifetime](references/stage-4.md) ·
[Stage 3 typing and calls](references/stage-3.md) ·
[Stage 2 conformance](references/stage-2.md) · [Stage 1 syntax and names](references/stage-1.md)

The first release slice covers non-generic and explicit, unconstrained generic
interfaces with class implementers. It builds on class and struct specialization.
Constraints and struct or enum implementers have separate
follow-ups. [Release scope](references/design.md#generic-interface-release-scope) · [Cloth generics](../cloth-generics/SKILL.md)

The design record owns confirmed behavior and open decisions. The implementation
plan owns compiler routes and acceptance scenarios. The public examples cover
ordinary class-backed interfaces and closed generic contracts across files.
[Design record](references/design.md) · [Implementation plan](../../../todo/interfaces-plan.md) ·
[Public interface guide](../../../README.md#interfaces) · [Test.co](../../../examples/Test.co)

GEN-6 Stage 3 checks method-owned type parameters on interface signatures
and default bodies. A class override inherits the method's bounds by
position. Stage 4 specializes calls through interface views and dispatches
to the corresponding class override or interface fallback.
[GEN-6 Stage 3](references/generic-interface-stage-3.md) ·
[GEN-6 Stage 4](references/generic-interface-stage-4.md) ·
[Generic interface implementation bounds](references/design.md#generic-interface-implementation-bounds)

## Preserve the interface boundary

- A required interface method is a signature with no body. Expression fallbacks
  use `= default (...)`; block fallbacks use `= default { ... }`. Both keep the
  explicit marker. Read the [confirmed method contract](references/design.md#required-methods-and-explicit-defaults-confirmed-2026-11-07).
- A class may name one class base and multiple implemented interfaces in its
  `:` header list. Read the [class header contract](references/design.md#class-base-and-interface-lists-confirmed-2026-11-07)
  before extending inheritance parsing.
- An interface may name multiple parent interfaces in a comma-separated `:`
  list. Read the [interface inheritance contract](references/design.md#multiple-parent-interfaces-confirmed-2026-11-07).
  Repeated direct clauses fail. Identical applications reached along different
  inheritance paths contribute one interface.
- A class method declared to implement an interface method must use `override:`
  before optional `pub`. A matching public method inherited from a base class
  may satisfy a newly implemented interface without a forwarding declaration,
  even if the base does not implement that interface. Read the
  [confirmed prefix and inherited-method rules](references/design.md#override-declaration-prefix-confirmed-2026-11-07).
  A newly declared matching method in an implementing class fails without the
  prefix even when the interface supplies a default.
  A private base implementation carries the original requirement it already
  satisfies. A marked subclass method can replace an inherited target.
- Resolve defaults by preferring a class implementation, then the most-specific
  interface default. Unrelated competing defaults require an explicit class
  `override:` implementation. Read the [precedence contract](references/design.md#default-precedence-confirmed-2026-11-07).
- Interface methods are callable through the interface without `pub`. Class
  visibility independently controls direct class calls. Do not require a public
  class implementation merely to expose the interface contract.
- Ordinary class methods satisfy requirements; state methods do not. Ordinary
  member calls use `.`, while state calls have their separate `::` spelling.
- Carry original declaration identity and exact ordered type arguments through
  imports, conformance, dispatch and metadata. Reuse generic owner scope and
  substitution for dependent parents and member signatures. Keep symbolic
  checking and each closed specialization separate.
- Enforce one application per interface declaration in each type's full interface
  set. Include inherited conformance, merge identical applications reached by
  multiple paths, and reject competing applications after substitution.
  [One-application rule](references/design.md#one-application-per-interface-declaration)
- Preserve the checked class identity and explicit deletion rules through
  every interface conversion. Aggregate copies preserve aliases to the same
  explicitly managed instance.
- Keep a required signature distinct from an executable body. A dispatch plan
  must distinguish the static call contract from the selected runtime body.
- Reuse existing argument binding and result transport. Argument fallbacks and
  an interface method's fallback body are separate contracts. Interface calls
  use labels and omitted-argument defaults from the static interface declaration;
  runtime dispatch selects only the body. Implementations receive resolved values
  and do not apply a second set of defaults. Read the
  [static call contract](references/design.md#static-interface-call-contracts-confirmed-2026-11-08).
- Preserve invariant array element types and the alias identity of interface
  elements across aggregate copies. `Object` widening, equality, formatting and
  nominal metadata follow the [integrated value contract](references/stage-6.md).
- Follow [feature integration](../cloth-feature-integration/SKILL.md) for each
  implemented stage. Preserve first-error reporting and use canonical enum
  spellings in compiler code. Record supported forms and explicit staged gates.

## Current source and verification boundary

The [plan's compiler map](../../../todo/interfaces-plan.md#compiler-ownership-map)
routes syntax, declarations, conformance, calls, flow, storage and lowering.
Interface dispatch selects a class implementation or fallback from the
initialized tag and the static slot. Ordinary class methods dispatch statically.

Stage 7 covers the full offline Compiler and Shuttle checks, the installed
launchers, and the native and cross-target matrix. Stage 6 covers aggregate
transport, guards, object operations and exact metadata.
[Stage 7 results](references/stage-7.md#regression-and-distribution-checks) ·
[Stage 6 results](references/stage-6.md#verification) ·
[Stage 5 results](references/stage-5.md#verification) ·
[Stage 4 results](references/stage-4.md#verification) ·
[Stage 3 results](references/stage-3.md#verification) ·
[Stage 2 results](references/stage-2.md#verification) ·
[Generic foundation](../cloth-generics/references/stage-6.md)
