# For-loop implementation stages

## Stage 1: syntax, AST and first-error diagnostics (2026-11-02)

Stage 1 parser implementation, focused verification and full compiler regressions
are complete; stages 2-5 remain pending.

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
