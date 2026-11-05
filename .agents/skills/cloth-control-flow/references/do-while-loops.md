# Do-while loops: accepted design (2026-11-03)

Status: Stages 0-5 integrate syntax, scopes, strict conditions, jump resolution,
completion, nullable/metadata flow, execution, proven cleanup and publication.
The agreed statement-loop slice is complete; see the
[implementation plan](../../../../todo/do-while-loops-plan.md).

The user approved this design in chat and requested staged implementation.
This reference owns the confirmed language rules; the plan owns staging, source
routing and the verification matrix. Stages 1-5 test parsing, semantic checking,
flow, native execution, cleanup, cross-profile LLVM/object emission and the
maintained [DoWhile.co](../../../../examples/DoWhile.co) example.

## Confirmed syntax and body ownership

```cloth
def attempts = 0
do {
    attempts++
    println(attempts)
} while (attempts < 3)
```

- Use `do body while (condition)`. The trailing keyword, condition and condition
  parentheses are required. The loop is a statement with no implicit result,
  output or enclosing return.
- A body is a brace-delimited statement list or one supported source statement,
  using the existing loop-body model. A broadcast definition counts as one
  source statement even when it normalizes into several AST nodes.
- An unbraced body may be a declaration, assignment, update, call, conditional,
  nested loop, return, break or continue under the ordinary statement rules.
  Multiple statements require braces. An empty body requires `{}`; a bare
  semicolon is not a body.
- Newlines and comments may separate `do`, its body and the trailing `while`.
  They do not close an unfinished do-while statement.
- Parse the complete body before consuming its required trailer. An inner while
  loop owns its own condition and body; each nested do-while owns its trailer.
  Existing nearest-if ownership of else remains unchanged.

```cloth
do attempts++ while (attempts < 3)

do
    attempts++
while (attempts < 3)

do {
    do work() while (innerCondition)
} while (outerCondition)

do
    while (ready()) work()
while (repeat())
```

These fragments assume the referenced functions and surrounding bindings exist.
The schematic grammar is `"do" loop-body "while" "(" expression ")"`, with
ordinary terminators owned by the boundaries described below.

## Confirmed semicolon boundaries

- An optional ordinary terminator may follow the complete loop's final `)`.
- An unbraced simple-statement body keeps its optional ordinary terminator. That
  semicolon terminates the body statement, not the enclosing do-while statement.
- No semicolon may intervene between a braced body and its trailing while.
- Do not allow repeated trailer separators, semicolons inside the condition,
  arbitrary expression whitespace or a semicolon as an empty body.
- Preserve the existing prohibition on a semicolon before else, including in
  an unbraced conditional used as the body.

```cloth
do attempts++ while (attempts < 3)
do attempts++; while (attempts < 3);
do { attempts++ } while (attempts < 3);

// Invalid: intervening semicolon after a braced body.
do { attempts++ }; while (attempts < 3)

// Invalid: a bare semicolon is not a body.
do; while (false)
```

Keep newline skipping distinct from statement-separator consumption when finding
the trailer. Nested compound bodies retain their contained statements' terminator
ownership. Cover unbraced if/for/while/do combinations during parser integration;
the body's final token alone cannot establish which terminator is legal.
Recognize a same-line bare return before a do trailer in the appropriate parsing
context without globally treating unrelated while keywords as return boundaries.

## Confirmed execution, conditions and scope

- Enter the body immediately on loop entry. On normal completion, evaluate the
  condition once; true repeats the body and false exits. Reevaluate on subsequent
  tests with ordinary left-to-right effects and boolean short circuits.
- The first example prints 1, 2 and 3. Even a false first condition follows the
  first body entry; break, return or a runtime failure in that body can bypass
  the condition altogether.
- Require one bool result, without truthiness or implicit nullable unwrapping.
  Existing presence proofs and fallback expressions may supply bool. Preserve
  non-continuing condition expressions and their actual control-flow outcomes.
- A continue targeting this loop proceeds to its trailing condition. A break
  targeting this loop exits without evaluating that condition. Return exits the
  actual enclosing function or deferred block. Runtime failure stops immediately.
- Reuse nearest lexical loop-body jump resolution and function/deferred-block
  boundaries from [shared loop control](loop-control.md). No labels, jump values
  or nonlocal jump syntax are added.
- Each body has its own scope, including when unbraced. Body locals are invisible
  to the condition and after the loop. Writes to surrounding bindings persist.
- The condition resolves in the surrounding scope and jump context, consistently
  with existing loop headers. A jump in a condition expression may target an
  enclosing body, never this do-while loop's already-completed body.
