# Nested types

Ownership, visibility, generic identity and the boundary for reusable states
follow the shared rules. [Language contract](semantics.md#nested-type-declarations)

```cloth
pub typedef<class> Box<T> {
    pub typedef<struct> Item { int quantity }
    pub typedef<struct> Entry<U> { U value }

    pub mt<Item> item(int quantity) { return { quantity } }
}

typedef<struct> Stock { Box.Item item }
```

`Stock` stores an `Item` because it declares a field. `Box<int>` and
`Box<string>` return the same `Box.Item` type. `Box.Entry<int>` specializes
`Entry` itself. Import `Box` to use these public types from another file.
For a private child, access stays inside its enclosing declaration, even when
an external use is in the same file. C3132 labels the access and declaration.

## Compiler integration

- The parser keeps nested declarations separate from storage and callable
  members. Classes, structs, enums, interfaces and reusable states can own
  nominal children. The reusable state declaration itself belongs at file
  scope. Struct field lists and enum case lists retain the `;` separator before
  a member section. [Parser](../../../../Compiler/src/main/kotlin/cloth/parser/Parser.kt)
- File binding registers each child once under its lexical owner. Unqualified
  lookup walks enclosing scopes from innermost to outermost before consulting
  the file. Qualified lookup walks child scopes and checks visibility at each
  step. File imports expose the owner and leave its children in that scope.
  [File binding](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/FileBindings.kt)
- Generic checking visits each nested type with its own parameter scope. A
  specialization retains the declaring path in type text and metadata. The
  usual layout and lifetime paths use the child's nominal descriptor. [Generic
  name analysis](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericNames.kt)
- Bare dotted operands of `is`, enum case selection and compiler data access
  can be resolved as type paths when the root has no value binding. `::new`
  takes a type receiver. Compile-time type paths contribute no eager operands
  to flow analysis. [Declaration analysis](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationAnalyzer.kt)

## Verification on 2026-11-10

All five nested-type tests passed through the production analyzer and compiler
request API. They cover private and public paths, nested declarations inside
each owner kind, separate identities for equally named children, explicit
field storage, independent generic parameters, and ordinary dotted value
access. The import fixture ran natively on Windows x64 and x86 and generated
objects for all nine target profiles. Its metadata includes `Box.Item` and
`Box.Entry<int<32>>`. The reusable-state fixture ran on Windows x64.
[NestedTypesTest](../../../../Compiler/src/test/kotlin/cloth/NestedTypesTest.kt)

The full `:Compiler:test` run on JDK 25 passed 2,082 of 2,083 tests. The
Bootstrap publication assertion expected `3` on its own line and the existing
lexer edit printed `Token Count: 3`. [ImportPublicationTest](../../../../Compiler/src/test/kotlin/cloth/ImportPublicationTest.kt)
The focused publication suites passed after the Bootstrap assertions switched
to token lexemes and successful completion. [Bootstrap verification](../../cloth-bootstrap-lexer/references/port.md#verification)
