# Struct methods Stage 2 syntax and declaration scope

Stage 2 integrates parsing and declaration resolution on 2026-11-05. The
[method contract](semantics.md#user-defined-methods) owns the language rules.
The parser and declaration analyzer accept this shape.

~~~cloth
pub typedef<struct> Meter {
    int value;

    mt<int> value() = value
    mt set(int value) {
        self.value = value
    }
}
~~~

## Implementation

- [StructParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/StructParser.kt)
  retains ordered fields and method declarations with source spans. A field
  list ends with a semicolon when followed by a method. A method-only body
  starts directly with `mt`. Methods inherit the struct's visibility.
  Method parameters and bodies use the shared function parser.
- [DeclarationAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/DeclarationAnalyzer.kt)
  collects signatures for each nominal owner before resolving bodies.
  Method overloads share the function signature key and receive independent
  declaration scopes. Only file-level functions enter the file callable
  groups and executable entry discovery.
- Each method has a fixed `self` binding. The resolver searches lexical scope
  first, including built-ins. In the absence of a lexical match, bare
  reads and assignments resolve to a receiver field, and bare calls resolve
  to a receiver method group. Explicit `self.` selects that receiver directly.
  Field and method lookup remain separate when their names coincide.
- Defaults get the read-only receiver scope. Direct and implicit field reads
  resolve there. The declaration pass rejects writes through that scope and
  user-defined calls on the current receiver, including implicit calls.
  Typed member selection on other receivers, typed default restrictions,
  receiver presence proofs and method execution follow in later stages.

## Diagnostics and verification

C2007 rejects `fn` in a struct body. C2008 rejects `mt` outside a type
member list. C3083 reports duplicate method signatures within an owner,
and C3084 locates `self` outside a method. Assigning `self` uses the
immutable binding diagnostic. At Stage 2, C3085 stopped source units with
methods after declaration resolution. [Stage 3](method-typing.md) moves C3085
to emission validation. The CLI reports one diagnostic before creating output.

`StructMethodSyntaxTest` and `StructMethodScopeTest` cover the shared
grammar, source spans, nominal groups, receiver and lexical lookup, defaults,
and the CLI boundary. All 482 selected tests across 40 suites passed on
Windows with zero failures or skips. This includes existing native and
cross-target regression fixtures for functions and basic structs. Stage 2
method coverage is parser, declaration and diagnostic verification.

~~~powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Struct*Test' --tests 'cloth.Function*Test' --tests cloth.CallResolutionTest --tests cloth.DeclarationScopesTest --tests 'cloth.OptionalArguments*Test' --tests 'cloth.MultiReturn*Test' --tests cloth.DestructuringTest --tests cloth.ProgramEntryTest --tests cloth.UnicodeArgumentsTest --tests cloth.BuiltinFunctionsTest --tests 'cloth.*AttachmentsTest' --tests cloth.StringFunctionTest --tests cloth.StringMemberStorageTest --tests cloth.ArbitraryIntegerWidthsTest --tests cloth.DiagnosticsTest --tests cloth.SemicolonsTest --tests cloth.ProviderSyntaxTest --tests cloth.AssignmentOperatorsTest --offline --console=plain --no-daemon
~~~