- Check unreachable statements and conditions normally. An early break or return
  does not excuse an invalid trailing condition or unreachable suffix. Stop on
  the first detected error with one diagnostic and any related source labels;
  add no diagnostic recovery or accumulation.
- Add no implicit counter/update, range overflow exit, finite/progress guard or
  termination guarantee. Body arithmetic retains its ordinary behavior.

```cloth
do {
    def again = shouldRepeat()
} while (again) // Invalid: body-local name is outside its scope.
```

## Required flow and lifetime integration

Stage 3 implements completion and dataflow below. Stage 4 implements execution
and proven lifetime reclamation; unproven regions retain frame lifetime.

- Represent guaranteed first entry directly. Do not inherit while's possible
  zero-trip completion or duplicate source-body analysis/emission as a lowering.
  An always-returning body may satisfy a non-void function with an unknown or
  false condition; an initial false test cannot erase body effects.
- Only normal body completion and reachable continues reach the condition.
  Breaks and returns bypass it. Ordinary exits are reachable false tests and
  breaks. A proven-true loop without reachable breaks cannot fall through.
- Consume jumps resolved to this loop, preserving condition jumps to outer bodies
  and real returns from either body or condition.
- Account for repeated body and condition writes, including transitive deferred
  captures and nested loops. The first body has no preceding true-condition
  proof. Prevent first-entry presence facts becoming unsound on backedges.
- Join condition inputs from body fallthrough and reachable continue paths;
  join false-test and reachable-break exit facts. Breaks do not establish false
  condition facts. Body assignments establish facts only if every relevant path
  performs them.
- Keep represented-type metadata conservative across repeated mutable writes
  and continue joins, while retaining valid immutable/local facts. No
  precision-maximizing fixed point is required.
- Reuse independent body, condition and whole-loop reclamation proofs. Release
  safe iteration storage before testing, including on continue. Reclaim safe
  condition temporaries after producing bool, including the final false test.
- Release crossed proven regions on breaks and outward condition jumps. Copy
  outgoing buffered returns before cleanup. Published buffers and unknown effects
  retain existing frame lifetimes. Add no class destruction, GC or universal
  bounded-memory guarantee.

## Stage 0 scope and verification

Stage 0 added this reference, skill/semantics routing, related loop and semicolon
guidance, plan status and a dated publication record. At that stage `Keywords.DO`
was reserved but `Parser.parseStatement` had no do dispatch. No do-while AST,
typing, flow analysis, emitter, runnable example or dedicated suite existed yet.

