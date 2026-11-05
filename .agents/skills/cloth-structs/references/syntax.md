# Struct syntax and AST (Stage 1)

Integrated 2026-11-04. Read the [struct contract](semantics.md) for the accepted
language behavior and the [plan](../../../../todo/basic-structs-plan.md) for
later execution stages. The [Stage 2 semantic record](semantic-analysis.md)
owns the current diagnostic boundary.

The parser accepts the following source and retains its declarations and
expressions in the AST. At Stage 1, compiling it reached the semantic boundary.

```cloth
pub typedef<struct> Position {
    int x,
    int y
}

fn<Position> origin() = { 0, 0 }

fn work() {
    def<Position> saved = { 1, 2 }
    def<Position> next = { return origin() }
    saved.x += next.y
}
```

## Parser and diagnostic boundaries

- [StructParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/StructParser.kt)
  parses the optional leading visibility, name and ordered typed fields.
  [Parser](../../../../Compiler/src/main/kotlin/cloth/parser/Parser.kt) retains
  functions and structs in source order. A struct closes at its body brace.
  Its following declaration starts on a new line. Other typedef kinds still
  fail at their header.
- Fields and literals accept empty and multiline lists with commas between
  entries. A trailing comma is a syntax error. Array extents and nullable suffixes
  are retained for Stage 2 to validate.
- In [BindingsParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/BindingsParser.kt),
  a named scalar annotation lets a bare brace list represent an aggregate,
  including the empty and single-field forms. A statement body with an explicit
  return stays deferred. The shared brace parser distinguishes the forms from
  their parsed contents and tracks returns within the current deferred boundary.
  A nested deferred binding keeps its own returns. Existing primitive and array
  blocks retain their grammar, and multiple named deferred bindings retain
  distinct bodies. A clear comma list without an annotation can parse as a
  literal. Untyped empty and single-expression binding braces retain the
  deferred block diagnostic for a missing return annotation.
- [ExpressionParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ExpressionParser.kt)
  parses brace literals in ordinary expression positions and keeps each literal
  as one value in a surrounding result list. The postfix parser distinguishes
  fields from method calls and supports field, index and provider chains. A
  field write node retains the receiver path, original operator and optional
  RHS for later lowering.
- C2007 locates forbidden `fn` declarations in struct bodies at the function
  keyword. The rule also applies when the function has a `pub` prefix.
  Type members use `mt`, whose syntax and behavior are later work.
- At Stage 1, C3077 stopped struct syntax during declaration and name
  resolution, or during snippet typing. Stage 2 moved the source unit gate to
  [emission validation](semantic-analysis.md#diagnostics-and-emission-boundary).

## Verification

On Windows, the initial focused run passed 112 tests. The full compiler run
completed 1,304 tests and found one failure in `DiagnosticsTest`. Adding the
brace token to the expected-expression message wrapped `end of file` across
lines. Restoring the existing wording fixed the failure. The focused rerun
with `DiagnosticsTest` passed all 121 tests, including nine `StructParsingTest`
and three `StructStageGateTest` cases.

The focused suites cover functions, deferred blocks, multiple results,
semicolons, providers, primitive member calls and assignment operators. The CLI
gate test checks status 1, one diagnostic and no output creation before linker
lookup.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.DiagnosticsTest --tests cloth.StructParsingTest --tests cloth.StructStageGateTest --tests cloth.FunctionParsingTest --tests cloth.FunctionBlocksTest --tests cloth.MultiReturnParsingTest --tests cloth.SemicolonsTest --tests cloth.ProviderSyntaxTest --tests cloth.StringAttachmentsTest --tests cloth.AssignmentOperatorsTest --offline --console=plain --no-daemon
```

Verification of the new syntax is parser and diagnostic coverage on the JVM.
Executable struct behavior and its target verification are later stages.
