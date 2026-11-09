# Generic types Stage 1: syntax and source identity

This is the historical syntax/name milestone; [Stage 2](stage-2.md) records the
subsequent closed-type implementation. [Stage 4](stage-4.md) owns the current
native execution boundary.

Stage 1 implements class/struct parameter syntax, nested named applications and
lexical type-name identities. The [contract](semantics.md) still owns the complete
first-slice semantics; [Stage 0](stage-0.md) retains its historical baseline.
Closed types, substitution, symbolic body checking and native generic execution
are not implemented by this stage. Compilation reports C4033 after name checks,
even for an unused generic declaration.

## Supported syntax and names

This input parses and binds type names; compiling it still reaches C4033:

```cloth
typedef<struct> Slot<T> { T value }
pub typedef<class> Box<T> (def<T> value) {
    pub mt<T> get() = value
}
fn main() {
    def value = Box<int[]>::new([1, 2])
    value::delete
}
```

Owner parameter and application lists retain their opening/closing tokens,
parameter tokens, argument references and source spans. `TypeReference.application`
is separate from numeric `width`; spelling renders nested arguments without
dropping nullable markers, array extents or primitive widths. Examples include
`Box<Slot<int<129>?[]>>`, `Box<int>[]?::type` and a base `Base<T>(value)`.
Constructor value arguments remain separate from base type arguments.

`TypeParser` owns parameter/application grammar and the shared 256-level generic
argument nesting limit. Empty/trailing-comma lists fail; newlines inside lists
are accepted. Nested `>>` closings retain ordinary lexer tokens. Keyword primitive
widths, `opt<T>` parameter wrappers, return lists and operator `int<auto>` selectors
retain their existing grammar. Direct duplicate nullable markers remain invalid.

Named type receivers commit to an application only after iterative token
lookahead finds a complete suffix followed by `::`. Cast/check targets use a
complete type suffix and its following token to preserve ordering comparisons.
For example, `value -> Named < low > high` retains its existing comparison parse.
Lookahead does not throw/catch diagnostics or recover from a committed error.

`GenericAnalyzer` registers owner parameters before visiting header/body uses.
Each `TypeParameterSymbol` belongs to its original owner declaration and position.
The same symbol is visible in fields, base applications, constructor inputs,
methods, defaults, operators, local annotations, captures and result lists.
Parameters shadow outer nominal types; duplicate parameters or an owner-name
parameter fail. Value names remain separate: an ordinary value named T does not
replace annotation T. Provider expressions preserve value-first lookup.

`GenericNameModel` stores identity maps from original references/receivers to
parameter, built-in-name or nominal-declaration identities. Built-in-name identity
does not normalize numeric widths or create concrete ValueTypes: the original
reference retains those details. Likewise the nominal identity for `Box<int>`
denotes the original Box declaration, not a closed `Box<int>` type. Ordered closed
identities and interning belong to Stage 2.

The pass uses existing `FileBindings` original symbols and each token's defining
source context before requiring any closed fields/layouts. Imported templates,
private helper types, repeated routes and circular imports retain their original
declarations. Same-spelled private templates in separate files remain distinct;
caller-private actual argument names resolve in the caller. Parameters are not
file exports. This adds no import alias syntax or signature-visibility checker.

Name checks reject missing/extra arguments, applying arguments to an ordinary
nominal or parameter, raw generic value types and void arguments. `opt<T>` is not
a value type argument. Unknown types keep their existing structured diagnostic.
For wholly non-generic programs the pass skips added name resolution, preserving
the established semantic checking order.

## First-error diagnostics and compiler boundary

| Code | Boundary |
| --- | --- |
| C2011 / UNSUPPORTED_GENERIC_SYNTAX | Constraints, owner parameter defaults, generic functions/methods and generic enums remain outside this slice. Other deferred grammar retains existing syntax failures. |
| C2012 / GENERIC_ARGUMENT_NESTING | The 257th nested argument application fails before recursion; 256 is accepted. This is separate from numeric width and array rank. |
| C3108 / INVALID_GENERIC_PARAMETER | A duplicate parameter or a parameter named after its owner, with declaration labels. |
| C3109 / GENERIC_ARITY | The supplied count differs from the original nominal declaration's parameter count, with application/declaration labels. |
| C3110 / RAW_GENERIC_TYPE | A template appears without required explicit arguments, including constructor/provider receivers. |
| C3111 / INVALID_GENERIC_ARGUMENT | A void/opt argument or application of a type parameter. |
| C4033 / UNSUPPORTED_GENERIC_TYPES | Generic names passed this stage, but closed compilation is unavailable. |

