# For-loop implementation stages

## Stage 2: names, types and repeated control flow (2026-11-02)

Stage 2 is complete and verified: 105 focused and all 868 compiler tests pass.
Stages 3-5, including all generated loop execution, remain pending.

### Semantic scope and source ownership

- [LoopBindingSymbol](../../../../Compiler/src/main/kotlin/cloth/semantic/symbols/Symbols.kt)
  gives each reception its own mutable identity without fabricating a local
  initializer. [DeclarationAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/DeclarationAnalyzer.kt)
  resolves endpoints/source in surrounding scope, then introduces the loop scope
  and a separate body scope. Header steppers cannot read body locals; source
  expressions and following code retain outer names. Unbraced declarations,
  nested loops, shadowing, duplicate names and default-parameter shadowing use
  existing lexical rules. DeclarationModel exposes loop binding/header/body scopes.
- [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/ExpressionTyping.kt)
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

- [ControlFlowModel](../../../../Compiler/src/main/kotlin/cloth/semantic/ControlFlowModel.kt)
  composes endpoint/source completion before a possible iteration. Preserve the
  zero-trip continuation; an always-returning body alone does not satisfy a
  required function return. Body/stepper returns belong to the enclosing function
  or deferred block; an endpoint that unconditionally returns prevents continuation.
  Nonempty ranges are not used as total-return proofs in this conservative stage.
- [NullFlowAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/NullFlowAnalysis.kt)
  evaluates entry facts first, then invalidates bindings written by the repeated
  body/stepper, including transitive deferred-block writes, **before** publishing
  occurrence proofs. It does not publish an optimistic first-iteration pass.
  Body guards can reestablish presence, body assignments can establish stepper
  presence, and exits include zero iterations. Unaffected input facts survive.
  Captured-effect scanning traverses nested loops, entry expressions and steppers.
- [MetadataAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/MetadataAnalysis.kt)
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
compiler regressions completed, with later stages pending. See Stage 2 above for
the current semantic scope and moved execution gate; details below retain the
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
