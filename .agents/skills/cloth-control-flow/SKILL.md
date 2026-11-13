---
name: cloth-control-flow
description: Design, explain, or extend Cloth conditionals, boolean short circuits, completion and branch merging, range/foreach/while/do-while loops, break/continue and reusable loop cleanup.
---

# Cloth control flow

[Generic Stage 3](../cloth-generics/references/stage-3.md) isolates each owner's
body, loop identities, completion/presence/metadata and lifetime facts using
source-linked views. Class checks and presence use substituted types rather than
raw T spelling. Declaration and closed checks never share mutable flow facts.

Numeric range membership in conditions uses `==` / `!=` in either operand order.
Read the [numeric membership contract](../cloth-numerics/references/range-membership.md)
for contextual widths, interval semantics, evaluation order and the shared
`EmitRangeValues` predicates/finite guards used by range loops.

[Arbitrary integer Stage 3](../cloth-numerics/references/arbitrary-width-arithmetic.md)
extends source scalar counters to widths 1..64. Signed one-bit header ++/-- use
mathematical one in the widened calculation; ordinary body updates still wrap.
Wide checked-operation kernels support 2N intermediates through 131072 bits;
[Stage 4](../cloth-numerics/references/arbitrary-width-storage.md) integrates
wide source counters, stable bounds and inline updates through the cap, with
stack checkpoints for repeated membership tests, safe bodies and steppers.

Read [confirmed checks and conditional semantics](references/semantics.md).
Value-producing conditionals require a final `else`. Inferred branch types
agree at each ordered result position. An explicit receiving type supplies
ordinary reception context. Each continuing branch yields its final
expression.

[ExpressionOperands](../../../Compiler/src/main/kotlin/cloth/semantic/flow/ExpressionOperands.kt)
provides eager children in source order, including the operands of a resolved
value-target `is`. Keep branch merging, short circuits, Elvis fallbacks, loop
edges and deferred-body handling in their respective passes. A bare value
target follows the ordinary operator call path and supplies no narrowing proof.
[Operand contexts and checks](../cloth-language/references/operand-contexts.md)