`DeclarationAnalyzer` runs this pass before closed `NamedTypes` field/signature
resolution. Name failures precede C4033; one detected failure stops processing.
Legacy `TypeResolver.resolve` and `SignatureType.of` also reject applications
instead of dropping their arguments into non-generic nominal keys. Diagnostics
retain original source snapshots and multiple labels for one error.

This stage does not check arbitrary expressions in template bodies, instantiate
types, normalize substituted `T?`, check private helpers in public signatures,
create generic metadata/layouts, or change the native ABI/lifetime model. The
64-step/4,096-instance policies remain unimplemented until Stage 2. Generic
interfaces, constraints, function/method parameters, variance and inference remain
deferred. Public List and Bootstrap migration remain outside this stage.

## Verification (2026-11-08)

The focused run passes 131 tests across ten suites: the three new generic suites,
class/struct/function/nullable parsing, provider syntax, import semantics and
arbitrary integer widths. Separately, `genericSyntaxTest` passes 25 tests across
the two parsing/name suites without native binary jars and with JavaCPP library
loading disabled. `importSemanticsTest` passes 33 tests under its corresponding
native-free configuration. All have zero failures/errors/skips.

The new suites are `GenericParsingTest` (11), `GenericNamesTest` (14) and
`GenericStageBoundaryTest` (6). They exercise lexical shadowing, source labels,
header/body scope, suffix grammar, comparison ambiguity, first-error ordering,
depth boundaries, original imported identities/private helpers and the unchanged
Stage 0 imported List fixture. The Gradle tasks track that fixture document as an
input, and `check` includes `genericSyntaxTest`.

The boundary suite verifies C4033 before object creation on all nine ABI profiles;
this is rejection evidence, not generic IR/object support. Ordinary class
construction, widths, casts/comparisons and value names execute on Windows x64
and x86. No target has native generic execution evidence at Stage 1.

Focused XML and a per-suite hash summary are archived under
`build/generic-types-stage1/focused-*-results/` and `focused-summary.json`.

The final full run passes in 9m 27s:

```powershell
.\gradlew.bat :Compiler:test :Compiler:genericSyntaxTest :Compiler:importSemanticsTest :Shuttle:test --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

| Task | Suites | Tests | Result |
| --- | ---: | ---: | --- |
| Compiler test | 168 | 1,852 | Fresh execution; zero failures/errors/skips. |
| Compiler genericSyntaxTest | 2 | 25 | Fresh native-free execution; zero failures/errors/skips. |
| Compiler importSemanticsTest | 1 | 33 | Fresh native-free execution; zero failures/errors/skips. |
| Shuttle test | 7 | 57 | Fresh execution; zero failures/errors/skips. |

The native-free tasks repeat their suites separately; they are not additional
unique Compiler tests. Final XML copies match their original SHA-256 hashes and
are archived under `full-*-results/`, with `full-summary.json` in the same Stage 1
artifact directory. All final production/test changes precede this run.

Six CLI probes cover C4033, C3110, C3109, C3108, C3111 and C2011. Every probe
returns 1, renders exactly one diagnostic and creates no executable. Logs and
`cli-summary.json` are archived in the same directory. All seven changed skills
pass the bundled skill-creator validator using temporary pinned PyYAML 6.0.3.

Publication validation checks 14 documents, 797 local links, 99 heading anchors,
balanced fences, final newlines, whitespace and 21 stage source/build files.
`publication-validation.json` records their hashes and the plan scope: four
generic tasks complete (Stages 0-1), while the array plan remains at 34 completed
tasks with public List and its later integration gate still open. Temporary probe
sources, validation scripts and dependencies are removed before completion.
