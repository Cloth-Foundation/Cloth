# For-loop implementation stages

The subsequent [loop-control extension](loop-control.md) integrates break/continue
and shared completion/cleanup infrastructure. Counts and deferred boundaries in
the original stages below describe those historical milestones.

## Stage 5: regression, portability and feature publication (2026-11-02)

Stages 0-5 are complete for the agreed numeric range/array foreach slice. The
final compiler suite passes all 936 tests, with zero failures/errors/skips.
Maintained ordinary-function examples, native Windows x64/x86 execution and
all-profile IR/object coverage complete publication. Historical stages remain below.

### Runnable examples and publication

- [ForRanges.co](../../../../examples/ForRanges.co) demonstrates nonzero saved
  endpoints, default/inclusive/descending steps, direction changes and unsigned
  header overflow completion.
- [FloatRanges.co](../../../../examples/FloatRanges.co) demonstrates all float
  formats, default one, fractional/descending steps, and an inclusive endpoint
  skipped by ordinary accumulated float32 rounding.
- [ForEach.co](../../../../examples/ForEach.co) demonstrates nested independent
  rows, original-source mutation, saved row values, string reception and empty
  row shapes. Each example checks results and returns zero; output is asserted
  by integration tests. Test.co retains intended class/state design, and Arr.co's
  local commented demonstration is preserved.
