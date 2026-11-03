---
name: cloth-semicolons
description: Design, explain, or extend Cloth optional semicolons and valid statement terminator placement.
---

# Cloth optional semicolons

## Confirmed behavior and implemented scope

On 2026-11-02 the user confirmed optional semicolons at the usual statement
terminator positions, unless a syntax explicitly requires them. The user then
clarified that this does not allow a terminator after a brace-bodied function.

```cloth
fn<int> value() { return 10; }
fn stop() { return; }
fn<int> main() { stop(); def x = value(); return x; }
```

- Bindings, assignments, updates, calls, expression statements and returns may
  end with a semicolon. Newlines continue to separate them without semicolons;
  semicolons also allow consecutive statements on one line.
- A brace-bodied function ends at `}`. `fn main() {};` is rejected, including
  when the semicolon is separated from `}` by comments or newlines. Source files
  do not allow standalone empty statements or leading/repeated semicolons.
- An expression-bodied function, such as `fn<int> value() = 10;`, may have one
  optional terminator. This includes an expression body whose value is a
  conditional ending in `}`; distinguish the grammar from the final token.
- Semicolons cannot intervene before `else` or `else if`. Newlines before `else`
  still belong to the conditional chain. `if (flag) {}; else {}` is rejected.
  This also applies to unbraced branches: `if (flag) return 1 else return 2`
  is valid inside an int-returning function, while a semicolon before `else`
  ends the chain. See [conditional bodies](../cloth-control-flow/references/semantics.md#conditional-expressions-and-statements).
- Empty statements inside function bodies and internal snippet bodies are
  tolerated, as in C/Java statement contexts. They create no AST nodes and do
  not change a branch's final value. A semicolon after a complete standalone
  conditional is an empty statement, not part of the conditional chain.
- A binding initialized by a deferred block or conditional expression can have a
  terminator, e.g. `def<int> work := { return 10; };`. A closing brace alone is
  insufficient to decide whether a semicolon is legal.
- `return;` is a bare return. Non-void return checking still rejects it when a
  value is required. Value and result-list returns may end with `;`.
- Bare `break` and `continue` accept the same optional statement terminators,
  including as unbraced loop bodies. They take no argument or label; see
  [loop control](../cloth-control-flow/references/loop-control.md).
- `while (condition)` requires a braced or single-statement body, using the same
  terminator rules as for bodies. A bare `;` is not a while body; see
  [while loops](../cloth-control-flow/references/while-loops.md).
- This is boundary syntax, not arbitrary expression whitespace. `1 + ;`,
  `call(1; 2)`, `[1; 2]`, `(1;)`, and `fn work(); {}` remain errors. Semicolons do
  not replace list commas, operands, header syntax or required delimiters.
- Literal/comment contents retain their semicolons. Keep the lexer token rather
  than globally dropping it, so future syntax can explicitly require semicolons.

The user used C/C++/Java as placement guidance, with the explicit rejection of
brace-bodied function terminators as the Cloth rule. This is not a promise to
adopt every empty-declaration or empty-statement rule of those languages.

## Required loop-header separator

On 2026-11-02 the user confirmed that an explicit range-loop stepper requires
a semicolon separating it from the range:

```cloth
for (def<int> i <- 5..100; i += 2) println(i)
for (def<int> i <- 5..100) println(i)
```

The second form omits the entire stepper clause and increments by one. The
semicolon in the first form is a required header separator, independent of
optional statement terminators. Stage 1 implements this grammar and preserves
the separator token/span. Stage 2 validates loop semantics; Stage 3 executes
numeric ranges, and Stage 4 executes foreach with its snapshot contract. Read the
[stage scope and tests](../cloth-control-flow/references/for-implementation.md).
The [control-flow contract](../cloth-control-flow/references/semantics.md#for-loops-and-ranges-design-confirmed-implementation-deferred)
owns loop syntax and behavior; both range and foreach bindings use `<-`.
Only numeric range loops admit the `; stepper` clause. Array foreach headers
reject it; their traversal advances automatically through snapshot elements.

## Source and invariants

[Parser](../../../Compiler/src/main/kotlin/cloth/parser/Parser.kt) distinguishes
expression and brace function bodies at declaration boundaries, owns body-level
separators and bare returns, and looks across newlines only to find `else`.
[ExpressionParser](../../../Compiler/src/main/kotlin/cloth/parser/subs/ExpressionParser.kt)
stops at `;` without consuming it; the enclosing grammar decides its validity.
Keep `skipNewlines` distinct from statement separators so argument lists, types,
defaults, interpolation and incomplete headers do not gain arbitrary semicolons.
Consume terminators after constructing statements/declarations, preserving their
source spans. Emit no empty statement nodes or semantic/emitter special cases.

Missing statement-separator diagnostics retain the stable `EXPECTED_NEWLINE`
code and mention both a newline and `;`. Brace function boundaries use a located
`EXPECTED_TOKEN` diagnostic without suggesting a semicolon. Preserve first-error
termination. Only currently supported declarations/statements are implemented;
this does not implement classes or methods. Loop header parsing is covered by
the stage record linked above; numeric range execution is implemented in Stage 3.
The executable [ForRanges.co](../../../examples/ForRanges.co) and
[FloatRanges.co](../../../examples/FloatRanges.co) preserve the required separator
and omitted-clause forms; ForIntegrationTest checks their native x64/x86 execution.

## Verification

[SemicolonsTest](../../../Compiler/src/test/kotlin/cloth/SemicolonsTest.kt) covers
valid placement, malformed syntax, body-sensitive function termination,
conditional-chain boundaries, source spans, ordered results, final branch values,
snippet grammar, first-error locations, literal preservation, verified LLVM IR
and a native Windows x64 program returning 11. The user's array-printing example
is rejected at its final semicolon and emits verified IR after removing it.

The corrected 2026-11-02 run of `:Compiler:test` with `--tests cloth.SemicolonsTest`,
`--tests cloth.FunctionParsingTest`, `--tests cloth.FunctionReturnsTest`,
`--tests cloth.DiagnosticsTest`, and `--tests cloth.ConditionalsTest` passed
all 81 tests, including twelve semicolon cases. Add
`--offline --console=plain --no-daemon` and use `./gradlew.bat` on Windows.
Parsing is target-independent; native execution on Linux/macOS and other
architectures was not run for this change. See the
[placement correction](../cloth-language/references/development.md#semicolon-placement-correction-2026-11-02).
