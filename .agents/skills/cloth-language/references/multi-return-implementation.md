# Multi-return functions and destructuring implementation plan

Status: all five stages implemented and verified (2026-11-01).
Ordered producers, returns, positional reception and discards now execute
natively through typed plans and an internal LLVM result carrier. Scalar
broadcasts retain their existing behavior. Unresolved multi-result/discard
deferred function-block declaration combinations still receive C3053.

## Confirmed direction

- Functions declare ordered result types: `fn<string, int> nameAndAge(...)`.
- Expression bodies return implicitly: `= name, age`. Brace bodies use explicit
  `return name, age`. Omitted function return annotations still mean void.
- Receiving declarations support explicit positional types or inference:
  `def<string, int> name, age = nameAndAge(...)` and
  `def name, age = nameAndAge(...)`.
- `_` discards a receiving position, including when its type is explicitly
  listed. It replaces the provisional `void` target spelling.
- Value-producing conditionals can produce multiple results without a function
  declaration: `def x, _ = if (true) { true, false } else { false, true }`.
- Preserve existing one-result broadcast definitions/assignments and arithmetic
  compound assignments. RHS result shape distinguishes broadcast from
  destructuring, independently of RHS syntax.
- Preserve fail-first compilation and diagnostics. Multiple source labels may
  explain one error; do not accumulate independent errors.

## Recommended contract for implementation

These are proposed details for the implementation, separate from the confirmed
decisions above. Resolve a necessary semantic disagreement before implementing
the affected behavior; do not treat this plan as new user confirmation.

1. Model an expression's ordered results separately from an individual
   `ValueType`. Use a result-shape abstraction for no values, one value,
   multiple values, and non-continuation. Each produced position still holds an
   ordinary Cloth value type. Keep this distinct from user-storable tuple types.
2. One-result RHS expressions keep the existing broadcast behavior. Multiple
   results require exactly one receiving position per result, including `_`.
   Target count and the presence of `_` validate reception; neither determines
   what an expression produces. Do not broadcast a multiple-result group.
3. Multiple explicit types are positional and must match result/target arity.
   Retain a shared single annotation for existing scalar broadcast definitions;
   recommend requiring a full positional list for explicitly typed multi-result
   destructuring rather than silently repeating one type over different slots.
4. Each named position follows ordinary exact typing, array-shape checks, and
   mutable `=` or immutable `:=` rules. Apply contextual literal typing per
   position, without introducing conversions of already-typed values. Ignored
   positions are still checked, including any explicit type annotation.
5. Treat exact `_` as a discard target in receiving lists. It creates no symbol
   or storage, may repeat, and cannot be read as a value produced by that
   declaration. Preserve ordinary identifiers such as `_age`.
6. Evaluate the producer once. For comma-separated results, evaluate and
   snapshot each position left to right before evaluating the next position.
   Discards retain evaluation and failures. Stop before later positions when
   evaluation exits or fails. This extends the existing argument snapshot model.
7. Recommend extending existing named assignment lists to positional reception:
   `name, age = nameAndAge(...)`, including `_` targets. Evaluate/snapshot the
   entire RHS before the first target write, then write in target order. This
   permits `a, b = b, a` without exposing intermediate writes. Preserve the
   existing validation and write-order rules for scalar broadcast assignments.
8. Continuing branches of a value-producing `if` must agree in result count
   and compatible type per position, using current scalar conditional rules
   position by position. A branch that returns from the enclosing function
   contributes no merge values. Preserve checking of skipped/unreachable source
   and the current final-else policy; do not settle unrelated conditional design.
9. Recommend whole-result forwarding (`return other()` and `= other()`) when
   the selected callee's result contract matches. No automatic expansion into
   call arguments, array elements, arithmetic, interpolation, or nested result
   lists. Scalar contexts reject multiple results with a located diagnostic.
