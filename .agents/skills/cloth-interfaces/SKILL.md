---
name: cloth-interfaces
description: Design or implement Cloth class interfaces, requirements and default methods, conformance, interface inheritance, visibility, dispatch and checked object lifetime.
---

# Cloth interfaces

Stage 1 implements interface syntax and nominal name binding. The parser accepts
braced interfaces with plain `mt` members, explicit defaults, generic parameters
and parent lists. C4035 marks the conformance boundary for compiler requests.
[Stage 1 implementation and verification](references/stage-1.md)

The planned first release covers non-generic and explicit, unconstrained generic
interfaces with class implementers. The non-generic fixture is the first
dispatch milestone. The generic work builds on delivered class and struct
specialization. Constraints and struct or enum implementers have separate
follow-ups. [Release scope](references/design.md#generic-interface-release-scope) · [Cloth generics](../cloth-generics/SKILL.md)

The design record owns confirmed behavior and open decisions. The implementation
plan owns stage status, compiler ownership, acceptance scenarios and foundation
evidence. Test.co retains the intended constrained interface example.
[Design record](references/design.md) · [Implementation plan](../../../todo/interfaces-plan.md) · [Test.co](../../../examples/Test.co)

## Preserve the interface boundary

- A required interface method is a signature with no body. Expression fallbacks
  use `= default (...)`; block fallbacks use `= default { ... }`. Both keep the
  explicit marker. Read the [confirmed method contract](references/design.md#required-methods-and-explicit-defaults-confirmed-2026-11-07).
- A class may name one class base and multiple implemented interfaces in its
  `:` header list. Read the [class header contract](references/design.md#class-base-and-interface-lists-confirmed-2026-11-07)
  before extending inheritance parsing.
- An interface may name multiple parent interfaces in a comma-separated `:`
  list. Read the [interface inheritance contract](references/design.md#multiple-parent-interfaces-confirmed-2026-11-07).
  Duplicate-parent handling remains open.
- A class method declared to implement an interface method must use `override:`
  before optional `pub`. A matching public method inherited from a base class
  may satisfy a newly implemented interface without a forwarding declaration,
  even if the base does not implement that interface. Read the
  [confirmed prefix and inherited-method rules](references/design.md#override-declaration-prefix-confirmed-2026-11-07).
  A newly declared matching method in an implementing class fails without the
  prefix even when the interface supplies a default.
  Inherited private methods and subclass replacement rules remain open.
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
- Preserve explicit deletion and checked class aliases through every interface
  value. The plan proposes identity-preserving conversion; it does not establish
  ownership transfer, garbage collection or automatic destruction.
- Keep a required signature distinct from an executable body. A dispatch plan
  must distinguish the static call contract from the selected runtime body.
- Reuse existing argument binding and result transport. Argument fallbacks and
  an interface method's fallback body are separate contracts. Interface calls
  use labels and omitted-argument defaults from the static interface declaration;
  runtime dispatch selects only the body. Implementations receive resolved values
  and do not apply a second set of defaults. Read the
  [static call contract](references/design.md#static-interface-call-contracts-confirmed-2026-11-08).
- Follow [feature integration](../cloth-feature-integration/SKILL.md) for each
  implemented stage. Preserve first-error reporting and use canonical enum
  spellings in compiler code. Record supported forms and explicit staged gates.

## Current source and verification boundary

The [plan's compiler map](../../../todo/interfaces-plan.md#compiler-ownership-map)
routes syntax, declarations, conformance, calls, flow, storage and lowering.
Existing ordinary class methods dispatch statically. Class formatting, equality,
type tests and deletion provide reusable checked runtime-tag mechanisms; they do
not establish ordinary virtual or interface dispatch.

Stage 1 passed 133 focused tests plus the native-free repeat of the 35 import
tests. Existing class and generic fixtures provide native Windows x64/x86 and
nine-profile IR and object evidence. The interface-specific coverage ends at
name analysis and the compilation diagnostic. [Stage 1 results](references/stage-1.md#verification) ·
[Generic foundation](../cloth-generics/references/stage-6.md)
