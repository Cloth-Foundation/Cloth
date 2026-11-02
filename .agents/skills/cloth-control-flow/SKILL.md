---
name: cloth-control-flow
description: Design, explain, or extend Cloth if/else expressions and statements, primitive is checks, boolean short-circuit control flow, return completion, or branch result merging.
---

# Cloth control flow

Read [confirmed checks and conditional semantics](references/semantics.md).
Keep confirmed language decisions distinct from the current implementation's
final-else, branch compatibility and final-expression policies.

## Development rules and current boundaries

- Standalone conditionals have no implicit result/output or enclosing return.
  Value-producing chains currently require a final else and compatible branch
  results, taking the final expression on continuing branches. Returns exit the
  actual enclosing function/block; non-continuing branches contribute no value.
- Conditions are bool. Type-check skipped alternatives/bodies and enforce lexical
  scope/shadowing rules even when a condition is constant. At runtime evaluate
  conditions once in order and execute only the selected branch.
- Boolean `&&`/`||` evaluate left to right and short-circuit. Type checks on their
  RHS still run during compilation. Keep ordinary equality distinct from `is`.
- Primitive `is` uses the declared/resolved type contract and still evaluates its
  operand once. General class/state checking and narrowing are intended language
  features with execution deferred; do not claim the primitive implementation
  establishes their full semantics.
  For [nullable scalars](../cloth-nullability/SKILL.md),
  compare the flow-adjusted expression type, including `?`; testing for T? never
  establishes presence. Bare `null is T?` is rejected; a typed source such as
  `(null -> int?) is int?` is valid and true. The `is` target supplies no literal
  context. Bare-left null Elvis may take context from its fallback,
  while any resulting nullable binding still needs an explicit annotation.
- Emit merges using each branch's actual final LLVM block, including blocks added
  by nested expressions or runtime guards. Only continuing predecessors feed phi
  nodes. Restore lexical names while preserving writes to enclosing bindings.
- Use [multiple results](../cloth-multiple-results/SKILL.md) for branch result lists
  and [buffer lifetimes](../cloth-buffer-lifetimes/SKILL.md) for buffered merges.

## Source and verification

Inspect [ExpressionParser](../../../Compiler/src/main/kotlin/cloth/parser/subs/ExpressionParser.kt),
[ExpressionTyping](../../../Compiler/src/main/kotlin/cloth/semantic/ExpressionTyping.kt),
[ControlFlowModel](../../../Compiler/src/main/kotlin/cloth/semantic/ControlFlowModel.kt),
and [EmitExpression](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt).
Example: [Conditionals.co](../../../examples/Conditionals.co).

Use `ConditionalsTest`, boolean cases in `BindingsTest`, `FunctionReturnsTest`,
`FunctionBlocksTest`, and the affected multi-return suites. Existing tests check
skipped-code errors, scopes, side effects, early returns, actual phi predecessors
and statement silence. Recorded evidence is in
[development history](../cloth-language/references/development.md#earlier-milestone-snapshots).
Exhaustiveness and branch-result policy beyond confirmed examples remain open
language questions even though the bounded implementation has concrete rules.
