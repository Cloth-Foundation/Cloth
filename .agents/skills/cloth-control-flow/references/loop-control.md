# Loop jumps and reusable loop control (2026-11-02)

The user approved implementing break/continue with shared completion and cleanup
infrastructure for existing for loops and future while/do-while loops. This
record owns that extension; the original for-loop stages remain historical.

## Supported contract

```cloth
for (def i <- 0..10) {
    if (i == 2) { continue }
    if (i == 5) { break }
    println(i)
}
for (def value <- [1, 2, 3]) {
    if (value == 2) { continue }
    println(value)
}
```

- `break` and `continue` are bare statements, with optional ordinary semicolons,
  in braced or single-statement bodies. They take no value, label or parentheses.
- Jumps resolve lexically to the nearest enclosing loop **body**. Break skips
  the remaining body and header update, then resumes after that loop. Continue
  skips the remaining body, executes the range's default/explicit update once
  or advances the foreach snapshot index once, then tests the next iteration.
- Range update overflow exits, finite/progress guards, ordinary RHS effects and
  failures are unchanged. A break does not execute a skipped update just to
  validate it. Existing known-invalid source diagnostics still apply.
- Statements after a jump and statically skipped branches are still checked.
  Jumping paths supply no value to a conditional result and never become phi
  predecessors at that conditional's merge. They do not count as function returns.
- A function/deferred block establishes a new jump boundary. A block declared
  inside a loop cannot jump to that loop, even when emitted inline. Loops inside
  the block work normally. Invalid jumps fail with located **C3069** before
  LLVM/linker/output work, preserving first-error behavior.
- Header placement is an implementation rule: endpoints, collection sources
  and steppers execute in the surrounding loop-body context. They cannot target
  the loop whose header is being evaluated. A conditional inside a nested
  header may jump to an enclosing body; crossed cleanup regions must be released.
  A top-level header jump has no enclosing body and receives C3069.

Maintained runnable example: [LoopControl.co](../../../../examples/LoopControl.co).

## Reuse and ownership

- [LoopControl.kt](../../../../Compiler/src/main/kotlin/cloth/parser/statement/LoopControl.kt)
  owns backend-independent LoopTarget identities, the LoopStatement interface
  and located LoopJumpStatement syntax. ForStatement supplies one identity;
  future loop forms should implement the same interface.
- DeclarationAnalyzer resolves each jump once, entering targets only for bodies
  and resetting the target stack for deferred blocks. DeclarationModel retains
  these resolutions; SemanticModel exposes targetOf(jump). FunctionSemanticModel
  preserves resolutions across forks/commits. Legacy snippets use the generic
  ExpressionTyping.loopBody hook and the same target model.
- ControlFlow distinguishes canFallThrough, canReturn, breaks and continues.
  Sequential composition propagates subsequent effects only along fallthrough;
  alternatives union outcomes. Jump sets retain originating syntax, which maps
  to resolved target identities. A for loop consumes its body's own jumps,
  routes fallthrough/continues through its stepper, and preserves header jumps
  to outer bodies. Zero trips still prevent a total-return proof.
- NullFlowAnalysis records facts at each jump. Stepper input intersects body
  fallthrough and all reachable continue paths; unreachable suffix assignments
  cannot establish those facts. MetadataAnalysis similarly merges represented
  types. Post-loop facts remain conservative, including zero trips and breaks;
  no precision-maximizing loop fixed point is claimed.
- EmitExpression.emitLoopBody registers a target, exit block, continue block
  and cleanup depth. emitLoopJump releases crossed proven regions in reverse
  nesting order and branches to the resolved destination. Loop-specific emitters
  own their tests, reception and updates. Separate range/foreach update blocks
  receive both ordinary completion and continues; foreach index phi inputs use
  the actual update predecessor. No-predecessor update blocks are unreachable.
- Proven arena regions release their suffix before restoring dynamic stack
  storage. Continue keeps the whole-loop region, including the foreach snapshot;
  break reaches normal whole-loop cleanup. Header jumps can cross nested
  whole-loop, stepper and outer iteration regions. Expression cleanup checkpoints
  also participate. Unknown/buffer-publishing effects retain frame lifetime;
  recognizing jump syntax is not permission to reclaim escaped buffers.
- Return handling remains separate, including copying outgoing values before
  frame cleanup. This feature adds neither automatic class destruction nor GC.

[While loops](while-loops.md) now provide their condition-test block as the
continue destination, reusing targets, jump emission and crossed-region cleanup.
Future `do/while` should provide its trailing condition-test block and its own
guaranteed-first-body schedule/completion rules. Do/while syntax/execution, labels
and nonlocal jumps remain deferred.

## Verification

[LoopControlTest](../../../../Compiler/src/test/kotlin/cloth/LoopControlTest.kt)
covers syntax/spans, invalid placements, deferred boundaries, nearest targets,
completion/value branches, nullable/metadata joins, range update effects and
guards, snapshot independence, expression/header jumps, snippets and CLI failure
before output. The maintained example executes on Windows x64/x86; one shared AST
emits verified IR and nonempty objects across all nine supported ABI profiles.

[ArrayTemporaryCleanupTest](../../../../Compiler/src/test/kotlin/cloth/ArrayTemporaryCleanupTest.kt)
adds poisoned-free/live-byte/peak probes for 20,000 continues with literal stack
storage, row/snapshot lifetime on continue and break, nested header cleanup and
retained published buffers. Native Linux/macOS/Windows ARM64 execution is untested.

Commands and final observed counts are recorded in the
[development milestone](../../cloth-language/references/development.md#reusable-loop-jumps-2026-11-02).
