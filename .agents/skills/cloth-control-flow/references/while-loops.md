# While loops (2026-11-02)

The user confirmed repeated strict-bool testing, shared loop-control machinery,
and then requested implementation. This record owns the current while contract
and its integration with existing range/foreach loops.

```cloth
def i = 0
while (i < 10) {
    i++
    if (i == 2) { continue }
    if (i == 4) { break }
    println(i)
}
while (i > 0) i--
```

## Syntax and behavior

- `while (condition) body` requires parentheses and a body, either braces or
  one supported source statement. A bare semicolon is not a body. The shared
  LoopBody representation retains normalized statements, braces and source spans;
  ForBody remains a compatibility typealias. Ordinary optional terminators and
  conditional `else` ownership apply without changing expression grammar.
- Conditions require bool, with no numeric/string/array truthiness or implicit
  nullable unwrapping. Existing presence proofs and Elvis fallback can supply a
  bool payload. Non-continuing conditions have bottom type and retain their real
  return/jump behavior. C3014 labels a non-bool while condition.
- Evaluate the condition before the first body and anew after every normally
  completed or continued body. False exits immediately. True enters the body.
  There is no implicit counter, update or range progress guard. A condition that
  remains true may intentionally loop indefinitely.
- Continue jumps to this loop's condition; break skips the rest of the body and
  the next condition test. Nearest lexical body resolution, deferred/function
  boundaries, and C3069 are shared with [loop jumps](loop-control.md).
- The condition resolves in the surrounding scope and jump context. A body has
  its own lexical scope, including for an unbraced declaration. Its locals do not
  enter the condition or escape after the loop. Writes to outer bindings persist.
  A jump inside a nested condition targets its enclosing body, not the while
  whose condition is currently being evaluated.
- Check skipped bodies and unreachable statements normally and stop on the first
  detected error. Runtime condition failures stop before the body or later effects.
  Returns in conditions/bodies exit the actual function or deferred block.

## Flow and storage

- ControlFlowModel includes a possible zero-trip exit for unknown/false
  conditions. Simple syntax-proven true conditions exit only through reachable
  body breaks. A true loop without reachable breaks cannot fall through, so it
  can close a non-void function through returns or intentional nontermination.
  False-loop bodies contribute no runtime returns/jumps. More complex constant
  arithmetic/comparison conditions are not promoted to termination proofs.
- NullFlowAnalysis scans both repeated condition and body writes, including
  transitive deferred captures and nested loops, before publishing condition
  proofs. The condition's true facts enter the body; false facts and reachable
  break facts intersect at the exit. Continue/body writes cannot leave an
  optimistic first-iteration proof at the next condition occurrence. This is a
  conservative analysis, not a precision-maximizing fixed point.
- MetadataAnalysis forgets visible mutable represented-type facts before the
  repeated condition and after the loop. Immutable facts and body-local facts
  remain available. A body's assignments cannot make later condition metadata
  optimistically constant.
- EmitWhileLoop builds a condition test, body and exit using the shared
  EmitExpression.emitLoopBody destinations and crossed-region cleanup. A
  non-fallthrough semantic exit is LLVM-unreachable, preserving return contracts.
- Safe conditions reclaim their arena suffix and dynamic literal stack storage
  after producing the scalar bool, including on the final false test. Safe bodies
  reclaim iteration regions on fallthrough/continue/break. Proven whole-loop
  regions finish on ordinary exits. Escaping buffers and unknown effects keep
  frame lifetime; outgoing buffered returns copy before frame cleanup.
- ArrayTemporaryAnalysis checks condition and body effects independently, so
  one safe region can reclaim even when another region publishes buffers.
  Conditions that publish captured arrays keep those values alive through the
  body and the final false test. No class destruction or GC is introduced.

## Source and verification

Source owners: WhileStatement.kt, Parser.parseWhile/parseLoopBody,
DeclarationAnalyzer and DeclarationModel body scopes, ExpressionTyping.checkWhile,
the function/snippet checkers, ControlFlowModel, NullFlowAnalysis,
MetadataAnalysis, EmitWhileLoop and ArrayTemporaryAnalysis. All share LoopTarget
identities and the existing jump implementation. No while-specific semantic plan
or target-dependent receiving binding is needed.

Maintained example: [While.co](../../../../examples/While.co).
[WhileTest](../../../../Compiler/src/test/kotlin/cloth/WhileTest.kt) adds 20 tests
for syntax/spans, strict conditions, scope/errors, completion, nullable and
metadata facts, captured writes, jumps, snippets, effects and failures.
The example executes on Windows x64/x86; one shared AST emits verified IR and
nonempty objects across all nine supported ABI profiles.
[ArrayTemporaryCleanupTest](../../../../Compiler/src/test/kotlin/cloth/ArrayTemporaryCleanupTest.kt)
adds three poisoned-free/live-byte/peak probes for 20,001 condition tests and
20,000 bodies (including continues), captured condition publication, break
publication and buffered returns. Native Linux/macOS/Windows ARM64 execution is
untested. Final commands/results are in the
[development milestone](../../cloth-language/references/development.md#while-loops-2026-11-02).

[Do-while](do-while-loops.md) Stages 0-5 integrate syntax, scopes, strict
conditions, jumps, first-entry flow, execution and proven cleanup. Its emitter
reuses condition and jump helpers, with native Windows x64/x86 and all-profile
LLVM/object evidence, a maintained example and full-suite publication. Read the
[plan](../../../../todo/do-while-loops-plan.md) before
extending these helpers. Labels and nonlocal jumps remain deferred.