- [README](../../../../README.md#for-loops) publishes syntax and user-facing
  behavior. Control-flow owns the confirmed loop contract and implementation
  record; numeric, array, semicolon and buffer skills route the interactions.
  No separate competing loop skill is necessary.

### Portability and verification

[ForIntegrationTest](../../../../Compiler/src/test/kotlin/cloth/ForIntegrationTest.kt)
adds six tests. The three published examples execute on the Windows x64 host
and as verified PE32/i686 programs. Native x86 checks all integer counter domains,
including dynamic wide multiplication overflow, and finite/progress failures
with preserved effects and locations. CLI object mode accepts explicit loop
example paths even with an unavailable linker override.

One canonical library AST with usize ranges, nested foreach, half-float ranges
and dynamic uint64 multiplication emits verified IR and nonempty objects for
all nine supported profiles: Windows MSVC i686/x86_64/aarch64; Linux GNU and musl
on x86_64/aarch64; macOS on x86_64/aarch64. This verifies target selection and
code generation, not foreign linking or execution. Native Linux/macOS and
Windows ARM64 loop execution were not run; unsupported 32-bit Unix profiles
remain rejected.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.ForIntegrationTest --tests cloth.ForEachTest --tests cloth.ForRangeTest --tests cloth.ForStageGateTest --tests cloth.ForSemanticsTest --tests cloth.ForFlowTest --tests cloth.ForParsingTest --tests cloth.ArrayTemporaryCleanupTest --tests cloth.ArrayCopyElisionTest --tests cloth.ArrayAssignmentTest --tests cloth.ArrayBindingShapeTest --tests cloth.RectangularArraysTest --tests cloth.StringFunctionTest --tests cloth.ArrayTextTest --tests cloth.NullabilityRulesTest --tests cloth.TypeInfoTest --tests cloth.WindowsX86Test --tests cloth.TargetLayoutTest --tests cloth.NumericWidthsTest --tests cloth.UnsignedIntegersTest --tests cloth.SemicolonsTest --tests cloth.DiagnosticsTest --offline --console=plain --no-daemon
```

Observed: **305 tests passed**, zero failures/errors/skips, including 112 loop
tests and 27 cleanup tests. The final complete run also passed:

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Observed: **936 tests passed**, zero failures/errors/skips, including all six
Stage 5 integration tests and all five WindowsX86Test cases without skips.

The dedicated validator passed for all ten changed skills using cached PyYAML
6.0.3 in a temporary workspace-only target; no global Python installation was
changed. All 325 relative links across 17 changed documentation records resolve,
including README and the ignored plan. Git/new-file whitespace checks pass.
The verified temporary dependency directory was removed after validation; tmp/
is empty. Repeating dedicated validation requires supplying PyYAML to its runtime.

### Completion boundary

The agreed slice includes numeric ranges and supported array foreach, lexical
scopes/inference/repeated flow, all admitted counter updates, finite/progress/
overflow behavior, independent buffered reception and conservative cleanup.
General iterators, string collections, range values outside headers, character
ranges, break/continue, class/method execution and nullable buffers remain outside
this completed slice. Buffered/indexed writes, captured publication and unknown
effects retain frame lifetime; bounded storage is established only for proven
regions/workloads. There is no GC, reference counting or changed object destruction.

## Stage 4: array foreach and lifetime integration (2026-11-02)

At this milestone all 930 compiler tests passed. Array foreach
executed alongside numeric ranges. The C3067 execution gate
is retired; its diagnostic enum entry remains reserved. Prior stages below are
historical milestones. Stage 5 above owns final publication and portability evidence.

### Snapshot and element execution

```cloth
for (def<int[]> row <- matrix) {
    for (def value <- row) println(value)
}
```

- [EmitForEach](../../../../Compiler/src/main/kotlin/cloth/emitter/types/loops/EmitForEach.kt)
  evaluates its source once before introducing the receiver, then captures an
  independent recursive contents/shape value through transferArrayValue. A fresh
  owned result transfers once; a stored source is copied even when immutable.
  Original element writes or whole-array replacement cannot affect traversal.
  Reentry captures anew, and an early source return prevents capture/body work.
- The saved count and hidden phi index use target usize. Test unsigned `index <
  count`, receive the element, complete the body, increment, and join from the
  actual final LLVM block. The final increment can reach the count without
  overflowing usize. Empty sources perform no element access, including zero
  outer counts with explicit/unknown inner shape metadata.
- Receive through existing checked indexing/child-shape validation and ordinary
  type adaptation. snapshotValue recursively copies array rows and string bytes;
  each mutable receiver is initialized anew inside its iteration scope. Stored
  row values retain their own initial shape and ordinary assignment constraints.
  Binding slots stay in function entry. Body writes/rebinding affect neither
  snapshot nor original; copying a row into an outer binding preserves its value
  across later receiver writes and later iterations.
- Scalars include all supported numeric identities/formats, bool, char and
  typeinfo; buffered elements include strings and rectangular nested arrays.
  Nullable buffers/elements remain under existing C3060 support gates. Header
  steppers still belong only to numeric ranges. No iterator protocol, string-as-
  collection, while/do/break/continue or new object-copy semantics is introduced.
- Existing return frames copy outgoing buffered results before releasing owning
  storage. Source/body returns retain function/deferred-block ownership; completed
  traversal has no result. Nested conditionals, loops, guards and deferred sources
  preserve evaluation order and actual predecessors. ForStageGateTest now checks
  successful library/executable/object/snippet emission and ordinary diagnostic
  priority, rather than expecting valid foreach to fail.

### Conservative loop cleanup

- [ArrayTemporaryAnalysis](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/ArrayTemporaryAnalysis.kt)
  adds closed proofs for iteration bodies, numeric step expressions, and whole
  loops. Permit local definitions with safe initializers, safe scalar stores and
  updates, supported safe conditional branches, returns and nested loops. Deferred
  block declarations themselves execute no work, but reading one still inspects
  its transitive captured effects through the existing proof.
- Buffered assignments (including local rebinding), indexed writes, buffered
  multi-assignment targets, built-in calls and unrecognized/captured effects
  conservatively disable the corresponding region. Ordinary source-function
  calls with safe arguments can be proven safe under existing value reception.
  Scalar source calls do not imply that captured blocks are safe. The old general
  expression proof remains conservative for statement-bearing blocks; these loop
  proofs do not turn every surrounding expression into a reclaimable region.
- EmitExpression.temporaryRegion takes an arena checkpoint and an
  [EmitStackScope](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitStackScope.kt)
  stacksave before a proven region. On normal completion, release the allocation
  suffix, then stackrestore dynamic literal storage, and discard fresh-ownership
  facts created inside it. Prior allocations and entry binding slots survive.
- Foreach iteration checkpoints precede element copying, so safe received row/
  string buffers are reclaimed after their last body use. Numeric bodies and safe
  explicit RHS expressions receive separate regions; source capture stays outside
  the body region. Whole-loop cleanup requires safe source/body/step effects and
  releases the saved snapshot and other new entry buffers after traversal.
- Return/fatal paths bypass normal region releases. Returns keep buffers until
  the existing frame or consuming outer expression can copy/release them. Integer
  update overflow can bypass its step region; the whole-loop or frame region
  retains responsibility. Buffered writes/captures retain frame lifetime and
  cannot be reclaimed merely because their lexical scope ended. There is no claim
  of bounded memory for arbitrary escaping loops, and no GC/reference counting.

### Stage 4 verification

[ForEachTest](../../../../Compiler/src/test/kotlin/cloth/ForEachTest.kt) adds 19
tests for snapshot independence, recursive mutable rows, once-per-entry sources,
all supported element families, empty shapes, scopes, conditional/deferred sources,
buffered returns, source failures, runtime shape constraints and snippets. One
canonical AST emits verified IR for i686 Windows MSVC, x86_64 Windows MSVC,
x86_64 Linux GNU and aarch64 macOS with target-sized counters and entry slots.

[ArrayTemporaryCleanupTest](../../../../Compiler/src/test/kotlin/cloth/ArrayTemporaryCleanupTest.kt)
adds 11 native allocation/poisoning probes for fresh transfer vs borrowed copying,
safe row/string reception, nested reentry, 20,000-iteration literal bodies/steppers,
saved values, source/transitive captured writes and early buffered returns.
Fresh scalar sources allocate once; borrowed captures add one snapshot. Literal
body/step probes bound peak requested arena storage to 264 bytes and restore stack
storage. Normal frame exits balance every tracked allocation, including fallback
cases. Native execution is Windows x64; foreign verified IR is not native evidence.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.ForEachTest --tests cloth.ForRangeTest --tests cloth.ForStageGateTest --tests cloth.ForSemanticsTest --tests cloth.ForFlowTest --tests cloth.ForParsingTest --tests cloth.ArrayTemporaryCleanupTest --tests cloth.ArrayCopyElisionTest --tests cloth.ArrayAssignmentTest --tests cloth.ArrayBindingShapeTest --tests cloth.RectangularArraysTest --tests cloth.StringFunctionTest --tests cloth.ArrayTextTest --tests cloth.NullabilityRulesTest --tests cloth.TypeInfoTest --offline --console=plain --no-daemon
```

Observed: **248 tests passed**, zero failures/errors/skips, including all 106 loop
tests and 27 temporary-cleanup tests. This stage adds 30 tests. The final full
compiler suite also passed:

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Observed: **930 tests passed**, zero failures/errors/skips. Direct checks validate
basic frontmatter and all 268 relative links across 14 changed records (eight
skills, references and the ignored plan); whitespace checks pass. The dedicated
skill validator still cannot import PyYAML; no dependency was installed.

## Stage 3: numeric range execution (2026-11-02)

At this milestone all 900 compiler tests passed and numeric ranges executed,
with foreach gated until Stage 4. The current execution/cleanup scope is above;
the numeric semantics and tests below remain relevant.

### Execution ownership and invariants

- [EmitRangeLoop](../../../../Compiler/src/main/kotlin/cloth/emitter/types/loops/EmitRangeLoop.kt)
  owns range execution, reached through EmitStatement and EmitExpression. Emit
  the starting endpoint, validate its finite value, then emit/validate the ending
  endpoint. Their SSA values dominate the loop and stay fixed on every backedge;
  early return/failure prevents later evaluations. Reentry evaluates both anew.
- EmitBindings gives the loop counter and body locals entry-block allocation in
  functions and snippets. Restore lexical identities after the body/loop while
  preserving outer stores. Body locals initialize on every executed iteration.
  Membership tests include the starting boundary and exclude/include the ending
  boundary as spelled. Explicit steppers admit descending bounds and direction
  changes; reversed intervals without a stepper stay empty. Equal endpoints and
  signed zero use ordinary comparison behavior.
- After a normally completed body, load the counter before evaluating the header
  RHS once. Default/postfix steps use one in the declared format; all eight
  accepted header forms execute. Return from the body or RHS skips the update or
  backedge, using the actual enclosing function/deferred-block return machinery.
  Nested expressions and runtime guards use their actual final insertion block.
- Integer `++`, `--`, `+=`, `-=`, `*=` calculate an exact double-width intermediate,
  narrow and reextend to detect overflow, then branch directly to the loop exit
  on overflow **before storing**. `/=` and `%=` reuse EmitIntegerDivision and its
  ordinary zero/signed-minimum failures. Plain `=` has no checked arithmetic
  operation; body and RHS arithmetic still wrap at their ordinary widths.
- Half/single/double counters retain their declared format after every update.
  Reuse EmitHalf/EmitCast for software half arithmetic and ordinary numeric RHS
  conversion. There is no wider persistent accumulator, snapping or tolerance.
  Exponent-bit guards reject nonfinite evaluated endpoints, supplied float step
  operands and resulting counters, even on a would-be empty/overshooting path.
  Do not execute a skipped stepper merely to validate it.
- Compare the pre-update and resulting counters; if equal **and** the resulting
  value is still a member, fail with `Range counter update made no progress`.
  Label explicit failures at the stepper and implicit failures at the header.
  Finite failures label the evaluated endpoint/operand or resulting update.
  Normal overshoot and checked integer overflow end traversal without errors.
- [RangeConstants](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/RangeConstants.kt)
  diagnoses closed nonfinite float endpoint/step expressions with C3068 during
  ExpressionTyping. It recognizes numeric literals/providers, signs, numeric
  casts and basic arithmetic, with ordinary per-operation format rounding and
  wrapping integer intermediates. Half decimal literals round directly from
  decimal, and wide integers convert directly to the working float format,
  avoiding false infinity from double rounding. Calls, bindings,
  conditionals, powers and unrecognized effects retain runtime guards; this is
  not general constant propagation. Integer failures keep their existing path.
- SemanticModel.requireExecutableLoops now filters array foreach only, preserving
  its located gate before LLVM/linker/output work, including skipped/deferred
  foreach. Source/type errors still precede that gate. No while/do/break/continue
  execution is introduced.

### Lifetime boundary

Existing frame arenas and recursive copy/return paths keep buffered values valid
through repeated bodies and early exits. The closed ArrayTemporaryAnalysis proof
still treats loop statements as unknown, and lexical exit does not justify new
reclamation. Existing independently proven expression cleanup remains available;
unproven allocations/replaced named buffers retain frame lifetime. Stage 3 does
not claim bounded memory for arbitrary loops. Foreach snapshots, row copies and
proven iteration cleanup remain Stage 4 work.

### Stage 3 verification

[ForRangeTest](../../../../Compiler/src/test/kotlin/cloth/ForRangeTest.kt) adds 32
tests: native traversal/effect order, all stepper forms, fixed-bound backtracking,
full byte domains and all integer extrema/identities, wrapping-vs-overflow exits,
float formats/subnormals/rounding, compile/runtime finite failures, progress spans,
nested scopes/returns, deferred blocks, buffered survival and snippets. Its IR
test verifies i686 Windows MSVC, x86_64 Windows MSVC and x86_64 Linux GNU modules
and checks that body-local allocation remains outside the backedge. Native range
execution uses the Windows x64 host; foreign IR is not native execution evidence.
ForStageGateTest's six cases now exercise foreach, and the parser test reads the
maintained traversal demonstration even while Arr.co keeps it commented.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.ForRangeTest --tests cloth.ForStageGateTest --tests cloth.ForParsingTest --tests cloth.ForSemanticsTest --tests cloth.ForFlowTest --tests cloth.NumericWidthsTest --tests cloth.AssignmentOperatorsTest --tests cloth.IntegerDivisionTest --tests cloth.FunctionReturnsTest --tests cloth.FunctionBlocksTest --tests cloth.NullabilityRulesTest --tests cloth.TypeInfoTest --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon
```

Observed: **203 tests passed**, zero failures/errors/skips, including 86 loop
tests. The additional wide-integer-to-float rounding regression also passed in
a focused run. The current loop suites contain 87 tests, including 32 new
execution tests. The final complete run passed:

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Observed: **900 tests passed**, zero failures/errors/skips. This includes the
final direct integer-to-float constant-rounding correction and regression.

The skill validator still cannot import PyYAML; direct checks validated basic
frontmatter and all 247 relative links across 12 changed records (including this
stage's seven skills and the ignored plan). Git/new-source whitespace checks
passed. No validation dependency was installed.

## Stage 2: names, types and repeated control flow (2026-11-02)

At this milestone, 105 focused and all 868 compiler tests passed. Stage 3 above
supersedes this milestone's execution boundary; the semantic invariants remain.

### Semantic scope and source ownership

- [LoopBindingSymbol](../../../../Compiler/src/main/kotlin/cloth/semantic/symbols/Symbols.kt)
  gives each reception its own mutable identity without fabricating a local
  initializer. [DeclarationAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationAnalyzer.kt)
  resolves endpoints/source in surrounding scope, then introduces the loop scope
  and a separate body scope. Header steppers cannot read body locals; source
  expressions and following code retain outer names. Unbraced declarations,
  nested loops, shadowing, duplicate names and default-parameter shadowing use
  existing lexical rules. DeclarationModel exposes loop binding/header/body scopes.
- [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)
  shares range/foreach validation between functions and legacy snippets. Infer
  one exact type from already-typed endpoints before defaulting contextual
  literals, independent of endpoint order, body and stepper. Preserve fixed
  widths, signedness and distinct target-sized integer identities. Float endpoint
  literals use their annotated/inferred format; already-typed values never convert
  to match. Both endpoints must produce a single compatible numeric value.
- Numeric counters are non-nullable; ordinary presence proofs/fallback permit
  nullable endpoint sources to supply a payload. Foreach requires an array and
  receives its element type/remaining rectangular shape. An explicit receiver
  supplies array-literal element context, including empty arrays; a typed source
  array stays exact. Ordinary mutable element/row assignments retain shape checks.
  Nullable array elements remain under their existing C3060 gate, even when a
  nullable foreach annotation would otherwise supply literal context.
- [ForLoopPlan](../../../../Compiler/src/main/kotlin/cloth/semantic/ForLoopPlan.kt)
  records the selected TargetLayout, receiving type, array source descriptor when
  available, and normalized stepper checking expression. Non-continuing sources
  need a receiving annotation when inference has no concrete type; their bottom
  result can satisfy a fixed annotation without inventing an actual value.
  Plans live in SemanticModel, not the canonical AST. FunctionSemanticModel
  fork/commit copies them with candidate facts. Original header operators remain
  in syntax; synthetic compound expressions are resolved once for checking.
- All eight stepper forms use ordinary assignment/arithmetic and division rules.
  Postfix/default steps need no fabricated initializer/expression. Bodies and
  steppers are checked even for empty/skipped loops. Intrinsic loop errors inside
  an overload argument stay fatal instead of becoming an unrelated no-match.
- The concurrent token migration reserves `for` as a keyword. Parser dispatch
  now uses keyword lookup; prefix and exact-token regression assertions reflect
  that classification. This does not implement while/do/break/continue behavior.

### Repeated flow and metadata

- [ControlFlowModel](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/ControlFlowModel.kt)
  composes endpoint/source completion before a possible iteration. Preserve the
  zero-trip continuation; an always-returning body alone does not satisfy a
  required function return. Body/stepper returns belong to the enclosing function
  or deferred block; an endpoint that unconditionally returns prevents continuation.
  Nonempty ranges are not used as total-return proofs in this conservative stage.
- [NullFlowAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/NullFlowAnalysis.kt)
  evaluates entry facts first, then invalidates bindings written by the repeated
  body/stepper, including transitive deferred-block writes, **before** publishing
  occurrence proofs. It does not publish an optimistic first-iteration pass.
  Body guards can reestablish presence, body assignments can establish stepper
  presence, and exits include zero iterations. Unaffected input facts survive.
  Captured-effect scanning traverses nested loops, entry expressions and steppers.
- [MetadataAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/MetadataAnalysis.kt)
  conservatively forgets visible mutable represented-type metadata before a
  repeated body and after the loop. Immutable metadata/body-local facts remain
  definite; foreach typeinfo elements start unknown, not as the first element's
  represented type. Unknown metadata retains existing runtime validation behavior.
  This stage does not implement a precision-maximizing metadata fixed point.
- Legacy snippets retain their previous function-call/nullable/return support
  boundaries; loop source/body scoping and ordinary supported value inference
  work without expanding that fragment language. Loops remain unknown to the
  closed early-temporary-reclamation proof; no buffer cleanup is newly enabled.

### Diagnostics and backend boundary

**C3068 / INVALID_FOR_LOOP** reports invalid receiving/source types or incompatible
typed endpoints, with labels for the invalid source and receiving binding. Keep
ordinary literal/range, array-shape, nullable, return and division diagnostics
when their existing checks fail first. No recovery or accumulation is added.

C3067 now means **semantic analysis completed but loop execution is not
implemented**. Declaration/call/function/snippet/completion APIs admit valid loops.
The gate moved to EmitFunctions validation and snippet emission/compile boundaries,
before LLVM construction, linker lookup or outputs. Semantic errors precede this
gate; valid skipped/deferred loops are still gated when emitting their module.

Runtime evaluation/storage order, range membership/progress/finite/overflow
guards, generated loop IR, actual snapshot/copy reception and iteration cleanup
remain stages 3-4 work. No loop runtime or loop IR verification is claimed.

### Stage 2 verification

[ForSemanticsTest](../../../../Compiler/src/test/kotlin/cloth/ForSemanticsTest.kt)
has 19 tests for exact contextual inference, all admitted domains/steppers,
single-result calls, arrays/shapes, mutability, scope identities, skipped errors,
defaults, overload probes, snippets and one AST alternating Windows 32/64/32-bit
semantic targets. [ForFlowTest](../../../../Compiler/src/test/kotlin/cloth/ForFlowTest.kt)
has 15 tests for return ownership, zero-trip paths, body/stepper presence,
captured effects and metadata. ForStageGateTest now has six tests for the later
backend boundary, semantic diagnostic priority and absent outputs. ForParsingTest
retains 15 grammar tests with updated keyword classification.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.ForParsingTest --tests cloth.ForSemanticsTest --tests cloth.ForFlowTest --tests cloth.ForStageGateTest --tests cloth.NullabilityRulesTest --tests cloth.NullabilityTypingTest --tests cloth.TypeInfoTest --tests cloth.OptionalArgumentsTest --offline --console=plain --no-daemon
```

Observed: **105 focused tests passed**, zero failures/errors/skips; 55 loop tests,
35 added since Stage 1. The final full compiler run also passed:

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Observed full result: **868 tests passed**, zero failures/errors/skips. The first
full run exposed an old operator-classification assertion; it was aligned with
the keyword migration while retaining prefix-identifier coverage, then the full
suite passed. New loop tests analyze semantics and stage gates
on Windows; alternating target layouts are semantic checks, not generated IR or
native iteration on those targets. No native Linux/macOS loop execution is claimed.

## Stage 1: syntax, AST and first-error diagnostics (2026-11-02)

At this milestone, Stage 1 parser implementation, focused verification and full
compiler regressions completed, with later stages pending. See Stages 2-3 above
for the semantic scope and current execution boundary; details below retain the
original Stage 1 scope/evidence.

### Supported syntax

```cloth
for (def<int> i <- 0..100; i++) println(i)
for (def i <- 5..=10) println(i)
for (def<float> x <- 0.0..=1.0; x += step()) println(x)
for (def i <- 10..0; i--) println(i)
for (def<int[]> row <- matrix) {
    for (def value <- row) println(value)
}
```

- Typed/inferred reception, both range delimiters, no-stepper headers, array
  foreach, nested loops, brace bodies and any single supported source statement
  as an unbraced body are parsed. A single declaration can normalize to multiple
  AST statements; it still occupies one implicit loop-body scope.
- Range headers retain exactly one counter assignment/update: `++`, `--`, `+=`,
  `-=`, `*=`, `/=`, `%=`, or plain `=`. RHS calls and ordinary nested expression
  delimiters are preserved. Steppers require `;`; newline is not a replacement.
- Header whitespace/comments may separate grammatical components across lines;
  expressions keep their existing newline rules. Arrays, calls and parentheses
  do not acquire general range expressions. `-2 ** 3 + 1..end()` keeps ordinary
  arithmetic precedence on its starting endpoint.
- Reject superseded `:` / `= range` headers, multiple/discard receiving names,
  multiple type annotations, foreach steppers, empty/repeated stepper clauses,
  unrelated targets, standalone calls and declarations in the stepper clause.
- A body is required. `{}` is an empty body; an omitted body or a bare `;` is not.
  An explicit terminator after a single-statement body remains an enclosing
  statement separator and does not extend the loop's source span.
- Existing `if` brace requirements and `else` ownership remain intact. No
  function/class grammar, stored range type, loop result or executable iteration
  is introduced by this parser stage.

### Representation and source ownership

- [ForStatement.kt](../../../../Compiler/src/main/kotlin/cloth/parser/statement/ForStatement.kt)
  stores a separate receiving binding, range/element source, original stepper
  operator/RHS and braced/unbraced body. The binding is not an ordinary
  `BindingDeclaration` with a fabricated initializer. An omitted stepper remains
  absent rather than manufacturing an arithmetic expression in the parser.
- [Parser.kt](../../../../Compiler/src/main/kotlin/cloth/parser/Parser.kt) owns
  header/body grammar, target restrictions, required separators and delimiters.
  Preserve keyword, annotation, name, arrow, source, range delimiter, optional
  separator/stepper, parentheses, header/body and complete statement spans.
- [ExpressionParser.kt](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ExpressionParser.kt)
  admits `..` / `..=` as a boundary only when the loop-source caller opts in.
  Propagate this boundary through binary/unary recursion, but not into nested
  calls, arrays or parenthesized subexpressions. Do not make ranges ordinary
  binary values or disturb other expression callers.
- The lexer already recognized `for`, `<-`, `..`, `..=` and `;`; no duplicate
  keyword or operator was added. Longest matching and identifier-prefix cases
  are verified rather than changing token classification.

### Stage boundary and diagnostics

Well-formed loops are rejected with `UNSUPPORTED_FOR_LOOP` / **C3067**, labeling
the original `for` keyword, before checking their receiving types/body names or
attempting output/linker work. The message states that parsing exists while loop
semantic analysis and execution do not. Ordinary malformed syntax uses the
existing structured C2000/C2003/C2006 diagnostics. Compilation stops at the first
detected error; no recovery or diagnostic collection is added.

The gate is explicit in declaration resolution, full-function statement typing,
snippet statement typing and standalone completion analysis. Existing semantic
errors encountered before the loop remain the first failure. Loops in otherwise
supported skipped branches and typed deferred blocks also receive a deliberate
gate rather than an internal unsupported-node exception.

**Not implemented:** loop binding scopes/type inference, endpoint compatibility,
numeric/array source validation, repeated nullable/metadata flow, step evaluation,
overflow/progress guards, foreach snapshots/copies, loop LLVM control flow and
iteration cleanup. A parsed annotation or absent stepper does not establish any
of those executable behaviors.

### Verification

[ForParsingTest](../../../../Compiler/src/test/kotlin/cloth/ForParsingTest.kt)
passes 15 tests covering token boundaries, sources, all eight original update
operators, precedence, multiline/comments, nested and single-statement bodies,
normalized declarations, CRLF/tab spans, malformed/legacy headers, first-error
locations, ordinary-range rejection and parsing the maintained Arr.co example.

[ForStageGateTest](../../../../Compiler/src/test/kotlin/cloth/ForStageGateTest.kt)
passes five tests covering declaration/call/function/snippet/completion paths,
skipped/deferred contexts, earlier errors, library/executable/object/snippet
emission, absent outputs and one CLI diagnostic before an invalid linker lookup.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.ForParsingTest --tests cloth.ForStageGateTest --tests cloth.SemicolonsTest --tests cloth.FunctionParsingTest --tests cloth.DiagnosticsTest --tests cloth.ConditionalsTest --offline --console=plain --no-daemon
```

Observed: **71 focused tests passed**, zero failures/errors/skips, including the
20 new loop tests. The subsequent full command also passed:

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Full-suite result: **833 tests passed**, zero failures/errors/skips. Parsing is
target-independent; tests ran on Windows. No loop IR or native loop execution is
claimed, nor new native Linux/macOS execution. The dedicated skill validator remains unavailable
because both inspected Python runtimes lack PyYAML; check metadata and links
directly without claiming the validator ran.