10. Preserve ordinary standalone call-result discarding and extend explicit
    `-> void` to discard all results. A void function's `=` body still needs a
    void-producing expression. Reject void positions in a multi-result contract;
    `<void>` continues to mean no values. Executable main keeps its existing
    single-result/void entry contract.

## Source audit and architecture

- Before stage 1, `Parser.parseBindings()` expanded a name list into ordinary
  `BindingDeclaration` nodes. Later initializers read the first stored binding.
  That loses the original receiving group before RHS typing can distinguish
  scalar broadcast from multi-result destructuring. Stage 1 retains grouped
  stored-definition syntax and introduced a guarded scalar compatibility expansion
  downstream; stage 3 replaces that bridge with typed reception plans.
- Before stage 1, `FunctionDeclaration.returnType`, `ReturnStatement.value`,
  `FunctionType.returns`, and `SemanticModel` currently describe a single type or
  expression result. Add explicit result contracts and expression result facts;
  retain scalar helpers that require exactly one result. Stage 1 replaces the
  function annotation with ordered `returnTypes` and adds a result-list AST node;
  stage 2 adds ordered contracts and expression result facts through `ResultShape`
  and the semantic result APIs.
- Commas already separate arguments, arrays, parameter lists and type arguments.
  `ExpressionParser` currently stops at a comma. Parse result lists only at
  designated result-producing boundaries, with delimiter-aware handling.
- Before stage 1, `parseStatement()` assumed an identifier followed by a comma starts
  an assignment. Look ahead through a valid target list to an assignment token
  before choosing that production, so branch tails such as `name, age` parse as
  results rather than malformed assignments. Do not parse by inferred type.
- `DeclarationAnalyzer`, `CallAnalyzer`, `ExpressionTyping`, `ControlFlowModel`,
  `MetadataAnalysis`, and `ArrayTemporaryAnalysis` traverse syntax explicitly.
  New nodes and per-slot facts must reach every relevant pass, including
  semantic-model forks/commits used for overload probes and snippet helpers.
- Before stage 4, `EmitFunctions`, `EmitFunctionFrame`, and conditionals carried
  one LLVM result. Stage 4 adds internal ordered carriers at these boundaries
  while retaining ordinary binding storage and type-owned operations per position.

## Stages and completion checks

### 1. Result syntax and retained receiving groups

- [x] Introduce ordered return annotations, a result-list expression node, and
  named/discard receiving positions with original source spans.
- [x] Parse multi-result expression bodies, explicit returns, receiving RHSs,
  and conditional expression tails. Keep function arguments/array literals and
  nested numeric/type delimiters independent of result-list commas.
- [x] Retain grouped stored definitions instead of expanding in the parser.
  Preserve existing deferred multiple-name function blocks, their separate AST
  identities, and execution-on-every-use behavior. Do not accidentally turn a
  brace initializer into an eagerly evaluated result producer.
- [x] Preserve assignment/update syntax, including `+=`, `-=`, `*=`, `/=`,
  `%=`, `++`, and `--`. Do not add elementwise arithmetic over result groups.
- [x] Verify AST/spans for calls, literal/name result lists, nested conditionals,
  explicit/inferred definitions, discards, and `:=`; verify first malformed
  syntax diagnostics and existing argument/array/type parsing regressions.

Completion: syntax preserves enough information for semantic classification;
existing supported programs still parse, with updated internal AST assertions
where retained groups deliberately replace parser expansion.

Implementation record (2026-11-01):

- `FunctionDeclaration.returnTypes` retains ordered type references; the
  transitional `returnType` accessor returns only a single annotation, never the
  first annotation from a multiple-result contract.
- `ResultListExpression`, `ReceivingTarget.Named`/`.Discard`, and
  `BindingGroupDeclaration` retain producer syntax, positional annotations and
  original source tokens. Assignment lists also retain receiving targets.
