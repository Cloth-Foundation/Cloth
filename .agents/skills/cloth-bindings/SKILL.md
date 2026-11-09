---
name: cloth-bindings
description: Design, explain, or extend Cloth binding inference, mutable and immutable declarations, broadcast definitions and assignments, compound assignments, or standalone postfix updates.
---

# Cloth bindings and assignments

Read [confirmed binding semantics](references/semantics.md). Use
[multiple results](../cloth-multiple-results/SKILL.md) when the RHS has an ordered
result list; use [arrays](../cloth-arrays/SKILL.md) for shape and copy rules.

## Development rules and current boundaries

- `def x = value` is mutable; `def x := value` is immutable. An omitted annotation
  infers once. Declared/inferred types remain fixed through reassignment, including
  width and signedness. Contextual literals do not convert already-typed values.
- `def x, y = make()` evaluates one scalar-result RHS once total and copies it
  independently to each name. Explicit annotations and binding mutability apply to
  all names. `x, y = value` requires existing mutable compatible targets.
- Broadcast assignment resolves one RHS type using the first target's ordinary
  context; every other target must accept that exact type. Do not independently
  contextualize the same integer literal for differing destination widths.
- Preserve source evaluation/snapshot order and array independence. Positional
  reception is selected by RHS result shape, not the superficial RHS syntax.
- Compound `+=`, `-=`, `*=`, `/=`, `%=` read the target before the once-only RHS.
  They can be the single statement in a [void `=` body](../cloth-functions/references/semantics.md#function-return-contracts).
  `++`/`--` are standalone postfix statements. Named bindings and writable field
  paths support compound assignments and updates. Array element targets without
  a field in the path use the separate plain indexed `=` path.
- Binding-only immutability permits array element writes and does not imply future
  class objects are frozen. A deferred function-block binding is not an ordinary
  stored result; use the [function skill](../cloth-functions/SKILL.md).

The confirmed [growable-array contract](../cloth-arrays/references/growable-arrays.md)
extends binding-only immutability to length mutation of `T[]`. Explicit growable
annotations and inferred growable copies retain that type rather than capturing
their current count as a fixed extent.
[Stage 1](../cloth-arrays/references/growable-arrays-stage-1.md) implements that
typing and whole-array reception, including broadcast and positional results.
[Stage 3](../cloth-arrays/references/growable-arrays-stage-3.md) executes local and
parameter append/reserve, including `:=`/`final`. Mutation publication updates
selected place views; whole-binding assignment ends their association with the
new binding value. Keep that distinction when extending reception or rebinding.
[Stage 4](../cloth-arrays/references/growable-arrays-stage-4.md) extends append/reserve
to class/struct fields and nested array-element fields, including final stored
receivers. Scoped owner observations preserve selected versions across growth in
separately emitted methods; whole replacement still receives an independent value.
[Stage 5](../cloth-arrays/references/growable-arrays-stage-5.md) adds insert,
removeAt and clear on those same one-dimensional receivers, including final
bindings. Removed values copy independently before source slot release. Existing
nullable receiving annotations and fixed receiving count checks still apply.
[Stage 6](../cloth-arrays/references/growable-arrays-stage-6.md) extends every
method to rectangular arrays. Whole replacement constrains against the current
binding descriptor, including widths established by mutation after an empty
initializer. Nullable retained state updates on publication and survives null;
selected older values retain their own outer count across replacement.

Stage 3 executes range headers with one existing assignment/update to their declared
counter. The [loop contract](../cloth-control-flow/references/semantics.md#for-loops-and-ranges-design-confirmed-implementation-deferred)
owns the source/type and implemented integer overflow-exit rules.
Preserve the original stepper operator: arithmetic inside a plain assignment RHS
retains ordinary wrapping, while header arithmetic update operators use the loop
overflow rule. Existing division/remainder invalid-operation checks still apply.

## Source and verification

Inspect [Parser](../../../Compiler/src/main/kotlin/cloth/parser/Parser.kt),
[DeclarationAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationAnalyzer.kt),
[ExpressionTyping](../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt),
[ReceptionPlan](../../../Compiler/src/main/kotlin/cloth/semantic/ReceptionPlan.kt),
[EmitBindings](../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitBindings.kt),
and [EmitStatement](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitStatement.kt).
Example: [Bindings.co](../../../examples/Bindings.co).

Relevant suites: `BindingsTest`, `MultiBindingsTest`, `MultiAssignmentTest`,
`AssignmentOperatorsTest`, plus `DestructuringTest` for shape-dependent routing.
Existing cases check once-total execution, independent storage, duplicate/unknown
names, strict types, source labels and captured side effects. Recorded evidence:
[multiple definitions](../cloth-language/references/development.md#multiple-binding-definitions-2026-10-31)
and [assignment milestone](../cloth-language/references/development.md#multiple-assignment-to-existing-bindings-2026-10-31).

`BindingsTest` and `AssignmentOperatorsTest` parse complete function fixtures and
check them through the production analyzer. Native fixtures use the shared
compile driver, print observed values explicitly and check `PEC` on normal
completion. Reuse these fixture helpers for new binding cases.
[Program fixtures](../../../Compiler/src/test/kotlin/cloth/ProgramFixtures.kt) ·
[Migration evidence](../cloth-language/references/development.md#production-path-fixture-migration-2026-11-06)

`MultiAssignmentTest` checks each immutable target through declaration
resolution, including the declaration and reassignment labels. Its native
cases share the fixture runner. [Reception test evidence](../cloth-language/references/development.md#production-path-reception-tests-2026-11-06)

Forward/self local reads are currently rejected; that is not a specification for
future class/module declaration resolution.
