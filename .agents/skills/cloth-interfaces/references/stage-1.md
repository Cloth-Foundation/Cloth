# Interface Stage 1 syntax and nominal identities

Stage 1 completed on 2026-11-08. The lexer, parser and name analyzer recognize
non-generic and explicit generic interfaces. Class headers preserve their base
and interface clauses. The compiler stops at the interface conformance boundary
with C4035. [Implementation plan](../../../../todo/interfaces-plan.md) ·
[Design contract](design.md)

```cloth
pub typedef<interface> IValue<T> {
    mt<T> read()
    mt<T> readAgain() = default (self.read())
}

typedef<class> ValueBox<T> (def<T> item) : IValue<T> {
    override: mt<T> read() = self.item
}
```

The sample parses and binds its type names through `GenericAnalyzer`. A compiler
request reaches the staged diagnostic before producing an artifact.

## Syntax and name binding

- `interface` and `override` are reserved keywords in both lexer tables.
  `override:` attaches to a single class method before optional `pub`.
  The marker retains its own source span. [Kotlin keywords](../../../../Compiler/src/main/kotlin/cloth/token/Keywords.kt) ·
  [Bootstrap keywords](../../../../Bootstrap/Frontend/token/Keywords.co)
- Stage 1 accepts braced interface declarations with plain `mt` members,
  including an empty braced body. Required signatures and expression defaults
  accept an optional semicolon. Block defaults end at the closing brace and
  reject a trailing semicolon. The parser retains the required signature
  separately from the optional explicit fallback. [Interface AST](../../../../Compiler/src/main/kotlin/cloth/parser/declaration/InterfaceDeclaration.kt) ·
  [Interface parser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/InterfaceParser.kt) ·
  [Method parser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/FunctionParser.kt)
- Class headers keep each type reference, its order and the presence of a
  constructor argument list. Name analysis distinguishes the single class base
  from interface entries. It accepts type references as interface parents and
  rejects constructor parentheses on class clauses that resolve to interfaces.
  Existing class constructor calls are built from the resolved class declaration
  during semantic analysis. [Class parser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ClassParser.kt) ·
  [Declaration analysis](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationAnalyzer.kt)
- A nominal descriptor preserves the original interface declaration across
  selected and wildcard imports. The generic name model binds forward parents,
  member signatures, parameter defaults and fallback bodies in their defining
  file. Interface type parameters use the existing owner scope and arity
  rules. [Interface descriptor](../../../../Compiler/src/main/kotlin/cloth/data/CInterface.kt) ·
  [File bindings](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/FileBindings.kt) ·
  [Generic names](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericNames.kt)
- C3118 reports invalid parent kinds, multiple class bases or a constructor
  argument list on an interface clause. Generic arity, raw template and
  unsupported constraint syntax retain their existing diagnostics. C4035
  guards semantic compilation and closed-type analysis of interface syntax,
  including a standalone `override:` prefix. [Interface diagnostics](../../../../Compiler/src/main/kotlin/cloth/diagnostics/InterfaceErrors.kt)

Stage 2 owns closed interface identities, inheritance cycles, duplicate
interface clauses, the one-application rule and conformance. Later stages
own member typing, defaults and runtime dispatch. Redundant visibility
modifiers and body omission remain design choices. The first parser slice
expects an explicit braced body and plain interface methods.

## Verification

The focused offline run passed 133 tests across 14 suites in 1m 1s. The
native-free import task also ran its 35 tests, which are included in the
focused count. Both tasks reported zero failures, errors or skips.

```powershell
.\gradlew.bat :Compiler:importSemanticsTest :Compiler:test --tests 'cloth.Interface*Test' --tests cloth.ClassParsingTest --tests cloth.ClassSemanticsTest --tests cloth.ClassInheritanceTest --tests cloth.ClassLifetimeTest --tests cloth.StructParsingTest --tests cloth.GenericParsingTest --tests cloth.GenericNamesTest --tests cloth.GenericClosedTypesTest --tests cloth.GenericStageBoundaryTest --tests cloth.GenericEmissionTest --tests cloth.ImportSemanticsTest --tests cloth.BootstrapLexerTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

Parser tests cover signature and fallback forms, member boundaries, ordered
headers, source spans and invalid prefixes. Name tests cover owner scope, raw
applications, arity, parent kinds and the compiler request boundary. Import
tests cover selected and wildcard imports, cycles, private defining-file
names and colliding exports. The object and executable requests both produce
C4035 before creating output. [Parser tests](../../../../Compiler/src/test/kotlin/cloth/InterfaceParsingTest.kt) ·
[Name and boundary tests](../../../../Compiler/src/test/kotlin/cloth/InterfaceNamesTest.kt) ·
[Import tests](../../../../Compiler/src/test/kotlin/cloth/ImportSemanticsTest.kt)

The class and generic regression fixtures ran on Windows x64 and x86 and
checked IR and object emission on all nine profiles. Bootstrap matched the
Kotlin lexer on 124 inputs on both native profiles. Interface-specific evidence
covers parsing, name analysis and staged diagnostics. [Class inheritance tests](../../../../Compiler/src/test/kotlin/cloth/ClassInheritanceTest.kt) ·
[Generic emission tests](../../../../Compiler/src/test/kotlin/cloth/GenericEmissionTest.kt) ·
[Bootstrap differential test](../../../../Compiler/src/test/kotlin/cloth/BootstrapLexerTest.kt)