- `Parser` opts into result-list parsing at explicit/implicit returns, receiving
  RHSs and expression statements/conditional tails. Target-list lookahead requires
  an assignment token before treating a named list as assignment. Argument,
  array, index, interpolation and type-list commas keep their own grammar.
  A comma permits continuation on the next line; it does not make all newlines
  insignificant or introduce parenthesized tuple expressions.
- Ordinary stored multi-name declarations remain groups in the AST.
  `ScalarBroadcastCompatibility` supplies stable scalar declarations only to
  preserve existing supported behavior through resolution, checking, metadata
  analysis and emission. It will be removed in stage 3. Deferred multi-name
  function blocks retain separate reparsed bodies and syntax identities.
- New multiple annotations, result-list expressions, and discard reception fail
  with one source-located C3053 diagnostic at the current semantic boundary.
  No first-result fallback, partial destructuring implementation, error recovery
  or diagnostic accumulation was added.
- Lexer matching preserves exact `_` as an operator/discard token while allowing
  identifiers beginning with underscores, such as `_age` and `__count`.
- `MultiReturnParsingTest` adds 13 tests for annotation/order/span fidelity,
  explicit/implicit results, groups/discards, named/boolean/nested conditional
  results, delimiter isolation, CRLF/continuation, block identity, arithmetic
  operators, malformed syntax, first errors and semantic staging. Existing
  `MultiBindingsTest` AST assertions now inspect retained groups while retaining
  native once-only evaluation, array-copy and deferred-block coverage.
  `MultiAssignmentTest` no longer lists named result tails and positional RHS
  lists as malformed syntax; their AST forms are covered by the new suite.

Verification: the focused `MultiReturnParsingTest`, `MultiBindingsTest`,
`FunctionParsingTest`, `FunctionBlocksTest`, and `AssignmentOperatorsTest` run
passed all 71 tests across five suites, zero failures/errors/skips, using
`gradlew.bat :Compiler:test --tests cloth.MultiReturnParsingTest --tests
cloth.MultiBindingsTest --tests cloth.FunctionParsingTest --tests
cloth.FunctionBlocksTest --tests cloth.AssignmentOperatorsTest --offline
--console=plain`.

The full `gradlew.bat :Compiler:test --offline --console=plain` run passed all
655 tests across 50 suites, zero failures/errors/skips. The first full run found
the obsolete `MultiAssignmentTest` syntax expectations described above; after
updating them, the complete rerun passed. Changed-file whitespace checks and 44
local Markdown links passed. Native coverage comes from the existing Windows
x64/x86 regressions; new multi-result execution is still pending later stages.

### 2. Result contracts, conditional typing, and flow

- [x] Add the result-shape representation and APIs separating scalar requirements
  from result-producing contexts. Preserve individual `ValueType` descriptors.
- [x] Resolve ordered function return contracts, retaining void, strict typing,
  contextual literals, recursion, and non-void completion checks.
- [x] Keep overload identity/selection based on parameters; receiving types or
  result count must not select a different overload.
- [x] Type result lists, whole-result forwarding, and conditional results using
  per-position expectations. Carry non-continuation separately from empty/void
  results; do not require merge values from terminating branches.
- [x] Extend control flow, name traversal, metadata facts, overload fact
  transactions, and diagnostics for new result-producing syntax.
- [x] Verify arity/type mismatches, incompatible branches, early returns,
  missing returns/else under current rules, literal boundaries, scalar misuse,
  ignored-position checking, void positions, and forbidden multi-result main.

Completion: the compiler can analyze result producers independently of receiver
syntax; the user's conditional example infers two booleans and an ordinary
unannotated function still returns void.

Implementation record (2026-11-01):

- `ResultShape.Empty`, `.Single`, `.Multiple` and `.Never` separate ordered
  expression/return contracts from individual `ValueType` descriptors. Function
  signatures expose `.results`; legacy `.returns` is an explicit scalar-only
  bridge. Semantic result APIs never substitute a first result for a carrier.