State predicates refine tracked object identities in the lifetime pass.
State-call proofs use the captured identity after supplied operands and
omitted defaults. [State flow and diagnostics](../cloth-language/references/state-runtime.md#flow-checks-and-errors)

The reference also owns [confirmed for-loop and range design](references/semantics.md#for-loops-and-ranges-design-confirmed-implementation-deferred).
Both forms use `<-`; only numeric ranges admit a required `; stepper` clause.
Preserve the accepted fixed-bound/free-direction, strict typing, default `+1`,
finite-float, integer header-overflow and independent foreach-value contracts
when extending execution. The [implementation plan](../../../todo/for-loops-plan.md)
maps the accepted contract to compiler stages and verification requirements.

## For loops: integrated range and foreach execution

[Growable arrays Stage 3](../cloth-arrays/references/growable-arrays-stage-3.md)
executes one-dimensional append/reserve with separate operand owners. Those
mutators still block enclosing temporary suffix proofs; captured publication
keeps conservative lifetime. Foreach retains its independent initial snapshot
while the original source grows. Extend cleanup only with the ownership proof.
[Stage 4](../cloth-arrays/references/growable-arrays-stage-4.md) adds separate
observation arenas for selections spanning field growth and source method calls.
Normal completion unregisters them; frame cleanup handles returns, and static
sites clear previous observations before reuse after deferred returns or loop
jumps. This scoped cleanup does not expand the enclosing loop/suffix proof.
[Stage 5](../cloth-arrays/references/growable-arrays-stage-5.md) executes removal
and clear without changing an active foreach snapshot. Cleared/removed source
payloads release through live slot heads; wanted results keep ordinary independent
frame lifetime. Direct discarded builtins omit unused result copies. Wide load
and bound scratch restore locally, while mutators remain barriers to enclosing
arena-suffix cleanup. Invalidated child selections fail before later access.
[Stage 6](../cloth-arrays/references/growable-arrays-stage-6.md) verifies rectangular
foreach snapshots while the source appends, removes and clears. Traversal retains
its original rows/count; each received growable row can mutate independently.
Conditional, Elvis and deferred captures retain ordinary independent reception.
[Stage 7](../cloth-arrays/references/growable-arrays-stage-7.md) verifies foreach
over a collection's independent array export while class aliases edit its source.
Ordinary classes do not gain direct foreach through private array storage;
get/size traversal works for independently sized rows. Generic types and a future
collection traversal protocol remain separate requirements.

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
- Loop and body bindings use resolved symbols and allocate in the function's entry block.
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

- `ConditionalsTest` uses complete function fixtures for syntax, typing and
  lowering. Its native fixtures use the shared compiler driver and explicitly
  print each observed value. A standalone conditional with no output checks for
  the program's completion report alone.
  [Program fixtures](../../../Compiler/src/test/kotlin/cloth/ProgramFixtures.kt) ·
  [Migration evidence](../cloth-language/references/development.md#production-path-fixture-migration-2026-11-06)

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
  completion. Use `WhileTest` and `ArrayTemporaryCleanupTest`.

- Do-while Stages 0-5 integrate syntax, scopes, strict bool/bottom conditions,
  jump resolution, completion/nullable/metadata flow, execution and proven cleanup.
  The agreed slice is complete. Read [do-while loops](references/do-while-loops.md)
  for body/trailer ownership, semicolons, strict bool, scopes, jumps and required
  first-entry flow/cleanup. The [implementation plan](../../../todo/do-while-loops-plan.md)
  records source routing and stage verification. Use `DoWhileParsingTest` and
  `DoWhileSemanticsTest`/`DoWhileFlowTest`, `DoWhileTest` and do-while probes in
  `ArrayTemporaryCleanupTest`, plus affected loop, call/default, nullable,
  conditional and provider suites.
  `DoWhileStatement` retains its LoopTarget, body and trailing-test tokens;
  the explicit trailer context follows unbraced bodies/expression tails and stops
  at blocks, headers and delimiters. Declaration/type checking visits the body
  before its trailing test; the test resolves outside the body's scope/target.
  Intrinsic loop errors survive call probes. First body entry is unconditional;
  only fallthrough/continue reaches the test, and false tests/breaks determine
  normal exit facts. Backedges invalidate repeated nullable writes and mutable
  metadata before checking; proven nonrepeating bodies keep incoming facts.
  Condition inputs and normal exits join actual path snapshots without zero
  trips or unreachable suffix writes. `EmitDoWhileLoop` enters body before test,
  uses test/exit jump destinations and terminates non-fallthrough blocks. Body,
  test and whole-loop cleanup use independent closed proofs; captured publication
  retains frame lifetime. Maintained [DoWhile.co](../../../examples/DoWhile.co)
  executes with exact-output checks on Windows x64/x86; it and the nested-loop
  fixture verify IR/objects for all nine profiles. Foreign native execution
  remains untested. Stage 5 records all 1,184 compiler tests passing across 91
  suites, plus publication evidence.

- For `break`/`continue`, read [loop control](references/loop-control.md): nearest
  body target resolution, function/block boundaries, separate fallthrough/return/
  jump outcomes, continue-path fact joins, shared destinations and crossed-region
  cleanup. Range, foreach, while and do-while bodies execute jumps. Do-while tests
  execute in the surrounding jump context. Use
  `LoopControlTest` and `ArrayTemporaryCleanupTest` when extending them. Header jumps target an
  enclosing body, never the loop being initialized.

- Standalone conditionals have no implicit result/output or enclosing return.
  A value-producing chain requires a final `else` and compatible branch
  results. Each continuing branch yields its final expression. A return
  exits the enclosing function or block.
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
[ExpressionTyping](../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt),
[ControlFlowModel](../../../Compiler/src/main/kotlin/cloth/semantic/flow/ControlFlowModel.kt),
and [EmitExpression](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt).
Example: [Conditionals.co](../../../examples/Conditionals.co).

Use `ConditionalsTest`, boolean cases in `BindingsTest`, `FunctionReturnsTest`,
`FunctionBlocksTest`, and the affected multi-return suites. Existing tests check
skipped-code errors, scopes, side effects, early returns, actual phi predecessors
and statement silence. Recorded evidence is in the
[FLOW-1 verification](references/semantics.md#flow-1-verification) and
[development history](../cloth-language/references/development.md#earlier-milestone-snapshots).
