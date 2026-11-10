# Nested-state syntax and checking

The production parser and semantic checker cover nested states, host-class
state signatures, transitions and predicates.
[Confirmed state contract](semantics.md#states-and-method-dispatch) ·
[STATE-0 stages](../../../../todo/open-questions-plan.md#state-0-implementation-stages)

## Parsed forms

```cloth
typedef<class> Player {
    def<int> power = 10
    pub state<int> ::attack(opt<int> amount ?: self.power)

    default:
    pub state<void> Alive {
        init { println("alive") }
        pub mt<int> attack(int amount) = amount
    }
}

fn<int> attackIfAlive(Player player) {
    if (player is Alive) { return player::attack() }
    return 0
}
```

- Nested declarations retain the payload type, visibility, initial-state
  prefix, and the ordered `init` and `mt` members. `default:` may precede
  the declaration on the same line or a separate line. Use
  `default: (payload)` to supply the initial payload. The prefix and payload
  retain their full spans. The `state` payload name is parsed in the nested
  state body.
- A bodyless `state ::name(...)` or `state<Results> ::name(...)` signature
  retains ordinary parameter declarations, labels and default expressions.
  It uses the same result-list and parameter parsers as existing callables.
- `receiver -> Target()` and `receiver -> Target(payload)` retain the
  receiver, arrow, target, optional payload and full span as a
  `StateTransitionExpression`. A bare `receiver -> Target` retains its named
  arrow target as a `CastExpression`. A named `is` target retains the existing
  `TypeCheckExpression` form. The checker resolves those names.
- Both lexers tokenize the prefix with the existing `DEFAULT` and `COLON`
  spellings. An `@` marker produces the ordinary unexpected-character error.
  [Kotlin operators](../../../../Compiler/src/main/kotlin/cloth/token/Operators.kt) ·
  [Bootstrap operators](../../../../Bootstrap/Frontend/token/Operators.co)

## Checked declarations and calls

- Each class that declares nested states selects one with `default:`. A class
  adding states selects its own initial state. A class adding none inherits
  the effective selection. Inherited states keep their defining class and
  cannot be redeclared. Generic views resolve payload and signature types in
  each closed class. [State catalog](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/StateCatalog.kt)
- Type the initial payload with the constructed `self` and its fields in
  scope. Referencing a constructor-only parameter or `state` there is a
  scope error. Each `init` and state-method body sees the declaring host's
  `self` and, for a value payload, its own `state`.
  [Scope resolution](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationAnalyzer.kt)
- A transition or predicate resolves the target in the receiver's static
  class and checks its visibility. Nullable receivers need presence proof.
  For bare targets, existing type bindings and value-target `is` keep their
  usual precedence. Parenthesized arrow targets always name states. A void
  transition accepts both forms. A value state requires one typed payload.
  [Expression checking](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/BodyTyping.kt)
- A class publishes one call contract per overload. For inferred contracts,
  bodies agree on labels, visibility, parameter types and ordered results.
  An explicit host signature supplies labels, defaults and visibility.
  Its bodies take the same required parameter types and result shape and
  can use local parameter names. Inherited contracts retain their original
  declaration owner. A state's body can be absent for a published overload.
- Bind supplied arguments and omissions with the ordinary argument binder.
  The checked call retains the receiver's static class, the contract and its
  ordered results. Shared defaults use the declaring file's scope, earlier
  parameters and read-only `self`. State methods occupy a separate lookup
  from ordinary methods and interface requirements.
  [Call resolution](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/CallResolution.kt) ·
  [Checked facts](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionSemanticModel.kt)
- Local lifetime checking covers transitions, predicates and state calls.
  Direct destructor `self` may test and call. A transition requires ordinary
  live access. A state call checks the captured receiver after supplied
  arguments and omitted defaults. [Lifetime checking](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/ClassLifetimeAnalysis.kt)

## Compiler boundary

Stage 3 executes activation, transitions and predicates. Stage 4 executes
state dispatch and applies flow proofs for missing implementations.
C3130 diagnoses a proved missing body before lowering or publication.
C3047 diagnoses provider-style calls on receivers without state support.
[Top-level declarations and attachments](state-attachments.md) use the same
shared call contracts, payload checking and state operations.
[Activation runtime](state-runtime.md) ·
[Class parser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ClassParser.kt) ·
[State declarations](../../../../Compiler/src/main/kotlin/cloth/parser/declaration/StateDeclaration.kt) ·
[Expression parser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ExpressionParser.kt) ·
[State dispatcher](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitStateDispatch.kt)

## Stage 1 verification

`ClassParsingTest` checks declaration and target spans, malformed prefixes,
and the existing cast, type-check, state-call and provider parses. Three
CLI fixtures check one C4036, exit status 1 and output protection.

The 2026-11-09 rerun passed 142 tests across 13 suites with zero failures,
errors or skips. The Bootstrap differential run matched 126 inputs on
Windows x64 and x86, including `default:`, the unexpected-character error
for the former annotation spelling, and eight Bootstrap source files. Its
token ownership probes passed on both profiles. The rerun used isolated
Compiler outputs and a read-only dependency cache with the selectors below.
[Parser and CLI tests](../../../../Compiler/src/test/kotlin/cloth/ClassParsingTest.kt) ·
[Bootstrap differential tests](../../../../Compiler/src/test/kotlin/cloth/BootstrapLexerTest.kt)

```powershell
.\gradlew.bat :Compiler:test `
  --tests cloth.ClassParsingTest --tests cloth.ClassSemanticsTest --tests cloth.ClassInheritanceTest `
  --tests cloth.ProviderSyntaxTest --tests cloth.InterfaceParsingTest --tests cloth.GenericParsingTest `
  --tests cloth.ImportResolutionTest --tests cloth.SemicolonsTest --tests cloth.BootstrapLexerTest `
  --tests cloth.CastsTest --tests cloth.NullabilityParsingTest --tests cloth.DiagnosticsTest --tests cloth.OptionalArgumentsTest `
  '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' `
  --no-build-cache --no-configuration-cache --offline --console=plain --no-daemon
```

## Stage 2 verification

The offline `:Compiler:check` run on 2026-11-09 passed 2,047 tests across 194
suites and 203 additional test runs in the dedicated generic and import tasks.
All tasks finished with zero failures, errors or skips. Bootstrap parity
matched 126 inputs on Windows x64 and x86.

The 19 tests in `StateSemanticsTest` and `ClassParsingTest` passed on Windows
x64. They cover inherited defaults and contract identity, imported default
scope, generic payloads, ordered results, target visibility, destructor
access and the runtime gate in CLI, IR and object entry points. An invalid
CLI fixture checks one C3129 before C4036 and leaves the output directory
uncreated. The state fixtures exercise semantic analysis and the emission
boundary. Native state execution starts in Stage 3.
[State semantics tests](../../../../Compiler/src/test/kotlin/cloth/StateSemanticsTest.kt) ·
[CLI and emitter tests](../../../../Compiler/src/test/kotlin/cloth/ClassParsingTest.kt)