- `ExpressionTyping.resultOf` checks producers independently of receiving
  syntax; `typeOf` requires the old scalar boundary. Result lists type every
  position, reject void elements and implicit nested result expansion, and carry
  non-continuation separately. Per-position contexts retain existing integer
  literal/array rules and strict already-typed values; floating return literals
  keep their existing contextual policy.
- `CallAnalyzer` resolves ordered function annotations and validates implicit
  and explicit result contracts, bare/missing returns, and whole-result
  forwarding, including direct/mutual recursion. Parameter-only overload keys
  and selection remain unchanged. Return errors inside argument branches remain
  fatal during overload probes.
- Conditional producer typing merges only continuing branch results, checking
  count and type per position and retaining the existing final-else and scalar
  branch rules. No implicit return-type inference was added to `fn`.
- `DeclarationAnalyzer` traverses result-list children. The existing ordered
  flow walker handles their completion. `MetadataAnalysis` checks every produced
  expression and retains represented-type facts per position, with conservative
  merges over executable continuing branches. `FunctionSemanticModel` copies
  result/statement/positional-metadata facts across overload trial transactions.
- C3054/C3055/C3056 describe result arity, scalar misuse and branch arity. Existing
  return-type diagnostics locate the mismatched result/annotation. Main's signed
  scalar/void policy remains; multi-result main is allowed only as an ordinary
  library function.
- Stage boundaries are explicit: `_` reception and positional annotations remain
  stage 3. Every produced position is checked even when the complete expression
  is ignored; checking a discard target's annotation is still stage 3. The
  ordinary scalar-broadcast bridge remains in place. Stage 4's native gate
  rejects valid multiple-result contracts and result-list syntax before output
  creation, including non-continuing lists; no native tuple carrier is emitted.
- `MultiReturnTypingTest` adds 21 focused cases for the above contracts,
  producer inference from the user's conditional RHS without receiver/signature
  context, arrays, metadata validation in ignored results, overload facts,
  reuse of one AST across 64/32/64 targets, and semantic/backend stage boundaries.

Verification: the focused `MultiReturnTypingTest`, `MultiReturnParsingTest`,
`FunctionReturnsTest`, `CallResolutionTest`, `ConditionalsTest`,
`FunctionBlocksTest`, and `MultiBindingsTest` run passed 137 tests across seven
suites, zero failures/errors/skips, using `gradlew.bat :Compiler:test --tests
cloth.MultiReturnTypingTest --tests cloth.MultiReturnParsingTest --tests
cloth.FunctionReturnsTest --tests cloth.CallResolutionTest --tests
cloth.ConditionalsTest --tests cloth.FunctionBlocksTest --tests
cloth.MultiBindingsTest --offline --console=plain`.

The full `gradlew.bat :Compiler:test --offline --console=plain` run passed all
676 tests across 51 suites, zero failures/errors/skips. Changed-file whitespace
checks, new-file trailing-whitespace checks, and 44 local Markdown links passed.
Existing Windows x64/x86 native scalar/buffer regressions remain green. The new
multi-result coverage is semantic plus explicit rejection at the native boundary;
it does not claim multiple-result native execution or new platform support.

### 3. Definitions, assignments, discards, and broadcast compatibility

- [x] Resolve the initializer in the enclosing scope before introducing the
  receiving names. Check duplicate named positions and ordinary shadowing rules;
  discards do not participate in duplicate-name checking.
- [x] Classify retained definition/assignment groups by typed RHS shape. Record
  one explicit binding/assignment plan: scalar broadcast or positional results.
- [x] Validate target/type/result counts, per-position types, mutability, array
  shape, inferred extents, and `_` behavior. Never expose a discarded first slot
  as a hidden dependency for later bindings.
- [x] Implement semantic plans for existing-binding destructuring with all RHS
  snapshots before stores; metadata follows this order now, and stage 4 must
  honor the recorded plan during native emission.
