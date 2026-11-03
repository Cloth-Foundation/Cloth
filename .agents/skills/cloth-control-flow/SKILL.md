---
name: cloth-control-flow
description: Design, explain, or extend Cloth conditionals, boolean short circuits, completion and branch merging, range/foreach/while loops, break/continue and reusable loop cleanup.
---

# Cloth control flow

Read [confirmed checks and conditional semantics](references/semantics.md).
Keep confirmed language decisions distinct from the current implementation's
final-else, branch compatibility and final-expression policies.

The reference also owns [confirmed for-loop and range design](references/semantics.md#for-loops-and-ranges-design-confirmed-implementation-deferred).
Both forms use `<-`; only numeric ranges admit a required `; stepper` clause.
Preserve the accepted fixed-bound/free-direction, strict typing, default `+1`,
finite-float, integer header-overflow and independent foreach-value contracts
when extending execution. The [implementation plan](../../../todo/for-loops-plan.md)
maps the accepted contract to compiler stages and verification requirements.

## For loops: integrated range and foreach execution

Read [stage scope, source ownership and verification](references/for-implementation.md)
before extending loops. Stages 1-4 implement syntax, scopes/types/repeated flow,
numeric ranges, array foreach snapshots/independent elements and proven cleanup.
Stage 5 adds runnable examples and integration coverage on native Windows x64/x86
and verified IR/objects across all nine supported ABI profiles. All 936 compiler
tests pass, including 112 loop tests and 27 cleanup tests. The agreed slice is
complete; the linked record retains exact commands, evidence and deferred scope.

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
  stepper cannot supply inference context. Nullable scalar/string foreach elements
  and nullable row values execute with explicit receiving types. Read the
  [buffer integration contract](../cloth-nullability/references/buffers.md).
- Completion includes zero trips and real return ownership. Presence proofs are
  invalidated for repeated/captured writes before body checking; metadata forgets
  visible mutable represented types across the loop and keeps immutable facts.
- EmitRangeLoop saves and validates endpoints once in source order, uses both
  fixed boundaries, and executes default/all explicit updates after normal body
  completion. Preserve descending/free direction, body writes and early returns.
- Integer header arithmetic overflow exits before storing; ordinary body/RHS
  wrapping and integer division failures remain. Float updates round in their
  declared format; finite and unchanged-update guards retain located failures.
  Closed nonfinite endpoint/step constants get C3068; unknown values are guarded.
- Loop/body binding allocation stays in the entry block, including snippets.
  EmitForEach captures its source once, transfers fresh results or copies stored
  values, traverses with usize counts, and receives a mutable independent value
  each iteration. Preserve recursive row/string copies, shapes and saved values.
- Closed body, stepper and whole-loop proofs enable arena suffix release plus
  dynamic stack restoration after normal completion. Buffered/indexed writes,
  captured publication and unknown effects retain frame lifetime. Early returns
  bypass normal regions and use existing buffered copy/return cleanup.
- C3068 labels invalid loop contracts, including known nonfinite range values;
  existing earlier diagnostics stay intact. C3067 is retired and reserved;
  valid range/foreach syntax reaches execution without a stage gate.

Use `ForIntegrationTest`, `ForEachTest`, `ForRangeTest`, `ForParsingTest`, `ForSemanticsTest`, `ForFlowTest`,
`ForStageGateTest` and `ArrayTemporaryCleanupTest`, plus
affected nullable, metadata, defaults and return suites.

Runnable examples: [ForRanges.co](../../../examples/ForRanges.co),
[FloatRanges.co](../../../examples/FloatRanges.co) and
[ForEach.co](../../../examples/ForEach.co). Preserve Test.co's intended class/state
syntax without treating it as a class-execution acceptance test.

## Development rules and current boundaries

- `IfParser` accepts either braces or one ordinary expression/statement per
  `if`/`else if`/`else` body, including definitions, returns, assignments, nested
  conditionals, loops and loop jumps. Preserve nearest-`if` ownership of `else`,
  branch-local scopes, source spans excluding terminators and the existing
  prohibition on semicolons before `else`. `Parser.returnStatement` recognizes
  an immediate `else` as a bare-return boundary. Unbraced bodies reuse the
  existing branch statement lists and need no semantic or emitter special case.
  See [the syntax contract](references/semantics.md#conditional-expressions-and-statements),
  [Conditionals.co](../../../examples/Conditionals.co), `UnbracedConditionalsTest`
  and [integration evidence](../cloth-language/references/development.md#unbraced-conditional-bodies-2026-11-03).

- `while (condition) body` executes with strict bool conditions, repeated tests,
  nearest-body jumps and proven condition/body cleanup. Read [while loops](references/while-loops.md)
  for scope, nullable/metadata invalidation, false/break exit facts and true-loop
  completion. Use `WhileTest` and `ArrayTemporaryCleanupTest`; do/while is deferred.

- For `break`/`continue`, read [loop control](references/loop-control.md): nearest
  body target resolution, function/block boundaries, separate fallthrough/return/
  jump outcomes, continue-path fact joins, shared destinations and crossed-region
  cleanup. Range, foreach and while bodies execute jumps; do/while syntax is
  deferred. Use `LoopControlTest` and `ArrayTemporaryCleanupTest` when extending
  them. Header jumps target an enclosing body, never the loop being initialized.

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