Stage 0 checks cover skill metadata, local links and documentation consistency.
Compiler tests, native execution and cross-target emission were not run for
Stage 0. The
[development record](../../cloth-language/references/development.md#do-while-stage-0-accepted-contract-publication-2026-11-03)
records the observed documentation checks and any validator limitation.
The plan's later stages retain the source audit, focused suites, cleanup probes
and portability requirements. Loop expressions/results, loop else clauses,
condition declarations, labels and nonlocal jump syntax are outside this plan.

## Stage 1: located syntax and parser boundaries (2026-11-03)

Stage 1 implemented parsing only; declaration/condition/jump checking was added
in Stage 2, completion/nullable/metadata joins in Stage 3 and emission in Stage 4
below. Parsing alone does not establish semantic validity.
Stage 5 below supplies the maintained runnable example.

- [DoWhileStatement.kt](../../../../Compiler/src/main/kotlin/cloth/parser/statement/DoWhileStatement.kt)
  implements LoopStatement with its own LoopTarget. It retains both keyword
  tokens, the shared LoopBody, condition parentheses and original expression.
  The condition span covers trailing while through final `)`; the statement
  span covers do through final `)`, excluding the complete-loop terminator.
- [DoWhileLoopParser.kt](../../../../Compiler/src/main/kotlin/cloth/parser/subs/loops/DoWhileLoopParser.kt)
  parses the complete body before requiring the trailer. Existing for/while
  body parsing and conditional branches share `Parser.parseUnbracedBody`.
- The explicit `allowWhileTrailer` parameter follows unbraced for/while/if bodies
  and statement-expression tails, including assignments, returns and binding
  initializers. Blocks, deferred blocks, header/test expressions and nested
  delimiters use the ordinary default context. An unrelated while never becomes
  a global bare-return boundary.
- A simple body may consume one terminator only when its next token, looking
  across newlines, is the required while. Nested compound bodies preserve the
  leaf's ownership. Braced-body separators and semicolons before else remain
  errors. A parenthesized conditional is an expression statement and keeps its
  ordinary terminator even though grouping does not add an AST node.
  `Parser.previousStatementEnd` keeps consumed terminators/newlines out
  of enclosing branch, body, binding and return spans.
- Syntax failures reuse existing located diagnostics and abort at the first
  error. The AST keeps the source body intact rather than lowering or copying it.

[DoWhileParsingTest](../../../../Compiler/src/test/kotlin/cloth/DoWhileParsingTest.kt)
adds 20 parser regressions for tokens/spans, CRLF/comments, all supported body
forms, broadcast deferred definitions, separate terminator owners, nested loops
and else attachment, bare returns/context boundaries, ordinary expression
precedence, snippets/deferred blocks and first-error locations. Parsing a numeric
condition is deliberately covered; bool validation belongs to Stage 2.

The final focused run passes 172 tests across ten suites, including the 20 new
parser tests, with no failures, errors or skips. Parsing is target-independent; no do-while native
execution, LLVM verification or object emission has been tested. Exact commands
and publication checks are in the
[Stage 1 development record](../../cloth-language/references/development.md#do-while-stage-1-located-syntax-and-parser-boundaries-2026-11-03).

## Stage 2: scopes, types and jump resolution (2026-11-03)

- [DeclarationAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/DeclarationAnalyzer.kt)
  resolves the body in a child lexical scope with this loop's target active.
  It then removes that target and resolves the condition in the surrounding
  scope. Body locals cannot leak into the condition or following code; shadowed
  outer names remain available. Existing function/deferred boundaries reset
  jump context. Earlier-parameter read-only/default rules remain enforced.
- [ExpressionTyping.checkDoWhile](../../../../Compiler/src/main/kotlin/cloth/semantic/ExpressionTyping.kt)
  checks the body first, leaves its scope/target, checks a scalar bool or valid
  non-continuing condition, and records ResultShape.Empty. The trailing test
  reuses C3014 with Keywords.WHILE; void/multiple results keep their existing
  diagnostics. Unreachable source remains checked, and traversal stops on the
  first detected error. Existing separate compiler passes retain their order.
- [CallAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/CallAnalyzer.kt)
  dispatches do-while from ordinary/deferred functions, defaults and overload
  trials. A probe uses ordinary non-probe loop checking so intrinsic body/test
  diagnostics cannot become a generic candidate mismatch. Generic loop-target
  maps and statement results survive existing semantic forks/commits.
- [SemanticAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/SemanticAnalyzer.kt)
  reuses the checker and generic body scope/target hook for legacy snippets.
  It retains the snippet API's existing nullable/deferred-form boundaries.
- Declaration, call and function analysis all accept the Stage 2 slice. Stage 2
  added semantic checking only; Stage 4 below adds emission. A maintained runnable
  example follows in Stage 5 below.

### Conservative flow bridge and remaining Stage 3 work

The pipeline runs flow visitors around typing. Stage 2 supplied this temporary
bridge, replaced by the Stage 3 implementation below:

- ControlFlowModel visited body then condition, consumed body-local jumps and
  preserved outer condition jumps, but always permitted ordinary fallthrough.
  It did not yet establish total-return or intentional-nontermination proofs.
- NullFlowAnalysis scanned repeated body/test writes and transitive deferred
  captures, checked against stable entry facts and conservatively returned those
  facts. Body-established condition proofs and continue/false/break joins were
  not implemented. The recursive write scanner already included nested do-while.
- MetadataAnalysis visited body before test but forgot mutable represented-type
  facts before the body, before the test and after the loop. It retained valid
  immutable/local facts without path joins.

[DoWhileSemanticsTest](../../../../Compiler/src/test/kotlin/cloth/DoWhileSemanticsTest.kt)
adds 19 tests for the semantic entry points, scopes/shadowing, outer writes,
scalar/nullable/bottom conditions, first-error order, unreachable checking,
return contracts, nested/header jump targets, deferred boundaries, default
arguments, overload diagnostics/commits, snippets, immutable metadata capability
checks and stale nullable facts.

The final focused run passes 336 tests across 23 suites with zero
failures/errors/skips. This includes declaration/call/default/return suites,
for/while/jump regressions and nullable/provider suites. Semantic verification
ran on the Windows host; no do-while native execution, LLVM IR or object emission
has been tested. Commands, publication checks and validator limits are recorded
in the [Stage 2 development record](../../cloth-language/references/development.md#do-while-stage-2-scopes-types-and-jump-resolution-2026-11-03).

## Stage 3: completion, nullable and metadata flow (2026-11-03)

### Completion and backedge reachability

[ControlFlowModel](../../../../Compiler/src/main/kotlin/cloth/semantic/ControlFlowModel.kt)
models unconditional first body entry. Only body fallthrough or reachable body
continues reach the condition. Ordinary exits are reachable body breaks and
reachable completing tests that may be false. A syntax-proven true test without
reachable breaks cannot fall through. Returns belong to the real function or
deferred block; unreachable tests contribute no runtime returns or outer jumps.
The body consumes its own jumps, while reached condition jumps propagate to the
surrounding body. Existing nearest-body resolution establishes those owners.

An always-returning body can now complete a non-void function even with an unknown
or false test. Non-continuing loop branches supply no conditional result. Skipped
tests and unreachable suffixes still receive ordinary semantic checking.
`canRepeat` requires a reached, completing test that may be true. A false,
unreachable or non-continuing test rules out this loop's backedge. Proofs remain
limited to existing simple boolean syntax, not arbitrary constant folding or
general termination analysis.

### Nullable and represented-type path joins

[NullFlowAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/NullFlowAnalysis.kt)
invalidates repeated body/test writes before body checking when a backedge is
possible, including nested loops and transitive deferred captures. A first body
never receives the trailing test's true facts. Provably nonrepeating bodies may
use incoming facts before changing them. Normal completion and each reachable
continue supply the condition's intersected input facts; unreachable suffix
assignments cannot establish those facts. Body assignments and body-local guards
can reestablish presence on every relevant path.

Normal exit facts intersect reachable false-test and body-break facts without
a zero-trip competitor. Breaks bypass the test and acquire no false-test proof.
An unreachable test is still analyzed for source checking, but supplies no exit
facts. Nested condition jumps carry the facts captured at the jump to their
enclosing body's join. Returned paths contribute no normal exit facts.

[MetadataAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/MetadataAnalysis.kt)
forgets visible mutable represented-type facts before a body that may repeat,
then joins body-fallthrough and continue snapshots for its test. It joins only
reachable break and false-test snapshots at the exit. Disagreement means unknown,
retaining the provider's checked runtime recipe for future emission. Proven
nonrepeating bodies keep incoming facts; immutable and body-local/shadowed facts
retain their existing scope rules. Known unsupported metadata capabilities still
produce located C3048. Unreachable jump suffixes cannot replace the jump snapshot.
These analyses are conservative and do not compute a precision-maximizing fixed
point or import later-iteration facts into first entry.

### Verification and remaining scope

[DoWhileFlowTest](../../../../Compiler/src/test/kotlin/cloth/DoWhileFlowTest.kt)
adds 27 semantic regressions for first entry, completion, test reachability,
outward jumps, deferred returns, non-continuing value branches, nullable joins,
captured writes, nested exits, metadata capability checks and jump snapshots.
The final affected run passes 387 tests across 26 suites, zero
failures/errors/skips. Exact commands and documentation checks are in the
[Stage 3 development record](../../cloth-language/references/development.md#do-while-stage-3-completion-nullable-and-metadata-flow-2026-11-03).
This stage established host-run semantic/flow verification. Stage 4 below adds
execution, cleanup probes, LLVM IR and objects; Stage 5 adds the maintained example.

## Stage 4: execution and proven cleanup (2026-11-03)

### Execution and source ownership

[EmitDoWhileLoop](../../../../Compiler/src/main/kotlin/cloth/emitter/types/loops/EmitDoWhileLoop.kt)
emits entry -> body -> test -> body/exit without duplicating the source body.
Continue targets the trailing test; break targets exit. The shared body helper
removes this loop's target before emitting the condition, retaining surrounding
targets for outward condition jumps. Returning/breaking bodies skip the test.
Unreached test blocks and semantically non-fallthrough exits receive unreachable
terminators, including when a reached condition itself returns or jumps outward.

[EmitStatement](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitStatement.kt)
dispatches the statement without a value. The
[EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
entry uses existing iteration, condition, whole-loop and crossed-region helpers.
Conditions are reevaluated after every normally completed or continued body;
effects, short circuits, located runtime failures and actual return contexts
retain ordinary behavior. Native nullable payload and metadata-provider reads
use the facts and checked recipes established by Stage 3.

### Proven cleanup and retained publication

[ArrayTemporaryAnalysis](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/ArrayTemporaryAnalysis.kt)
recognizes nested do-while statements in iteration proofs and adds a whole-loop
proof over body plus condition. Body and test regions are independently proven:
safe bodies release storage before testing, including on continue, and safe
tests release storage after producing bool, including the final false test.
Cleanup reclaims the arena suffix before restoring dynamic stack storage.
Breaks and outward test jumps release crossed proven regions. Ordinary returns
copy outgoing buffered values before frame cleanup.

Captured buffer publication and unknown effects retain frame lifetime; an unsafe
test does not prevent independently safe body cleanup, or vice versa. Inline
wide-integer stores use the existing closed proof and stack checkpoints. No
universal bounded-storage guarantee or class destruction is added.

[ArrayTemporaryCleanupTest](../../../../Compiler/src/test/kotlin/cloth/ArrayTemporaryCleanupTest.kt)
adds six do-while probes using poisoned frees, allocation counts and live/peak
bytes. Every probe ends with zero outstanding allocations/live bytes:

- 20,000 bodies/tests with alternating continues perform 30,000 allocations and
  peak at 264 live bytes, with zero live bytes at each body start and loop exit.
- Separate captured condition-publication and body-publication probes preserve
  saved arrays while reclaiming the independently safe region, including the
  final false test and later discarded calls.
- Buffered string-array returns survive poisoned callee frames, a body break
  and a later call; nested Unicode string storage remains valid.
- Outward condition continues across 2,000 enclosing range iterations, plus an
  outward break, perform 4,002 allocations with a 264-byte peak and no retained
  bytes at each outer iteration or final exit.
- A 2,000-iteration `int<65536>` body/test probe with continues exercises stack
  restoration while preserving a pre-loop array. It performs one allocation,
  peaks at 24 live arena bytes and retains exactly those 24 bytes until function
  cleanup. This measures arena usage, not an exact stack peak.

### Verification and remaining scope

[DoWhileTest](../../../../Compiler/src/test/kotlin/cloth/DoWhileTest.kt) adds 12
execution/IR regressions: first-entry/effect order, skipped failing tests,
body/test/deferred returns, non-fallthrough blocks, nested nearest targets,
outward condition jumps, nullable/metadata facts, buffer publication/returns,
located runtime failures and silent loop statements in snippets.

The final affected run passes 284 tests across 14 suites, with zero failures,
errors or skips. The shared fixture executes on Windows x64 and x86. A nested
do/foreach fixture verifies LLVM IR and emits nonempty objects for all nine
profiles: `i686-pc-windows-msvc`, `x86_64-pc-windows-msvc`,
`aarch64-pc-windows-msvc`, `x86_64-unknown-linux-gnu`,
`aarch64-unknown-linux-gnu`, `x86_64-unknown-linux-musl`,
`aarch64-unknown-linux-musl`, `x86_64-apple-darwin` and
`aarch64-apple-darwin`. Windows ARM64, Linux and macOS native execution was not
tested. Exact commands and publication checks are in the
[Stage 4 development record](../../cloth-language/references/development.md#do-while-stage-4-execution-and-proven-cleanup-2026-11-03).
Stage 5 below adds the maintained example and full compiler-suite verification.

## Stage 5: integration, portability and publication (2026-11-03)

The maintained [DoWhile.co](../../../../examples/DoWhile.co) example executes
first entry with a false test, repeated effectful tests, a single-statement body
with both optional semicolons, continue-to-test and break-without-test behavior.
It checks its results through main's exit status and prints:

```text
first=1
repeated=3, tests=3
single=2
visited=5, sum=8, tests=4
```

Ordinary process-completion output follows. `DoWhileTest` reads this maintained
source and asserts exact output and zero status on both Windows x64/MSVC and
x86/MSVC. The same parsed example and existing nested do/foreach fixture each
verify LLVM IR and emit nonempty objects across the nine profiles listed in
Stage 4. This checks main, strings/interpolation and a captured condition as
well as nested jumps, arrays and target reuse. The suite now has 13 tests.

The focused affected run passes 285 tests across 14 suites with zero
failures/errors/skips. The full compiler suite passes 1,184 tests across 91
suites, zero failures/errors/skips. Exact commands and publication checks are
recorded in the
[Stage 5 development record](../../cloth-language/references/development.md#do-while-stage-5-integration-portability-and-publication-2026-11-03).
Native execution is confirmed on Windows x64/x86 only; Windows ARM64, Linux and
macOS native do-while execution remains untested. Foreign IR/objects do not
establish native execution. Unknown/captured buffer publication keeps frame
lifetime; arbitrary loops have no bounded-storage guarantee.

The accepted slice is complete. Loop expressions/results, loop else clauses,
condition declarations, labels, nonlocal jump syntax and class destruction
remain outside this feature, as specified in the plan.