- [x] Extend per-binding metadata and represented-type facts without treating a
  multi-result producer as one `typeinfo` value. Preserve target-specific types.
- [x] Verify scalar broadcast calls/arrays execute once and copy independently;
  check positional definitions/assignments, first/repeated/all discards, swaps,
  `:=`, immutable writes, duplicates, outer-name reads and exact literal context.

Completion: reception is determined by RHS results, with regression coverage for
both existing scalar broadcast and new positional destructuring.

Implementation record (2026-11-01):

- `ReceptionPlan` records the original producer, ordered result shape, reception
  kind, and per-target type/source-index slots. Scalar broadcast uses index zero
  for every receiving position; positional reception uses one index per position.
  A non-continuing producer has a distinct plan and performs no stores.
- Retained group definitions resolve all producer names before introducing
  `GroupLocalSymbol` identities. No synthetic binding or first-name dependency
  remains. `_` has a typed slot but no symbol/storage; duplicates among real
  names retain original diagnostic locations. Existing multi-name scalar function
  blocks are still independent deferred bodies, not eager result producers.
- `ExpressionTyping.definitionPlan` and `assignmentPlan` share checking across
  source functions and snippet helpers. `ReceptionContext` provides either one
  scalar context or nullable per-position contexts without inventing a wildcard
  `ValueType`. Source result count remains authoritative; target count and `_`
  do not choose broadcast versus positional behavior.
- Explicit positional annotations must cover every result/target. The implemented
  default reserves a shared single annotation for scalar broadcasts. Inferred
  forms resolve each result independently. Scalars may broadcast through discard
  positions; the first named assignment destination supplies their scalar type
  context. Discarded annotations and producer expressions still undergo ordinary
  strict type, literal range and array-shape checks.
- Existing-binding positional assignments retain all destinations, including
  repeated names and discards. Plans require every RHS result snapshot before
  writes; named writes occur in target order. Metadata facts already follow this
  contract for swaps and repeated targets. No positional runtime execution is
  claimed before stage 4.
- `SemanticModel` stores reception plans, and function-model fork/commit copies
  them with type/provider/result facts. `MetadataAnalysis` maps represented-type
  facts to bindings by result index, preserving independent conditional merges
  and checking ignored positions. Array bindings infer static extents through
  ordinary rules and retain their fixed initial assignment shape contract.
- The scalar emitter consumes broadcast plans directly, evaluating the producer
  once and copying array storage independently for every named binding. Discards
  allocate no local binding. C4030 guards positional native source/object/snippet
  paths before output creation; C3053 is limited to unresolved deferred-block
  receiving combinations. C3057/C3058 diagnose receiving arity/annotation lists.
- `DestructuringTest` adds 23 cases covering semantic reception and its boundaries.
  Native scalar tests run on Windows x64/x86 with once-total print effects,
  independent array copies, first/all discards, no `_` alloca and early-return
  producers. Existing parser/type/broadcast assertions now inspect real grouped
  symbols and plans rather than the removed scalar compatibility expansion.

Verification: `DestructuringTest`, `MultiBindingsTest`, `MultiAssignmentTest`,
`MultiReturnTypingTest`, `MultiReturnParsingTest`, `FunctionBlocksTest`, and
`CallResolutionTest` passed all 128 tests across seven suites, zero
failures/errors/skips, using `gradlew.bat :Compiler:test --tests
cloth.DestructuringTest --tests cloth.MultiBindingsTest --tests
cloth.MultiAssignmentTest --tests cloth.MultiReturnTypingTest --tests
cloth.MultiReturnParsingTest --tests cloth.FunctionBlocksTest --tests
cloth.CallResolutionTest --offline --console=plain`.

