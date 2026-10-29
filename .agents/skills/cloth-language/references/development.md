# Cloth development status

## Current work

As of 2026-10-29, the user selected explicit integer bindings as the first
compiler milestone after arithmetic. It is implemented and verified: `def<int>`
with mutable `=` or immutable `:=`, binding reads in expressions, and reassignment
with `=`. Reusable parsing of delimited contents is an explicit implementation
requirement. Type inference and other binding types remain later steps.

Binding rules, object deletion, state dispatch, class/state
signature collisions, initial-state construction, state re-entry, absence of exit
hooks, and payload binding for running methods have been settled; see
[confirmed semantics](semantics.md).

Reusable top-level states, their host contracts, declaration and attachment
syntax, attached initial-state selection, and automatic external-transition
permission for attached public states are also confirmed in that reference.

State calls now use `object::method(...)`; ordinary instance method calls use
`object.method(...)`. `Test.co`'s attack call has been updated accordingly.

The milestone after explicit integer bindings has not been chosen. Do not treat
the order of open questions or missing compiler features as an approved plan.

## Implementation snapshot

Inspected from repository source on 2026-10-29; recheck before relying on it:

- The compiler is Kotlin/JVM in `Compiler`, built with Gradle. Its module declares
  Kotlin 2.4.20, JDK toolchain 25, and Bytedeco LLVM bindings.
- `Compiler/src/main/kotlin/cloth/Entry.kt` wires source loading, lexer, parser,
  and native LLVM compilation. It defaults to `Math.co` and accepts
  `[source.co] [output]`, preserving source paths and using a separate output.
- `cloth/lexer/Lexer.kt`, `cloth/token/Keywords.kt`, and `Operators.kt` contain
  broader lexical machinery. Recognizing a token does not establish parser,
  type-checker, or emitter support for that language feature.
- `cloth/parser/Parser.kt` builds expression statements, explicit binding
  declarations, and simple `name = expression` assignment statements. Statements
  currently require newline separation (or EOF).
  `cloth/parser/subs/ExpressionParser.kt` handles integer and float literals,
  binding names, parentheses, unary `+`/`-`, and binary `+`, `-`, `*`, `/`, `%`
  with precedence.
- `cloth/parser/subs/DelimitedParser.kt` provides a generic delimiter wrapper
  with caller-supplied content parsing, including angle and parenthesis helpers.
  Binding type annotations and parenthesized expressions use this wrapper;
  recursive callbacks can handle nested delimiters. `TypeParser` currently
  reads a single type name, not generic type arguments or width annotations.
- `cloth/semantic/SemanticAnalyzer.kt` checks the current single executable scope
  before LLVM emission. It rejects unknown names, duplicate declarations,
  immutable reassignment, and float-to-int assignments. Only explicit `int`
  bindings are implemented; other type names receive an unsupported-feature
  diagnostic. Bindings become available after their initializer is checked;
  forward/self reads are currently rejected. This is implementation coverage,
  not a decision about declaration resolution for the broader language.
- `cloth/emitter/LLVM.kt` and `cloth/emitter/types/EmitExpression.kt` emit LLVM
  code for arithmetic and binding reads. `EmitBindings` allocates typed local
  storage and emits loads/stores; `EmitStatement` handles declarations and
  assignments. Native compilation prints the last statement's value and links
  a native executable.
- `Math.co` is the arithmetic example; `Bindings.co` demonstrates explicit
  integer bindings and evaluates to `61`. `Test.co` describes the broader target
  language; it is not currently an end-to-end compiler test.
- Verified on 2026-10-29: `gradlew.bat :Compiler:test --offline --console=plain`
  passed all nine tests in `Compiler/src/test/kotlin/cloth/BindingsTest.kt`.
  Tests cover syntax, nested delimiter callbacks, semantic diagnostics, native
  arithmetic/assignment/copy behavior, mixed arithmetic, and CLI source/output
  paths. CLI tests compile and run both examples (`61` and `6689`).
  `gradlew.bat :Compiler:run --args="Bindings.co" --offline --console=plain`
  also succeeded; running `Bindings.exe` printed `61`.

Repository-relative paths above identify current implementation locations; they
are not language specification boundaries.

## Open design questions

The following have not yet been settled. Ask about them when relevant, keeping
the user's preference for one question at a time:

- Type-narrowing behavior after reassignment and outside successful `if` branches.
- Interface methods callable through the interface without `pub` are confirmed.
  Clarify how the general default-private rule relates to struct fields inheriting
  their enclosing struct's visibility in `Test.co`.
- Whether there are restrictions on individual source-to-target transition paths.
  The confirmed phrase "permitted states" means class-supported states, not an
  approved transition-graph restriction mechanism.
- Whether state payloads can escape through stored or returned bindings, and
  their lifetime after the originating method finishes.
- The exact diagnostics/error model for invalid-state calls and deleted-object access.
- Deleting an already-invalid binding or intentionally null value.
- The next compiler milestone and its acceptance criteria after explicit integer
  bindings.

This list is a set of unresolved areas, not an exhaustive specification checklist
or a set of required features.
