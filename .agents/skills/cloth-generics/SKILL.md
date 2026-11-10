---
name: cloth-generics
description: Plan, design, or implement Cloth user-defined generic classes and structs, closed type identities, substitution, specialization, imports, and metadata.
---

# Cloth generic types

The first generic class/struct slice and public source List are complete through
[Stage 6 integration and publication](references/stage-6.md). Start with the
[user guide](../../../docs/generics.md) and runnable
[Generics.co](../../../examples/Generics.co).
[Stage 5](references/stage-5.md) owns the actual imported library, private slots
and checked value semantics. Use
[cloth-collections](../cloth-collections/SKILL.md) for the public collection API.
[Stage 4](references/stage-4.md) owns native generic values, ABI/storage and lifetime.
[Stage 3](references/stage-3.md) owns declaration-time symbolic checking and closed
callable/flow checking, with source-linked views and isolated owner/candidate facts.
[Stage 2](references/stage-2.md) owns closed identities/substitution/worklist rules;
[Stage 1](references/stage-1.md) owns syntax and lexical/imported name identities.
Only closed models emit native code; C4033 guards symbolic/unresolved contexts.

[Explicit generic functions](../cloth-functions/references/generic-functions.md)
use the same symbolic and closed analysis, including when calls from generic
types pass their owner parameters. The original function symbol and ordered
types identify each specialization. This slice includes the source `magic`
helper in `cloth.lang.num.NumberUtilities`.

Stage 0 confirms the [first-slice contract](references/semantics.md) and captures
[baseline evidence](references/stage-0.md). Read the
[acceptance/rejection fixtures](references/acceptance-fixtures.md) when implementing
the [staged plan](../../../todo/generic-types-plan.md). The imported List and
nullable acceptance fixtures execute today. Actual-library tests now import the
public List file.

The user confirmed explicit, invariant class/struct parameters, owner-wide lexical
type scope, checking both declarations and closed instances, ordinary value type
arguments, normalized nullable substitution and private implementation helpers
without public signature leaks. Exact details and selected resource limits live
in the contract. Constraints, generic interfaces/functions/method parameters,
variance and inferred arguments remain outside this first slice.

The [growable-array Stage 7 record](../cloth-arrays/references/growable-arrays-stage-7.md)
owns the concrete collection proof and confirmed List requirements:

- `List<int[]>` permits independent element lengths. Direct `T[]` backing would
  impose rectangular constraints when T is an array. The concrete RowList proves
  ordinary struct-slot storage; generic class **and struct** support are needed
  for that implementation, not a List-specific compiler intrinsic.
- `copy()` returns a new collection preserving element value semantics and
  independent lengths. Class-valued elements still copy identity.
- `toArray()` receives into ordinary rectangular `T[]`, checking incompatible
  dimensions at runtime where necessary. It does not create a jagged array type.

Interface Stage 2 uses the same interner and symbolic owner substitution for
explicit, unconstrained generic interfaces with class implementers. Interface
parent cycles are checked before instantiation. Stage 3 checks their bodies and
argument defaults in separate symbolic and closed views, including unused
templates. Preserve `Entity<T: Entity<T>>` and `IHealth<T: Number>` for the
constraint work. [Interface typing](../cloth-interfaces/references/stage-3.md) ·
[Interface conformance](../cloth-interfaces/references/stage-2.md) ·
[Interface scope](../cloth-interfaces/references/design.md#generic-interface-release-scope)

Interface Stage 4 uses the exact closed interface application and class
specialization for native dispatch and membership. Stage 5 emits fallback
bodies and argument defaults in their closed declaring interface views.
Stage 6 permits applications such as `Slot<Value<string>>` and retains their
exact nominal identity in storage, imports and metadata.
[Native interface dispatch](../cloth-interfaces/references/stage-4.md) ·
[Closed interface defaults](../cloth-interfaces/references/stage-5.md) ·
[Closed interface values](../cloth-interfaces/references/stage-6.md) ·
[Interface release verification](../cloth-interfaces/references/stage-7.md)

When extending implementation, keep declaration identity plus ordered closed
arguments in type, callable, metadata and layout caches. Resolve template names
in their defining file. Isolate body/candidate facts between specializations and
retain the target layout. Preserve first-error diagnostics and explicit lifetime.
Share one EmitTypeInfo.Types registry across the module's callable string services.
Intern nominal metadata by canonical closed descriptor identity, including through
array/nullable wrappers, never rendered names. Canonical metadata keeps explicit
numeric widths and rejects expansion beyond 1,048,576 UTF-8 bytes as C4034 before
flattening a shared argument DAG. Human display remains separately bounded.
Apply the confirmed Stage 0 rules; keep later proposals and unimplemented support
distinct from them. An unconstrained T does not imply numeric/member/class
capabilities merely because one chosen instantiation happens to provide them.

The public `Standard-Library/cloth/collections/List.co` implementation passes
actual imported-library tests for ordinary element types, checked rectangular
export and independent copy. Preserve that delivery evidence when extending it;
concrete wrappers and generated concrete sources do not replace those tests.
Bootstrap uses the public List as storage for its owning token list.
[Bootstrap lexer skill](../cloth-bootstrap-lexer/SKILL.md)

Constraints, method-owned parameters, inference and
collection protocols remain in the
[separate follow-up record](../../../todo/generic-follow-ups.md). Completion of
the first slice neither implements those features nor publishes a release pin.