The full `gradlew.bat :Compiler:test --offline --console=plain` run passed all
699 tests across 52 suites, zero failures/errors/skips. Changed/new-file whitespace
checks and 44 local Markdown links passed. Native scalar broadcast execution is
verified on Windows x64/x86; positional reception is verified through semantic
and metadata plans and native preflight rejection, not native result transport.

### 4. Native emission and lifetime preservation

- [x] Use an explicit internal multi-result carrier, preferably an LLVM struct
  of per-position ABI types. Keep existing scalar/void function layouts stable.
  LLVM aggregate lowering is internal; it adds no source-level pointer syntax,
  tuple storage, or foreign C multi-return ABI promise.
- [x] Emit lists once in source order with snapshots, emit/extract call results,
  merge conditional results through an aggregate phi or per-position phis using
  actual continuing predecessor blocks, and join function exits correctly.
- [x] Encode/decode each position using existing scalar ABI rules, including
  boolean representation and distinct target-sized integer identities.
- [x] Supply a caller arena if any returned position needs buffered storage.
  Copy outgoing strings and nested arrays before callee-frame cleanup, preserving
  independent values even when slots originate from the same mutable array.
- [x] Audit temporary checkpoints, ownership/transfer facts, forwarding, ignored
  results, repeated reads and recursion. Begin with correct conservative copies;
  do not suppress discarded-slot effects or introduce aliasing optimizations.
- [x] Emit binding/assignment plans with one producer evaluation and no stores
  before all positional RHS snapshots. Discards allocate no local binding.
- [x] Verify mixed scalar/string/nested-array results, copies after mutation,
  recursion, conditional and early-return paths, repeated/discarded calls,
  allocation cleanup, guarded runtime failures, and verified LLVM modules.

Completion: compiled multi-result programs preserve values, evaluation order,
failure behavior, and buffer lifetimes across calls and scopes.

Implementation record (2026-11-01):

- `EmitResults` owns the internal result carrier: an LLVM struct of ABI-encoded
  fields for multiple results, with existing scalar/void layouts unchanged.
  Half values use i16 boundary bits; reception decodes each field for ordinary
  value storage/operations. No new source value type or C foreign ABI is exposed.
- `EmitFunctions` declares complete result signatures before bodies and supplies
  a caller arena whenever any returned slot is buffered. `EmitFunctionFrame`
  merges encoded results, copies every outgoing buffered slot before freeing
  its frame, then returns the carrier. Independent recursive copies preserve
  alias separation for repeated array/string-array source positions.
- `EmitExpression` snapshots each result-list position before later result
  effects. It tracks one-use array freshness and owned carriers separately,
  consumes carrier ownership on extraction, and resets both proofs at temporary
  cleanup. Whole-result forwarding and direct/mutual recursion use the ordinary
  return boundary rather than special forwarding ownership assumptions.
- Conditional result carriers use phi values with actual continuing predecessor
  blocks. Early returns/guards and nested conditionals may move the builder;
  terminated branches contribute no incoming carrier. Non-continuing lists stop
  before later positions and retain normal frame cleanup after return copying.
- `EmitStatement` consumes reception plans: positional snapshots/copies and shape
  guards are prepared before any receiving binding/store, then named destinations
  are written in source order. Broadcasts keep their old semantics. `_` retains
  evaluation/guards without storage; all-discard reception executes the producer
  through the discard path without subsequently unpacking a discarded value.
- `ArrayTemporaryAnalysis` understands result-list and multi-result call/if
  effects. Safe discarded array-containing carriers reclaim temporary buffers
  promptly; captured publishing writes retain frame lifetime. Multiple string
  snapshots remain conservative. Scalar ownership/elision behavior remains green.
- The temporary native C4030 result gate is removed, including snippet/object
  entry points. Existing type validation still precedes IR/output work. Internal
  snippet last-value instrumentation prints only scalar results; no tuple text
  conversion was introduced.
