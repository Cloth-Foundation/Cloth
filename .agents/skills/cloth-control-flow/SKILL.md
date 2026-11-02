---
name: cloth-control-flow
description: Design, explain, or extend Cloth if/else expressions and statements, primitive is checks, boolean short-circuit control flow, return completion, branch merging, or for-loop parsing, scopes, types and repeated flow.
---

# Cloth control flow

Read [confirmed checks and conditional semantics](references/semantics.md).
Keep confirmed language decisions distinct from the current implementation's
final-else, branch compatibility and final-expression policies.

The reference also owns [confirmed for-loop and range design](references/semantics.md#for-loops-and-ranges-design-confirmed-implementation-deferred).
Both forms use `<-`; only numeric ranges admit a required `; stepper` clause.
Preserve the accepted fixed-bound/free-direction, strict typing, default `+1`,
finite-float, integer header-overflow and independent foreach-value contracts
when implementing later stages. The [implementation plan](../../../todo/for-loops-plan.md)
maps the accepted contract to compiler stages and verification requirements.

## For loops: Stage 2 semantic support

Read [stage scope, source ownership and verification](references/for-implementation.md)
before extending loops or enabling execution. Stage 2 is complete: 105 focused
and all 868 compiler tests pass, including 55 loop tests. Loop execution is
still gated, independently of accepted static scopes/types/flow.

- `ForStatement.kt` retains a separate receiving binding, range/element source,
  original stepper operator/RHS, body form and exact spans. Do not fabricate an
  ordinary initializer or desugar away the original header update operator.
- Parser supports typed/inferred syntax, both range delimiters, all eight single
  counter assignment/update forms, mandatory `;` before a stepper, foreach without
  a stepper, brace bodies and one supported source statement as an unbraced body.
- Range boundaries are opt-in within a loop source. Preserve ordinary nested
  expression grammar, precedence, declaration boundaries and `if`/`else` ownership.
- LoopBindingSymbol and separate header/body scopes preserve outer endpoint
  lookup, body-local isolation, default-parameter shadowing and ordinary mutability.
- Shared checkFor validates exact range types and foreach element/row shapes;
  ForLoopPlan is target-aware and copied through semantic forks/commits. Body and
  stepper cannot supply inference context. Nullable array elements remain gated.
- Completion includes zero trips and real return ownership. Presence proofs are
  invalidated for repeated/captured writes before body checking; metadata forgets
  visible mutable represented types across the loop and keeps immutable facts.
- Semantic APIs accept valid loops. C3068 labels invalid loop source/binding
  contracts; existing earlier diagnostics stay intact. C3067 now gates only
  backend execution before LLVM/linker/output work, after semantic validation.
- No loop IR, runtime progression/finite/overflow guards, actual foreach snapshot
  or value transfer, or iteration cleanup is implemented in this stage.

Use `ForParsingTest`, `ForSemanticsTest`, `ForFlowTest` and `ForStageGateTest`, plus
affected nullable, metadata, defaults and return suites.

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