- Eight new `MultiReturnEmissionTest` cases verify real results, side effects,
  runtime failures and ABI transport. Existing lifetime instrumentation adds four
  probes: poisoned recursive result copies with zero outstanding allocations,
  allocation failure in a discarded slot before later effects, immediate safe
  discard cleanup, and captured writes whose buffers must remain live. Existing
  stage-boundary tests now verify successful source/object/snippet paths.

Verification: `MultiReturnEmissionTest`, `DestructuringTest`,
`MultiReturnTypingTest`, `ArrayCopyElisionTest`, `ArrayTemporaryCleanupTest`, and
`StringFunctionTest` passed all 108 tests across six suites, zero
failures/errors/skips, using `gradlew.bat :Compiler:test --tests
cloth.MultiReturnEmissionTest --tests cloth.DestructuringTest --tests
cloth.MultiReturnTypingTest --tests cloth.ArrayCopyElisionTest --tests
cloth.ArrayTemporaryCleanupTest --tests cloth.StringFunctionTest --offline
--console=plain`. Mixed carrier ABI fields and recursive nested string-array
transport execute on Windows x64/x86. Foreign-target integration, broader CLI
coverage and the dedicated runnable example remain stage 5.

The full `gradlew.bat :Compiler:test --offline --console=plain` run passed all
711 tests across 53 suites, zero failures/errors/skips. Changed/new-file whitespace
checks and 44 local Markdown links passed. This establishes native Windows
x64/x86 coverage for the tested carriers and buffers, without claiming native
Linux/macOS execution or completing stage 5's integration matrix.

### 5. Integration, portability, and documentation

- [x] Add a runnable focused example covering explicit/inferred destructuring,
  `_`, conditional results, mutable/immutable bindings, broadcast preservation,
  and arithmetic compound assignments on received values.
- [x] Add focused parsing/typing/native suites for the behaviors above and keep
  existing function, conditional, broadcast, assignment, string, array,
  function-block, provider/metadata and entry tests passing.
- [x] Run native Windows x64/x86 checks with mixed return layouts and buffers;
  verify IR for currently supported Linux/macOS target profiles. Reuse parsed
  source across target widths to detect leaked host/previous-target facts.
  Distinguish native coverage from foreign-target IR verification.
- [x] Check CLI first-error diagnostics before emission/linking and complete
  the full `gradlew.bat :Compiler:test --offline --console=plain` regression run.
- [x] Reconcile intended examples with the user's current edits; update README,
  confirmed semantics, development status, and this plan with actual coverage,
  limitations, commands, and results. Mark implementation complete only then.

Completion: the full pipeline and supported target matrix are verified and the
project records accurately distinguish implementation from remaining design.

Implementation record (2026-11-01):

- `examples/MultiReturns.co` checks explicit/inferred name/age reception, `_`,
  conditional producers, `=`/`:=`, forwarded calls, swaps, arithmetic updates,
  once-total scalar broadcasts and independent array copies. It prints
  `Cloth: 8` and PEC: 0. The original provisional spellings in `examples/Test.co`
  are reconciled with the confirmed `_` and implicit-return decisions; unrelated
  intended syntax in that file is preserved.
- README explains result count/type rules, complete positional annotations,
  evaluation/snapshot order, discards, forwarding, broadcast preservation,
  retained scalar deferred-block contracts, execution coverage and runnable
  commands. It also corrects the earlier unconditional frame-lifetime wording
  for discarded strings to account for proven mixed-result temporary cleanup.
- `MultiReturnIntegrationTest` adds five cases, including native 64/32/64 reuse
  of one AST with PE header checks, target-specific results and metadata,
  Unicode startup arguments, nested buffered recursion, captures, snapshots,
  literal contexts, scopes and mixed broadcast/positional effects.
- The same parsed source verifies executable LLVM modules across the nine
  canonical existing triples. This expands integration coverage, not supported
  targets. Native/IR coverage is deliberately distinguished:

  | Target profiles | Verification |
  | --- | --- |
  | Windows x64/x86 MSVC | Native execution, CLI executable/object builds, verified IR |
  | Windows ARM64 MSVC | Verified IR |
  | Linux x64/ARM64 GNU and musl | Verified IR |
  | macOS x64/ARM64 | Verified IR |

- CLI failures cover parser, return/branch/receiving arity, annotation lists,
  ignored-position types, mutability, scalar misuse, void positions, main,
  deferred-block boundaries, initializer scope and metadata. All 50 failing
  invocations report one expected located diagnostic before outputs/linker work.
  Executable-only main restrictions are excluded from the valid library case.
- The example compiles and executes through `runCompiler` at both native widths
  and emits object files through CLI object mode. Four failure programs execute
  at both widths to check division, array bounds/shape and numeric cast guards,
  effect order, exactly-once evaluation and suppression of completion output.

Focused verification: `MultiReturnIntegrationTest`, `MultiReturnEmissionTest`,
`DestructuringTest`, `MultiReturnTypingTest`, and `MultiReturnParsingTest` passed
all 70 tests across five suites, zero failures/errors/skips, using
`gradlew.bat :Compiler:test --tests cloth.MultiReturnIntegrationTest --tests
cloth.MultiReturnEmissionTest --tests cloth.DestructuringTest --tests
cloth.MultiReturnTypingTest --tests cloth.MultiReturnParsingTest --offline
--console=plain`.

The full `gradlew.bat :Compiler:test --offline --console=plain` run passed all
716 tests across 54 suites, zero failures/errors/skips. Changed/new-file
whitespace checks and 50 local Markdown links passed. All five stages are
complete within the documented boundaries. Native execution is verified on
Windows x64/x86; the remaining supported profiles have verified LLVM IR coverage.

## Final review

Final review (2026-11-01): runtime receiving extents are now enforced even when
an array position is discarded, including all-discard scalar broadcasts and
positional groups. The first review regression reproduced a missing guard for
`def<int[2], bool> _, _ = pair()` with a dynamically sized one-element array.
Discard constraints now run before temporary cleanup and require no receiving
storage/copy. Five review tests exercise this fix, nested shapes, captured writes,
compound evaluation order, all compound operators/updates and their diagnostics
and runtime guards on Windows x64/x86. See development status for verification.

Verification: the full `gradlew.bat :Compiler:test --offline --console=plain`
run passed all 721 tests across 55 suites, zero failures/errors/skips. Subsequent
expanded poisoned-buffer checks for explicit discard constraints and the review
suite passed all 21 tests across two suites, zero failures/errors/skips, using
`gradlew.bat :Compiler:test --tests cloth.ArrayTemporaryCleanupTest --tests
cloth.MultiReturnReviewTest --offline --console=plain`. Whitespace checks and
50 local Markdown links passed.

## Boundaries and remaining choices

- Numeric result selection such as `myFunc().1` is recorded in a separate
  [deferred RFC](../../../../todo/result-selection-rfc.md). It is outside this
  completed implementation and has no scheduled implementation work. Recording
  the proposal does not add tuple indexing or argument spreading.
- The representation should be reusable for methods when method/class execution
  is implemented; this work does not require implementing unrelated class or
  state machinery.
- General tuple types, tuple fields/indexing, nested/flattened result groups,
  implicit argument spreading, and result-count-based overloads are outside this
  implementation proposal.
- Multi-result deferred function-block declarations need a separate decision:
  current multiple-name blocks declare independent deferred computations, while
  destructuring receives produced values. Preserve existing block behavior and
  diagnose unsupported new combinations rather than assigning them accidental
  semantics.
- Stage 3 implements the documented defaults for single annotations, scalar
  broadcast discards and repeated assignment destinations. More general result
  grouping/placement and multi-result deferred blocks remain outside the current
  implementation contract; resolve any future semantic disagreement before
  extending those forms.
